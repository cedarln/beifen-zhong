package cn.linan.backingup

import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.StoryEngine
import cn.linan.backingup.engine.Template
import cn.linan.backingup.story.Node
import cn.linan.backingup.story.ScriptRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 剧本变量替换（v0.1.6）。
 *
 * 起因是一张真机截图：D2 屏幕上出现两串 `{{player_name}}。`。占位符漏到玩家眼前，
 * 对这部作品来说不是排版事故——她是一个只存在你手机缓存里的人，一旦她的话里露出模板语法，
 * 玩家看见的就不是"她在说话"，而是"一个程序在填表"。
 *
 * 所以这里判的三件事，一件比一件要紧：
 *   1. **剧本里出现的每一个 `{{x}}`，引擎都认识、也都拿得到值**（漏一个就是一个玩家看得见）；
 *   2. **两条路线（给过名字 / 没给名字）走完，转写里一个花括号都没有**；
 *   3. **日期念的是她那边的日子**：游戏时钟跳到后天，她嘴里的"明天"就跟着变，
 *      而不是跟着设备走（docs/04 第三节：倍率只作用在时钟层）。
 *
 * D5 那两条路线是直接从第 5 章开局验的（引擎判的是 `save.variables`，跨章走过去判的是同一个条件），
 * D2 的名字路线则老老实实从选项点进去——那条路上"她先问、玩家再打、她念出来"这个次序
 * 本身就是契约的一部分。
 */
class TemplateTest {

    private val repo = ScriptRepository.fromDirectory(scriptDir())

    // ── 1. 剧本与引擎认的变量必须严丝合缝 ────────────────────────────────────

    @Test
    fun `剧本里出现的占位符引擎全认识，而且就在本章声明过`() {
        val unknown = mutableListOf<String>()
        repo.chapters.forEach { script ->
            val declared = script.variables.keys
            script.nodes.forEach { node ->
                surfacesOf(node).flatMap { Template.names(it) }.distinct().forEach { name ->
                    if (name !in Template.KNOWN) unknown += "${node.id} 用了引擎不认识的 {{$name}}"
                    if (name !in declared) unknown += "${node.id} 用了 {{$name}}，但 D${script.meta.chapter} 章没声明"
                }
            }
        }
        assertEquals("占位符对不上账：\n" + unknown.joinToString("\n"), emptyList<String>(), unknown)
    }

    @Test
    fun `引擎认识的变量剧本必须都声明`() {
        // 反向也要闭合：Template.KNOWN 里多留一个剧本没有的名字，等于引擎在编内容
        val missing = Template.KNOWN - repo.declaredVariables
        assertEquals("引擎认识但剧本从未声明的变量：$missing", emptySet<String>(), missing)
    }

    @Test
    fun `运行时变量要么有人给她输入，要么剧本写好兜底称呼`() {
        val used = repo.chapters.flatMap { it.nodes }.flatMap { node ->
            surfacesOf(node).flatMap { Template.names(it) }.distinct().map { it to node.id }
        }
        used.forEach { (name, nodeId) ->
            val isSystem = Template.systemVars(0L).containsKey(name)
            if (isSystem) return@forEach
            val declared = repo.chapters.firstOrNull { it.variables.containsKey(name) }
            val hasCapture = repo.chapters.any { s -> s.nodes.any { it.capture?.variable == name } }
            val hasUnset = repo.variableUnsetValue(name) != null
            assertTrue(
                "$nodeId 用了 {{$name}}，但它既不是系统变量，也没有 capture 节点（$hasCapture）" +
                    "或 unset_value（$hasUnset）——玩家屏幕上就会露出代码",
                hasCapture && hasUnset,
            )
            assertNotNull("变量 $name 没有在任何一章声明", declared)
        }
    }

    // ── 2. 名字路线：她先问，玩家打，她念出来 ───────────────────────────────

