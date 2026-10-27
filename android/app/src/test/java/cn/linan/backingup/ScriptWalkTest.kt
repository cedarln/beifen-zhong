package cn.linan.backingup

import cn.linan.backingup.engine.GameClock
import cn.linan.backingup.engine.SaveFile
import cn.linan.backingup.engine.StateCondition
import cn.linan.backingup.engine.StoryEngine
import cn.linan.backingup.engine.TranscriptLine
import cn.linan.backingup.story.Node
import cn.linan.backingup.story.ScriptRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剧本走查：不装模拟器、不接真机，把仓库根 script/ 的每个节点在 JVM 里全部走一遍。
 *
 * 四条断言各自守的错（写在这里是因为它们会各自以不同的方式骗过其它测试）：
 *  1. 装载与去重 —— Gson 对类型不符是硬抛、对多写字段是静默忽略，两种事故都得当场看见；
 *     重复 id 更阴险：它不报错，只是后一份永远读不到。
 *  2. 每个节点都能被引擎走进去 —— 闸门节点缺 fallback、next 写错、type 不认，都在这里露出来。
 *  3. 静态可达 —— "引擎能走"不等于"玩家能走到"：孤儿节点是写完 D6/D7 最容易留下的东西。
 *  4. 条件语法 —— 剧本里真实出现过的每一种 needs / requires / conditional_line.if
 *     都必须被求值器认识；不认识的只有《紧急联系人》那句写给作者看的话。
 */
class ScriptWalkTest {

    private val repo: ScriptRepository = ScriptRepository.fromDirectory(scriptDir())
    private val clock = GameClock(installAt = 1_700_000_000_000L)

    @Test
    fun `七个章文件装载成功且 id 不重复`() {
        val scripts = repo.chapters
        assertEquals(7, scripts.size)
        assertEquals((1..7).map { "d$it" }, scripts.map { it.meta.chapterId })

        val seen = mutableMapOf<String, Int>()
        scripts.forEachIndexed { i, script ->
            assertTrue("d${i + 1} 一个节点都没有", script.nodes.isNotEmpty())
            script.nodes.forEach { node ->
                val dup = seen.put(node.id, i + 1)
                require(dup == null) { "节点 id ${node.id} 在章 $dup 与 ${i + 1} 重复" }
                assertTrue("${node.id} 的 type 不在契约的七种里：${node.type}", node.type in NODE_TYPES)
            }
        }
        // 182 = 结构文档的 179 + 发布前复审拆出来的三个长气泡（d5_n510b / d5_n513b / d5_n545b）。
        // 拆节点是为了手机上不出现一整面墙，改完必须回到这里改总数：这条断言的作用
        // 不是"数字对得上"，是"任何增删都要被看见一次"。
        assertEquals("全篇节点数与结构文档不符", 182, seen.size)
    }

    @Test
    fun `每个节点都能被引擎走进去`() {
        val failures = mutableListOf<String>()
        repo.chapters.forEachIndexed { index, script ->
            val chapter = index + 1
            script.nodes.forEach { node ->
                val save = SaveFile(chapter = chapter).also { fresh ->
                    repo.chapters[index].meta.startState.forEach { (k, v) -> fresh.metrics.set(k, v) }
                    satisfyEveryFact(fresh)
                }
                val engine = StoryEngine(repo, FakeSignals.allIn())
                val halt = engine.advance(save, clock, node.id, ignoreEntryGate = true)
                if (halt is StoryEngine.Halt.DeadEnd) {
                    failures += "d$chapter ${node.id}(${node.type}) → ${halt.why}"
                }
            }
        }
        assertTrue("引擎走不进这些节点：\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * 每一章都是**独立的一张图**：章与章之间没有 next，跨章靠的是"上一章的结局解锁下一章、
     * 引擎从 `script.nodes.first()` 起这一章"。所以可达性必须按 7 个入口分别算，
     * 从 d1_n000 单源去够全篇是错的——它会报告 D2~D7 全是孤儿，而剧本其实没写错。
     *
     * 这里做多源 BFS：7 个章入口 + 所有 `ui_entry` 节点一起入队。后者是剧本里唯一一类
     * "不由图边到达、由玩家的手到达"的节点（d7_n750『结束通话』在会话页右上角，
     * 不是选项），它们本来就没有前驱；GameViewModel.uiEntryNode() 负责把它们挂进 UI。
     * 这样仍然能抓真孤儿：一个节点要是连本章入口和 UI 入口都够不着，七个源一起也够不着。
     */
    @Test
    fun `每个节点都从本章入口或 UI 入口静态可达`() {
        val all = repo.chapters.flatMap { it.nodes }.associateBy { it.id }
        val entries = repo.chapters.map { it.nodes.first().id } +
            all.values.filter { it.uiEntry == true }.map { it.id }

        val reached = linkedSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.addAll(entries)
        val dangling = linkedMapOf<String, String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id in reached) continue
            val node = all[id]
            if (node == null) {
                dangling["?$id"] = "被引用但不存在"
                continue
            }
            reached += id
            edgesOf(node).forEach { target ->
                if (target in all) queue.add(target) else dangling[node.id] = "指向不存在的 $target"
            }
        }

        val orphans = all.keys - reached
        assertTrue(
            "这些节点没有任何一条路径走到：\n" + orphans.sorted().joinToString("\n"),
            orphans.isEmpty(),
        )
        assertTrue(
            "这些边指向剧本里没有的节点：\n" + dangling.entries.joinToString("\n") { "${it.key} → ${it.value}" },
            dangling.isEmpty(),
        )
    }

