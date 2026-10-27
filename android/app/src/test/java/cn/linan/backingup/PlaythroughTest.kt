package cn.linan.backingup

import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.Metrics
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.StoryEngine
import cn.linan.backingup.story.ScriptRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 贪心通关：每一章从入口走到章末，引擎一步都不许卡住。
 *
 * 策略故意笨：选项永远取第一条能点的、等待一律强制到点、该说话就说一个字。
 * 这不是"最好的打法"，是**最省事的打法**——它测的是"一个不作弊、不研究攻略的玩家
 * 能不能把这一章走完"。走完不了就是剧本事故，跟我们写得优不优雅无关。
 *
 * D7 额外测隐藏结局《紧急联系人》的两端：条件齐了必须出，差一条必须不出。
 * 这条测试就是 docs/04 第④项（度量实时读写）存在的原因——可达性要靠跑，不靠读代码推。
 */
class PlaythroughTest {

    private val repo = ScriptRepository.fromDirectory(scriptDir())

    @Test
    fun `每一章都能从入口走到章末`() {
        val reports = mutableListOf<String>()
        for (chapter in 1..7) {
            reports += "D$chapter → " + runChapter(chapter)
        }
        val failures = reports.filter { it.contains("卡住") || it.contains("死循环") }
        assertTrue("有章走不完：\n" + reports.joinToString("\n"), failures.isEmpty())
        println(reports.joinToString("\n"))
    }

    @Test
    fun `D7 隐藏结局在条件齐时可达且差一条时不可达`() {
        // 齐：awareness 落在 [70,76]、integrity ≥38、没开快速模式、没当共犯、三封灭屏信都读全了
        val earned = driveD7ToC12(awareness = 72.0, integrity = 60.0, quickMode = false, accomplice = false)
        assertTrue("条件齐了，「（不说再见）」却没出现：\n$earned", earned.contains("（不说再见）"))
        assertTrue("选项出现了却没走进结局：\n$earned", earned.contains("paused@d7_e760"))

        // 差一条：integrity 掉到 30（<38）→ 这个选项根本不该出现在屏幕上
        val brittle = driveD7ToC12(awareness = 72.0, integrity = 30.0, quickMode = false, accomplice = false)
        assertTrue("integrity 不够却还是给了隐藏结局入口：\n$brittle", !brittle.contains("（不说再见）"))

        // 差一条：开了快速模式（quick_mode == false 不成立），同时「我把你存下来了」该出现
        val sped = driveD7ToC12(awareness = 72.0, integrity = 60.0, quickMode = true, accomplice = false)
        assertTrue("开了快速模式还给隐藏结局入口：\n$sped", !sped.contains("（不说再见）"))
        assertTrue("开了快速模式却没有《备份》入口：\n$sped", sped.contains("我把你存下来了"))

        // 差一条：三封灭屏信只读了两封（没传送 d7_l729）
        val twoLetters = driveD7ToC12(
            awareness = 72.0, integrity = 60.0, quickMode = false, accomplice = false,
            readLetters = listOf("d2_l290", "d5_l550"),
        )
        assertTrue("少读一封灭屏信还给隐藏结局入口：\n$twoLetters", !twoLetters.contains("（不说再见）"))
    }

    /**
     * 「原地重判」不许有副作用。
     *
     * UI 每 60 秒要重问一次"沉默四小时那条选项出来了没有"（GameScreen 的 recheck 定时器），
     * 而重问的方式是"从当前节点再走一遍"。emit 是有副作用的：贴台词、加度量、记 flag、
     * 挂补投。上一版 recheck 走的就是带 emit 的那条路，于是玩家盯着选择页读一分钟，
     * 她就把同一句话说一遍、那一笔信任再加一次——转写导出《备份》时满纸复读机。
     *
     * 这条测试钉的是修法本身：`reEmitEntry = false` 的重判必须**一个字都不落**。
     */
    @Test
    fun `原地重判不重复贴台词也不重复加度量`() {
        for (chapter in 1..7) {
            val clock = GameClock(installAt = 1_700_000_000_000L)
            val engine = StoryEngine(repo, FakeSignals.allIn())
            val save = SaveFile(chapter = chapter)
            engine.applyChapterStartState(save)
            var halt: StoryEngine.Halt = engine.advance(save, clock, null)
            var guard = 0
            while (halt !is StoryEngine.Halt.AwaitingChoice && guard++ < 50) {
                halt = when (halt) {
                    is StoryEngine.Halt.AwaitingWait -> engine.resolveWait(save, clock, forced = true)
                    is StoryEngine.Halt.AwaitingInput -> engine.playerSays(save, clock, "。")
                    is StoryEngine.Halt.AwaitingGrant -> engine.resolveGrant(save, clock, granted = true)
                    else -> break
                }
            }
            if (halt !is StoryEngine.Halt.AwaitingChoice) continue

            val linesBefore = save.transcript.size
            val metricsBefore = save.metrics.snapshot()
            val noticesBefore = save.pendingNotifications.size
            repeat(5) { engine.advance(save, clock, save.nodeId, reEmitEntry = false) }
            assertEquals(
                "D$chapter 在 ${halt.node.id} 上重判五次，转写长了 $linesBefore → ${save.transcript.size}",
                linesBefore, save.transcript.size,
            )
            assertEquals(
                "D$chapter 在 ${halt.node.id} 上重判五次，度量变了",
                metricsBefore, save.metrics.snapshot(),
            )
            assertEquals("重判不该再挂一条补投", noticesBefore, save.pendingNotifications.size)
        }
    }