    @Test
    fun `名字路线：d2_n219 停下来等她听，打完她当场念`() {
        val drive = Drive(2, name = "临安")
        val stuck = drive.run()
        assertNull("这一路应该走到头，却停在：$stuck", stuck)
        // 问话必须是剧本写的台词，先贴进转写、再停下来听：界面不许自己弹一个没有出处的框
        assertEquals(
            "她问名字那两句",
            listOf("那你打过来。", "一个字一个字打，我数着。"),
            drive.save.transcript.filter { it.node == "d2_n219" }.map { it.text },
        )
        assertEquals("临安", drive.save.variables["player_name"])

        val spoken = drive.save.transcript.filter { it.node in setOf("d2_n221", "d2_n222") }.map { it.text }
        assertTrue("她把玩家给的名字念出来了：$spoken", "临安。" in spoken)
        assertNoPlaceholder(drive.save)
    }

    @Test
    fun `空手按发送不推进，也不替玩家填一个称呼`() {
        val drive = Drive(2)
        val halt = drive.engine.advance(drive.save, drive.clock, "d2_n219", reEmitEntry = false)
        assertTrue("d2_n219 有 capture 就该停下来听", halt is StoryEngine.Halt.AwaitingCapture)
        val before = drive.save.transcript.size
        val again = drive.engine.submitCapture(drive.save, drive.clock, "   ")
        assertTrue("空白输入不许算数", again is StoryEngine.Halt.AwaitingCapture)
        assertEquals("没输入就不许多出一条气泡", before, drive.save.transcript.size)
        assertNull("引擎不许替玩家填默认称呼", drive.save.variables["player_name"])
    }

    @Test
    fun `已经给过名字的玩家不该再被问一遍`() {
        val drive = Drive(2, name = "临安")
        assertFalse(
            "问第二遍就成了假话：她记得自己听过",
            drive.engine.advance(drive.save, drive.clock, "d2_n219", reEmitEntry = false)
                is StoryEngine.Halt.AwaitingCapture,
        )
    }

    @Test
    fun `把占位符当名字打进来也不会递归`() {
        val drive = Drive(2)
        drive.engine.advance(drive.save, drive.clock, "d2_n219", reEmitEntry = false)
        // 花括号被整个剥掉，剩下的就是玩家自己打的那串字——存档里永远不会有一个能被子替换的串
        drive.engine.submitCapture(drive.save, drive.clock, "{{player_name}}")
        assertEquals("player_name", drive.save.variables["player_name"])
        val spoken = drive.save.transcript.filter { it.node == "d2_n221" }.map { it.text }
        assertTrue("她念的是玩家给的那串字，没有再替换一遍：$spoken", "player_name。" in spoken)
        assertNoPlaceholder(drive.save)
    }

    @Test
    fun `名字只吃掉括号并按 max_len 截断`() {
        val short = Drive(2)
        short.engine.advance(short.save, short.clock, "d2_n219", reEmitEntry = false)
        short.engine.submitCapture(short.save, short.clock, "{{临}}安")
        assertEquals("临安", short.save.variables["player_name"])

        val long = Drive(2)
        long.engine.advance(long.save, long.clock, "d2_n219", reEmitEntry = false)
        long.engine.submitCapture(long.save, long.clock, "临安临安临安临安临安临安")
        assertEquals(
            "剧本声明的 max_len 就是那口气泡能念出来的长度",
            12, long.save.variables["player_name"]?.length,
        )
    }

    // ── 3. 两条路线都不能露代码 ──────────────────────────────────────────────

    @Test
    fun `命名与未命名两条路线走完 D2 和 D5，屏幕上没有一串代码`() {
        listOf(true, false).forEach { named ->
            listOf(2, 5).forEach { chapter ->
                val drive = Drive(chapter, name = if (named) "临安" else null)
                val result = drive.run()
                assertNull("D$chapter（${if (named) "命名" else "未命名"}）卡住：$result", result)
                assertNoPlaceholder(drive.save)
            }
        }
    }

