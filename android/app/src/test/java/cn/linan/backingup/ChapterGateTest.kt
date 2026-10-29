package cn.linan.backingup

import cn.linan.backingup.engine.ChapterGate
import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.StoryEngine
import cn.linan.backingup.sched.WakeGate
import cn.linan.backingup.story.ScriptRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 章与章之间的门（v0.1.5）。
 *
 * 这条测试的存在本身是一份检讨：`PlaythroughTest` 一直是**每章单独开局**跑的
 * （`SaveFile(chapter = N)` 直接造出来），它证明"每章内部走得完"，
 * 却从来没证明过"章和章接得上"。于是七十一章全绿的版本里，D1 走完就是断头路——
 * 真机玩家撞见了，测试没有。这里补的是那个盲区：从 D1 第一句一路走到最后一个结局，
 * 中间不许有人替玩家按"跳章"。
 *
 * 另外三条硬约定也在这里钉住：
 *   1. 开章时刻是**次日**早上 07:30，不是"下一个 07:30"——凌晨两点读完一章不该五小时后就翻篇；
 *   2. 自然跨章**不注入** `meta.start_state`，玩家自己攒的三度量带进第二天，
 *      只按跨过的真实日数扣 integrity（docs/02 规则 2）；
 *   3. 章末锚点只记一次，原地重判既不重复贴台词，也不把锚点往后推。
 */
class ChapterGateTest {

    private val repo = ScriptRepository.fromDirectory(scriptDir())
    private val zone = ZoneOffset.ofHours(8)

    // ── 时刻本身 ──────────────────────────────────────────────────────────────

