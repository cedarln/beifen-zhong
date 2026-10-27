package cn.linan.backingup.story

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * 剧本 JSON 的数据模型——对应 docs/02 第七节的格式契约。
 *
 * 三条决定这份模型形状的事实（改模型前先读那段契约）：
 *  1. 节点类型只有七种：system / message / silence / letter / choices / wait / ending。
 *     这里用一个 Node 承载全部字段而不是 sealed class，是为了让"剧本改了字段不用发版"成立：
 *     Gson 对未知字段静默忽略，新加的注释性字段（note、style_note、engine_behavior）
 *     不会让旧包崩掉。七种类型的合法性由 tools/validate_script.py 在写侧保证。
 *  2. needs 的 value 既可能是布尔（flag），也可能是字符串（"trust >= 60"），
 *     所以是 Map<String, JsonElement>，判定逻辑在 engine/ConditionEvaluator。
 *  3. conditional_line（单对象）与 conditional_lines（数组）两种形态并存，都要收。
 */

data class Script(
    val meta: Meta = Meta(),
    val variables: Map<String, JsonElement> = emptyMap(),
    val nodes: List<Node> = emptyList(),
) {
    /** 章内节点索引：全篇节点全量加载后按 id 取，比每次线性找划算 */
    val nodeById: Map<String, Node> by lazy { nodes.associateBy { it.id } }
}

data class Meta(
    val project: String = "",
    val chapter: Int = 0,
    val chapterId: String = "",
    val title: String = "",
    val version: String = "",
    val readingMinutes: Int = 0,
    val realWaitTotal: String = "",
    val startState: Map<String, Double> = emptyMap(),
    val expectedExitState: Map<String, JsonElement> = emptyMap(),
    val clamp: JsonElement? = null,
    val signalsAllowed: List<String> = emptyList(),
    val ambience: Ambience? = null,
)

/** 声音层契约（docs/06 第二节）：编排是数据，节点级不写 ambience */
data class Ambience(
    val beds: List<String> = emptyList(),
    val defaultGains: Map<String, Double> = emptyMap(),
    val fadeMs: Long = 4000,
    val segments: List<AmbienceSegment> = emptyList(),
    val chapterNote: String? = null,
)

data class AmbienceSegment(
    val from: String = "",
    val to: String = "",
    val gain: Map<String, Double> = emptyMap(),
    val note: String = "",
    val fadeMs: Long? = null,
)

data class Choice(
    val text: String = "",
    val to: String = "",
    val effects: Map<String, Double> = emptyMap(),
    /** 选项级闸门：d7_c12 的「我把你存下来了」只在 quick_mode==true 时出现 */
    val needs: Map<String, JsonElement> = emptyMap(),
    /** 选项级世界事实：共犯次数、逻辑路线、盒子开没成都从这里写 */
    val flagsSet: List<String> = emptyList(),
    val costNote: String? = null,
    val note: String? = null,
) {
    /** 语法与节点级 needs 完全一致，共用同一个拼接函数，不允许两处各写一份 */
    fun needsExpressions(): List<String> = needsExpressionsOf(needs)
}

/** d1_n140 的授权分支：允许/拒绝各自带效果、信号与落点，是剧本里唯一的"设备权限即剧情"处 */
data class GrantBranch(
    val effects: Map<String, Double> = emptyMap(),
    val signal: String? = null,
    val then: String? = null,
    val note: String? = null,
)

/** 退出后补投的那一条（d1_n150 / d6_end）：delay_ms 到点由本地调度投，only_if 决定发不发 */
data class PostExitNotification(
    val delayMs: Long = 0L,
    val text: String = "",
    val onlyIf: String? = null,
    val note: String? = null,
)

/**
 * conditional_line / conditional_lines 的元素。
 *
 * `replace_text` / `append_text` 声明成 JsonElement 而不是 List<String>，是被剧本逼的：
 * D6 与 D7 各有一处作者把单句直接写成了字符串。Gson 对**类型不符**是硬抛
 * （Expected BEGIN_ARRAY but was STRING），那等于开包即崩；而"改剧本不用重发版"
 * 这条承诺要求装载层对单句/数组两种写法都不敏感。[lines] 是唯一读法。
 */
