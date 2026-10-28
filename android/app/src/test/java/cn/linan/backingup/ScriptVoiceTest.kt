package cn.linan.backingup

import cn.linan.backingup.story.Node
import cn.linan.backingup.story.Script
import cn.linan.backingup.story.ScriptRepository
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文风守门。这几条断言守的不是「剧情对不对」，是她读起来像不像一个正在打字的人。
 *
 * 起因是 v0.1.1 之后一次通读复核的结论：剧本「AI 味太重」。机器味在这里有四个可数的形状，
 * 每一条都拿改之前的原文当证据：
 *
 *  1. **每段都要收一个金句**。「等得挺熟练的。」「它比我还有礼貌，起码我还会回话。」
 *     人深夜打字不逐段修辞，句长分布也不可能这么整齐。
 *  2. **同一个反转句式复读**。「不是坏了。是那些房没了。」＋「不是删了。是那一栏整个不在。」
 *     ＋「它不是被拆了。它是一直都是墙。」——一章三次，指纹就不是指纹了。
 *  3. **自我点评**。「哦，对，礼貌。我忘了礼貌。」「我不算了，算了不好看。」
 *     角色替观众解释自己为什么这么说，是生成文本最稳的习惯。
 *  4. **等待回来她把同一句话说两遍**。引擎先贴 wait 的 first_line_after_wait，再走 next
 *     节点正文；两边都写同一句，玩家看到的就是复读。旧稿有 8 处，那是 bug 不是风格。
 *
 * 阈值取改完之后的实测值再留一档余量：最短那章 ≤6 字气泡占 12.9%，最长那章均长 14.8，
 * 最多那章反转句 2 次（D6 旧稿一章六次）。往回滑就红。
 */
class ScriptVoiceTest {

    private val repo = ScriptRepository.fromDirectory(scriptDir())

    /**
     * 她说过的话：正文气泡，外加等待回来的那一句——引擎会把那一句作为一条 her 贴进转写，
     * 所以它算她说的话，只查 text 数组会漏。
     */
    private fun herLines(script: Script): List<Pair<Node, String>> = script.nodes
        .filter { it.speaker == "xiaoman" }
        .flatMap { node ->
            when (node.type) {
                "wait" -> listOf(node to (node.firstLineAfterWait ?: ""))
                else -> node.text.map { node to it }
            }
        }
        .filter { it.second.isNotBlank() }

    private fun stripped(s: String): String = s.replace(Regex("\\s"), "")

    private fun pct(part: Long, whole: Int): String = String.format("%.1f", part * 100.0 / whole)

    private fun one(x: Double): String = String.format("%.1f", x)

    @Test
    fun `等待回来的那一句不许在下一节点再说一遍`() {
        val offenders = mutableListOf<String>()
        for (script in repo.chapters) {
            for (node in script.nodes.filter { it.type == "wait" }) {
                val line = node.firstLineAfterWait ?: continue
                val next = node.next?.let { repo.node(it) } ?: continue
                // 只有「她接着说」才算复读：next 是系统消息或选项时，等待那句是唯一的过渡
                if (next.speaker != "xiaoman" || next.type == "wait") continue
                val a = stripped(line)
                for (other in next.text.map { stripped(it) }) {
                    if (other.isBlank()) continue
                    if (a == other || a.startsWith(other) || other.startsWith(a)) {
                        offenders += "${node.id} → ${node.next}：$line ⟂ ${next.text.firstOrNull()}"
                    }
                }
            }
        }
        assertTrue(
            "她刚说完的话又原样说一遍，玩家只会觉得这是机器在复读" +
                "（等待承接句与下一节点正文必须二选一）：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `她一句话长一句话短，不是每句都一样长`() {
        val report = mutableListOf<String>()
        var allLines = 0
        var allShort = 0
        for (script in repo.chapters) {
            val lens = herLines(script).map { stripped(it.second).length }
            val short = lens.count { it <= 6 }
            allLines += lens.size
            allShort += short
            val shortPct = short * 100.0 / lens.size
            val mean = lens.average()
            report += "D${script.meta.chapter} 气泡 ${lens.size}，≤6 字 $short，" +
                "占比 " + pct(short.toLong(), lens.size) + "%，均长 " + one(mean)
            assertTrue(
                "D${script.meta.chapter} 短句只占 " + pct(short.toLong(), lens.size) + "%，下限 12%。" +
                    "真人打字一大半是「你」「好的」「没了」这种；句句都是中等长度的完整句子，" +
                    "那就是生成文本的形状。明细：" + report.joinToString("；"),
                shortPct >= 12.0,
            )
            assertTrue(
                "D${script.meta.chapter} 平均句长超过 20：整章都在写小作文，她没有在打字。" +
                    "明细：" + report.joinToString("；"),
                mean <= 20.0,
            )
        }
        val total = allShort * 100.0 / allLines
        assertTrue(
            "全书 ≤6 字短气泡只占 " + pct(allShort.toLong(), allLines) + "%，下限 15%。" +
                "单章阈值管局部，这条管整体像不像聊天。",
            total >= 15.0,
        )
        println(report.joinToString("\n"))
    }

    @Test
    fun `同一个反转句式一章不许出现第三次`() {
        // 「不是X。是Y。」是这次复核里出现最多的机器指纹（旧稿她的气泡里 16 处）
        val reversal = Regex("不是[^。]{1,14}。[^。]{0,8}是")
        val offenders = mutableListOf<String>()
        for (script in repo.chapters) {
            val hits = herLines(script).map { it.second }.filter { reversal.containsMatchIn(it) }
            if (hits.size > 2) {
                offenders += "D${script.meta.chapter} 出现 ${hits.size} 次：" + hits.joinToString(" / ")
            }
        }
        assertTrue(
            "「不是……是……」是她的讲理方式，一次是性格，三次是模板：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `她的哈哈哈哈只许单独成条且后面跟一句真话`() {
        val offenders = mutableListOf<String>()
        for (script in repo.chapters) {
            val lines = herLines(script).map { it.second }
            lines.forEachIndexed { i, s ->
                val t = stripped(s)
                // 只有以「哈哈哈哈」开头的气泡才算笑——「不是哈哈哈哈那种」她在描述笑，不是在笑
                if (!t.startsWith("哈哈哈哈")) return@forEachIndexed
                if (t != "哈哈哈哈") {
                    offenders += "D${script.meta.chapter} 笑和话挤在同一条气泡里：$s"
                }
                val next = stripped(lines.getOrNull(i + 1) ?: "")
                if (next.length < 6 || next == "……" || next.startsWith("哈哈哈哈")) {
                    offenders += "D${script.meta.chapter} 笑完没有真话：$s ⟂ ${lines.getOrNull(i + 1)}"
                }
            }
        }
        assertTrue(
            "圣经规则 8：她每次笑都是撑着，所以笑必须单独一条、后面必须落到一句真话上。\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