    @Test
    fun `开章时刻是次日早上七点半而不是下一个七点半`() {
        // 章末落在 10 月 28 日 23:40（本地）
        val end = LocalDate.of(2026, 10, 28).atTime(23, 40).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-10-29T07:30", stamp(ChapterGate.openingAt(end, zone)))

        // 章末落在早上 07:00 —— 当天那个 07:30 只有半小时，仍然要等到第二天
        val early = LocalDate.of(2026, 10, 28).atTime(7, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-10-29T07:30", stamp(ChapterGate.openingAt(early, zone)))
    }

    @Test
    fun `跨过的真实日数按日历日算且至少一天`() {
        val end = LocalDate.of(2026, 10, 28).atTime(23, 40).atZone(zone).toInstant().toEpochMilli()
        val nextMorning = LocalDate.of(2026, 10, 29).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val threeDaysLate = LocalDate.of(2026, 10, 31).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(1, ChapterGate.daysElapsed(end, nextMorning, zone))
        assertEquals(3, ChapterGate.daysElapsed(end, threeDaysLate, zone))
        // 迟到比章末还早（时钟倒退、Doze 抽风）也不许变成 0 天以下
        assertEquals(1, ChapterGate.daysElapsed(end, end - 10_000, zone))
    }

    // ── 走通：D1 → D7 ────────────────────────────────────────────────────────

    @Test
    fun `一章一章自己往上接：从 D1 第一句能走到最后一个结局`() {
        val clock = GameClock(installAt = 1_700_000_000_000L)
        val engine = StoryEngine(repo, FakeSignals.allIn())
        val save = SaveFile(chapter = 1)
        engine.applyChapterStartState(save)
        val trace = StringBuilder()
        var halt = engine.advance(save, clock, null)
        var days = 0

        repeat(MAX_STEPS) {
            if (halt is StoryEngine.Halt.Paused && halt.node.chapterEnd == true) {
                val opens = engine.chapterOpensAt(save)
                    ?: error("走到 ${halt.node.id}（章末）却没有开章时刻")
                clock.jump((opens - clock.gameTime()).coerceAtLeast(0L) + HOUR_MS)
                days += 1
                halt = engine.openNextChapter(save, clock)
                    ?: error("推过了次日早上仍然开不了章（停在 ${save.nodeId}，第 ${save.chapter} 章）")
                trace.append("第 $days 夜过去 → D${save.chapter} 入口 ${save.nodeId}｜${save.metrics.snapshot()}\n")
                return@repeat
            }
            halt = when (halt) {
                is StoryEngine.Halt.AwaitingChoice -> {
                    if (halt.options.isEmpty()) {
                        // D6 的沉默路线：屏幕上没有可点的选项，只能真的闭嘴四小时。
                        // 测试不装四小时，把 `silenceSince` 往前挪——那是同一个设备事实。
                        save.silenceSince = System.currentTimeMillis() - 5 * HOUR_MS
                        engine.advance(save, clock, save.nodeId, reEmitEntry = false)
                    } else engine.choose(save, clock, halt.options.first())
                }

                is StoryEngine.Halt.AwaitingWait -> engine.resolveWait(save, clock, forced = true)
                is StoryEngine.Halt.AwaitingInput -> engine.playerSays(save, clock, "。")
                is StoryEngine.Halt.AwaitingGrant -> engine.resolveGrant(save, clock, granted = true)
                is StoryEngine.Halt.DeadEnd -> error("跨章走查卡在 ${halt.nodeId}：${halt.why}\n$trace")
                is StoryEngine.Halt.Paused -> return   // 结局页：这一局走完了
            }
        }

        assertEquals("走了一遍却没到任何结局：\n$trace", 7, save.chapter)
        assertTrue("没停在结局页（停在 ${save.nodeId}）：\n$trace",
            (halt as? StoryEngine.Halt.Paused)?.node?.isEnding == true)
        assertEquals("七章应该跨过六个夜晚", 6, days)
        println(trace)
    }

    @Test
    fun `自然跨章保留玩家自己的度量，只按过了几天扣 integrity`() {
        val (save, clock, engine) = d1Finished()
        val trust = save.metrics.trust
        val awareness = save.metrics.awareness
        val integrity = save.metrics.integrity
        val linesBefore = save.transcript.size
        assertTrue("D1 走完 trust 应该非零：$save", trust > 0.0)

        val opens = engine.chapterOpensAt(save)!!
        clock.jump(opens - clock.gameTime() + HOUR_MS)
        engine.openNextChapter(save, clock)

        // 开章会演出下一章入口那一段，那些节点自带效果（d2_n200 是 trust+1）照旧要算进去；
        // 判据是"除了这些效果之外，玩家自己的数一个都没被换掉"——如果这里改成注入
        // meta.start_state，三个数会各自差出一大截（D2 声明的是 T32·A30·I94）。
        val entered = save.transcript.drop(linesBefore).map { it.node }.distinct()
        val applied = { name: String -> entered.mapNotNull { repo.node(it)?.effects?.get(name) }.sum() }
        assertEquals("trust 必须带进第二天（注入 start_state 是后门跳章的作弊）",
            trust + applied("trust"), save.metrics.trust, 0.001)
        assertEquals("awareness 同理", awareness + applied("awareness"), save.metrics.awareness, 0.001)
        assertEquals("跨一天该扣 3 点 integrity", integrity - 3.0 + applied("integrity"),
            save.metrics.integrity, 0.001)
        assertEquals(2, save.chapter)
        assertTrue("开章没接上第二章（停在 ${save.nodeId}）", save.nodeId?.startsWith("d2_") == true)
        assertTrue("下一章第一句没上屏：${save.transcript.takeLast(4).map { it.text }}",
            save.transcript.any { it.node == "d2_n200" && it.text == "早。" })
    }

    // ── 老存档补锚（v0.1.5 之前没有这道门） ────────────────────────────────────

    /**
     * 这一条是"我们后补了规则"欠下的债。
     *
     * v0.1.4 的玩家读完 D1，存档里 `chapter_end_at_game_time` 是空的（那版还没这个字段），
     * 装上这一版她就同时满足两个坏条件：屏幕上是"她那边还在过时间"，而引擎认为本章没走完。
     * 拿 `lastPlayedAt` 当章末时刻补一下，她三天前读完的，今天就该接上 D2——
     * 不补等于要求玩家再等一个早上，去等一条我们从来没排过的闹钟。
     */
    @Test
    fun `老存档没有锚点，冷启动补完当天就能接上第二章`() {
        val deviceNow = LocalDate.of(2026, 10, 29).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val clock = GameClock(
            installAt = deviceNow - 3 * GameClock.DAY_MS,
            now = { deviceNow },
        )
        val engine = StoryEngine(repo, FakeSignals.allIn())
        // 玩家三天前读完 D1 就睡了：存档停在章末节点，字段是旧的（null）
        val save = SaveFile(chapter = 1, nodeId = "d1_n150", lastPlayedAt = deviceNow - 3 * GameClock.DAY_MS)
        assertNull(save.chapterEndAtGameTime)
        assertNull("补锚之前，这道门根本不认她已走完本章", engine.chapterOpensAt(save))

        engine.seedChapterAnchor(save, clock)
        val opens = engine.chapterOpensAt(save)
        assertNotNull("补锚后该有开章时刻", opens)
        assertTrue("三天前读完的，开章时刻早该过了：opens=${stamp(opens!!)} now=${stamp(clock.gameTime())}",
            clock.gameTime() >= opens)

        val halt = engine.openNextChapter(save, clock)
        assertNotNull(halt)
        assertEquals("冷启动第一次重判就该翻篇，不许让玩家再等一早上", 2, save.chapter)
    }

    @Test
    fun `补锚只认章末节点，中途存档不许被白送下一章`() {
        val deviceNow = LocalDate.of(2026, 10, 29).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val clock = GameClock(installAt = deviceNow - 3 * GameClock.DAY_MS, now = { deviceNow })
        val engine = StoryEngine(repo, FakeSignals.allIn())
        val mid = repo.chapterEntry(1)!!.id
        assertTrue("入口节点本身就是章末的话这条测试就没意义了", mid != "d1_n150")

        val save = SaveFile(chapter = 1, nodeId = mid, lastPlayedAt = deviceNow - 3 * GameClock.DAY_MS)
        engine.seedChapterAnchor(save, clock)
        assertNull("没走到章末就不该有锚点——否则她读到一半被直接抬进第二天", save.chapterEndAtGameTime)
        assertNull(engine.openNextChapter(save, clock))
        assertEquals(1, save.chapter)

        // 已经有锚点的存档不许被覆盖：那是原地重判把开章时刻往后推的另一条路
        val anchored = SaveFile(chapter = 1, nodeId = "d1_n150", lastPlayedAt = deviceNow - 3 * GameClock.DAY_MS)
        val first = clock.gameTime() - 10 * GameClock.DAY_MS
        anchored.chapterEndAtGameTime = first
        engine.seedChapterAnchor(anchored, clock)
        assertEquals("补锚不许把已记的锚点推到此刻", first, anchored.chapterEndAtGameTime)
    }

    // ── 不许发生的几件事 ──────────────────────────────────────────────────────

    @Test
    fun `章末当天不许提前开章，重判也不许把锚点往后推`() {
        val (save, clock, engine) = d1Finished()
        val anchor = save.chapterEndAtGameTime
        assertNotNull("走到章末就该记下那一刻", anchor)

        repeat(5) {
            engine.advance(save, clock, save.nodeId, reEmitEntry = false)
            engine.openNextChapter(save, clock)
        }
        assertEquals("原地重判把开章时刻推后了——玩家盯着屏幕她就永远到不了第二天",
            anchor, save.chapterEndAtGameTime)
        assertEquals("还没跨天就开章了", 1, save.chapter)
    }

    @Test
    fun `最后一章没有下一章`() {
        val clock = GameClock(installAt = 1_700_000_000_000L)
        val engine = StoryEngine(repo, FakeSignals.allIn())
        val save = SaveFile(chapter = 7)
        engine.applyChapterStartState(save)
        save.chapterEndAtGameTime = clock.gameTime()
        clock.jump(10 * GameClock.DAY_MS)
        assertNull("D7 之后再无次日", engine.chapterOpensAt(save))
        assertNull(engine.openNextChapter(save, clock))
    }

    @Test
    fun `开章后那条章间闹钟要闭嘴：同一天早上她敲一次就够了`() {
        val (save, clock, engine) = d1Finished()
        val ref = ChapterGate.refFor(2)
        val opens = engine.chapterOpensAt(save)!!

        // 到点了，闹钟醒来：该投
        assertTrue(WakeGate.worthPosting(save, ref, opens, opens))
        assertNotNull("投递身份取不到，等于这条永远发不出去", WakeGate.deliveryToken(save, ref))

        // 玩家自己先打开了 App：开章把锚点清空，迟到的闹钟无话可说
        clock.jump(opens - clock.gameTime() + HOUR_MS)
        engine.openNextChapter(save, clock)
        assertEquals(2, save.chapter)
        assertTrue("章已翻过去，闹钟还在投昨天的『早。』",
            !WakeGate.worthPosting(save, ref, opens + HOUR_MS, opens + HOUR_MS))
        assertNull(WakeGate.deliveryToken(save, ref))
    }

    @Test
    fun `倍率作用在时钟层：3600 倍下排闹钟给的是真实世界的等待`() {
        val clock = GameClock(installAt = 1_700_000_000_000L, initialRate = 3600.0)
        val due = clock.gameTime() + 22 * HOUR_MS      // 游戏里还有 22 小时
        assertEquals(22 * HOUR_MS, clock.remaining(due))
        assertEquals("3600× 下 22 小时该是 22 秒，不是 22 小时",
            22_000L, clock.deviceDelayUntil(due))
    }

    // ── 驱动器 ────────────────────────────────────────────────────────────────

    /** 把 D1 贪心走到章末，停在 d1_n150，时钟不动（还在"当天"） */
    private fun d1Finished(): Triple<SaveFile, GameClock, StoryEngine> {
        val clock = GameClock(installAt = 1_700_000_000_000L)
        val engine = StoryEngine(repo, FakeSignals.allIn())
        val save = SaveFile(chapter = 1)
        engine.applyChapterStartState(save)
        var halt = engine.advance(save, clock, null)
        // 手写 while：走到章末要"跳出函数把三元组交出去"，repeat 那个内联 lambda 里 return 不出来
        var steps = 0
        while (steps++ < MAX_STEPS) {
            halt = when (halt) {
                is StoryEngine.Halt.AwaitingChoice ->
                    if (halt.options.isEmpty()) error("D1 出现空选项组：${halt.node.id}")
                    else engine.choose(save, clock, halt.options.first())

                is StoryEngine.Halt.AwaitingWait -> engine.resolveWait(save, clock, forced = true)
                is StoryEngine.Halt.AwaitingInput -> engine.playerSays(save, clock, "。")
                is StoryEngine.Halt.AwaitingGrant -> engine.resolveGrant(save, clock, granted = true)
                is StoryEngine.Halt.DeadEnd -> error("D1 走不完：${halt.nodeId} ${halt.why}")
                is StoryEngine.Halt.Paused -> return Triple(save, clock, engine)
            }
        }
        error("D1 在 $MAX_STEPS 步内没走到章末")
    }

    private fun stamp(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().toString().take(16)

    private companion object {
        const val MAX_STEPS = 900
        const val HOUR_MS = 60 * 60 * 1000L
    }
}