data class Conditional(
    @SerializedName("if") val condition: String = "",
    val replaceText: JsonElement? = null,
    val appendText: JsonElement? = null,
    val note: String? = null,
) {
    /** 命中后整段替换的正文；没写就是 null（调用方据此决定替换还是追加） */
    fun replaceLines(): List<String>? = replaceText?.let { linesOf(it) }

    /** 命中后追加在正文之后的句子 */
    fun appendLines(): List<String> = appendText?.let { linesOf(it) } ?: emptyList()

    companion object {
        /** 字符串算一句，数组算多句，其它（对象/null）算没有——绝不猜 */
        fun linesOf(value: JsonElement): List<String> = when {
            value.isJsonArray -> value.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            value.isJsonNull -> emptyList()
            value.isJsonPrimitive -> listOf(value.asString)
            else -> emptyList()
        }
    }
}

data class Node(
    val id: String = "",
    val type: String = "",
    val speaker: String? = null,

    // ── 演出 ────────────────────────────────────────────────────────────────
    val text: List<String> = emptyList(),
    val delayMs: Long? = null,
    val promptFrom: String? = null,
    /** prompt_text 在剧本里是数组（多条气泡），不是单句 */
    val promptText: List<String> = emptyList(),
    val choices: List<Choice> = emptyList(),
    val typingIndicator: Boolean? = null,
    val typingStoppedAtMs: Long? = null,
    val durationMs: Long? = null,
    val activity: String? = null,
    /** 回指结构文档第二节的等待表（W1-3）：跳等待、失真日志都靠它，不靠节点 id */
    val waitRef: String? = null,
    val firstLineAfterWait: String? = null,
    val preWaitLine: String? = null,
    val fastModeSummary: String? = null,
    val fastModeSkippable: Boolean? = null,
    val wordLimit: Int? = null,
    val title: String? = null,

    // ── 边 ──────────────────────────────────────────────────────────────────
    val next: String? = null,
    val then: String? = null,
    val after: String? = null,
    val afterNote: String? = null,
    val afterAction: String? = null,
    val fallbackId: String? = null,
    val chapterEnd: Boolean? = null,
    /** 作者是拿它当注记写的，单句字符串（不是数组） */
    @SerializedName("_in_from") val inFrom: String? = null,

    // ── 条件与效果 ──────────────────────────────────────────────────────────
    val effects: Map<String, Double> = emptyMap(),
    val needs: Map<String, JsonElement> = emptyMap(),
    val needsSignal: Map<String, JsonElement> = emptyMap(),
    val requires: String? = null,
    val requiresAll: JsonElement? = null,
    val flagsSet: List<String> = emptyList(),
    val conditionalLine: Conditional? = null,
    val conditionalLines: List<Conditional> = emptyList(),

    // ── 设备信号（白名单见 docs/02 第七节；新增必须先回圣经补感知映射表） ──
    val signal: String? = null,
    val signalLine: String? = null,
    val actionButton: String? = null,
    val onGrant: GrantBranch? = null,
    val onDeny: GrantBranch? = null,
    val notifyDeniedBehavior: String? = null,
    val postExitNotification: PostExitNotification? = null,
    val noReply: Boolean? = null,
    val noReplyNote: String? = null,
    val delivery: String? = null,
    val deliveredOnly: Int? = null,
    val disconnect: Boolean? = null,

    /** player_may_send 在 d6_w640 里是**条数**（3），不是布尔；两种写法都要能吃 */
    val playerMaySend: JsonElement? = null,
    val farewellRequired: Boolean? = null,
    val farewellLine: String? = null,
    val uiElement: String? = null,
    val confirmText: List<String> = emptyList(),
    val guard: JsonElement? = null,
    /** 后门面板的额外入口（结局页、章节入口节点都靠它被直接访问到） */
    val uiEntry: Boolean? = null,

    // ── 结局 ────────────────────────────────────────────────────────────────
    val endingId: String? = null,
    val endingName: String? = null,
    val recoverable: Boolean? = null,
    val hidden: Boolean? = null,
    val isTrueEnding: Boolean? = null,
    val action: String? = null,
    val audioRef: String? = null,
    /** d7_n711 写的是 1（可重放次数），不是布尔 */
    val replayAllowed: Int? = null,
    /** d7_end 写的是对象（`{"quick_mode": true, "epilogue_teaser": "…"}`），不是字符串 */
    val postCompletionUnlock: JsonElement? = null,
    val causedBy: String? = null,

    // ── 只给作者和审查看，引擎不消费 ────────────────────────────────────────
    val note: String? = null,
    val styleNote: String? = null,
    val playerToneNote: String? = null,
    val altVersionNote: String? = null,
    val engineRequirement: String? = null,
    val engineBehavior: String? = null,
    val sampleYearlyLines: JsonElement? = null,
    val preEndingNodes: JsonElement? = null,
) {
    /** 演出节拍：message/silence 用 delay_ms；缺省给一个不像机器的人工延迟 */
    fun typingMsFor(): Long =
        delayMs ?: if (type == "message" || type == "silence") DEFAULT_TYPING_MS else 0L

    /** 统一 needs 判定用的表达式串：`{"trust": "< 40"}` / `{"box_opened": true}` 都能拼 */
    fun needsExpressions(): List<String> = needsExpressionsOf(needs)

    /**
     * 结局自带的硬条件（`requires` 单句 / `requires_all` 数组，两种写法并存）。
     * 与 needs 的区别不是语法，是位置：needs 挂在闸门节点上、有 fallback_id；
     * requires 挂在结局上，代表"这个结局本来就不该在此刻出现"。
     */
    fun requiresExpressions(): List<String> = buildList {
        requires?.let { add(it) }
        val all = requiresAll
        if (all != null && all.isJsonArray) {
            all.asJsonArray.forEach { if (it.isJsonPrimitive) add(it.asString) else add(it.toString()) }
        }
    }

    /** 结构文档第三节的约定：note 里带 ◆ 的是本章恐怖峰值，后门面板的"直达峰值"按它取 */
    val peakMarker: Boolean get() = note?.contains("◆") == true

    /**
     * d7_end 的 `post_completion_unlock.quick_mode`：通关才解锁的快速模式。
     * 由引擎在走到这一节点时兑现，不是设置页里那个可以先打开的开关。
     */
    fun unlocksQuickMode(): Boolean =
        postCompletionUnlock?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("quick_mode")?.takeIf { it.isJsonPrimitive }?.asBoolean == true

    /**
     * 《紧急联系人》的周年文案池（`sample_yearly_lines`）。
     *
     * 哪个结局有周年消息由**剧本**决定：这个字段非空就是它有，代码里不写死 `d7_e760`，
     * 也不写死"ending_emergency_contact"。以后想给别的结局加，改剧本就行。
     * 契约（docs/02）要求 12 条、不重复、12 年后循环；条数由 `tools/validate_script.py` 守。
     */
    fun yearlyLines(): List<String> = sampleYearlyLines?.let { Conditional.linesOf(it) } ?: emptyList()

    val isWait: Boolean get() = type == "wait"
    val isChoices: Boolean get() = type == "choices"
    val isEnding: Boolean get() = type == "ending"

    /** 授权节点：有 action_button 且带 on_grant/on_deny，引擎必须停下来等玩家点 */
    val isGrant: Boolean get() = actionButton != null && (onGrant != null || onDeny != null)

    /** d6_w640：断开期间玩家最多能发几条（`player_may_send` 给数字用它，给布尔当 1 条） */
    fun sendAllowance(): Int = when {
        playerMaySend == null -> 0
        playerMaySend!!.isJsonPrimitive && playerMaySend!!.asJsonPrimitive.isNumber ->
            playerMaySend!!.asInt
        playerMaySend!!.asBoolean -> 1
        else -> 0
    }
    val isSilence: Boolean get() = type == "silence"

    /**
     * 条件命中后的实际气泡。契约：replace_text 整段替换，append_text 追加在正文之后。
     * 多条命中时按剧本顺序全部应用（校验器保证 replace 与 append 不会互相打架）。
     */
    fun resolvedText(state: ConditionContext): List<String> {
        var out = text
        for (c in conditionals()) {
            if (!state.check(c.condition)) continue
            c.replaceLines()?.let { out = it }
            out = out + c.appendLines()
        }
        return out
    }

    /** 单对象与数组两种形态统一成一个列表，避免调用点分叉 */
    fun conditionals(): List<Conditional> =
        (if (conditionalLine != null) listOf(conditionalLine!!) else emptyList()) + conditionalLines
}

/** 契约没写 delay_ms 时用的默认"正在输入"时长：太快不像人，太慢像卡住 */
const val DEFAULT_TYPING_MS = 2500L

/**
 * needs 的两种写法拼成判定串：
 *   `{"trust": "< 40"}`     → "trust < 40"（值本身就是比较式）
 *   `{"box_opened": true}`  → "box_opened == true"
 * 节点级与选项级共用这一个函数——两边语法必须永远一致，否则剧本会写出"在这儿能用、
 * 在那儿不能用"的同一条条件。
 */
fun needsExpressionsOf(needs: Map<String, JsonElement>): List<String> = needs.map { (name, value) ->
    val literal = if (value.isJsonPrimitive) value.asString else value.toString()
    if (literal.matches(Regex("""[<>!=].*"""))) "$name $literal" else "$name == $literal"
}

/** 条件判定需要的最小上下文，让 story 包不依赖 engine 包 */
interface ConditionContext {
    fun check(expression: String): Boolean
}
