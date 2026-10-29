package cn.linan.backingup.engine

import cn.linan.backingup.story.ConditionContext

/**
 * 存档：一局游戏的全部可变态。
 *
 * 三度量（trust / awareness / integrity）是剧情判定唯一的依据；flag 与 counter
 * 承载世界事实（她有没有被打开过、玩家做了几次共犯）。设备状态不进存档——
 * 它是每次判定时现读的（见 DeviceSignals），否则"灭屏三十分钟"会变成"上次退出时灭屏"。
 *
 * 数值用 Double 存、按 Int 读：Gson 把所有 JSON 数字解成 Double，与其到处写 toInt()
 * 不如在入口统一。契约里 |Δ| ≤ 12 是单节点效果的硬上限，由 tools/validate_script.py
 * 在写侧保证，引擎这里只做章内安全阀（clamp）的截断。
 */
data class SaveFile(
    var schema: Int = SCHEMA,
    var installAt: Long = System.currentTimeMillis(),
    var rate: Double = 1.0,
    var offset: Long = 0L,
    var chapter: Int = 1,
    var nodeId: String? = null,
    var metrics: Metrics = Metrics(),
    var flags: MutableMap<String, Boolean> = mutableMapOf(),
    var counters: MutableMap<String, Int> = mutableMapOf(),
    /**
     * 玩家亲手给过的值（目前只有 `player_name`）。剧本里 `{{x}}` 的运行时那一半。
     *
     * 与 flags/counters 分开是因为语义不同：那两个是"发生过什么"，这个是"她怎么称呼你"，
     * 要参与文本渲染。系统类变量（日期）不进这里——它们每次现算，存下来就会过期。
     */
    var variables: MutableMap<String, String> = mutableMapOf(),
    var transcript: MutableList<TranscriptLine> = mutableListOf(),
    /** 玩家层快速模式：通关一次后解锁；结局《备份》的前置条件之一 */
    var quickMode: Boolean = false,
    /** 开发层用过后门。正式包不污染玩家数据（docs/04 第五节第 5 条） */
    var debugUsed: Boolean = false,
    /** 失真日志：被跳过/加速的等待总量，面板顶部常驻那一行读它 */
    var skippedMs: Long = 0L,
    var skippedWaits: MutableList<String> = mutableListOf(),
    var lastPlayedAt: Long = System.currentTimeMillis(),
    /** 到期时间（游戏时间）：null = 当前没有在途等待 */
    var waitUntilGameTime: Long? = null,
    var waitRef: String? = null,
    /** 断开期间已发送条数（d6_w640 最多三条，只有一条送达） */
    var waitSendsUsed: Int = 0,
    /**
     * d6_w650 的告别：玩家替她说完那句才武装这个等待。存节点 id 不存布尔，
     * 是为了"重新走到同一个等待"时不用再问一遍——问第二遍就成了逼玩家撒谎。
     */
    var farewellSentFor: String? = null,
    /** d6 的沉默：设定 player_silent 的真实时刻，4 小时闸门按设备时间判，不按游戏时钟 */
    var silenceSince: Long? = null,
    /**
     * 走到本章章末（`chapter_end`）那一刻的**游戏时间**，null = 本章还没走完。
     *
     * 它是 [cn.linan.backingup.engine.ChapterGate] 唯一的锚点：下一章开在"次日早上 07:30"，
     * 而"次日"必须相对于这一章真正结束的那天算，不能相对于"玩家哪天想起来打开 App"。
     * 只在第一次走到章末时写（原地重判会反复经过同一个节点），开章/传送/清空时清掉。
     */
    var chapterEndAtGameTime: Long? = null,
    /** 待投递的"退出后补投"（剧本 post_exit_notification），arm 之后由调度器清空 */
    var pendingNotifications: MutableList<PendingNotice> = mutableListOf(),

    /**
     * 周年触发（《紧急联系人》的"每年 10 月 31 日 20:14"）。
     *
     * `yearlyDueAt` 是**设备时间**、不是游戏时间：这条消息是整个作品里唯一一件玩家要在真实
     * 世界里等满一年的事，它必须绕开倍率——3600× 下把它也算过去，结局就没了。
     * 0 = 这个存档没有周年消息（没走到那个结局）。
     *
     * `yearlySourceNode` 存节点 id 而不是抄一份文案池：文案池是剧本的东西，
     * 每次排下一次都回剧本取（引擎不发明内容）。存档只记"从哪儿取的"。
     */
    var yearlyDueAt: Long = 0L,
    var yearlySourceNode: String? = null,
    /** 已经投过几条：文案池下标 = 这个数 % 池长，所以第 13 年自动回到第一条 */
    var yearlyMessagesSent: Int = 0,
) {
    fun copyEmpty(): SaveFile = copy(
        installAt = installAt, metrics = Metrics(), flags = mutableMapOf(),
        counters = mutableMapOf(), transcript = mutableListOf(), nodeId = null,
        variables = mutableMapOf(),
        waitSendsUsed = 0, farewellSentFor = null, silenceSince = null,
        chapterEndAtGameTime = null,
        pendingNotifications = mutableListOf(), skippedMs = 0L, skippedWaits = mutableListOf(),
        // 清空重开＝这一局没发生过。周年那条已经发出去的通知收不回来，但下一次不许再排
        yearlyDueAt = 0L, yearlySourceNode = null, yearlyMessagesSent = 0,
    )

    companion object {
        const val SCHEMA = 1
    }
}

