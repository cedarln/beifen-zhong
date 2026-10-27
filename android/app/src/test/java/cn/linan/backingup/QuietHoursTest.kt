package cn.linan.backingup

import cn.linan.backingup.sched.QuietHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 夜间静默窗口。
 *
 * 这条策略的代价在真机上几乎不可测（要熬夜），但它的判据是纯算术，
 * 于是全部结论能在 JUnit 里钉死：**她可以在凌晨到点，通知不行**。
 * 玩家亲手打开「夜里也让她说」之后才反过来。
 *
 * 时区固定 Asia/Shanghai：这套判据按设备本地时间走，测试也必须按本地时间写，
 * 不能让 CI 的 UTC 把 23:30 变成中午。
 */
class QuietHoursTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private fun millisOf(atMillis: Long): Pair<Int, Int> {
        val t = java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalTime()
        return t.hour to t.minute
    }

    @Test
    fun `窗口端点：23 点 29 还醒着，23 点 30 起静默`() {
        assertFalse(QuietHours.isQuiet(at(2026, 10, 27, 23, 29), zone = zone))
        assertTrue(QuietHours.isQuiet(at(2026, 10, 27, 23, 30), zone = zone))
    }

    @Test
    fun `窗口端点：07 点 29 仍静默，07 点 30 整恢复投递`() {
        assertTrue(QuietHours.isQuiet(at(2026, 10, 28, 7, 29, 59), zone = zone))
        assertFalse(QuietHours.isQuiet(at(2026, 10, 28, 7, 30), zone = zone))
    }

    @Test
    fun `窗口跨过午夜，凌晨三点当然在里`() {
        assertTrue(QuietHours.isQuiet(at(2026, 10, 28, 3, 0), zone = zone))
        assertFalse(QuietHours.isQuiet(at(2026, 10, 28, 20, 14), zone = zone))
    }

    @Test
    fun `玩家授权夜里投递后一律不静默`() {
        assertFalse(QuietHours.isQuiet(at(2026, 10, 28, 2, 0), allowNightDelivery = true, zone = zone))
    }

    @Test
    fun `凌晨两点半的唤醒点推到当天早上 07 点 30`() {
        val pushed = QuietHours.clamp(at(2026, 10, 28, 2, 30), zone = zone)
        assertEquals(7 to 30, millisOf(pushed))
        val day = java.time.Instant.ofEpochMilli(pushed).atZone(zone).toLocalDate()
        assertEquals(28, day.dayOfMonth)
    }

    @Test
    fun `夜里 23 点 40 的唤醒点推到次日早上`() {
        val pushed = QuietHours.clamp(at(2026, 10, 27, 23, 40), zone = zone)
        val (h, m) = millisOf(pushed)
        assertEquals(7 to 30, h to m)
        val day = java.time.Instant.ofEpochMilli(pushed).atZone(zone).toLocalDate()
        assertEquals(28, day.dayOfMonth)
        // 23:40 → 次日 07:30 = 7 小时 50 分，不是一天
        assertEquals(7 * 3_600_000L + 50 * 60_000L, pushed - at(2026, 10, 27, 23, 40))
    }

    @Test
    fun `白天与傍晚的唤醒点一动不动`() {
        val noon = at(2026, 10, 28, 12, 0)
        assertEquals(noon, QuietHours.clamp(noon, zone = zone))
        val evening = at(2026, 10, 28, 20, 14)      // 周年那条就落在这儿
        assertEquals(evening, QuietHours.clamp(evening, zone = zone))
    }

    @Test
    fun `推后是幂等的：不会把已经推到 07 点 30 的点再推到明天`() {
        val once = QuietHours.clamp(at(2026, 10, 28, 3, 0), zone = zone)
        assertEquals(once, QuietHours.clamp(once, zone = zone))
    }

    @Test
    fun `不在窗口里就没有可等的时长`() {
        assertEquals(0L, QuietHours.millisUntilWindowEnd(at(2026, 10, 28, 15, 0), zone = zone))
        assertEquals(
            30 * 60_000L,
            QuietHours.millisUntilWindowEnd(at(2026, 10, 28, 7, 0), zone = zone),
        )
    }

    @Test
    fun `静默整窗八小时，其余十六小时正常`() {
        var quietMinutes = 0
        for (minute in 0 until 24 * 60) {
            if (QuietHours.isQuietMinute(minute)) quietMinutes += 1
        }
        assertEquals(8 * 60, quietMinutes)
    }
}
