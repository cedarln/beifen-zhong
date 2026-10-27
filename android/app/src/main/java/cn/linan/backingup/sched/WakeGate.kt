package cn.linan.backingup.sched

import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile

/**
 * 到点之后"这句还该不该说"的判据。纯函数，不碰 Android，所以能在 JVM 单测里逐条验收。
 *
 * 三类 ref（等待 / `post-` 补投 / `yearly-` 周年）加一个投递身份，各自的失败方式：
 *  · 等待已经被玩家点开、或被后门跳走 → `wait_ref` 已被引擎清空或换掉，这是一次过期唤醒。
 *    不判这一条的话，第二天通知栏会多出昨天那句话的复读。
 *  · Doze 提前唤醒 → 游戏时钟还没走到 `wait_until`。**提前说话比不说话更假**：
 *    她说"两小时后见"，四十分钟后就来了，玩家只会认为闹钟坏了。
 *  · `post-` 那条还没 arm（`armed_at == 0`）→ 存档里躺着一条没排过闹钟的待办，
 *    说明这条通知根本不该存在（被 `only_if` 挡掉的），闭嘴。
 *  · `yearly-` 只认存档里那一个锚点：`yearly_due_at` 一旦被 VM 推到下一年，
 *    上一年那个迟到的闹钟就永远闭嘴（一年一次的消息，宁可早排不可重排）。
 *
 * 判据只读存档与两个时间戳，不读调度器的内存：进程被杀、设备重启之后仍然成立。
 */
object WakeGate {

    const val POST_PREFIX = "post-"

    /** 三路 ref 的分流标记：调度、闸门、面板都用它，不再各自 `startsWith("yearly-")` */
    fun annualRef(ref: String): Boolean = ref.startsWith(YearlyTrigger.REF_PREFIX)

    fun worthPosting(save: SaveFile, ref: String, nowMillis: Long, gameTimeMillis: Long): Boolean =
        when {
            annualRef(ref) -> {
                // 周年那条走设备时间：RATE 不能参与"到没到点"（见 YearlyTrigger 的说明）
                val due = YearlyTrigger.dueAtOf(ref) ?: return false
                save.yearlyDueAt == due && nowMillis >= due
            }
            ref.startsWith(POST_PREFIX) -> {
                save.pendingNotifications.any { notice ->
                    POST_PREFIX + notice.node == ref &&
                        notice.armedAt > 0L &&
                        nowMillis - notice.armedAt >= notice.delayMs
                }
            }
            else -> {
                val due = save.waitUntilGameTime ?: return false
                save.waitRef == ref && gameTimeMillis >= due
            }
        }

    /** 闹钟醒来时那两个时间戳的真实来源，集中一处便于测试替换 */
    fun worthPostingNow(save: SaveFile, ref: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val clock = GameClock.restore(save.installAt, save.rate, save.offset, now = { nowMillis })
        return worthPosting(save, ref, nowMillis, clock.gameTime())
    }

    /**
     * "这一次投递"的身份。
     *
     * 不能只用 ref：W1-2 这类等待在二周目（通关后开快速模式重走 D1）会原样再来一次，
     * 按 ref 记账会把二周目的她永久消音——那是比复读更糟的错。
     * 所以等待用 `wait_until`（游戏时刻）作后缀，补投用 `armed_at`（设备时刻）作后缀：
     * 同一条消息的每一次发生各自记一笔。取不到身份就返回 null，意思是"这句现在不存在"。
     */
    fun deliveryToken(save: SaveFile, ref: String): String? =
        when {
            // 锚点时刻就在 ref 里，一年一个值，天然不会和二周目的某条等待撞车
            annualRef(ref) ->
                if (save.yearlyDueAt == YearlyTrigger.dueAtOf(ref)) ref else null

            ref.startsWith(POST_PREFIX) ->
                save.pendingNotifications
                    .firstOrNull { POST_PREFIX + it.node == ref && it.armedAt > 0L }
                    ?.let { "$ref@${it.armedAt}" }

            else -> if (save.waitRef == ref) save.waitUntilGameTime?.let { "$ref@$it" } else null
        }
}
