package cn.linan.backingup.sched

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.linan.backingup.engine.SaveStore
import cn.linan.backingup.ui.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 闹钟的唯一落点。
 *
 * 上一版这里直接 `PendingIntent.getActivity`，有两个后果：
 *   1. 到点的是**通知**，不是"把应用拽到前台"。她在你锁屏时发来一句，比手机自己亮屏更像人；
 *      而且 `post_exit_notification` 那条（d1_n150『我数到六十了』）的 ref 以 `post-` 开头，
 *      MainActivity 收到后直接 `return`，正文谁也没投——真正送达全靠 5 分钟后的兜底 worker，
 *      于是"按时"变成了"迟到五分钟"。
 *   2. 等待被玩家正常点开之后，之前排的闹钟还在。它会迟到一次，投出一句她已经说过的话。
 *
 * 所以现在：闹钟 → 这里 → 照存档判该不该说（[WakeGate]）→ 投通知；
 * 玩家点了通知才进 [cn.linan.backingup.MainActivity]。"到点"与"玩家回来了"是两件事。
 *
 * 这个 receiver 是 `exported=false`，只由自己的 PendingIntent.getBroadcast 触发，
 * 别的 App 伪造不出"她来找你了"。
 */
class WakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val ref = intent.getStringExtra(ReminderScheduler.EXTRA_REF) ?: return
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT) ?: return
        // 存档是 DataStore 的，读它要挂起；广播却必须尽快返回，否则系统会把这个 receiver 记一笔 ANR。
        // goAsync() 给我们一张"我还在干活"的条子，条子必须在 finally 里撕掉，撕晚了整个进程会被杀。
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val save = SaveStore(app).read()
                if (!WakeGate.worthPostingNow(save, ref)) return@launch
                val token = WakeGate.deliveryToken(save, ref) ?: return@launch
                if (DeliveryLedger.alreadyPosted(app, token)) return@launch
                val now = System.currentTimeMillis()
                val allowNight = SettingsStore(app).nightDeliveryEnabled()
                if (QuietHours.isQuiet(now, allowNight)) {
                    // 走到这里说明唤醒点被系统推迟进夜里了（Doze 合并、上一次静默的续排）。
                    // 不投、也不记台账——话还是她的话，只是改到早上说。
                    // 续排走 [ReminderScheduler.schedule]，它自己会 clamp 到窗口结束：
                    // 07:30 整已经不算静默，所以这一手最多循环一次就落地，不会自己喂自己。
                    ReminderScheduler.schedule(
                        app, QuietHours.millisUntilWindowEnd(now) + 60_000L, ref, text, allowNight,
                    )
                    Log.i(TAG, "静默窗口：$ref 推到早上")
                    return@launch
                }
                ReminderScheduler.post(app, ref, text)
                DeliveryLedger.markPosted(app, token)
            } catch (e: Exception) {
                // 到点却没说出口：这句话以后只能在后门面板的日志里看到，不能在玩家面前崩
                Log.w(TAG, "唤醒 $ref 投递失败：${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WakeReceiver"
    }
}