    @Test
    fun `没给名字的玩家：她叫「喂」，D5 那句换成作者写好的另一种说法`() {
        val drive = Drive(5)
        val stuck = drive.run()
        assertNull("D5 未命名路线卡住：$stuck", stuck)
        val lines = drive.save.transcript.map { it.text }
        assertTrue("D5 第一句她该用 d2_n220 里自己定的称呼：$lines", lines.any { it == "早，喂。" })
        assertTrue("替代文案得真的出现", lines.any { it.startsWith("对了，昨天你说你那边今天不下雨") })
        assertFalse("没给过名字，她不许说『你跟我说你叫…』", lines.any { it.contains("你跟我说你叫") })
        assertFalse("兜底值不该是省略号（那是引擎在认输）", lines.any { it == "早，……。" })

        val named = Drive(5, name = "临安").apply { run() }.save.transcript.map { it.text }
        assertTrue("给过名字的玩家保留原文：$named", named.any { it == "对了，昨天你跟我说你叫临安。" })
        assertFalse("命名路线不许读到替代文案", named.any { it.startsWith("对了，昨天你说你那边今天不下雨") })
    }

    @Test
    fun `d5_n520 两版文案只在那两行上不同`() {
        // replace_text 是节点级整段替换，所以 D5 峰值那一长段在剧本里存了两份。
        // 这份测试盯的就是"以后有人改了 text 忘了改这里"：两版必须只差在被替换的那两行。
        val node = repo.node("d5_n520") ?: error("d5_n520 不在了")
        val cond = node.conditionals().firstOrNull() ?: error("d5_n520 的 conditional_line 不在了")
        val alt = cond.replaceLines() ?: error("替代文案不是数组")
        assertEquals("等于 1", "player_named == false", cond.condition)
        assertEquals("替换后少一行", node.text.size - 1, alt.size)
        val head = node.text.size.let { n ->
            // 前面五句必须一字不差
            (0 until 5).map { i -> assertEquals("第 ${i + 1} 句漂了", node.text[i], alt[i]) ; node.text[i] }
        }
        assertEquals(5, head.size)
        val namedTail = node.text.drop(7)
        val altTail = alt.drop(6)
        assertEquals("后半段漂了：$namedTail vs $altTail", namedTail, altTail)
        assertTrue(
            "作者授权的替代句必须保持『昨天』句式（错置量是这一节的全部意义）",
            alt[5].startsWith("对了，昨天"),
        )
    }

    // ── 4. 日期念的是她那边的日子 ────────────────────────────────────────────

    @Test
    fun `那句明天是她那儿的明天，跟游戏时钟走`() {
        val zone = ZoneId.systemDefault()
        // 把"此刻"钉死在 2026-09-30 中午：这条测试判的是日期跟着时钟走，
        // 拿真实墙上时间跑会随跑测试的那天飘
        val now = LocalDate.of(2026, 9, 30).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val clock = GameClock(installAt = now, now = { now })
        val engine = StoryEngine(repo, FakeSignals.allIn())
        val save = SaveFile(chapter = 1)
        val raw = repo.node("d1_n120")!!.text.first()

        assertEquals("明天是 星期四，2026-10-01。", engine.renderNotification(save, clock.gameTime(), raw))

        // 后门跳三天（OFFSET += 86_400_000×3），同一段剧本渲染出的就是三天后的那个"明天"；
        // 设备时间一点没动——这正是"倍率作用在时钟层"在文案上的体现
        clock.jump(3 * GameClock.DAY_MS)
        assertEquals("明天是 星期日，2026-10-04。", engine.renderNotification(save, clock.gameTime(), raw))

        // 换倍率的那一刻，她那边的"今天"不许跳（GameClock.setRate 会先把此刻锚回原位）
        val fast = GameClock(installAt = now, now = { now + 3_600_000L })
        assertEquals("09-30", Template.dayLabel(fast.gameTime()))
        fast.setRate(3600.0)
        assertEquals("换倍率那一刻日子不许跟着跳", "09-30", Template.dayLabel(fast.gameTime()))
        // 之后设备再过一小时，3600× 下她那边的日子真的往前滚了四十多天
        assertTrue(
            "倍率作用下日子该跟着走",
            GameClock(installAt = now, initialRate = 3600.0, now = { now + 3_600_000L })
                .let { Template.dayLabel(it.gameTime()) } != "09-30",
        )
    }

