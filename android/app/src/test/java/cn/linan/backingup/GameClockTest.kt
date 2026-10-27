package cn.linan.backingup

import cn.linan.backingup.engine.GameClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时钟层（docs/04 第三节）：倍率必须作用在"流逝"上，而不是作用在某个等待节点上。
 *
 * 这几条断言各自对应的真实事故：
 *  · 换倍率不重锚 → 3× 时正在走的 22 小时突然变成 7 小时，玩家看到的等待比刚才还短；
 *  · 跳章只改节点不改时钟 → D5 以为只过了一天，周年触发与 integrity 衰减全线错位；
 *  · 存档只存倍率不存偏移 → 重启后所有在途等待的到期时刻整体漂移。
 *
 * 全程用一个可拨的假"此刻"（[FakeNow]）：拿真实墙上时间跑这些算式，测试会随机飘。
 */
class GameClockTest {

    /** 可拨的设备时钟 */
    private class FakeNow(var at: Long) {
        operator fun invoke(): Long = at
    }

    private val install = 1_700_000_000_000L

    @Test
    fun `换倍率的那一刻游戏时间不跳变`() {
        val now = FakeNow(install + 10 * GameClock.DAY_MS)
        val clock = GameClock(installAt = install, now = { now.at })
        val before = clock.gameTime()
        clock.setRate(3600.0)
        assertEquals("同一刻的设备时间必须给出同一刻的游戏时间", before, clock.gameTime())
    }

    @Test
    fun `换倍率之后时间按新倍率流逝`() {
        val now = FakeNow(install + 10 * GameClock.DAY_MS)
        val clock = GameClock(installAt = install, now = { now.at })
        clock.setRate(3600.0)
        val anchor = clock.gameTime()
        now.at += 3_600_000L                     // 设备时间过了一小时
        assertEquals(3600.0 * 3_600_000.0, (clock.gameTime() - anchor).toDouble(), 1.0)
    }

    @Test
    fun `倍率可以来回切且每次都对齐在当前时刻`() {
        val now = FakeNow(install + GameClock.DAY_MS)
        val clock = GameClock(installAt = install, now = { now.at })
        listOf(1.0, 3.0, 3600.0, 0.5, 1.0).forEach { rate ->
            val anchor = clock.gameTime()
            clock.setRate(rate)
            assertEquals("切到 ${rate}× 的那一瞬间不能跳", anchor, clock.gameTime())
            now.at += 60_000L                    // 一小时设备时间
        }
        // 最后一段是 1×：一小时设备时间就该是一小时游戏时间
        val tail = clock.gameTime()
        now.at += 60_000L
        assertEquals(60_000.0, (clock.gameTime() - tail).toDouble(), 1.0)
    }

    @Test
    fun `正常游玩时游戏时间就是设备时间`() {
        val now = FakeNow(install + 5_000L)
        val clock = GameClock(installAt = install, now = { now.at })
        assertEquals(install + 5_000L, clock.gameTime())
        assertEquals(install + 5_000L, clock.deviceTime())
    }

    @Test
    fun `跳章推的是偏移不是倍率`() {
        val now = FakeNow(install + GameClock.DAY_MS)
        val clock = GameClock(installAt = install, now = { now.at })
        val before = clock.gameTime()
        clock.jump(3 * GameClock.DAY_MS)
        assertEquals(3 * GameClock.DAY_MS, clock.gameTime() - before)
        assertEquals("跳章不许改动流逝速度", 1.0, clock.rate, 1e-9)
    }

    @Test
    fun `等待在倍率下按游戏时间到点`() {
        val now = FakeNow(install)
        val clock = GameClock(installAt = install, now = { now.at })
        clock.setRate(12.0)
        val due = clock.gameTime() + 22 * 3_600_000L      // W6-2：22 小时游戏时间
        now.at += 1_000                                    // 设备时间一秒：游戏时间 12 秒
        assertTrue("才过一秒就到期了", clock.remaining(due) > 0)
        now.at += (22 * 3_600_000L / 12) + 1
        assertTrue("22 小时游戏时间到了却没到期", clock.isDue(due))
    }

    @Test
    fun `存档恢复后等待的到期时刻仍然对得上`() {
        val now = FakeNow(install + GameClock.DAY_MS)
        val original = GameClock(installAt = install, now = { now.at })
        original.setRate(12.0)
        val anchor = original.gameTime()
        val due = anchor + 22 * 3_600_000L          // W6-2：22 小时游戏时间

        // 存档只可能带走三个数：安装时刻、倍率、偏移。少带一个，重启后 due 就对不上
        val restored = GameClock.restore(original.installAt, original.rate, original.offset) { now.at }
        assertEquals("恢复的那一刻游戏时间必须接得上", anchor, restored.gameTime())

        now.at += 22 * 3_600_000L / 12 + 1          // 设备时间过 1 小时 50 分 = 游戏时间 22 小时
        assertTrue("游戏时间已经走完 22 小时，恢复后的存档却说没到期", restored.isDue(due))
    }
}