    // ── 驱动器 ──────────────────────────────────────────────────────────────

    private fun runChapter(chapter: Int): String {
        val clock = GameClock(installAt = 1_700_000_000_000L)
        val signals = FakeSignals.allIn()
        val engine = StoryEngine(repo, signals)
        val save = SaveFile(chapter = chapter)
        engine.applyChapterStartState(save)
        var halt = engine.advance(save, clock, null)
        var steps = 0
        while (true) {
            if (++steps > MAX_STEPS) return "死循环（走了 $steps 步还没停）"
            halt = when (halt) {
                is StoryEngine.Halt.AwaitingChoice ->
                    if (halt.options.isEmpty()) return "停在 ${halt.node.id}：没有可点的选项（沉默路线，属设计）"
                    else engine.choose(save, clock, halt.options.first())

                is StoryEngine.Halt.AwaitingWait -> engine.resolveWait(save, clock, forced = true)
                is StoryEngine.Halt.AwaitingInput -> engine.playerSays(save, clock, "。")
                is StoryEngine.Halt.AwaitingGrant -> engine.resolveGrant(save, clock, granted = true)
                is StoryEngine.Halt.Paused ->
                    return "走到 ${halt.node.id}（${halt.node.type}）· ${save.metrics.snapshot()} · 转写 ${save.transcript.size} 条"

                is StoryEngine.Halt.DeadEnd -> return "卡住 ${halt.nodeId}：${halt.why}"
            }
        }
    }

    /**
     * 把 D7 走到 d7_c12（最后那组选项），再把每条选项真的各走一遍，看结局落在谁身上。
     *
     * 隐藏结局要"三封灭屏信全部完整阅读"。这三封分散在 D2 / D5 / D7，而第三封（d7_l729）
     * 在 c12 之后的《再见》分支里——所以它天然是一次通关拿不到的条件：玩家得先走完一次、
     * 再回来选"不说再见"。测试就照这个事实造状态：先用传送把三封信**真的读出来**
     * （让转写里出现它们），而不是手写三条 TranscriptLine。判据必须由剧本产生。
     */
    private fun driveD7ToC12(
        awareness: Double,
        integrity: Double,
        quickMode: Boolean,
        accomplice: Boolean,
        readLetters: List<String> = listOf("d2_l290", "d5_l550", "d7_l729"),
    ): String {
        val clock = GameClock(installAt = 1_700_000_000_000L)
        val engine = StoryEngine(repo, FakeSignals.allIn())
        val save = SaveFile(chapter = 7, quickMode = quickMode)

        // 传送会改写章号与起始度量，所以放在最前面
        readLetters.forEach { engine.teleport(save, clock, it) }
        save.chapter = 7
        engine.applyChapterStartState(save)
        save.metrics.set("awareness", awareness)
        save.metrics.set("integrity", integrity)
        save.flags["quick_mode"] = quickMode
        save.flags["accomplice"] = accomplice

        val trace = StringBuilder()
        var halt = engine.advance(save, clock, "d7_n720")
        var steps = 0
        while (steps++ < MAX_STEPS) {
            trace.append(halt.describe()).append('\n')
            if (halt is StoryEngine.Halt.AwaitingChoice && halt.node.id == "d7_c12") break
            if (halt is StoryEngine.Halt.Paused || halt is StoryEngine.Halt.DeadEnd) break
            halt = when (halt) {
                is StoryEngine.Halt.AwaitingChoice -> {
                    check(halt.options.isNotEmpty()) { "D7 出现了空选项组：剧本事故" }
                    engine.choose(save, clock, halt.options.first())
                }

                is StoryEngine.Halt.AwaitingWait -> engine.resolveWait(save, clock, forced = true)
                is StoryEngine.Halt.AwaitingInput -> engine.playerSays(save, clock, "再见")
                is StoryEngine.Halt.AwaitingGrant -> engine.resolveGrant(save, clock, granted = true)
                else -> error("循环条件写漏了：${halt.describe()}")
            }
        }

        // 站在 d7_c12 上逐条试：可见性由 availableChoices 判，结局由 choose 判
        val c12 = repo.node("d7_c12")!!
        val visible = engine.availableChoices(c12, save, clock)
        trace.append("可点选项：").append(visible.map { it.text }).append('\n')
        visible.forEach { option ->
            val probe = save.copy(
                metrics = Metrics(save.metrics.trust, save.metrics.awareness, save.metrics.integrity),
                flags = save.flags.toMutableMap(),
                counters = save.counters.toMutableMap(),
                transcript = save.transcript.toMutableList(),
            )
            trace.append("〔${option.text}〕→ ${engine.choose(probe, clock, option).describe()}\n")
        }
        return trace.toString()
    }

    private fun StoryEngine.Halt.describe(): String = when (this) {
        is StoryEngine.Halt.AwaitingChoice -> "choice@${node.id}(${options.size} 条可选)"
        is StoryEngine.Halt.AwaitingWait -> "wait@${node.id}"
        is StoryEngine.Halt.AwaitingInput -> "input@${node.id}"
        is StoryEngine.Halt.AwaitingGrant -> "grant@${node.id}"
        is StoryEngine.Halt.Paused -> "paused@${node.id}(${node.endingName ?: node.type})"
        is StoryEngine.Halt.DeadEnd -> "DEAD@${nodeId}: $why"
    }


    private companion object {
        const val MAX_STEPS = 600
    }
}
