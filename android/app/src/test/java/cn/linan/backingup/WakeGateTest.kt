package cn.linan.backingup

import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.PendingNotice
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.sched.WakeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 到点之后"这句还该不该说"。这条判据是唤醒链路的最后一道闸，也是上一版三个真实缺陷的合集：
 * 闹钟落到 Activity 所以 `post-` 的正文没人投、玩家自己点开之后旧闹钟还会迟到一次、
 * Doze 提前唤醒会让她说漏嘴。每一条都在这里钉死。
 *
 * WakeGate 之所以是纯函数（存档 + 两个时间戳进，布尔出），就是为了这一刻：
 * 跨重启、跨进程、Doze 提前——这些在单测里全能造出来，在真机上要等 22 小时。
 */
class WakeGateTest {

    private val installAt = 1_700_000_000_000L
    private val dueGameTime = installAt + 3_600_000L          // 一小时后

    /** 一个正武装着的等待：W6-1 */
    private fun armedWait() = SaveFile(
        installAt = installAt,
        nodeId = "d6_w640",
        waitRef = "W6-1",
        waitUntilGameTime = dueGameTime,
    )

    @Test
    fun `等待到点且 ref 对得上就该说`() {
        val save = armedWait()
        assertTrue(WakeGate.worthPosting(save, "W6-1", dueGameTime, dueGameTime))
    }

    @Test
    fun `没到点不许提前说话`() {
        val save = armedWait()
        // Doze 把唤醒提前了 10 分钟：宁可这条不来，也不能让她说"两小时后见"却四十分钟就到
        assertFalse(WakeGate.worthPosting(save, "W6-1", dueGameTime - 600_000L, dueGameTime - 600_000L))
    }

    @Test
    fun `玩家已经点开过就没有第二次`() {
        val resolved = armedWait().apply {
            waitUntilGameTime = null
            waitRef = null                    // 引擎 resolveWait 的产物
        }
        assertFalse(WakeGate.worthPosting(resolved, "W6-1", System.currentTimeMillis(), dueGameTime))
    }

    @Test
    fun `ref 不属于当前这次等待就是过期唤醒`() {
        val switched = armedWait().apply { waitRef = "W6-2" }   // 后门跳到下一个等待
        assertFalse(WakeGate.worthPosting(switched, "W6-1", System.currentTimeMillis(), dueGameTime))
    }

    @Test
    fun `补投没排过闹钟就不存在`() {
        val save = armedWait().copy(pendingNotifications = mutableListOf(notice(armedAt = 0L)))
        assertFalse(WakeGate.worthPosting(save, "${WakeGate.POST_PREFIX}d1_n150", save.installAt + 60_000L, 0L))
    }

    @Test
    fun `补投到点才发早一秒都不发`() {
        val armed = notice(armedAt = 1_000L)
        val save = armedWait().copy(pendingNotifications = mutableListOf(armed))
        val ref = WakeGate.POST_PREFIX + armed.node
        // 60 秒的延迟：59.999 秒时醒来不说，60 秒整才说
        assertFalse(WakeGate.worthPosting(save, ref, 1_000L + 59_999L, 0L))
        assertTrue(WakeGate.worthPosting(save, ref, 1_000L + 60_000L, 0L))
    }

    @Test
    fun `二周目的同一条等待是两个投递身份`() {
        val first = armedWait().apply { waitUntilGameTime = installAt + 3_600_000L }
        val encore = armedWait().apply { waitUntilGameTime = installAt + 9_600_000L }  // 重走 D1，ref 一模一样
        val t1 = WakeGate.deliveryToken(first, "W6-1")
        val t2 = WakeGate.deliveryToken(encore, "W6-1")
        assertTrue("第一次拿不到身份：$t1", t1 != null)
        assertNotEquals("二周目必须能重新投一次：台账把 ref 当身份就会把她永久消音", t1, t2)
    }

    @Test
    fun `过期等待没有投递身份`() {
        val resolved = armedWait().apply { waitRef = null; waitUntilGameTime = null }
        assertEquals(null, WakeGate.deliveryToken(resolved, "W6-1"))
        val noNotice = armedWait().copy(pendingNotifications = mutableListOf())
        assertEquals(null, WakeGate.deliveryToken(noNotice, "${WakeGate.POST_PREFIX}d1_n150"))
    }

    @Test
    fun `补投的身份由 arm 时刻决定`() {
        val save = armedWait().copy(pendingNotifications = mutableListOf(notice(armedAt = 4_242L)))
        val ref = WakeGate.POST_PREFIX + "d1_n150"
        assertEquals("$ref@4242", WakeGate.deliveryToken(save, ref))
    }

    /** 真实入口那条链：游戏时钟由存档换算，倍率与偏移都算进"到点没到点" */
    @Test
    fun `worthPostingNow 用存档里的时钟判到点`() {
        val notYet = armedWait()
        assertFalse(WakeGate.worthPostingNow(notYet, "W6-1", nowMillis = dueGameTime - 1L))
        assertTrue(WakeGate.worthPostingNow(notYet, "W6-1", nowMillis = dueGameTime))
        // 后门把时钟推过一整天（OFFSET += 86_400_000）：等待自然算到"早该说了"
        val jumped = armedWait().apply { offset = GameClock.DAY_MS }
        assertTrue(WakeGate.worthPostingNow(jumped, "W6-1", nowMillis = installAt))
    }

    private fun notice(armedAt: Long) = PendingNotice(
        node = "d1_n150", delayMs = 60_000L, text = "小满：我数到六十了。灯还没灭。", armedAt = armedAt,
    )
}
