package cn.linan.backingup.sched

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.SaveStore
import cn.linan.backingup.story.ScriptRepository
import cn.linan.backingup.ui.SettingsStore

/**
 * 兜底补投：闹钟被 Doze / 省电模式吞掉时，由它确认"她是不是该说话了"。
 *
 * 只有这一处用 WorkManager 做与时间相关的事，而且它只做一件事——检查是否逾期。
 * 真正按时送达靠 AlarmManager.setExactAndAllowWhileIdle + [WakeReceiver]，
 * 因为 WorkManager 的延迟可以到几小时，那对她来说就是"失约"。
 *
 * 文案现从剧本读（`first_line_after_wait`），正文不接 `inputData` 里传进来的字符串。
 * 理由是重启：`BOOT_COMPLETED` 排的是新任务、输入数据是空的，上一版这里直接
 * `return Result.success()`，等于"重启之后她就再没来过"——而这正是这个类存在的意义。
 * 剧本在 assets 里永远读得到，存档里的 `node_id` 就是路标。
 *
 * 与 [WakeReceiver] 共用 [DeliveryLedger]：两条路径是同一条消息的两个入口，不是两条消息。
 */
class CatchUpWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext
        val save = SaveStore(app).read()
        val clock = GameClock.restore(save.installAt, save.rate, save.offset)

        // 1) 在途的长等待：只有真的逾期才补投。没到点时**不无脑重排**——
        //    重排会 enqueue 下一个 5 分钟后的自己，22 小时的等待等于 264 次空转。
        //    但"闹钟已经不在了"是另一件事：重启清空 AlarmManager、不清存档，
        //    这时补排一次（有 [ReminderScheduler.hasPendingAlarm] 兜着，一次就收敛）。
        save.waitUntilGameTime?.let { due ->
            val node = save.nodeId?.let { id -> runCatching { ScriptRepository(app).node(id) }.getOrNull() }
            val text = node?.firstLineAfterWait
            val ref = node?.waitRef ?: node?.id
            if (text != null && ref != null) {
                if (clock.gameTime() >= due) post(app, save, ref, text)
                else if (!ReminderScheduler.hasPendingAlarm(app, ref)) {
                    ReminderScheduler.schedule(app, clock.remaining(due), ref, text)
                }
            }
        }

        // 2) 退出后补投（d1_n150『我数到六十了』那类）：armedAt 存在存档里，
        //    所以逾期判定不依赖调度器还活着。没到点且闹钟已随重启消失的重排一次：
        //    60 秒级的短延迟最多循环一轮就收敛，不会像长等待那样自我喂养。
        val now = System.currentTimeMillis()
        save.pendingNotifications.filter { it.armedAt > 0L }.forEach { notice ->
            val ref = WakeGate.POST_PREFIX + notice.node
            val elapsed = now - notice.armedAt
            when {
                elapsed >= notice.delayMs -> post(app, save, ref, notice.text)
                !ReminderScheduler.hasPendingAlarm(app, ref) ->
                    ReminderScheduler.schedule(app, notice.delayMs - elapsed, ref, notice.text)
            }
        }

        // 3) 周年那条（《紧急联系人》的 10-31 20:14）：一年只有一次机会，
        //    闹钟被省电模式吃掉就等于错过一年，所以兜底必须真能兜住。
        //    文案回剧本取（池子在结局节点上，存档只记 node id 与"第几年"）。
        val yearlyDue = save.yearlyDueAt
        if (yearlyDue != 0L && now >= yearlyDue) {
            val text = yearlyText(app, save)
            if (text != null) post(app, save, YearlyTrigger.ref(yearlyDue), text)
        } else if (yearlyDue > now) {
            // 还没到点，但**重启会把 AlarmManager 清空、不会清存档**：玩家一年内任一次开机
            // （必然发生）之后，那条承诺就没有闹钟了。这里照存档补排一次，
            // 否则"每年 10 月 31 日 20:14"会变成"只在你不重启的那些年里成立"。
            val ref = YearlyTrigger.ref(yearlyDue)
            if (!ReminderScheduler.hasPendingAlarm(app, ref)) {
                yearlyText(app, save)?.let {
                    ReminderScheduler.schedule(app, yearlyDue - now, ref, it)
                }
            }
        }
        return Result.success()
    }

    /** 从结局节点的 `sample_yearly_lines` 取"第 N 年"那一句；取不到就什么都不发（引擎不发明内容） */
    private fun yearlyText(context: Context, save: SaveFile): String? {
        val node = save.yearlySourceNode ?: return null
        val pool = runCatching { ScriptRepository(context).node(node)?.yearlyLines() }.getOrNull()
        return YearlyTrigger.lineFor(pool ?: return null, save.yearlyMessagesSent)
    }

    /**
     * 投递的唯一出口：与 [WakeReceiver] 走同一张 [DeliveryLedger]、同一个 token 口径，
     * 所以闹钟和兜底 worker 谁先到都一样，只发一次。
     *
     * 夜里（23:30–07:30）不从这里投出去：worker 会被冷启动、开机、`schedule()` 排的兜底
     * 叫起来，那几条路径都可能撞在玩家睡着的时候。话留着，闹钟续到早上，台账不动。
     */
    private suspend fun post(context: Context, save: SaveFile, ref: String, text: String) {
        val token = WakeGate.deliveryToken(save, ref) ?: return
        if (DeliveryLedger.alreadyPosted(context, token)) return
        val now = System.currentTimeMillis()
        val allowNight = SettingsStore(context).nightDeliveryEnabled()
        if (QuietHours.isQuiet(now, allowNight)) {
            ReminderScheduler.schedule(
                context, QuietHours.millisUntilWindowEnd(now) + 60_000L, ref, text, allowNight,
            )
            return
        }
        ReminderScheduler.post(context, ref, text)
        DeliveryLedger.markPosted(context, token)
    }
}
