package cn.linan.backingup.audio

import cn.linan.backingup.story.Ambience

/**
 * 增益计算——纯逻辑，不碰 Android，所以 docs/06 第五节那几条验收能在 JVM 单测里跑。
 *
 * 三层叠加，顺序不能换（docs/06 第三节）：
 *   1. 章内 `[from, to]` 闭区间覆盖 default_gains（未列的声床沿用 default）
 *   2. integrity 驱动劣化：额外 `+= (100-integrity)/100 × 0.20`，单调
 *   3. 设备调制：灭屏/深夜/低电/省电/离开前台——乘性，因为它改的是"这个房间现在的物理状态"
 *
 * 两条不能忘的硬约束：
 *   · `d7_n711` 那六秒语音必须实测四路全 0（剧本段落负责，这里负责不许任何调制把它抬回去）
 *   · 后台 > 90 秒直接归零，且是最后一步——调制系数相乘永远得不到 0
 */
class AmbienceDirector {

    /** 设备事实的快照；由 UI 层每次 tick 现读，不进存档 */
    data class DeviceState(
        val screenOff: Boolean = false,
        val nightWindow: Boolean = false,
        val batteryLow: Boolean = false,
        val powerSave: Boolean = false,
        /** 不在前台且已超过 90 秒（docs/06 红线 2） */
        val leftOver90s: Boolean = false,
    )

    /** @param nodeOrder 该章 nodes 的 id 顺序，用来判 [from,to] 闭区间 */
    fun targetGains(
        ambience: Ambience?,
        nodeId: String?,
        nodeOrder: List<String>,
        integrity: Double,
        device: DeviceState,
    ): Map<String, Double> {
        if (ambience == null) return emptyMap()
        val beds = ambience.beds.ifEmpty { ALL_BEDS }

        // ── 1) 章内编排 ────────────────────────────────────────────────────────
        val base = HashMap<String, Double>()
        beds.forEach { base[it] = ambience.defaultGains[it] ?: 0.0 }
        val seg = segmentAt(ambience, nodeId, nodeOrder)
        seg?.let { it.gain.forEach { (k, v) -> base[k] = v } }

        // 编排写"全零"就是全零：docs/06 第五节验收 3 要求 d7_n711 那六秒语音四路实测为 0。
        // 这一步必须排在 integrity 劣化与设备调制之前并且直接返回——乘性调制永远得不到 0，
        // 加性劣化又一定会把 anomaly 抬起来（她那时恰恰散架得最厉害）。
        if (seg != null && seg.gain.keys.containsAll(beds) && seg.gain.values.all { it <= 0.0 }) {
            return beds.associateWith { 0.0 }
        }

        // ── 2) integrity → 劣化增益（她散架多少，声音就破多少，同一个数） ──────
        val anomalyExtra = ((100.0 - integrity).coerceIn(0.0, 100.0) / 100.0) * ANOMALY_MAX
        if (ANOMALY in beds) base[ANOMALY] = (base[ANOMALY] ?: 0.0) + anomalyExtra

        // ── 3) 设备调制 ───────────────────────────────────────────────────────
        val global = when {
            device.powerSave -> 1.0            // 省电：非底噪全部关掉，见下
            device.batteryLow -> BATTERY_LOW_GLOBAL
            device.screenOff -> SCREEN_OFF_GLOBAL
            else -> 1.0
        }
        val out = HashMap<String, Double>()
        beds.forEach { bed ->
            var g = (base[bed] ?: 0.0) * global
            if (device.powerSave && bed != BED_ROOM) g = 0.0
            if (device.powerSave && bed == BED_ROOM) g *= POWER_SAVE_BED_ROOM
            if (device.batteryLow && bed == HUM_MAINS) g = 0.0                 // 它也在省着活
            if (device.nightWindow && bed == HUM_MAINS) g *= NIGHT_HUM
            if (device.nightWindow && bed == DETAIL_DRIP) g *= NIGHT_DRIP
            if (device.screenOff && bed == BED_ROOM) g *= BED_ROOM_BEHIND_SCREEN   // ≈ −3dB
            out[bed] = g.coerceIn(0.0, GAIN_CEILING)
        }

        // 后台超 90 秒：最后一步，直接归零。什么都不放比放什么都可怕。
        if (device.leftOver90s) out.replaceAll { _, _ -> 0.0 }
        return out
    }

