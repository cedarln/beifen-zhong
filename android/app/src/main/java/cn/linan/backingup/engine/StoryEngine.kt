package cn.linan.backingup.engine

import cn.linan.backingup.story.Node
import cn.linan.backingup.story.ScriptRepository

/**
 * 剧情引擎：把剧本 JSON 走成一条转写。
 *
 * 三条结构性约定，决定了这个类为什么这么小：
 *  1. 引擎不发明内容。所有文本来自 script 目录的 act1..act7 剧本，所有分支由
 *     needs / needs_signal / conditional_line(s) 三种声明驱动。引擎里不许出现任何一句台词。
 *     （唯一的例外是 silence 节点那行"若干秒没有再出现输入指示"——那是演出说明，
 *     不是她的话；契约里 silence 节点本来就没有 text。）
 *  2. 时钟只有一个来源（[GameClock]）。等待不挂在 Handler 上，而是把 due 写进存档，
 *     冷启动与开机各做一次补投——逾期不补投等于玩家以为游戏坏了。
 *  3. 推进与演出分离。[advance] 只回答"下一个停在哪"，逐条气泡的节拍在 UI 层。
 *     这样后门面板按 node id 传送时不需要伪造演出。
 */
class StoryEngine(
    private val repo: ScriptRepository,
    private val signals: SignalReader,
) {

    /** 一次推进停在哪里，UI 只需要这一个类型 */
    sealed interface Halt {
        data class AwaitingChoice(val node: Node, val options: List<ChoiceOption>) : Halt
        data class AwaitingWait(val node: Node, val dueGameTime: Long) : Halt
        data class AwaitingInput(val node: Node, val wordLimit: Int) : Halt
        /** d1_n140：唯一的"设备权限即剧情"节点。允许/拒绝都是剧情，不是弹窗 */
        data class AwaitingGrant(val node: Node) : Halt
        data class Paused(val node: Node) : Halt
        data class DeadEnd(val nodeId: String, val why: String) : Halt
    }

    data class ChoiceOption(
        val text: String,
        val to: String,
        val effects: Map<String, Double>,
        val flagsSet: List<String> = emptyList(),
    )

    /**
     * 从 [startNodeId]（默认存档位置）往前走，自动节点一路走完，直到需要玩家或需要时间。
     * 每一步都写进 [SaveFile.transcript]——转写是结局《备份》的唯一素材。
     *
     * [ignoreEntryGate] 只给后门用（docs/04 第二节第 2/3 条）：传送的目标本身常常在闸门
     * 后面，连它都要判就等于不能传送。但它之后的每个节点照常判——后门开的是这一扇门，
     * 不是整条路。
     *
     * [reEmitEntry] = false 给"原地重判"用（d6 沉默四小时那条真实时间闸门，UI 每分钟要
     * 重问一次选项出没出来）。emit 是有副作用的：贴台词、加度量、记 flag、挂补投。
     * 重判入口节点却把 emit 再走一遍，等于她每分钟把同一句话说一遍、同一笔信任加一次——
     * 所以入口可以不 emit，后面的节点照旧必须 emit（它们是真没走过）。
     */
    fun advance(
        save: SaveFile,
        clock: GameClock,
        startNodeId: String? = save.nodeId,
        ignoreEntryGate: Boolean = false,
        reEmitEntry: Boolean = true,
    ): Halt {
        var id = startNodeId ?: repo.chapterEntry(save.chapter)?.id
            ?: return Halt.DeadEnd(startNodeId ?: "?", "第 ${save.chapter} 章不在剧本里")
        var guard = 0
        var atEntry = true
        while (guard++ < MAX_STEPS) {
            val node = repo.node(id)
                ?: return Halt.DeadEnd(id, "assets 里没有这个节点")
            save.nodeId = id

            // needs 不满足 → 走 fallback。契约规定带 needs 的闸门节点必须有 fallback，
            // 没有就是剧本事故，报出来而不是静默卡住
            if (!atEntry || !ignoreEntryGate) {
                if (!gatePasses(node, save, clock)) {
                    val fallback = node.fallbackId
                        ?: return Halt.DeadEnd(id, "needs 不满足且没有 fallback（剧本事故）")
                    id = fallback
                    continue
                }
            }
            val entry = atEntry
            atEntry = false

            if (!entry || reEmitEntry) emit(save, node, clock)

            when (node.type) {
                "choices" -> {
                    val options = availableChoices(node, save, clock)
                    // 全被闸门挡住是设计而不是 bug：d6 的沉默路线就是"没有选项可点"，
                    // 玩家只能退出、等真实时间过去。这里给 UI 一个空列表，不许自己造选项。
                    return Halt.AwaitingChoice(node, options)
                }

                "wait" -> return armWait(save, clock, node)

                "ending" -> {
                    // 结局页只有『关掉它』一个按钮。after 不是自动边，是那个按钮的落点，
                    // 所以这里停下来；点按钮由 UI 调 openAfter()
                    return Halt.Paused(node)
                }
            }

            if (node.isGrant) return Halt.AwaitingGrant(node)
            if (node.sendAllowance() > 0 && node.type != "wait") {
                return Halt.AwaitingInput(node, node.wordLimit ?: DEFAULT_WORD_LIMIT)
            }
            if (node.chapterEnd == true) {
                // 章末是"这一天过完了"的事实，只记一次：原地重判会反复经过这个节点，
                // 每次都改锚点的话，玩家盯着屏幕看她就永远到不了第二天
                if (save.chapterEndAtGameTime == null) save.chapterEndAtGameTime = clock.gameTime()
                return Halt.Paused(node)
            }

            val next = node.next ?: node.then
                ?: return Halt.DeadEnd(id, "没有出边（type=${node.type}）")
            id = next
        }
        return Halt.DeadEnd(save.nodeId ?: id, "推进超过 $MAX_STEPS 步，next 图成环")
    }

    /**
     * 玩家可见的选项：needs 不满足的直接不出现（隐藏，不置灰——她不会告诉玩家"你还不够信任我"）。
     *
     * 除选项自己的 needs，还要判**目标节点**的 requires：隐藏结局《紧急联系人》的条件写在
     * 结局上（那是结局的定义，不是选项的限制），但让一个"点下去什么也不会发生"的按钮留在
     * 屏幕上更糟。两条都判，取交集。
     */
    fun availableChoices(node: Node, save: SaveFile, clock: GameClock): List<ChoiceOption> {
        val cond = condition(save, clock)
        return node.choices.filter { choice ->
            choice.needsExpressions().all { cond.check(it) } &&
                (repo.node(choice.to)?.requiresExpressions()?.all { cond.check(it) } ?: true)
        }.map { ChoiceOption(it.text, it.to, it.effects, it.flagsSet) }
    }

    /** 玩家选了某条选项：先结算它自带的效果与事实，再从它的 to 继续走 */
    fun choose(save: SaveFile, clock: GameClock, option: ChoiceOption): Halt {
        applyEffects(save.metrics, option.effects, "选项 ${option.text}")
        applyFlagList(save, option.flagsSet)
        recordPlayerLine(save, clock, save.nodeId ?: "?", option.text)
        return advance(save, clock, option.to)
    }

    /**
     * 等待到点（或由快速模式/后门强制到点）。
     * 每个 wait 都必须带 first_line_after_wait（结构文档第七节，自检脚本已强制），
     * 所以摘要永远有东西可显示。
     */
    fun resolveWait(save: SaveFile, clock: GameClock, forced: Boolean = false): Halt {
        val due = save.waitUntilGameTime ?: return advance(save, clock)
        val node = save.nodeId?.let { repo.node(it) } ?: return advance(save, clock)
        if (!forced && clock.gameTime() < due) return Halt.AwaitingWait(node, due)

        val waitedMs = (clock.gameTime() - (due - (node.durationMs ?: 0L))).coerceAtLeast(0L)
        if (forced && node.fastModeSummary != null) {
            save.transcript.add(
                TranscriptLine(
                    node.id, SPEAKER_SYSTEM, node.fastModeSummary,
                    clock.gameTime(), kind = "wait_summary", debug = true,
                )
            )
            // 失真日志：被跳过的等待必须留下痕迹，否则我们无法"不作弊地评估"
            save.skippedMs += (due - clock.gameTime()).coerceAtLeast(0L)
            node.waitRef?.let { if (it !in save.skippedWaits) save.skippedWaits.add(it) }
        }
        save.waitUntilGameTime = null
        save.waitRef = null
        // 断开期的三条配额属于这一个等待，不跟着进下一个（d6_w640 → d6_w650）
        save.waitSendsUsed = 0

        node.firstLineAfterWait?.let { line ->
            save.transcript.add(
                TranscriptLine(
                    node.id, node.speaker ?: SPEAKER_HER, line,
                    clock.gameTime(), kind = "her",
                    debug = forced, skipped = forced,
                )
            )
        }
        // 等了多少也记进度量：她一个人在那边过的时间不能白过
        if (waitedMs > 0) decayByWait(save, node, waitedMs)
        return advance(save, clock, node.next ?: node.then)
    }

    /**
     * 章与章之间的门：这一章走完了、且游戏时钟跨过了"次日早上"，就把下一章接上。
     *
     * 剧本里 `chapter_end` 节点全部没有出边（见 [ChapterGate] 的类注释）：下一章不属于
     * 这一章的图，它属于第二天。所以这条是唯一的自然跨章入口——冷启动、她那条章间闹钟、
     * 屏上的秒级自判，三个地方都会走到这里，谁先到算谁，其余两次因为锚点已清空而空转。
     *
     * 度量**不注入** `meta.start_state`（那是后门跳章的作弊），玩家自己攒的数带进第二天，
     * 只按跨过的真实日数扣 integrity：docs/02 规则 2「玩家拖着不玩，她就提前散架」。
     *
     * @return null = 现在不该开章（本章没走完 / 还没到次日早上 / 已经是最后一章）
     */
    fun openNextChapter(save: SaveFile, clock: GameClock): Halt? {
        val opens = chapterOpensAt(save) ?: return null
        if (clock.gameTime() < opens) return null
        val ended = save.chapterEndAtGameTime ?: return null
        val days = ChapterGate.daysElapsed(ended, clock.gameTime())
        val next = save.chapter + 1
        save.metrics.set("integrity", save.metrics.integrity - days * ChapterGate.INTEGRITY_DECAY_PER_DAY)
        save.chapter = next
        save.nodeId = null
        save.chapterEndAtGameTime = null
        save.waitUntilGameTime = null
        save.waitRef = null
        save.waitSendsUsed = 0
        return advance(save, clock)
    }

    /** 下一次开章的游戏时刻；null = 现在不在章与章之间。给秒级自判和章间闹钟共用 */
    fun chapterOpensAt(save: SaveFile): Long? {
        val ended = save.chapterEndAtGameTime ?: return null
        if (save.chapter >= ChapterGate.LAST_CHAPTER) return null
        if (repo.chapterEntry(save.chapter + 1) == null) return null
        return ChapterGate.openingAt(ended)
    }

    /**
     * 老存档补锚点（v0.1.5 之前根本没有这道门，走到章末的存档里这个字段是空的）。
     *
     * 不补的话，已经读完 D1 的玩家装上这一版会永远停在"她那边还在过时间"——那是我们后补
     * 规则的债，不该让她再等一个早上。所以拿**最后一次游玩**那一刻当章末时刻：
     * 她三天前读完的，今天就该接上 D2；此后的日子照旧一天一章。
     */
    fun seedChapterAnchor(save: SaveFile, clock: GameClock) {
        if (save.chapterEndAtGameTime != null) return
        val node = save.nodeId?.let { repo.node(it) } ?: return
        if (node.chapterEnd == true) save.chapterEndAtGameTime = clock.gameTime(save.lastPlayedAt)
    }

    /**
     * 章间闹钟的文案：**下一章入口节点的第一句**（引擎不发明内容，通知也不行）。
     * 没有下一章 / 还没走到章末时返回 null，调用方就不排这条。
     */
    fun nextChapterOpeningLine(save: SaveFile, clock: GameClock): String? {
        chapterOpensAt(save) ?: return null
        val entry = repo.chapterEntry(save.chapter + 1) ?: return null
        return entry.resolvedText(condition(save, clock)).firstOrNull()
    }

    /**
     * 结局页之后：『关掉它』按钮的落点（after 字段）。
     * d7_end 负责解锁快速模式与尾声预告，所以这里只负责走过去。
     */
    fun openAfter(save: SaveFile, clock: GameClock): Halt {
        val current = save.nodeId?.let { repo.node(it) }
            ?: return Halt.DeadEnd(save.nodeId ?: "", "当前节点不存在")
        val after = current.after ?: return Halt.Paused(current)
        return advance(save, clock, after)
    }

    /** 后门：按 node id 传送。跳章要先注入该章起始三度量，否则 needs 全线不满足 */
    fun teleport(save: SaveFile, clock: GameClock, nodeId: String): Halt {
        val node = repo.node(nodeId) ?: return Halt.DeadEnd(nodeId, "不存在")
        save.chapter = chapterOf(nodeId) ?: save.chapter
        applyChapterStartState(save)
        save.waitUntilGameTime = null
        save.chapterEndAtGameTime = null
        save.debugUsed = true
        save.transcript.add(
            TranscriptLine(
                node.id, SPEAKER_SYSTEM,
                "［DBG］传送 → ${node.id}｜度量注入 D${save.chapter} 起点 ${save.metrics.snapshot()}",
                clock.gameTime(), kind = "system", debug = true,
            )
        )
        return advance(save, clock, nodeId, ignoreEntryGate = true)
    }

    /**
     * d1_n140 的授权结果。允许=trust+3 且从此可以精确唤醒；拒绝=trust-1 且
     * `notify_denied` 成为后续节点的世界事实（D5 那三十分钟没有红点可推）。
     */
    fun resolveGrant(save: SaveFile, clock: GameClock, granted: Boolean): Halt {
        val node = save.nodeId?.let { repo.node(it) }
            ?: return Halt.DeadEnd(save.nodeId ?: "", "当前节点不是授权节点")
        val branch = if (granted) node.onGrant else node.onDeny
        applyEffects(save.metrics, branch?.effects ?: emptyMap(), node.id)
        signals.recordGrant(granted)
        save.flags[if (granted) FLAG_NOTIFY_GRANTED else FLAG_NOTIFY_DENIED] = true
        val then = branch?.then ?: node.next ?: node.then
            ?: return Halt.DeadEnd(node.id, "授权分支没有落点（剧本事故）")
        return advance(save, clock, then)
    }

    /** 新章开局 / 跳章：三度量拉到 meta.start_state */
    fun applyChapterStartState(save: SaveFile, chapter: Int = save.chapter): Boolean {
        val script = repo.chapters.getOrNull(chapter - 1) ?: return false
        script.meta.startState.forEach { (name, value) -> save.metrics.set(name, value) }
        return true
    }

    /** 章内安全阀：meta.clamp.awareness_max_this_chapter（圣经规则 1：D6 之前不得 ≥70） */
    fun applyChapterClamp(save: SaveFile) {
        val clamp = repo.chapters.getOrNull(save.chapter - 1)?.meta?.clamp ?: return
        val max = clamp.asJsonObject?.get("awareness_max_this_chapter")?.asDouble ?: return
        if (save.metrics.awareness > max) save.metrics.awareness = max
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 武装一个等待节点。
     *
     * 两条必须在 arm 之前判掉的规则：
     *  · `farewell_required`（d6_w650）——玩家不开口，这个 22 小时的等待就不许开始。
     *    她是先说"你别熬夜等我"才走的，但那句话得由玩家先接住；这是全篇唯一一次
     *    玩家主动输入，所以它是闸门，不是提示。
     *  · 同一个等待不重复武装。断开期补投（d6_w640 的三条）走的也是 advance，
     *    如果每次都重算 due，玩家每发一条就把 11 小时往后推一次——那等于玩家
     *    说话反而让她消失得更久。已有 waitRef 在途时直接返回原来的 due。
     */
    private fun armWait(save: SaveFile, clock: GameClock, node: Node): Halt {
        if (node.farewellRequired == true && save.farewellSentFor != node.id) {
            // 先让她把那句说完，玩家才有一个要接的话。重复 advance 不重复贴同一条
            val line = node.farewellLine
            if (line != null && save.transcript.none { it.node == node.id && it.kind == "her" }) {
                save.transcript.add(
                    TranscriptLine(node.id, node.speaker ?: SPEAKER_HER, line,
                        clock.gameTime(), kind = "her", typingMs = node.typingMsFor())
                )
            }
            return Halt.AwaitingInput(node, node.wordLimit ?: DEFAULT_WORD_LIMIT)
        }
        // ref 归一：wait_ref 缺失时用节点 id 兜底，保证"在途等待必有一个非空 ref"。
        // 调度层（WakeReceiver）拿它当"这句还该不该说"的凭据，null 会让到点的唤醒石沉大海。
        val ref = node.waitRef ?: node.id
        val inFlight = save.waitUntilGameTime
        if (inFlight != null && save.waitRef == ref && !clock.isDue(inFlight)) {
            return Halt.AwaitingWait(node, inFlight)
        }
        save.waitRef = ref
        save.waitUntilGameTime = clock.gameTime() + (node.durationMs ?: 0L)
        return Halt.AwaitingWait(node, save.waitUntilGameTime!!)
    }

    /**
     * 玩家说话，唯一的入口。三种落点在剧本里互不重叠：
     *  · 带 `player_may_send` 的断开期等待（d6_w640，三条只算一条送达）
     *  · 带 `farewell_required` 的等待（d6_w650，说完才武装计时）
     *  · 普通输入节点（目前七个章文件里没有，留着是因为契约允许剧本随时加）
     */
    fun playerSays(save: SaveFile, clock: GameClock, rawText: String): Halt {
        val text = rawText.trim()
        // 空话不推进，但也不许把当前节点重新演一遍（这是"原地重判"，不是"再走一次"）
        if (text.isEmpty()) return advance(save, clock, save.nodeId, reEmitEntry = false)
        val node = save.nodeId?.let { repo.node(it) }
            ?: return Halt.DeadEnd(save.nodeId ?: "", "当前节点不存在，无处可说")
        val allowance = node.sendAllowance()
        if (allowance > 0 && save.waitSendsUsed >= allowance) {
            // 配额用完：话还是要记进转写（结局《备份》里那两条"没送达的"必须存在），
            // 但不再推进——她事后会说"另外两条等我有空再讲"
            recordPlayerLine(save, clock, node.id, text)
            return Halt.AwaitingWait(node, save.waitUntilGameTime ?: clock.gameTime())
        }
        if (allowance > 0) save.waitSendsUsed += 1
        recordPlayerLine(save, clock, node.id, text)
        if (node.farewellRequired == true) save.farewellSentFor = node.id
        return if (node.type == "wait") armWait(save, clock, node)
        else advance(save, clock, node.next ?: node.then)
    }

    private fun recordPlayerLine(save: SaveFile, clock: GameClock, nodeId: String, text: String) {
        save.transcript.add(
            TranscriptLine(
                node = nodeId, speaker = SPEAKER_PLAYER, text = text,
                gameTime = clock.gameTime(), kind = "you", typingMs = PLAYER_TYPING_MS,
            )
        )
    }

    /**
     * needs（flag / 度量）与 needs_signal（设备 / 时间窗）分开判——契约就是这么定的：
     * needs 只承载世界事实，设备状态不许写进 needs，否则存档会记住一个早就过去的瞬间。
     */
    private fun gatePasses(node: Node, save: SaveFile, clock: GameClock): Boolean {
        val cond = condition(save, clock)
        if (node.needs.isNotEmpty()) {
            for (expression in node.needsExpressions()) {
                if (!cond.check(expression)) return false
            }
        }
        // 结局自带的 requires / requires_all：隐藏结局《紧急联系人》靠它，
        // d7_e750《备份》的 quick_mode 也写了两遍（选项一份、结局一份），判两次不为过
        for (expression in node.requiresExpressions()) {
            if (!cond.check(expression)) return false
        }
        for ((name, value) in node.needsSignal) {
            val window = if (value.isJsonPrimitive) value.asString else value.toString()
            val at = if (signals.nightUsesDeviceClock) clock.deviceTime() else clock.gameTime()
            val hit = if (window.contains("-")) signals.inWindow(window, at)
            else signals.matches(signalOnly(name, node), clock.gameTime())
            if (!hit) return false
        }
        return true
    }

    private fun emit(save: SaveFile, node: Node, clock: GameClock) {
        val cond = condition(save, clock)
        val lines = node.resolvedText(cond)
        val speaker = when {
            node.type == "system" -> SPEAKER_SYSTEM
            node.type == "letter" -> SPEAKER_LETTER
            node.type == "ending" -> SPEAKER_ENDING
            node.speaker == SPEAKER_PLAYER -> SPEAKER_PLAYER
            else -> SPEAKER_HER
        }
        val kind = when {
            speaker == SPEAKER_SYSTEM -> "system"
            speaker == SPEAKER_LETTER -> "letter"
            speaker == SPEAKER_ENDING -> "ending"
            speaker == SPEAKER_PLAYER -> "you"
            else -> "her"
        }
        val debug = node.signal != null && signals.isOverridden(node.signal!!)
        val typing = node.typingMsFor()
        for ((i, line) in lines.withIndex()) {
            // 只有第一条前面挂"正在输入"：她一次说三句是连着的，不是一句想三回
            save.transcript.add(
                TranscriptLine(node.id, speaker, line, clock.gameTime(), kind, debug, typingMs = if (i == 0) typing else 0L)
            )
        }
        if (node.type == "silence" && lines.isEmpty()) {
            save.transcript.add(
                TranscriptLine(
                    node.id, SPEAKER_SYSTEM,
                    "对方正在输入…（${(node.durationMs ?: 0) / 1000} 秒后停了）",
                    clock.gameTime(), kind = "silence",
                    typingMs = node.durationMs ?: 0L,
                )
            )
        }
        applyEffects(save.metrics, node.effects, node.id)
        applyFlags(save, node)
        // 通关解锁的快速模式：布尔写在剧本里，引擎负责把它同时落到存档和 flag
        if (node.unlocksQuickMode()) {
            save.quickMode = true
            save.flags["quick_mode"] = true
        }
        node.postExitNotification?.let { post ->
            if (post.text.isNotBlank() && save.pendingNotifications.none { it.node == node.id }) {
                save.pendingNotifications.add(
                    PendingNotice(node.id, post.delayMs, post.text, post.onlyIf)
                )
            }
        }
    }

    /**
     * 判定用的上下文。两句中文条件在这里翻成数据判据，除此之外引擎不解释任何自然语言：
     *
     *  · `no_reply_gt_4h` —— 剧本里唯一一个"真实时间闸门"（d6 沉默四小时）。
     *    必须按设备时间判：游戏时钟可以加速，玩家的沉默不行。
     *  · `三封灭屏信全部完整阅读（未被跳过）` —— 隐藏结局《紧急联系人》的第四条前置。
     *    它看着像写给作者看的注，其实完全可判：全篇 signal=screen_off 的 letter 节点
     *    恰好三个（d2_l290 / d5_l550 / d7_l729），"完整阅读"在数据上就是它们都进了转写
     *    且没被标成跳过。判据来自剧本自己，不来自这里硬写的节点 id 清单。
     *
     * 其它解析不了的表达式一律 false 并记进 unparsed——引擎不许猜，猜错的方向是
     * "玩家没达成却触发了"，那比不触发更糟。
     */
    private fun condition(save: SaveFile, clock: GameClock): StateCondition = StateCondition(
        metrics = save.metrics,
        flags = save.flags,
        counters = save.counters,
        named = mapOf(
            "no_reply_gt_4h" to {
                val since = save.silenceSince
                since != null && System.currentTimeMillis() - since >= SILENCE_GATE_MS
            },
            SCREEN_OFF_LETTERS_CONDITION to { allScreenOffLettersRead(save) },
        ),
    )

    /** 灭屏信 = 剧本里 signal=screen_off 的信；跨章取全集，因为那三封分散在 D2/D5/D7 */
    private fun allScreenOffLettersRead(save: SaveFile): Boolean {
        val letters = repo.chapters.flatMap { it.nodes }
            .filter { it.type == "letter" && it.signal == "screen_off" }
        if (letters.isEmpty()) return false
        return letters.all { letter ->
            save.transcript.any { it.node == letter.id && !it.skipped }
        }
    }

    private fun applyEffects(metrics: Metrics, effects: Map<String, Double>, where: String) {
        effects.forEach { (name, delta) ->
            require(name in METRICS) { "$where 的 effects 出现未知度量 $name" }
            require(kotlin.math.abs(delta) <= MAX_DELTA) {
                "$where 单节点效果 |$delta| 超契约上限 $MAX_DELTA（$name）"
            }
            metrics.set(name, (metrics[name] ?: 0.0) + delta)
        }
    }

    /** flags_set 两种写法：裸名（置 true）与 "flag=value"（数字写 counter，布尔写 flags） */
    private fun applyFlags(save: SaveFile, node: Node) = applyFlagList(save, node.flagsSet)

    private fun applyFlagList(save: SaveFile, list: List<String>) {
        for (entry in list) {
            val name: String
            if ('=' in entry) {
                val (k, raw) = entry.split("=", limit = 2)
                name = k.trim()
                val v = raw.trim()
                v.toLongOrNull()?.let { save.counters[name] = it.toInt() }
                    ?: run { save.flags[name] = v == "true" }
            } else {
                name = entry.trim()
                save.flags[name] = true
            }
            // 沉默不是度量，是时钟：记下玩家真的闭嘴的那一刻
            if (name == FLAG_PLAYER_SILENT && save.silenceSince == null) {
                save.silenceSince = System.currentTimeMillis()
            }
        }
    }

    /** 等待期间 integrity 掉一点：她独自过的时间必须留下痕迹（docs/01 第五节衰减表） */
    private fun decayByWait(save: SaveFile, node: Node, waitedMs: Long) {
        val days = waitedMs.toDouble() / GameClock.DAY_MS
        if (days >= 1.0) save.metrics.set("integrity", save.metrics.integrity - days * 3.0)
    }

    /** needs_signal 的键是信号名，而 DeviceSignals.matches 读的是 node.signal——合成一个只带名字的节点 */
    private fun signalOnly(name: String, node: Node): Node = node.copy(signal = name)

    companion object {
        const val MAX_STEPS = 400
        const val MAX_DELTA = 12.0
        const val DEFAULT_WORD_LIMIT = 12
        val METRICS = setOf("trust", "awareness", "integrity")
        const val SPEAKER_SYSTEM = "system"
        const val SPEAKER_PLAYER = "player"
        const val SPEAKER_HER = "xiaoman"
        const val SPEAKER_LETTER = "letter"
        const val SPEAKER_ENDING = "ending"

        /**
         * 引擎自己写进存档的三个世界事实。名字和剧本里 `needs` 用的键一一对应：
         * d5_n545 要 `notify_denied`，d6_c610 的沉默选项 `flags_set` 写 `player_silent`。
         * 授权成功不写 flag——它写的是信号 `exact_alarm_granted`（剧本 on_grant.signal），
         * 这里是给后门的度量视图一个可读写的位置，两者都置是安全的。
         */
        const val FLAG_NOTIFY_GRANTED = "notify_granted"
        const val FLAG_NOTIFY_DENIED = "notify_denied"
        const val FLAG_PLAYER_SILENT = "player_silent"

        /** d6 的沉默闸门：4 小时真实时间，不吃游戏倍率（剧本注：「沉默不该被加速」） */
        const val SILENCE_GATE_MS = 4L * 60 * 60 * 1000

        /** 剧本里唯一一句中文条件原文（d7_e760.requires_all 第三项），引擎照原样作键 */
        const val SCREEN_OFF_LETTERS_CONDITION = "三封灭屏信全部完整阅读（未被跳过）"

        /** 玩家打字比她快：真实的人按下发送前只停一下（演出层读它） */
        const val PLAYER_TYPING_MS = 1200L

        /** 节点 id 的前缀就是章号：d5_n540 → 5 */
        fun chapterOf(nodeId: String): Int? =
            nodeId.removePrefix("d").takeWhile { it.isDigit() }.toIntOrNull()
    }
}
