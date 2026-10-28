package cn.linan.backingup.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.linan.backingup.audio.AmbienceDirector
import cn.linan.backingup.audio.AmbiencePlayer
import cn.linan.backingup.engine.DeviceSignals
import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.SaveStore
import cn.linan.backingup.engine.StoryEngine
import cn.linan.backingup.engine.TranscriptLine
import cn.linan.backingup.sched.DeliveryLedger
import cn.linan.backingup.sched.ReminderScheduler
import cn.linan.backingup.sched.ScheduledAlarms
import cn.linan.backingup.sched.WakeGate
import cn.linan.backingup.sched.YearlyTrigger
import cn.linan.backingup.R
import cn.linan.backingup.story.Node
import cn.linan.backingup.story.ScriptRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 一局游戏的会话状态。
 *
 * 引擎是纯的（改 SaveFile + 返回 Halt），副作用全部收在这里：写存档、排闹钟、
 * 驱动声床、记失真日志。这样单测可以不碰 Android，而后门面板能直接调到同一套方法。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ScriptRepository(app)
    /** 设置页「关于这一版」那行用：数字由剧本算出来，不写在代码里 */
    val scriptNodeCount: Int get() = repo.totalNodes
    private val store = SaveStore(app)
    private val signals = DeviceSignals(app)
    private val settings = SettingsStore(app)
    private val director = AmbienceDirector()
    private val player = AmbiencePlayer(app)

    private var clock = GameClock()
    private val engine = StoryEngine(repo, signals)

    private var save: SaveFile = SaveFile()
    private var ambienceOn = false
    private var nightDeliveryOn = false
    /** 上一次放过人声的节点 id：见 persist 里那条"只播一次"的注释 */
    private var lastVoiceNode: String? = null
    private var backgroundSince = 0L
    private var showInvite = false
    /** 本次开机读盘时转写已有多少行——见 [ViewState.seenAtLaunch] */
    private var seenAtLaunch = 0

    private val _state = MutableStateFlow(ViewState())
    val state: StateFlow<ViewState> = _state.asStateFlow()

    /**
     * 存档读完了没有。冷启动时 Activity 的 `handleWakeIntent` 可能比 init 的 `store.read()`
     * 先跑到（点通知进游戏就是这条路径），那时 [save] 还是全零的默认值——
     * 让 wake 先走一步的结果是"用空存档 mutate + 落盘"，把刚读到的进度盖掉。
     * 所以所有玩家动作都在这张条子前面等一会儿。
     */
    private val loaded = CompletableDeferred<Unit>()

    init {
        viewModelScope.launch {
            save = store.read()
            // 先记下"这次开机之前她已经说过多少行"。会话页拿它把历史一次性上屏，
            // 只对这一次新说的话逐条浮现——否则玩到后面每次重开都要把前面几章重演一遍。
            seenAtLaunch = save.transcript.size
            loaded.complete(Unit)
            clock = GameClock.restore(save.installAt, save.rate, save.offset)
            ambienceOn = settings.ambienceEnabled()
            nightDeliveryOn = settings.nightDeliveryEnabled()
            showInvite = !ambienceOn && !settings.ambienceInvited()
            val halt = if (save.nodeId == null) {
                engine.applyChapterStartState(save, chapter = 1)
                save.chapter = 1
                engine.advance(save, clock)
            } else {
                // 冷启动先补投：逾期不补 = 玩家以为游戏坏了
                if (save.waitUntilGameTime != null) engine.resolveWait(save, clock)
                else engine.advance(save, clock)
            }
            // 两个"她能不能叫醒你"的设备事实，冷启动各读一次真值。
            // 不读真值的话，D1 那几个授权点之前的等待会按"通知已开"来排——
            // 而 13+ 新装机默认是没开的，玩家走出去一次就发现她根本没敲过门。
            signals.exactAlarmGranted = ReminderScheduler.canScheduleExact(getApplication())
            signals.notifyDenied =
                !NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()
            // 冷启动先把自己记的账销掉，让 [armWait] 重新排一次。
            // 理由是在模拟器上真撞见过的一幕：`adb install -r`（玩家收到更新并覆盖安装）
            // 和设置里那个"强制停止"都会把系统里的闹钟清掉，可 SharedPreferences 活得比它们久，
            // 开机标记也没变——于是账上说"排过了"，AlarmManager 里其实一条都没有，
            // 她那句"我回来找你"从此没有人负责。账防的是"每次点屏幕都重排"，不是"这次开机不重排"。
            val inFlight = save.waitRef
            if (inFlight != null) ScheduledAlarms.forget(getApplication(), inFlight)
            persist(save, halt)
            ReminderScheduler.scheduleCatchUp(getApplication())
        }
        // 声床节拍：1.5 秒一次逼近（docs/06 第三节）。它只在开关打开时占资源。
        viewModelScope.launch {
            while (true) {
                delay(AmbienceDirector.TICK_MS)
                if (ambienceOn) stepAmbience()
            }
        }
        // 倒计时给等待卡片用，一秒一跳足够（等待以小时计）
        viewModelScope.launch {
            var resolvedFor: Long? = null
            while (true) {
                delay(1000)
                val due = (_state.value.halt as? StoryEngine.Halt.AwaitingWait)?.dueGameTime
                    ?: continue
                val left = clock.remaining(due).coerceAtLeast(0)
                _state.value = _state.value.copy(remainingMs = left)
                // 到点时玩家正盯着屏幕，就别逼她去拉通知栏：通知是"她不在看"时的路径。
                // 闹钟那条照旧存在（她不在前台时靠它），这里只是同一件事的第二个入口，
                // 台账与 resolveWait 的 due 判据保证两边不会把她结算两次。
                if (left <= 0L && resolvedFor != due) {
                    resolvedFor = due
                    onWake(save.waitRef)
                }
            }
        }
    }

    // ── 玩家侧动作 ────────────────────────────────────────────────────────────

    fun choose(option: StoryEngine.ChoiceOption) = mutate { s, eng -> eng.choose(s, clock, option) }

    /** 结局页唯一的按钮：『关掉它』 */
    fun closeEnding() = mutate { s, eng -> eng.openAfter(s, clock) }

    /**
     * 玩家按下发送。全部规则在引擎的 playerSays 里（配额、告别闸门、不重复武装等待），
     * 这里只负责把 UI 的输入框清空——演出层不该自己决定"这句话算不算数"。
     */
    fun sendPlayerLine(text: String) = mutate { s, eng -> eng.playerSays(s, clock, text) }

    /**
     * d1_n140 的『允许』。真正的系统弹窗由 Activity 提供（引擎层不许长出手去碰 Activity），
     * 弹窗结果无论允许还是拒绝都回灌引擎——她从不假装玩家答应过。
     */
    var grantRequester: (() -> Unit)? = null

    fun acceptGrant() {
        val ask = grantRequester
        if (ask == null) mutate { s, eng -> eng.resolveGrant(s, clock, true) } else ask()
    }

    fun resolveGrant(granted: Boolean) = mutate { s, eng -> eng.resolveGrant(s, clock, granted) }

    /** d7_n750『结束通话』：UI 入口，不注入度量（那是传送才需要的作弊） */
    fun openUiEntry(nodeId: String) = mutate { s, eng -> eng.advance(s, clock, nodeId) }

    /** 断开期间（d6_w640）玩家还能发几条：只有一条真的送达，剩下的是她事后解释的对象 */
    fun sendDuringWait(text: String) = mutate { s, eng -> eng.playerSays(s, clock, text) }

    /**
     * D7 那六秒（d7_n711，全篇唯一人声）。播之前四路声床必须已经淡到 0——
     * 段落编排在那两个节点上写了全 0，这里只负责不叠加任何东西。
     */
    fun playVoice() {
        val node = save.nodeId?.let { repo.node(it) } ?: return
        if (node.audioRef != null) player.playVoiceOnce(R.raw.voice_2014)
    }

    /** 剧本允许重放（replay_allowed）时才给按钮；否则那六秒只有一次，和她妈那次一样 */
    fun voiceReplayAllowed(): Boolean =
        (save.nodeId?.let { repo.node(it) }?.replayAllowed ?: 0) > 0

    /**
     * 真实时间闸门（沉默四小时）到点靠它重判；60 秒一次，够轻。
     *
     * `reEmitEntry = false`：这一句只是"再问一次选项出没出来"，不是"再走一遍这个节点"。
     * 带 emit 的重判会让玩家每盯着屏幕一分钟，她就把同一句话说一遍、那一笔度量再加一次
     * （转写导出《备份》时看得清清楚楚）。
     */
    fun recheck() = mutate { s, eng -> eng.advance(s, clock, s.nodeId, reEmitEntry = false) }

    /** 闹钟到点带回来的 ref：确认逾期就结算，没到点什么都不做（防重复投递） */
    fun onWake(ref: String?) = mutate { s, eng ->
        val due = s.waitUntilGameTime
        if (due != null && clock.gameTime() >= due) eng.resolveWait(s, clock)
        else eng.advance(s, clock, s.nodeId)
    }

    // ── 设置 ──────────────────────────────────────────────────────────────────

    fun setAmbienceEnabled(enabled: Boolean) {
        ambienceOn = enabled
        viewModelScope.launch { settings.setAmbienceEnabled(enabled) }
        if (!enabled) player.release() else stepAmbience()
        _state.value = _state.value.copy(ambienceOn = enabled)
    }

    /**
     * 「夜里也让她说」（docs/02 投递礼貌窗口）。默认关：23:30–07:30 唤醒点推到早上 07:30。
     * 改这条不动存档、不动游戏时钟，只影响下一次 `schedule()`——所以关掉之后已经排好的
     * 夜间闹钟还在，那是玩家的设备事实，我们不在她背后偷偷改玩家的睡眠。
     */
    fun setNightDeliveryEnabled(enabled: Boolean) {
        nightDeliveryOn = enabled
        viewModelScope.launch { settings.setNightDeliveryEnabled(enabled) }
        _state.value = _state.value.copy(nightDeliveryOn = enabled)
    }

    /** 圣经规则：她只在被需要时出声。回前台 > 90 秒之外一律静音 */
    fun onForeground(foreground: Boolean) {
        signals.foreground = foreground
        if (foreground) {
            backgroundSince = 0L
            signals.reenterCount += 1
        } else {
            backgroundSince = System.currentTimeMillis()
        }
        if (!foreground) player.release() else if (ambienceOn) stepAmbience()
    }

    /** 那行小字只出现一次，之后不再打扰 */
    fun dismissAmbienceInvite() {
        showInvite = false
        viewModelScope.launch { settings.markAmbienceInvited() }
        _state.value = _state.value.copy(showAmbienceInvite = false)
    }

    /**
     * 设置页的「恢复默认」。清的是设置：声床开关、夜间授权、那行邀请的一次性标记。
     *
     * 三件它**不做**的事都写在设置页那几行小字里，因为一颗会顺手毁掉七天进度的按钮
     * 不该只靠"用户以为它不会"来兜底：不删存档（那是『清空重开』的活，在开发面板里）、
     * 不撤系统权限（我们撤不掉，也不该假装撤得掉）、不关在途的等待闹钟（她该几点来还几点来）。
     */
    fun resetSettings() {
        ambienceOn = false
        nightDeliveryOn = false
        // 邀请那行下次进章节时才会重新出现（init 里那三个条件），这一屏不当场弹回去打扰
        showInvite = false
        player.release()
        viewModelScope.launch { settings.resetAll() }
        _state.value = _state.value.copy(
            ambienceOn = false,
            nightDeliveryOn = false,
            ambience = emptyMap(),
            showAmbienceInvite = false,
        )
    }

    fun onScreenOff(off: Boolean) {
        signals.screenOff = off
        if (ambienceOn) stepAmbience()
    }

    /** 退出到后台/关闭前：把在途存档落盘（引擎已经每次 mutate 都写，这里只补时间戳） */
    fun touchLastPlayed() {
        viewModelScope.launch {
            save.lastPlayedAt = System.currentTimeMillis()
            store.write(save)
        }
    }

    // ── 后门（docs/04 第二节八项）────────────────────────────────────────────

    /** ① 时间倍率：1× / 60× / 3600× / 自定义。时钟层生效，不是逐节点打补丁 */
    fun setRate(rate: Double) {
        clock.setRate(rate.coerceIn(1.0, 3600.0))
        mutate { s, eng -> eng.advance(s, clock, s.nodeId) }
    }

    /** ③ 按 node id 传送；跳章入口也走这里 */
    fun teleport(nodeId: String) = mutate { s, eng -> eng.teleport(s, clock, nodeId.trim()) }

    /** ② 跳章：注入该章起点三度量（面板必须把注入的数字显示出来） */
    fun startChapter(chapter: Int) {
        mutate { s, eng ->
            s.chapter = chapter
            eng.applyChapterStartState(s, chapter)
            s.nodeId = null
            s.debugUsed = true
            eng.advance(s, clock)
        }
    }

    /** ④ 度量实时读写：可达性检查靠它，不靠读代码推 */
    /**
     * ④ 度量读写。
     *
     * `reEmitEntry = false` 是必须的：这里只是"把这一个数改掉然后重判闸门"，
     * 不是"再走一遍这个节点"。带着 emit 走一遍的代价是真撞到的——拖一下滑块，
     * 她当前那段话就又被贴进转写一遍、那一笔信任又加一次（和 `recheck()` 修过的是同一类坑，
     * 见 PlaythroughTest 里"原地重判不许有副作用"那条）。
     */
    fun setMetric(name: String, value: Double) {
        mutate { s, eng ->
            s.metrics.set(name, value)
            s.debugUsed = true
            eng.advance(s, clock, s.nodeId, reEmitEntry = false)
        }
    }

    /** ④ 的复位：把三度量拉回**本章起点**声明的那组值，不动节点、不动转写 */
    fun resetMetricsToChapterStart() {
        mutate { s, eng ->
            s.debugUsed = true
            eng.applyChapterStartState(s)
            eng.advance(s, clock, s.nodeId, reEmitEntry = false)
        }
    }

    /** ⑤ 伪造 signal：null 表示取消伪造 */
    fun overrideSignal(name: String, value: Boolean?) {
        signals.override(name, value)
        mutate { s, eng -> eng.advance(s, clock, s.nodeId, reEmitEntry = false) }
    }

    /** 跳等待：把时钟推到 due 之后。等待照常结算，只是"她被压缩了"——记进 skippedMs */
    fun skipCurrentWait() = mutate { s, eng ->
        s.debugUsed = true
        // 跳掉的等待不能留一个闹钟在后面。投递会被 WakeGate 的"这条还在不在档上"挡住，
        // 但那是一次无谓的唤醒、一条还挂在系统里的 PendingIntent，通知栏里可能还贴着
        // 她上一句已经发出去的话——三样都得当场撤掉。
        s.waitRef?.let { ReminderScheduler.cancel(getApplication(), it) }
        eng.resolveWait(s, clock, forced = true)
    }

    /** 伪造一天：OFFSET += 86_400_000×N，等待与衰减一起被推过去 */
    fun jumpDays(days: Int) {
        clock.jump(GameClock.DAY_MS * days)
        mutate { s, eng -> eng.advance(s, clock, s.nodeId) }
    }

    /** 夜间窗口按设备时钟还是游戏时钟（docs/04 第三节唯一例外） */
    fun setNightUsesDeviceClock(use: Boolean) {
        signals.nightUsesDeviceClock = use
        mutate { s, eng -> eng.advance(s, clock, s.nodeId) }
    }

    /** ⑥ 结局直达：面板在旁边显示"自然达成需要的前置条件"，我们对比设计意图与实际演出 */
    fun jumpToEnding(nodeId: String) = mutate { s, eng ->
        s.debugUsed = true
        eng.teleport(s, clock, nodeId)
    }

    /** ⑦ 强停模拟：先落盘再 kill，验"重启后不重复投递、不丢消息" */
    fun forceKill() {
        viewModelScope.launch {
            save.rate = clock.rate
            save.offset = clock.offset
            store.write(save)
            delay(300)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    /**
     * ⑦ 的另一半：投递台账（设备侧那张"这句发过了"的表）。
     *
     * 清台账 + 强停重开，才是 docs/04 第⑦项要的完整验收——只 kill 进程不清台账的话，
     * 同一条等待第二次醒来必然被"已发过"挡掉，你会误判成"补投失效"。
     * 反过来说：**清完台账她应该重新说一次**；不清就绝不重说。两句话都要能当场验。
     */
    fun postedDeliveries(): List<String> = DeliveryLedger.postedTokens(getApplication())

    fun clearDeliveryLedger() {
        DeliveryLedger.clear(getApplication())
    }

    /** 玩家层快速模式（通关一次后解锁）；W5-2 由剧本 fast_mode_skippable=false 保证不可跳 */
    fun setQuickMode(on: Boolean) {
        mutate { s, eng ->
            s.quickMode = on
            // 剧本判定读的是 flag（d7_c12 的「我把你存下来了」needs quick_mode、
            // 隐藏结局要 quick_mode == false），存档那个布尔只管界面按钮
            s.flags["quick_mode"] = on
            eng.advance(s, clock, s.nodeId)
        }
    }

    /** ⑧ 失真日志导出 + 结局《备份》的素材：把转写写成 txt 落到应用私有目录 */
    fun exportTranscript(): File {
        val dir = File(getApplication<Application>().filesDir, "exports").apply { mkdirs() }
        val file = File(dir, "小满-307.txt")
        file.writeText(
            buildString {
                appendLine("《正在备份》转写导出｜章 D${save.chapter}｜${save.metrics.snapshot()}")
                appendLine("跳过 ${"%.1f".format(save.skippedMs / 3_600_000.0)} 小时｜" +
                        "失真节点 ${save.skippedWaits.joinToString("、")}｜" +
                        "倍率 ${clock.rate}×")
                appendLine("─".repeat(40))
                // 屏幕上有分隔，导出也必须有一份：这份 txt 是她"以文本形式活着"的证据，
                // 里面每一条等待都是真等过的，不该被压成一封连续的短信
                val gaps = TimeGaps.gaps(save.transcript)
                save.transcript.forEachIndexed { i, line ->
                    gaps[i]?.let { appendLine("─".repeat(12) + " $it " + "─".repeat(12)) }
                    val marks = buildString {
                        if (line.debug) append("〔DBG〕")
                        if (line.skipped) append("〔已跳过〕")
                    }
                    appendLine("[${line.node}] ${line.speaker}：${line.text}$marks")
                }
            }
        )
        return file
    }

    /** 后门面板解锁（连点标题 5 次 + 口令 307 由 UI 侧把关，这里只记状态） */
    fun unlockBackdoor() {
        _state.value = _state.value.copy(backdoorUnlocked = true)
    }

    fun wipeAndRestart() {
        viewModelScope.launch {
            store.wipe()
            save = SaveFile()
            clock = GameClock(save.installAt)
            engine.applyChapterStartState(save, chapter = 1)
            persist(save, engine.advance(save, clock))
        }
    }

    /** D7 的『结束通话』：会话页右上角唯一的显式结束入口（圣经：非选项） */
    fun uiEntryNode(): Node? {
        if (save.chapter != 7) return null
        val script = repo.chapters.getOrNull(6) ?: return null
        val order = script.nodes.map { it.id }
        val gate = order.indexOf("d7_n740")
        val at = save.nodeId?.let { order.indexOf(it) } ?: -1
        val entry = script.nodes.firstOrNull { it.uiEntry == true } ?: return null
        return if (at >= gate && at < order.indexOf(entry.id)) entry else null
    }

    // ── 声床 ──────────────────────────────────────────────────────────────────

    /**
     * 一次 tick：算目标、切变体、推给播放器。
     *
     * 整段搬到 Default 线程：`ensureBed` 会把整条 27 秒 / 16kHz / 16bit 的 WAV（约 800KB）
     * 一次性读进内存再写进 AudioTrack，五条床第一次装载就是 4MB 上下的 IO——
     * 这在主线程上是一次可被玩家看见的卡顿（她正要点下一个选项）。
     * StateFlow 与 MediaPlayer 都受得了后台线程，`save` 在这里只读不写。
     */
    private fun stepAmbience() {
        viewModelScope.launch(Dispatchers.Default) { doStepAmbience() }
    }

    private fun doStepAmbience() {
        val script = repo.chapters.getOrNull(save.chapter - 1) ?: return
        val order = script.nodes.map { it.id }
        val device = AmbienceDirector.DeviceState(
            screenOff = signals.screenOff,
            nightWindow = signals.inNightWindow(
                if (signals.nightUsesDeviceClock) clock.deviceTime() else clock.gameTime()
            ),
            batteryLow = signals.batteryLow(),
            powerSave = signals.powerSaveOn(),
            leftOver90s = backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > LEFT_BACKGROUND_MS,
        )
        val gains = director.targetGains(script.meta.ambience, save.nodeId, order, save.metrics.integrity, device)
        // 变体切换：淡到 0 再换素材，听不出"换了"才算环境
        gains[AmbienceDirector.ANOMALY]?.let {
            player.swapVariant(AmbienceDirector.ANOMALY, director.anomalyVariant(save.metrics.integrity))
        }
        player.applyTargets(gains)
        player.tick()
        _state.value = _state.value.copy(ambience = player.status(), nodeId = save.nodeId)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private fun mutate(block: (SaveFile, StoryEngine) -> StoryEngine.Halt) {
        viewModelScope.launch {
            loaded.await()          // 见 [loaded]：读盘没完成前不许动存档
            val halt = block(save, engine)
            persist(save, halt)
        }
    }

    private suspend fun persist(save: SaveFile, halt: StoryEngine.Halt) {
        engine.applyChapterClamp(save)
        save.rate = clock.rate
        save.offset = clock.offset
        save.lastPlayedAt = System.currentTimeMillis()
        store.write(save)
        if (halt is StoryEngine.Halt.AwaitingWait) armWait(save, halt)
        // 人声只在"走进这个节点"的那一刻放一次：persist 每次状态变动都会走（点一下屏幕、
        // 跳一次声床节拍），在这儿无条件播等于 d7_n711 那六秒被重播无数遍——
        // 而剧本给它的设定是"只有一次，和她妈那次一样"（replay_allowed=0）
        val nodeId = save.nodeId
        if (nodeId != null && nodeId != lastVoiceNode && repo.node(nodeId)?.audioRef != null) {
            lastVoiceNode = nodeId
            player.playVoiceOnce(R.raw.voice_2014)
        }
        armPendingNotifications(save)
        syncYearly(save, (halt as? StoryEngine.Halt.Paused)?.node)
        _state.value = _state.value.copy(
            ready = true,
            showAmbienceInvite = showInvite,
            transcript = save.transcript.toList(),
            seenAtLaunch = seenAtLaunch,
            halt = halt,
            metrics = save.metrics.snapshot(),
            chapter = save.chapter,
            nodeId = save.nodeId,
            rate = clock.rate,
            skippedHours = save.skippedMs / 3_600_000.0,
            skippedWaits = save.skippedWaits.toList(),
            debugUsed = save.debugUsed,
            quickMode = save.quickMode,
            flags = save.flags.filterValues { it }.keys.toList(),
            exactAlarmBlocked = !signals.exactAlarmGranted,
            notifyBlocked = signals.notifyDenied,
            ambienceOn = ambienceOn,
            nightDeliveryOn = nightDeliveryOn,
            remainingMs = (halt as? StoryEngine.Halt.AwaitingWait)
                ?.let { clock.remaining(it.dueGameTime).coerceAtLeast(0) } ?: 0L,
        )
        if (ambienceOn) stepAmbience()
    }

    /**
     * 剧本声明的"退出后补投"（d1_n150 那条 60 秒后的『我数到六十了』）。
     * 只排一次：armedAt 记在存档里，重启不重复投递（docs/04 验收 4）。
     */
    private fun armPendingNotifications(save: SaveFile) {
        val fresh = save.pendingNotifications.filter { it.armedAt == 0L }
        val keep = save.pendingNotifications.filterNot { it in fresh }.toMutableList()
        fresh.forEach { notice ->
            val skip = notice.onlyIf != null && save.flags[notice.onlyIf] != true
            if (!skip) {
                ReminderScheduler.schedule(
                    getApplication(), notice.delayMs, "post-${notice.node}", notice.text,
                    nightDeliveryOn,
                )
                notice.armedAt = System.currentTimeMillis()
            }
            keep.add(notice)      // 被 only_if 挡掉的也留着：她确实写过这条，只是不发
        }
        save.pendingNotifications = keep
    }

    /** 等待只声明 duration_ms，唤醒由时钟层算差值；exact 被拒时在状态里标出来 */
    private fun armWait(save: SaveFile, halt: StoryEngine.Halt.AwaitingWait) {
        val remaining = clock.remaining(halt.dueGameTime)
        val text = halt.node.firstLineAfterWait ?: return
        val ref = halt.node.waitRef ?: halt.node.id
        // 已经在系统里挂着的闹钟不要重排：publish 每次状态变动都会走到这里，
        // 重排等于反复替换同一个 PendingIntent，并把兜底 worker 的 5 分钟一直往后推——
        // 玩家每次点一下屏幕她就永远迟到 5 分钟。闹钟在，就不动它。
        if (remaining > 0 && ReminderScheduler.hasPendingAlarm(getApplication(), ref)) return
        val exact = ReminderScheduler.schedule(getApplication(), remaining, ref, text, nightDeliveryOn)
        _state.value = _state.value.copy(exactAlarmBlocked = !exact)
    }

    /**
     * 周年触发（《紧急联系人》那句"每年 10 月 31 日 20:14，你会真的收到一条她的消息"）。
     *
     * 三件事按顺序做，顺序不能换：
     *   1. 今年那条还在路上（`now < yearly_due_at`）→ 什么都不做。
     *      这个函数每次状态变动都会走，一年内可能要走上千次。
     *   2. 上一年那条**还没真的投出去**（台账里没有）→ 也不动。
     *      推前推等于把迟到的闹钟闭嘴：玩家错过一次省电模式，就白等一年。
     *   3. 都过完了才排下一次，并把"第几年"加一——文案池下标由它算，第 13 年回到第一条。
     *
     * 走哪个结局的文案池由剧本决定（结局节点带 `sample_yearly_lines` 就是它有周年），
     * 这里不写死 `d7_e760`，也不写死 `ending_emergency_contact`。
     * 锚点用设备时间：RATE 能压缩 22 小时的等待，但不能压缩一年。
     */
    private fun syncYearly(save: SaveFile, endingNode: Node?) {
        val now = System.currentTimeMillis()
        val due = save.yearlyDueAt
        if (due != 0L && now < due) return
        val source = endingNode?.takeIf { it.yearlyLines().isNotEmpty() }
            ?: save.yearlySourceNode?.let { repo.node(it) }
        val pool = source?.yearlyLines().orEmpty()
        if (source == null || pool.isEmpty()) return
        if (due != 0L) {
            if (!DeliveryLedger.alreadyPosted(getApplication(), YearlyTrigger.ref(due))) return
            save.yearlyMessagesSent += 1
        }
        val anchor = YearlyTrigger.nextAnchor(now)
        val text = YearlyTrigger.lineFor(pool, save.yearlyMessagesSent) ?: return
        save.yearlySourceNode = source.id
        save.yearlyDueAt = anchor
        ReminderScheduler.schedule(
            getApplication(), anchor - now, YearlyTrigger.ref(anchor), text,
        )
    }

    /** 面板与后门要读的静态资料，一次性给齐 */
    fun backdoorData(): BackdoorData = BackdoorData(
        chapters = repo.chapters.map { it.meta },
        nodeIds = repo.chapters.flatMap { script -> script.nodes.map { it.id } },
        endings = repo.chapters.flatMap { s -> s.nodes.filter { it.isEnding }.map { it to s.meta } },
        peaks = repo.chapters.flatMap { s -> s.nodes.filter { it.peakMarker }.map { it.id to (it.note ?: "") } },
        choiceNodes = repo.chapters.flatMap { s -> s.nodes.filter { it.isChoices }.map { it.id } },
        chapterOrder = repo.chapters.getOrNull(save.chapter - 1)?.nodes?.map { it.id } ?: emptyList(),
        startStates = repo.chapters.map { it.meta.chapterId to it.meta.startState },
        signals = signals.whitelist.toList(),
        metricRange = 0.0..100.0,
        metrics = Triple(save.metrics.trust, save.metrics.awareness, save.metrics.integrity),
        ambienceNote = repo.chapters.getOrNull(save.chapter - 1)?.meta?.ambience?.let { a ->
            director.segmentAt(a, save.nodeId, repo.chapters[save.chapter - 1].nodes.map { it.id })?.note
        },
    )

    data class BackdoorData(
        val chapters: List<cn.linan.backingup.story.Meta>,
        val nodeIds: List<String>,
        val endings: List<Pair<Node, cn.linan.backingup.story.Meta>>,
        val peaks: List<Pair<String, String>>,
        val signals: List<String>,
        val metricRange: ClosedRange<Double>,
        val metrics: Triple<Double, Double, Double>,
        val ambienceNote: String?,
        /** 传送到上一个 choice 用 */
        val choiceNodes: List<String>,
        val chapterOrder: List<String>,
        /** 跳章注入的数字必须能跟预算表逐字对上，所以面板把它读出来给*/
        val startStates: List<Pair<String, Map<String, Double>>>,
    )

    data class ViewState(
        val ready: Boolean = false,
        val transcript: List<TranscriptLine> = emptyList(),
        /**
         * 本次开机**读盘时**转写里已经有多少行。
         * 会话页靠它区分"她已经说过的历史"和"这一次新说的话"：历史一次性上屏，
         * 新说的才逐条浮现。没有这个数，玩到 D7 之后每次重开 App 都要把七章重演一遍。
         */
        val seenAtLaunch: Int = 0,
        val halt: StoryEngine.Halt? = null,
        val metrics: String = "",
        val chapter: Int = 1,
        val nodeId: String? = null,
        val rate: Double = 1.0,
        val skippedHours: Double = 0.0,
        val skippedWaits: List<String> = emptyList(),
        val debugUsed: Boolean = false,
        val quickMode: Boolean = false,
        val exactAlarmBlocked: Boolean = false,
        /** 系统层面的通知开关真值（13+ 装机默认关）；D1 授权点之前它也是真的关着 */
        val notifyBlocked: Boolean = false,
        val flags: List<String> = emptyList(),
        val remainingMs: Long = 0L,
        val ambienceOn: Boolean = false,
        /** 「夜里也让她说」：false = 23:30–07:30 她的话推到早上 07:30 进通知栏 */
        val nightDeliveryOn: Boolean = false,
        val showAmbienceInvite: Boolean = false,
        val ambience: Map<String, String> = emptyMap(),
        /** 后门面板：连点标题 5 次后弹口令 */
        val backdoorUnlocked: Boolean = false,
    )

    companion object {
        const val LEFT_BACKGROUND_MS = 90_000L      // docs/06 红线 2
        const val PLAYER_TYPING_MS = 1200L
    }
}