/** 剧本声明的一条延迟通知：到点由 ReminderScheduler 投，only_if 决定发不发 */
data class PendingNotice(
    val node: String = "",
    val delayMs: Long = 0L,
    val text: String = "",
    /** 送达前再判一次：剧本里两条 post_exit_notification 都带 only_if（她不说没发生过的话） */
    val onlyIf: String? = null,
    var armedAt: Long = 0L,
)

data class Metrics(
    var trust: Double = 0.0,
    var awareness: Double = 0.0,
    var integrity: Double = 100.0,
) {
    operator fun get(name: String): Double? = when (name) {
        "trust" -> trust
        "awareness" -> awareness
        "integrity" -> integrity
        else -> null
    }

    fun set(name: String, value: Double) {
        when (name) {
            "trust" -> trust = value.coerceIn(0.0, 100.0)
            "awareness" -> awareness = value.coerceIn(0.0, 100.0)
            "integrity" -> integrity = value.coerceIn(0.0, 100.0)
        }
    }

    fun snapshot(): String = "T${trust.toInt()}·A${awareness.toInt()}·I${integrity.toInt()}"
}

/**
 * 一条已发生的话。转写全量留在存档里：结局《备份》要把它导出成
 * exports/小满-307.txt，那是她"以一份 412KB 文本的形式活着"的唯一证据。
 */
data class TranscriptLine(
    val node: String = "",
    val speaker: String = "",
    val text: String = "",
    val gameTime: Long = 0L,
    /** system / her / you / letter / wait_summary / ending */
    val kind: String = "her",
    /** 后门伪造触发的气泡打角标，避免我们误以为它自然触发了（docs/04 第二节第 5 条） */
    val debug: Boolean = false,
    val skipped: Boolean = false,
    /**
     * 这条气泡之前"对方正在输入…"该亮多久（来自节点 delay_ms）。
     * 存进转写而不是回查剧本：演出节拍一旦成为历史事实，导出时也得能重放。
     */
    val typingMs: Long = 0L,
)

/**
 * 条件求值。语法只支持剧本里真实出现过的四类（我把 script 目录下七个 act 文件全量扫过）：
 *
 *   flag                     → flags["flag"] == true
 *   flag == true|false       → 同上，显式
 *   度量 op 数               → trust|awareness|integrity，op ∈ >= > <= < == !=
 *   counter == 数            → counters（如 accomplice_count == 2）
 *   度量 in [a, b]            → 闭区间（隐藏结局《紧急联系人》的 awareness in [70, 76]）
 *
 * 解析不了的一律 false 并把原文记进 [unparsed]：隐藏结局的条件里有一句
 * "三封灭屏信全部完整阅读（未被跳过）"是写给作者看的，不是写给引擎的。
 * 引擎不许猜——猜错的方向是"玩家没达成却触发了"，那比不触发更糟。
 */