    /** 当前节点落在哪个编排段；闭区间按章内 nodes 的书写顺序判定 */
    fun segmentAt(ambience: Ambience?, nodeId: String?, nodeOrder: List<String>): Segment? {
        ambience ?: return null
        val id = nodeId ?: return null
        val at = nodeOrder.indexOf(id)
        if (at < 0) return null
        return ambience.segments.firstOrNull { seg ->
            val f = nodeOrder.indexOf(seg.from)
            val t = nodeOrder.indexOf(seg.to)
            f >= 0 && t >= 0 && at in minOf(f, t)..maxOf(f, t)
        }?.let { Segment(it.from, it.to, it.gain, it.note, it.fadeMs) }
    }

    /**
     * 实际淡入淡出时长：段级 `fade_ms` 覆盖章级，再限进 600–15000。
     * 快于 0.6 秒玩家会听成"切换"而不是"房间变了"，慢于 15 秒编排就失效了。
     */
    fun fadeMs(seg: Segment?, chapter: Ambience): Long =
        (seg?.fadeMs ?: chapter.fadeMs).coerceIn(FADE_MIN_MS, FADE_MAX_MS)

    /** 劣化的文件变体切换点（docs/06 第三节末）：≥55 播 14k 轻档，<55 播 9k 重档 */
    fun anomalyVariant(integrity: Double): String =
        if (integrity >= INTEGRITY_VARIANT_SWITCH) ANOMALY_LITE else ANOMALY

    /** 编排段的解析结果（fadeMs=null 表示沿用章级值） */
    data class Segment(
        val from: String,
        val to: String,
        val gain: Map<String, Double>,
        val note: String,
        val fadeMs: Long?,
    )

    companion object {
        const val BED_ROOM = "bed_room"
        const val HUM_MAINS = "hum_mains"
        const val DETAIL_DRIP = "detail_drip"
        const val ANOMALY = "anomaly_bitcrush"
        const val ANOMALY_LITE = "anomaly_bitcrush_lite"

        val ALL_BEDS = listOf(BED_ROOM, HUM_MAINS, DETAIL_DRIP, ANOMALY)

        const val ANOMALY_MAX = 0.20
        const val INTEGRITY_VARIANT_SWITCH = 55.0
        const val SCREEN_OFF_GLOBAL = 0.60             // docs/06：灭屏全局 −40%
        const val BATTERY_LOW_GLOBAL = 0.40            // 低电全局 −60%
        const val POWER_SAVE_BED_ROOM = 0.70           // 省电时底噪也退一层
        const val NIGHT_HUM = 1.40                     // 夜里电流声更明显 +40%
        const val NIGHT_DRIP = 1.20                    // 水管 +20%
        const val BED_ROOM_BEHIND_SCREEN = 0.708   // −3 dB（叠在全局 −40% 之上）
        const val GAIN_CEILING = 0.50              // 绝对上限，约 −6 dBFS
        const val FADE_MIN_MS = 600L
        const val FADE_MAX_MS = 15_000L

        /** 斜坡周期：1.5 秒一步，玩家不该意识到声音在变（docs/06 第三节） */
        const val TICK_MS = 1500L

        /**
         * 每个 tick 向目标移动的比例：0.35 → 约 4 个 tick（6 秒）走完一次编排变化。
         * 用"固定比例逼近"而不是"线性走完 fade_ms"，因为节点跳转随时可能发生，
         * 线性斜坡被打断时会留下一个正在路上的中间增益，逼近法永远不会。
         */
        const val RAMP_APPROACH = 0.35
    }
}
