package cn.linan.backingup.ui

import cn.linan.backingup.engine.TranscriptLine

/**
 * 时间分隔标识——玩家往回翻的时候，得能看见中间在哪儿空过一段。
 *
 * 起因是真机玩家的建议：「她每次继续跟我说话，跟上一条之间最好有个分隔标识，
 * 不然往后看不知道哪里有时间间隔。」这句话是对的，而且指出了一个我们没做到的事：
 * 等待是这个作品唯一的恐怖手段，玩家却只能在**经历**它的时候知道它在发生，
 * 回看时全篇被压成了一条连续的聊天记录。
 *
 * 三条设计决定，都不是随手定的：
 *
 *  1. **纯表现层，不写进存档。**分隔靠相邻两条的 `game_time` 差算出来，
 *     老存档（包括玩家已经推到的进度）装上就立刻有分隔，不用清档重来，`schema` 仍是 1。
 *  2. **阈值 2 分钟。**全篇最短的一个等待是 D1 那扇门锁（`d1_w040`，3 分钟），
 *     所以 2 分钟这条线以上一定是"她那边过了一段时间"，以下一定是"她在打字"——
 *     把演出节拍误标成时间间隔，比漏标更糟，因为玩家会以为剧情跳了。
 *  3. **同节点内一律不标。**倍率 3600× 时一个节点内部两条之间也是"几十分钟"，
 *     标出来就是我们在后门加速时自造的假历史；分隔只属于跨节点的那次断档。
 *
 * [gaps] 是与入参等长的列表，`null` 表示这条不加分隔。它是纯函数，单测直接喂假转写。
 */
object TimeGaps {

    /** 低于这个间隔不算"空过一段时间"——见文件头第 2 条 */
    const val MIN_GAP_MS: Long = 120_000L

    /** 导出 txt 与界面共用的那一行文案，两处不许各写一套 */
    fun label(prev: Long, cur: Long, sameNode: Boolean): String? {
        if (sameNode) return null
        // 设备时间被改过、或倍率回拨时 gameTime 会倒退；"过了 -3 分钟"不许出现在玩家面前
        val delta = cur - prev
        if (delta < MIN_GAP_MS) return null
        return "过了 " + humanize(delta)
    }

    fun gaps(lines: List<TranscriptLine>): List<String?> = lines.mapIndexed { i, line ->
        if (i == 0) null else label(lines[i - 1].gameTime, line.gameTime, lines[i - 1].node == line.node)
    }

    /**
     * 「3 分钟」「2 小时 14 分」「6 小时」「1 天」「3 天 5 小时」。
     *
     * 六小时以上不再补分钟数：那已经是一整块时间，报"7 小时 12 分"会让人以为
     * 分钟数有意义。整天之后的零头保留，因为跨天正是这个作品真实的节奏。
     */
    fun humanize(ms: Long): String {
        val totalMin = ms / 60_000
        val hours = totalMin / 60
        val days = hours / 24
        return when {
            days > 0 -> if (hours % 24 > 0) "${days} 天 ${hours % 24} 小时" else "${days} 天"
            hours > 0 -> if (hours < 6 && totalMin % 60 > 0) "${hours} 小时 ${totalMin % 60} 分" else "${hours} 小时"
            else -> "${totalMin} 分钟"
        }
    }
}
