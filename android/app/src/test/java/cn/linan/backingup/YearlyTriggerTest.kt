package cn.linan.backingup

import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.sched.WakeGate
import cn.linan.backingup.sched.YearlyTrigger
import cn.linan.backingup.story.ScriptRepository
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 周年触发（《紧急联系人》那句"每年 10 月 31 日 20:14"）。
 *
 * 这条链在路上要过一整年，中间会发生：设备重启、时区改变、用户改系统时间、
 * 省电模式吞掉 exact 闹钟、玩家一年没打开应用。真机验收没法等一年，
 * 所以能纯算的部分全部算清楚：锚点、文案池下标、以及"这一句是不是还该说"。
 *
 * 固定用 Asia/Shanghai 造时刻，避免测试跟着机器时区飘。
 */
class YearlyTriggerTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private fun at(text: String): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `锚点只认 10 月 31 日 20 点 14 分`() {
        val anchor2026 = at("2026-10-31T20:14:00")
        // 十月之前：今年
        assertEquals(anchor2026, YearlyTrigger.nextAnchor(at("2026-03-01T09:00:00"), zone))
        // 当天 20:13：还来得及，就是今天
        assertEquals(anchor2026, YearlyTrigger.nextAnchor(at("2026-10-31T20:13:00"), zone))
        // 当天 20:14 整点及之后：明年（不给"刚过一秒就排一个已经过去的闹钟"留缝）
        assertEquals(at("2027-10-31T20:14:00"), YearlyTrigger.nextAnchor(anchor2026, zone))
        assertEquals(at("2027-10-31T20:14:00"), YearlyTrigger.nextAnchor(at("2026-11-01T00:00:00"), zone))
        // 十二月：排到后年
        assertEquals(at("2027-10-31T20:14:00"), YearlyTrigger.nextAnchor(at("2026-12-25T10:00:00"), zone))
    }

    @Test
    fun `锚点至少还差一分钟`() {
        val now = at("2026-10-31T20:13:30")
        val anchor = YearlyTrigger.nextAnchor(now, zone)
        assertTrue("剩余 ${anchor - now}ms", anchor - now >= 60_000L)
        // 只剩 20 秒的那天，直接排到明年，不许排一个马上过期的闹钟
        assertEquals(at("2027-10-31T20:14:00"), YearlyTrigger.nextAnchor(at("2026-10-31T20:13:50"), zone))
    }

    @Test
    fun `文案池按年取且第十二年后循环`() {
        val pool = (1..12).map { "第 $it 年" }
        assertEquals("第 1 年", YearlyTrigger.lineFor(pool, 0))
        assertEquals("第 12 年", YearlyTrigger.lineFor(pool, 11))
        assertEquals("第 1 年", YearlyTrigger.lineFor(pool, 12))
        assertEquals("第 2 年", YearlyTrigger.lineFor(pool, 13))
        assertNull("空池子绝不编句子", YearlyTrigger.lineFor(emptyList(), 0))
    }

    @Test
    fun `剧本里的周年池是十二句且能在装载后取到`() {
        val repo = ScriptRepository.fromDirectory(scriptDir())
        val node = requireNotNull(repo.node("d7_e760")) { "d7_e760 不在了" }
        val pool = node.yearlyLines()
        assertEquals("契约要 12 句（第 13 年回第一条）", 12, pool.size)
        assertEquals("第一句锚点年份的味道不能改", "我今天迟到了一分钟。他们没扣我。", pool.first())
        assertTrue("不能有重复句", pool.size == pool.distinct().size)
        assertTrue("每句都得是一条能读完的消息", pool.all { it.isNotBlank() && it.length <= 120 })
    }

    @Test
    fun `周年闸只认存档里那一个锚点`() {
        val due = at("2027-10-31T20:14:00")
        val save = SaveFile(nodeId = "d7_e760").apply {
            yearlyDueAt = due
            yearlySourceNode = "d7_e760"
        }
        val ref = YearlyTrigger.ref(due)
        assertFalse("提前一秒都不发（她不能提前一年说话）", WakeGate.worthPosting(save, ref, due - 1L, due - 1L))
        assertTrue(WakeGate.worthPosting(save, ref, due, due))
        // 迟到的闹钟：存档已经把锚点推到明年，这一年这一次已经算过去了
        val rolled = SaveFile(nodeId = "d7_e760").apply { yearlyDueAt = due + 31_536_000_000L }
        assertFalse(WakeGate.worthPosting(rolled, ref, due + 60_000L, due))
        assertNull("锚点不匹配的 ref 没有投递身份", WakeGate.deliveryToken(rolled, ref))
        assertEquals("匹配时 ref 本身就是身份（含时刻，天然一年一个）", ref, WakeGate.deliveryToken(save, ref))
    }

    @Test
    fun `两个年份的周年是两个互不相干的投递`() {
        val a = YearlyTrigger.ref(at("2027-10-31T20:14:00"))
        val b = YearlyTrigger.ref(at("2028-10-31T20:14:00"))
        assertNotEquals(a, b)
        assertTrue(WakeGate.annualRef(a))
        assertFalse("等待那条路不能误认周年 ref", WakeGate.annualRef("W6-1"))
    }
}