class StateCondition(
    private val metrics: Metrics,
    private val flags: Map<String, Boolean>,
    private val counters: Map<String, Int>,
    private val named: Map<String, () -> Boolean> = emptyMap(),
) : ConditionContext {

    val unparsed = linkedSetOf<String>()

    override fun check(expression: String): Boolean {
        val e = expression.trim()
        if (e.isEmpty()) return true

        // 具名判据既可能以裸名出现（requires_all 里的中文那句），也可能被 needs 拼成
        // "`no_reply_gt_4h == true`"——needs 的 value 一律是布尔，所以两种写法都得认。
        NAMED_EQ.matchEntire(e)?.destructured?.let { (name, value) ->
            named[name]?.let { return it() == (value == "true") }
        }
        named[e]?.let { return it() }

        IN_REGEX.matchEntire(e)?.destructured?.let { (lhs, lo, hi) ->
            val v = metrics[lhs.trim()] ?: counters[lhs.trim()]?.toDouble() ?: flagsFlag(lhs.trim())
            val a = lo.trim().toDoubleOrNull() ?: return@let
            val b = hi.trim().toDoubleOrNull() ?: return@let
            return v != null && v >= a && v <= b
        }

        CMP_REGEX.matchEntire(e)?.destructured?.let { (lhs, op, rhs) ->
            // 名字没见过 = 这条世界事实从没发生过 = false。
            // 剧本里 `quick_mode == false`、`accomplice == false` 是隐藏结局《紧急联系人》
            // 的前置条件，走的就是这条：默认存档里这两个 flag 根本不存在，玩家应当合格。
            // 反过来若把缺席当"判不了"，隐藏结局就对最干净的那批玩家永久关闭。
            val left = resolve(lhs.trim()) ?: if (rhs.trim() in BOOLEAN_LITERALS) 0.0 else return false
            val right = resolve(rhs.trim())
            if (right != null) return compare(left, right, op.trim())
            val literal = rhs.trim().toDoubleOrNull() ?: when (rhs.trim()) {
                "true" -> 1.0
                "false" -> 0.0
                else -> {
                    unparsed.add(e)
                    return false
                }
            }
            return compare(left, literal, op.trim())
        }

        // 裸 flag：`avoided_date_question` / `promised_rescue`。同样"没发生过就是 false"，
        // 但只有认得出是个标识符才这么判——中文长句走不到这里，它会被记进 unparsed。
        BARE_REGEX.matchEntire(e)?.destructured?.component1()?.let { name ->
            flags[name]?.let { return it }
            counters[name]?.let { return it != 0 }
            metrics[name]?.let { return it > 0.0 }
            return false
        }

        unparsed.add(e)
        return false
    }

    private fun flagsFlag(name: String): Double? =
        flags[name]?.let { if (it) 1.0 else 0.0 } ?: counters[name]?.toDouble()

    private fun resolve(token: String): Double? =
        metrics[token] ?: counters[token]?.toDouble() ?: flagsFlag(token)

    private fun compare(a: Double, b: Double, op: String): Boolean = when (op) {
        ">=" -> a >= b
        ">" -> a > b
        "<=" -> a <= b
        "<" -> a < b
        "==", "=" -> a == b
        "!=" -> a != b
        else -> {
            unparsed.add(op)
            false
        }
    }

    private companion object {
        val IN_REGEX = Regex("""^\s*([\w.\u4e00-\u9fa5]+)\s+in\s*\[\s*([-\d.]+)\s*,\s*([-\d.]+)\s*\]\s*$""")
        val CMP_REGEX = Regex("""^\s*([\w.\u4e00-\u9fa5]+)\s*(>=|<=|!=|==|>|<)\s*([\w.\u4e00-\u9fa5]+)\s*$""")
        val BARE_REGEX = Regex("""^\s*([\w.\u4e00-\u9fa5]+)\s*$""")
        val NAMED_EQ = Regex("""^([\w.\u4e00-\u9fa5]+)\s*==\s*(true|false)$""")
        val BOOLEAN_LITERALS = setOf("true", "false")
    }
}
