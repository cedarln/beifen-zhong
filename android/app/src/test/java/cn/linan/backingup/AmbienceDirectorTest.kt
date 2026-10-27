package cn.linan.backingup

import cn.linan.backingup.audio.AmbienceDirector
import cn.linan.backingup.story.ScriptRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音层的增益算式（docs/06 第三、五节）。纯逻辑，不碰 AudioTrack。
 *
 * 验收 3 是这里最要紧的一条：`d7_n711` 那六秒语音播出时四路必须实测为 0。
 * 它必须在 integrity 劣化和设备调制**之后**仍然是 0——她那时恰好散架得很厉害，
 * 加性劣化一定会把 anomaly 抬起来，所以编排里的"全零"要有否决权，而不是参与求和。
 */
class AmbienceDirectorTest {

    private val director = AmbienceDirector()
    private val repo = ScriptRepository.fromDirectory(scriptDir())
    private val d7 = repo.chapters.last()
    private val order = d7.nodes.map { it.id }

    @Test
    fun `那六秒语音期间四路全零`() {
        val ambience = requireNotNull(d7.meta.ambience) { "act7 没写 ambience" }
        listOf("d7_n711", "d7_n712").forEach { id ->
            // 最差情况：她快散架了、又是深夜、又是低电量——都不许把声音抬回来
            val gains = director.targetGains(
                ambience, id, order, integrity = 12.0,
                device = AmbienceDirector.DeviceState(
                    screenOff = true, nightWindow = true, batteryLow = true, powerSave = true,
                ),
            )
            assertEquals("$id 的声床必须全零", 0.0, gains.getValue(AmbienceDirector.BED_ROOM), EPS)
            assertEquals("$id 的工频哼鸣必须全零", 0.0, gains.getValue(AmbienceDirector.HUM_MAINS), EPS)
            assertEquals("$id 的水声必须全零", 0.0, gains.getValue(AmbienceDirector.DETAIL_DRIP), EPS)
            assertEquals("$id 的劣化必须全零", 0.0, gains.getValue(AmbienceDirector.ANOMALY), EPS)
            assertEquals("$id 的淡入淡出用段级 1200ms", 1200L, director.fadeMs(
                director.segmentAt(ambience, id, order), ambience))
        }
    }

    @Test
    fun `离开前台超过九十秒直接归零`() {
        val ambience = requireNotNull(d7.meta.ambience)
        val gains = director.targetGains(
            ambience, "d7_n700", order, integrity = 40.0,
            device = AmbienceDirector.DeviceState(leftOver90s = true),
        )
        assertTrue("离开前台还留着声音：$gains", gains.values.all { it == 0.0 })
    }

    @Test
    fun `劣化随 integrity 单调上升且不超过上限`() {
        val ambience = requireNotNull(d7.meta.ambience)
        val quiet = director.targetGains(ambience, "d7_n700", order, 100.0, DEV_IDLE).getValue(AmbienceDirector.ANOMALY)
        val mid = director.targetGains(ambience, "d7_n700", order, 50.0, DEV_IDLE).getValue(AmbienceDirector.ANOMALY)
        val bad = director.targetGains(ambience, "d7_n700", order, 0.0, DEV_IDLE).getValue(AmbienceDirector.ANOMALY)
        assertTrue("integrity 越低劣化必须越大：$quiet/$mid/$bad", quiet < mid && mid < bad)
        // 章级 default 0.10 + (100-0)/100×0.20 = 0.30，仍在上限 0.50 之内
        assertEquals(0.30, bad, EPS)
    }

    @Test
    fun `增益永不越过绝对上限`() {
        val ambience = requireNotNull(d7.meta.ambience)
        order.forEach { id ->
            listOf(
                AmbienceDirector.DeviceState(),
                AmbienceDirector.DeviceState(screenOff = true),
                AmbienceDirector.DeviceState(nightWindow = true, batteryLow = true),
                AmbienceDirector.DeviceState(powerSave = true, nightWindow = true),
            ).forEach { device ->
                listOf(100.0, 40.0, 0.0).forEach { integrity ->
                    director.targetGains(ambience, id, order, integrity, device).forEach { (bed, gain) ->
                        assertTrue(
                            "$id／$bed 在 $device/$integrity 下增益 $gain 越界",
                            gain in 0.0..AmbienceDirector.GAIN_CEILING,
                        )
                    }
                }
            }
        }
    }

    /**
     * 设备调制测在 D3，不测 D7——因为 D7 的章级 `hum_mains` 是 0，
     * 「深夜哼鸣更响」在 D7 会变成 0×1.4 还是 0，测不出乘性调制（act7 章注：哼鸣全章归零）。
     * D3 的章首节点落在任何编排段之外，四条声床全用 default_gains，是最干净的被试。
     */
    @Test
    fun `灭屏是全局压低加上底噪单独再退三分贝`() {
        val d3 = repo.chapters[2]
        val ambience = requireNotNull(d3.meta.ambience)
        val ids = d3.nodes.map { it.id }
        val id = "d3_n300"
        require(director.segmentAt(ambience, id, ids) == null) { "$id 落在编排段里，本测的前提没了" }

        val on = director.targetGains(ambience, id, ids, 100.0, DEV_IDLE)
        val off = director.targetGains(ambience, id, ids, 100.0, AmbienceDirector.DeviceState(screenOff = true))
        assertTrue("D3 章首底噪必须开着", on.getValue(AmbienceDirector.BED_ROOM) > 0.0)
        assertEquals(
            "docs/06：灭屏 = 全局 −40%，bed_room 再单独 −3dB（手机扣在桌上，声音被闷住）",
            on.getValue(AmbienceDirector.BED_ROOM) * AmbienceDirector.SCREEN_OFF_GLOBAL * AmbienceDirector.BED_ROOM_BEHIND_SCREEN,
            off.getValue(AmbienceDirector.BED_ROOM),
            EPS,
        )
        // 其它声床只吃全局那一路，不再叠加
        assertEquals(
            on.getValue(AmbienceDirector.DETAIL_DRIP) * AmbienceDirector.SCREEN_OFF_GLOBAL,
            off.getValue(AmbienceDirector.DETAIL_DRIP),
            EPS,
        )
        val night = director.targetGains(ambience, id, ids, 100.0, AmbienceDirector.DeviceState(nightWindow = true))
        assertTrue("深夜哼鸣必须更响", night.getValue(AmbienceDirector.HUM_MAINS) > on.getValue(AmbienceDirector.HUM_MAINS))
        assertTrue("深夜水声必须更远", night.getValue(AmbienceDirector.DETAIL_DRIP) > on.getValue(AmbienceDirector.DETAIL_DRIP))
    }

