package cn.linan.backingup

import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.StoryEngine
import cn.linan.backingup.engine.TranscriptLine
import cn.linan.backingup.story.ScriptRepository
import cn.linan.backingup.ui.TimeGaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间分隔标识的守门。
 *
 * 玩家提的原话是：「她每次继续跟我说话，跟上一条发言之间最好做个分隔标识，
 * 不然往后看没办法知道中间哪里是有时间间隔的。」这条判据把它变成可红的断言：
 * 该出现的要出现（真实引擎走过等待之后），不该出现的不许出现（她在打字、同一段话内部、
 * 设备时间被改过之后）。
 */
class TimeGapTest {

    private fun line(node: String, at: Long, kind: String = "her") =
        TranscriptLine(node = node, speaker = "xiaoman", text = "……", gameTime = at, kind = kind)

    @Test
    fun `三分钟以上的跨节点间隔才算空过一段时间`() {
        val lines = listOf(
            line("d1_n040", 0L),
            line("d1_w040", 3 * 60_000L),          // 全篇最短的一个等待：D1 那扇门锁
            line("d1_n042", 3 * 60_000L + 50_000L), // 只隔 50 秒——那是她在打字，不是断档
        )
        val gaps = TimeGaps.gaps(lines)
        assertNull("第一条前面没有上一条，不许有分隔", gaps[0])
        assertEquals("过了 3 分钟", gaps[1])
        assertNull("50 秒的间隔是演出节拍，不是时间断档：$gaps", gaps[2])
    }

    @Test
    fun `同一个节点内部永远不加分隔`() {
        // 倍率 3600× 时，一个节点内部两条气泡的 gameTime 也能差出几十分钟。
        // 那种间隔是我们自己造出来的，标在玩家面前就等于写了一段没发生过的历史。
        assertNull(
            "同节点内给了分隔，加速走查时会满屏假历史",
            TimeGaps.label(prev = 0L, cur = 40 * 60_000L, sameNode = true),
        )
        assertEquals("过了 40 分钟", TimeGaps.label(prev = 0L, cur = 40 * 60_000L, sameNode = false))
    }

    @Test
    fun `时钟倒退时不许写出负数`() {
        // 玩家改过设备时间、或后门回拨 OFFSET 之后，相邻两条的 gameTime 可能是负的。
        // 界面上出现「过了 -2 小时」比不出现分隔严重得多。
        assertNull(TimeGaps.label(prev = 10 * 60_000L, cur = 0L, sameNode = false))
        assertNull(TimeGaps.label(prev = 10 * 60_000L, cur = 10 * 60_000L, sameNode = false))
    }

    @Test
    fun `间隔怎么说人话`() {
        assertEquals("3 分钟", TimeGaps.humanize(3 * 60_000L))
        assertEquals("59 分钟", TimeGaps.humanize(59 * 60_000L + 40_000L))
        assertEquals("2 小时 14 分", TimeGaps.humanize((2 * 60 + 14) * 60_000L))
        // 六小时以上不再报零头：那已经是一整块时间，分钟数会让人以为它有meaning
        assertEquals("6 小时", TimeGaps.humanize((6 * 60 + 40) * 60_000L))
        assertEquals("11 小时", TimeGaps.humanize(11 * 60 * 60_000L))
        assertEquals("1 天", TimeGaps.humanize(24 * 60 * 60_000L))
        assertEquals("1 天 6 小时", TimeGaps.humanize(30 * 60 * 60_000L))
        assertEquals("7 天", TimeGaps.humanize(7 * 24 * 60 * 60_000L))
    }

    /**
     * 判据必须由剧本产生：这里不手写转写，是真的用引擎走一遍 D1，
     * 走到第一个等待（`d1_w040`，那扇她确认了三遍的门锁），按时钟推进三分钟再回来。
     * 这条挂了就说明界面上那个分隔在真实链路里根本不会出现——前面的单测全绿也没用。
     */
    @Test
    fun `真的走过一次等待之后那行分隔会出现`() {
        var fakeNow = 1_700_000_000_000L
        val clock = GameClock(installAt = fakeNow, now = { fakeNow })
        val engine = StoryEngine(ScriptRepository.fromDirectory(scriptDir()), FakeSignals.allIn())
        val save = SaveFile(chapter = 1)
        engine.applyChapterStartState(save)

        var halt = engine.advance(save, clock, null)
        var steps = 0
        while (halt !is StoryEngine.Halt.AwaitingWait && steps < 200) {
            halt = when (halt) {
                is StoryEngine.Halt.AwaitingChoice -> engine.choose(save, clock, halt.options.first())
                is StoryEngine.Halt.AwaitingInput -> engine.playerSays(save, clock, "。")
                is StoryEngine.Halt.AwaitingGrant -> engine.resolveGrant(save, clock, granted = true)
                else -> error("D1 走不到第一个等待，停在 $halt")
            }
            steps++
        }
        assertTrue("D1 前 200 步内没有等到一个等待，这条测试该跟着剧本改了", halt is StoryEngine.Halt.AwaitingWait)

        // 她那边真的过了三分钟（不是"玩家按了跳过"——forced 只影响闸门，不影响时钟读到的间隔）
        fakeNow += 3 * 60_000L
        engine.resolveWait(save, clock, forced = true)

        val gaps = TimeGaps.gaps(save.transcript).filterNotNull()
        assertTrue(
            "从等待里回来之后没有分隔标识：gaps=$gaps，转写 ${save.transcript.size} 条",
            gaps.contains("过了 3 分钟"),
        )
    }
}