    @Test
    fun `通知栏那句和屏上那句是同一句话`() {
        val drive = Drive(2, name = "临安")
        val node = repo.node("d5_n500") ?: error("d5_n500 不在了")
        val raw = node.text.first()
        assertTrue(raw.contains("{{player_name}}"))
        val rendered = drive.engine.renderNotification(drive.save, drive.clock.gameTime(), raw)
        assertEquals("早，临安。", rendered)
        assertFalse("通知里不许有代码", "{{" in rendered)
    }

    // ── 5. 老存档：屏幕上的代码不会因为发了一版修复就自己消失 ────────────────

    @Test
    fun `升级后往回翻，老转写里的占位符按当时那天修好`() {
        val drive = Drive(1)
        val then = drive.clock.gameTime()
        drive.save.transcript.add(
            cn.linan.backingup.engine.TranscriptLine(
                node = "d1_n120", speaker = "你",
                text = "明天是 {{tomorrow_weekday_cn}}，{{tomorrow_date}}。",
                gameTime = then - 2 * GameClock.DAY_MS, kind = "you",
            ),
        )
        drive.save.transcript.add(
            cn.linan.backingup.engine.TranscriptLine(
                node = "d2_n221", speaker = "小满", text = "{{player_name}}。",
                gameTime = then, kind = "her",
            ),
        )
        drive.save.variables["player_name"] = "临安"
        assertEquals("两行都该修", 2, drive.engine.repairUnrenderedTranscript(drive.save))
        val dates = Template.systemVars(then - 2 * GameClock.DAY_MS)
        assertEquals(
            "日期类要按那一行**当时**那天重算，不是按今天",
            "明天是 ${dates["tomorrow_weekday_cn"]}，${dates["tomorrow_date"]}。",
            drive.save.transcript[drive.save.transcript.size - 2].text,
        )
        assertEquals("临安。", drive.save.transcript.last().text)
        assertEquals("修过一遍就不该再改动", 0, drive.engine.repairUnrenderedTranscript(drive.save))
    }

    @Test
    fun `剧本没给兜底值又取不到值时落到省略号，而不是花括号`() {
        val rendered = Template.render("她说：{{player_name}}", emptyMap())
        assertEquals("她说：${Template.FALLBACK}", rendered)
        // unset_value 优先于兜底
        assertEquals(
            "她说：喂",
            Template.render("她说：{{player_name}}", emptyMap()) { repo.variableUnsetValue(it) },
        )
    }

    // ── 6. 通知栏那条路：文案进闹钟之前必须已经念成人的话 ────────────────────

    /**
     * 扫源码，不跑运行结果（和 `NetworkBoundaryTest`、`BackdoorPanelTest` 同一个套路）。
     *
     * 判的是：剧本原文被直接塞进 `ReminderScheduler` 或 `post(...)`，玩家就会在锁屏上
     * 看见 `{{player_name}}`——那条比屏上更难撤，它已经挂进系统通知栏了。
     * `CatchUpWorker`（重启补投）手里没有引擎实例，它以前就是从这儿漏的。
     */
    @Test
    fun `进通知栏的每一句都必须是渲染过的`() {
        val worker = mainSrc("sched", "CatchUpWorker.kt")
        val vm = mainSrc("ui", "GameViewModel.kt")

        // 这三种写法都是"把刚取出来的剧本原文直接交给投递口"。
        // `post(...)` 那个唯一出口自己除外：它转发的已经是渲染过的句子，
        // 判它等于要求它给自己套一层 render，那是假阳性不是判据。
        val callSites = worker.substringBefore("private suspend fun post(")
        val raw = listOf("ref, text)", "ref, notice.text)", "ref, it)")
        val hits = raw.filter { callSites.contains(it) }
        assertTrue("CatchUpWorker 里有不渲染就投递的写法：$hits", hits.isEmpty())
        assertTrue(
            "CatchUpWorker 必须自带一份渲染口径，且与屏上同一份（Template.varsFor）",
            "Template.render(" in worker && "Template.varsFor(" in worker,
        )
        // 前台排等待闹钟那条：文案要按**到点那一刻**渲染，不是按此刻
        assertTrue(
            "armWait 要把承接句按 dueGameTime 渲染后再排闹钟",
            vm.contains("renderNotification(save, halt.dueGameTime"),
        )
    }