    @Test
    fun `低电量全局压低六成且工频哼鸣完全退出`() {
        val d3 = repo.chapters[2]
        val ambience = requireNotNull(d3.meta.ambience)
        val ids = d3.nodes.map { it.id }
        val id = "d3_n300"
        val on = director.targetGains(ambience, id, ids, 100.0, DEV_IDLE)
        val low = director.targetGains(ambience, id, ids, 100.0, AmbienceDirector.DeviceState(batteryLow = true))
        assertTrue("满电时哼鸣必须存在（D3 章级 default 里就有）", on.getValue(AmbienceDirector.HUM_MAINS) > 0.0)
        assertEquals("低电时哼鸣必须归零", 0.0, low.getValue(AmbienceDirector.HUM_MAINS), EPS)
        assertEquals(
            on.getValue(AmbienceDirector.BED_ROOM) * AmbienceDirector.BATTERY_LOW_GLOBAL,
            low.getValue(AmbienceDirector.BED_ROOM),
            EPS,
        )
    }

    /** act7 章注是剧本承诺：D7 是唯一回暖的一章，哼鸣不许在任何设备状态下回来 */
    @Test
    fun `D7 全章哼鸣归零`() {
        val ambience = requireNotNull(d7.meta.ambience)
        listOf(
            DEV_IDLE,
            AmbienceDirector.DeviceState(nightWindow = true),
            AmbienceDirector.DeviceState(screenOff = true, nightWindow = true),
        ).forEach { device ->
            order.forEach { id ->
                assertEquals(
                    "D7 $id 在 $device 下哼鸣不该响（章注：哼鸣全章归零）",
                    0.0,
                    director.targetGains(ambience, id, order, 100.0, device).getValue(AmbienceDirector.HUM_MAINS),
                    EPS,
                )
            }
        }
    }

    @Test
    fun `省电模式只留房间底噪`() {
        val ambience = requireNotNull(d7.meta.ambience)
        val gains = director.targetGains(
            ambience, "d7_n700", order, 60.0,
            AmbienceDirector.DeviceState(powerSave = true),
        )
        assertTrue(gains.getValue(AmbienceDirector.BED_ROOM) > 0.0)
        assertEquals(0.0, gains.getValue(AmbienceDirector.HUM_MAINS), EPS)
        assertEquals(0.0, gains.getValue(AmbienceDirector.DETAIL_DRIP), EPS)
        assertEquals(0.0, gains.getValue(AmbienceDirector.ANOMALY), EPS)
    }

    @Test
    fun `劣化在 integrity 55 处换文件变体`() {
        assertEquals(AmbienceDirector.ANOMALY_LITE, director.anomalyVariant(55.0))
        assertEquals(AmbienceDirector.ANOMALY_LITE, director.anomalyVariant(80.0))
        assertEquals(AmbienceDirector.ANOMALY, director.anomalyVariant(54.9))
        assertEquals(AmbienceDirector.ANOMALY, director.anomalyVariant(0.0))
    }

    @Test
    fun `淡入淡出永远落在六百毫秒到十五秒之间`() {
        val ambience = requireNotNull(d7.meta.ambience)
        val seg = director.segmentAt(ambience, "d7_n711", order)
        assertEquals(1200L, director.fadeMs(seg, ambience))
        assertEquals(600L, director.fadeMs(seg?.copy(fadeMs = 1L), ambience))
        assertEquals(15_000L, director.fadeMs(seg?.copy(fadeMs = 999_999L), ambience))
        assertEquals(ambience.fadeMs, director.fadeMs(null, ambience))
    }

    /** 七章的编排段都不能把玩家丢在没定义的区间里：区间端点必须是本章真实存在的节点 */
    @Test
    fun `每章的编排端点都是真实节点且床名合法`() {
        val legal = setOf(
            AmbienceDirector.BED_ROOM, AmbienceDirector.HUM_MAINS,
            AmbienceDirector.DETAIL_DRIP, AmbienceDirector.ANOMALY, AmbienceDirector.ANOMALY_LITE,
        )
        repo.chapters.forEachIndexed { i, script ->
            val ids = script.nodes.map { it.id }
            val ambience = script.meta.ambience ?: return@forEachIndexed
            ambience.segments.forEach { seg ->
                assertTrue("d${i + 1} 编排端点 ${seg.from} 不是节点", seg.from in ids)
                assertTrue("d${i + 1} 编排端点 ${seg.to} 不是节点", seg.to in ids)
                seg.gain.keys.forEach { assertTrue("d${i + 1} 编排里有未知声床 $it", it in legal) }
            }
            ambience.defaultGains.keys.forEach { assertTrue("未知声床 $it", it in legal) }
        }
    }


    private companion object {
        const val EPS = 1e-9
        val DEV_IDLE = AmbienceDirector.DeviceState()
    }
}
