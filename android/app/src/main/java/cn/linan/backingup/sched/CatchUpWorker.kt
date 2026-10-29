package cn.linan.backingup.sched

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.linan.backingup.engine.ChapterGate
import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.SaveStore
import cn.linan.backingup.engine.Template
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
        // 剧本在这个类里要读三处（等待承接句、下一章第一句、周年那句），一次一个实例就是把
        // assets 解析三遍。更关键的是渲染口径只有一份，见 [render]。
        val repo = runCatching { ScriptRepository(app) }.getOrNull()

        // 1) 在途的长等待：只有真的逾期才补投。没到点时**不无脑重排**——
        //    重排会 enqueue 下一个 5 分钟后的自己，22 小时的等待等于 264 次空转。
        //    但"闹钟已经不在了"是另一件事：重启清空 AlarmManager、不清存档，
        //    这时补排一次（有 [ReminderScheduler.hasPendingAlarm] 兜着，一次就收敛）。
        save.waitUntilGameTime?.let { due ->
            val node = save.nodeId?.let { id -> runCatching { repo?.node(id) }.getOrNull() }
            val text = node?.firstLineAfterWait
            val ref = node?.waitRef ?: node?.id
            if (text != null && ref != null) {
                val line = render(repo, save, due, text)
                if (clock.gameTime() >= due) post(app, save, ref, line)
                else if (!ReminderScheduler.hasPendingAlarm(app, ref)) {
                    ReminderScheduler.schedule(app, clock.deviceDelayUntil(due), ref, line)
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
            // notice.text 在挂上去那一刻已经被引擎渲染过（v0.1.6 起），这里再过一遍是幂等的，
            // 管的是升级前存档里那批原文
            val line = render(repo, save, clock.gameTime(), notice.text)
            when {
                elapsed >= notice.delayMs -> post(app, save, ref, line)
                !ReminderScheduler.hasPendingAlarm(app, ref) ->
                    ReminderScheduler.schedule(app, notice.delayMs - elapsed, ref, line)
            }
        }

        // 3) 周年那条（《紧急联系人》的 10-31 20:14）：一年只有一次机会，
        //    闹钟被省电模式吃掉就等于错过一年，所以兜底必须真能兜住。
        //    文案回剧本取（池子在结局节点上，存档只记 node id 与"第几年"）。
        val yearlyDue = save.yearlyDueAt
        if (yearlyDue != 0L && now >= yearlyDue) {
            val text = yearlyText(repo, save)?.let { render(repo, save, clock.gameTime(), it) }
            if (text != null) post(app, save, YearlyTrigger.ref(yearlyDue), text)
        } else if (yearlyDue > now) {
            // 还没到点，但**重启会把 AlarmManager 清空、不会清存档**：玩家一年内任一次开机
            // （必然发生）之后，那条承诺就没有闹钟了。这里照存档补排一次，
            // 否则"每年 10 月 31 日 20:14"会变成"只在你不重启的那些年里成立"。
            val ref = YearlyTrigger.ref(yearlyDue)
            if (!ReminderScheduler.hasPendingAlarm(app, ref)) {
                yearlyText(repo, save)?.let {
                    ReminderScheduler.schedule(app, yearlyDue - now, ref, render(repo, save, yearlyDue, it))
                }
            }
        }

        // 4) 章与章之间那条（次日早上的"早。"）：和上面三条同一个道理——
        //    重启清空调度器、不清存档，锚点还在存档里，所以这里能自己补回来。
        chapterGate(app, repo, save, clock)
        return Result.success()
    }

    /**
     * 通知栏里的一句 → 玩家读得到的一句。
     *
     * 这条路上没有引擎实例（worker 是冷启动、开机、`schedule()` 的兜底叫起来的），
     * 但剧本里的 `{{player_name}}` 和日期不认"这条是通知"：v0.1.6 那次真机事故里，
     * 漏到屏上的原文就是这么进转写的。口径与屏上完全一致（[Template.varsFor]），
     * 只有一个区别——按**这条通知该被读到的那一刻**算，不是按 worker 跑起来的此刻：
     * 她约的是"明天见"，用此刻渲染会让"明天是周四"在她真回来那天变成周三。
     *
     * 幂等：已经渲染过的句子里没有 `{{`，原样返回，不会把她的话洗第二遍。
     */
    private fun render(repo: ScriptRepository?, save: SaveFile, atGameTime: Long, raw: String): String {
        if (!raw.contains("{{")) return raw
        return Template.render(raw, Template.varsFor(save.variables, atGameTime)) { name ->
            runCatching { repo?.variableUnsetValue(name) }.getOrNull()
        }
    }

    /**
     * 章与章之间那条（次日早上她敲的"早。"）。
     *
     * 判据全在存档里（章末锚点 + 章号），所以它天然收敛：到点就投，投完 [WakeGate] 的台账
     * 挡住重复；玩家自己先打开过 App 的话锚点已清空，这里直接不算这一路。
     * 文案取下一章入口节点的第一句——引擎不发明内容，通知栏也不。
     */
    private suspend fun chapterGate(app: Context, repo: ScriptRepository?, save: SaveFile, clock: GameClock) {
        val ended = save.chapterEndAtGameTime ?: return
        if (save.chapter >= ChapterGate.LAST_CHAPTER) return
        val opens = ChapterGate.openingAt(ended)
        val ref = ChapterGate.refFor(save.chapter + 1)
        val text = runCatching { repo?.chapterEntry(save.chapter + 1)?.text?.firstOrNull() }
            .getOrNull() ?: return
        when {
            clock.gameTime() >= opens -> post(app, save, ref, render(repo, save, clock.gameTime(), text))
            !ReminderScheduler.hasPendingAlarm(app, ref) ->
                // 还没到点：按"她该敲门的时刻"（次日早上）渲染，不是按 worker 跑起来的此刻，
                // 否则凌晨补排一次，那句"早。"里带的就是昨天的日期
                ReminderScheduler.schedule(
                    app, clock.deviceDelayUntil(opens), ref, render(repo, save, opens, text),
                )
        }
    }

    /** 从结局节点的 `sample_yearly_lines` 取"第 N 年"那一句；取不到就什么都不发（引擎不发明内容） */
    private fun yearlyText(repo: ScriptRepository?, save: SaveFile): String? {
        val node = save.yearlySourceNode ?: return null
        val pool = runCatching { repo?.node(node)?.yearlyLines() }.getOrNull()
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