    @Test
    fun `剧本里出现的每种条件语法都能被求值`() {
        val unparsed = linkedSetOf<String>()
        val expressions = linkedSetOf<String>()
        repo.chapters.flatMap { it.nodes }.forEach { node ->
            node.needsExpressions().forEach { expressions += it }
            node.requiresExpressions().forEach { expressions += it }
            node.choices.forEach { c -> c.needsExpressions().forEach { expressions += it } }
            node.conditionals().forEach { expressions += it.condition }
        }
        expressions.forEach { expression ->
            val save = SaveFile().also { satisfyEveryFact(it) }
            save.metrics.set("trust", 60.0)
            save.metrics.set("awareness", 72.0)
            save.metrics.set("integrity", 80.0)
            save.counters["accomplice_count"] = 2
            save.silenceSince = System.currentTimeMillis() - StoryEngine.SILENCE_GATE_MS - 1
            // 这里造的是"裸语法"：不带引擎那两个具名判据，所以引擎在 condition() 里
            // 显式翻成数据判据的那句中文，会作为唯一一条落进 unparsed。
            val condition = StateCondition(save.metrics, save.flags, save.counters)
            condition.check(expression)
            unparsed += condition.unparsed
        }
        assertEquals(
            "引擎的裸语法只该认不出这一句（它由 condition() 的具名判据负责）",
            setOf(StoryEngine.SCREEN_OFF_LETTERS_CONDITION),
            unparsed,
        )
    }

    /**
     * 把世界事实填满，让闸门节点走主路径而不是 fallback——测的是"这条路径本身走不走得通"。
     * 真实时间类条件（no_reply_gt_4h）也在这里造好，否则每个测试都得自己记得。
     */
    private fun satisfyEveryFact(save: SaveFile) {
        ScriptRepository.engineFlagNames(repo.flags).forEach { save.flags[it] = true }
        ScriptRepository.storyFlagNames(repo.flags).forEach { save.flags[it] = true }
        save.silenceSince = System.currentTimeMillis() - StoryEngine.SILENCE_GATE_MS - 1L
        save.farewellSentFor = "d6_w650"
        // 灭屏信读没读要体现在转写里，否则隐藏结局的中文条件永远不成立
        listOf("d2_l290", "d5_l550", "d7_l729").forEach { id ->
            save.transcript.add(TranscriptLine(id, "letter", "…", 0L, kind = "letter"))
        }
    }

    private fun edgesOf(node: Node): List<String> {
        val out = mutableListOf<String>()
        listOf(node.next, node.then, node.after, node.fallbackId).forEach { it?.let(out::add) }
        node.choices.forEach { out += it.to }
        node.onGrant?.then?.let(out::add)
        node.onDeny?.then?.let(out::add)
        return out
    }

    private companion object {
        val NODE_TYPES = setOf("system", "message", "silence", "letter", "choices", "wait", "ending")
    }
}