    /** 读主源码：这条判据看的是"代码里怎么写的" */
    private fun mainSrc(vararg path: String): String =
        java.io.File("src/main/java/cn/linan/backingup", path.joinToString("/")).readText()

    // ── 驱动器 ────────────────────────────────────────────────────────────────

    /**
     * 一章从头走完：[run] 返回 null = 走到底没卡住，返回字符串 = 卡在哪。
     *
     * [name] 决定 D2 那条"我有名字"的选项点不点：给了名字就点第三条并把名字交给她，
     * 没给就一律取第一条（那是剧本里的主线路径）。
     */
    private inner class Drive(val chapter: Int, val name: String? = null) {
        val clock = GameClock(installAt = 1_700_000_000_000L)
        val engine = StoryEngine(repo, FakeSignals.allIn())
        val save = SaveFile(chapter = chapter)

        init {
            engine.applyChapterStartState(save)
            if (name != null) save.variables["player_name"] = name
        }

        fun start(): StoryEngine.Halt = engine.advance(save, clock, null)

        fun run(): String? {
            var halt = start()
            var steps = 0
            while (true) {
                if (++steps > MAX_STEPS) return "死循环（$steps 步没停）"
                halt = when (halt) {
                    is StoryEngine.Halt.AwaitingChoice -> {
                        val take = if (name != null) {
                            halt.options.firstOrNull { "我有名字" in it.text } ?: halt.options.firstOrNull()
                        } else halt.options.firstOrNull()
                        when {
                            take == null -> return "停在 ${halt.node.id}：没有可点的选项"
                            else -> engine.choose(save, clock, take)
                        }
                    }

                    is StoryEngine.Halt.AwaitingWait -> engine.resolveWait(save, clock, forced = true)
                    is StoryEngine.Halt.AwaitingInput -> engine.playerSays(save, clock, "。")
                    is StoryEngine.Halt.AwaitingGrant -> engine.resolveGrant(save, clock, granted = true)
                    is StoryEngine.Halt.AwaitingCapture -> {
                        if (name == null) {
                            // 未命名路线：主线路径本来问不到名字；真问到了就说明剧本改了路
                            return "走到了 capture 节点 ${halt.node.id}，但这一路是不给名字的"
                        }
                        engine.submitCapture(save, clock, name)
                    }

                    is StoryEngine.Halt.DeadEnd -> return "卡住 ${halt.nodeId}：${halt.why}"
                    is StoryEngine.Halt.Paused -> return null
                }
            }
        }
    }

    private fun assertNoPlaceholder(save: SaveFile) {
        val leaked = save.transcript.filter { "{{" in it.text }.map { "${it.node}: ${it.text}" }
        assertEquals("屏幕上出现了代码：\n${leaked.joinToString("\n")}", emptyList<String>(), leaked)
    }

    /** 一个节点里所有会被人看见的句子——占位符只能出现在这些地方，扫描必须全覆盖 */
    private fun surfacesOf(node: Node): List<String> = buildList {
        addAll(node.text)
        addAll(node.promptText)
        addAll(node.confirmText)
        node.conditionals().forEach { c ->
            c.replaceLines()?.let { addAll(it) }
            addAll(c.appendLines())
        }
        node.firstLineAfterWait?.let { add(it) }
        node.fastModeSummary?.let { add(it) }
        node.signalLine?.let { add(it) }
        node.farewellLine?.let { add(it) }
        node.postExitNotification?.let { add(it.text) }
        addAll(node.yearlyLines())
        node.choices.forEach { add(it.text) }
        node.capture?.let { add(it.hint) }
    }

    private companion object {
        const val MAX_STEPS = 400
    }
}
