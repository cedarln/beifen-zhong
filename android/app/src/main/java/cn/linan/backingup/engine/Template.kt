package cn.linan.backingup.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 剧本变量替换：把 `{{player_name}}` 这种占位符换成她此刻会说的那几个字。
 *
 * 契约早就写在结构文档第七节和 `tools/render_transcript.py` 里了——
 * 「变量由引擎在渲染时替换」——但引擎一直没实现它。后果不是"少个功能"，
 * 是**玩家屏幕上出现了一行代码**：真机 D2 出现过「{{player_name}}。」，
 * D1 那句"明天是 星期几"出现过 `{{device_date}}`。这一版补上，并且把
 * "占位符不许漏到屏幕"变成判据（`TemplateTest` + `tools/validate_script.py`）。
 *
 * 三条不是随手定的规则：
 *
 * 1. **单遍替换，绝不递归**。玩家把 `{{player_name}}` 当成名字输入，如果替换后的结果
 *    再扫一遍，她就说出了自己的代码；更坏的情况是自我指涉的死循环。
 *    [render] 用一次 `Regex.replace` 完成，替换进去的内容不会再被扫描。
 * 2. **日期类变量按游戏时钟算，不按设备时钟**。这条和 `QuietHours`（按设备时间判，
 *    因为"别在凌晨吵醒人"是卧室里的事实）相反，理由是 `docs/04` 第三节：倍率作用在
 *    时钟层，D1 说的"明天"必须是她那儿的明天。1× 下两者本来就是同一天。
 * 3. **没有值的时候说人话，不说代码**。剧本在顶层 `variables.<名>.unset_value` 里
 *    写好"没给名字她叫你什么"（D2 的 `d2_n220` 已经写了：「喂」）。真取不到值时
 *    落到 [FALLBACK]，那是最后一道遮羞布，出现即说明剧本或引擎有洞——
 *    所以 [unresolved] 把它单独暴露给测试判，而不是让它悄悄滑过屏幕。
 */
object Template {

    /** 引擎认识的全部变量名。剧本里出现不在这个集合里的 `{{x}}` 是剧本事故 */
    val KNOWN: Set<String> = setOf(
        "player_name",          // 运行时：D2 c5 玩家输入
        "device_date",          // 系统：游戏时钟当天，yyyy-MM-dd
        "tomorrow_date",        // 系统：游戏时钟次日，yyyy-MM-dd
        "tomorrow_weekday_cn",  // 系统：游戏时钟次日是星期几（中文）
    )

    /** 取不到值又没写 unset_value 时的兜底。她常用的那个省略号——不是代码，也不假装是内容 */
    const val FALLBACK = "……"

    private val PLACEHOLDER = Regex("""\{\{([a-z0-9_]+)}}""")
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val WEEKDAYS_CN = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** 一段文本里出现的所有占位符名字（按出现顺序，不去重） */
    fun names(text: String): List<String> = PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toList()

    /** 还没法解析的占位符：值缺失且没给 unset_value 时非空。测试与后门日志用它，不给玩家看 */
    fun unresolved(text: String, vars: Map<String, String>, unset: (String) -> String? = { null }): List<String> =
        names(text).filter { vars[it].isNullOrBlank() && unset(it).isNullOrBlank() }

    /**
     * 渲染。[vars] 是"此刻能拿到的值"，[unset] 是"这个变量在剧本里声明的兜底值"。
     *
     * 空白值当没有值处理：玩家输入框里敲一个空格不等于给她起了个名字。
     */
    fun render(
        text: String,
        vars: Map<String, String>,
        unset: (String) -> String? = { null },
    ): String {
        if (text.indexOf("{{") < 0) return text
        return PLACEHOLDER.replace(text) { match ->
            val name = match.groupValues[1]
            vars[name]?.takeIf { it.isNotBlank() } ?: unset(name)?.takeIf { it.isNotBlank() } ?: FALLBACK
        }
    }

    /** 系统变量：由游戏时钟那一刻算，玩家给不了、后门也不该单独改（改 OFFSET 就是改它） */
    fun systemVars(
        atGameTime: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<String, String> {
        val today = Instant.ofEpochMilli(atGameTime).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        return mapOf(
            "device_date" to today.format(DATE),
            "tomorrow_date" to tomorrow.format(DATE),
            // DayOfWeek 是 1..7（周一=1），正好直接下标
            "tomorrow_weekday_cn" to WEEKDAYS_CN[tomorrow.dayOfWeek.value - 1],
        )
    }

    /**
     * 存档里玩家给过的值 + 那一刻的系统值——"她此刻能说出的一切"。
     *
     * 单独开一个口是因为调度层（`CatchUpWorker`）手里没有引擎实例，但它排的闹钟
     * 念出来必须和屏上那句是同一句话；两边各拼一遍 `systemVars(...) + save.variables`
     * 迟早会拼出两个日子。空白值当没有（输入框里敲个空格不等于给她起了名字）。
     */
    fun varsFor(
        saveVariables: Map<String, String>,
        atGameTime: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<String, String> =
        systemVars(atGameTime, zone) + saveVariables.filterValues { it.isNotBlank() }

    /**
     * 玩家亲手输入的值要过的这道门。
     *
     * 只保留"能在一个气泡里被念出来"的东西：去首尾空白、压掉换行（她那句"我念了一遍"
     * 不该被拆成三条气泡）、限长（剧本声明的 max_len）、以及**把花括号整个拿掉**。
     *
     * 花括号为什么剥得这么狠：单遍替换（[render]）本来就已经保证不递归——玩家输入
     * `{{player_name}}` 只会得到字面的它，不会再扫第二遍。但转写导出《备份》时那是
     * 一份文件，文件里出现 `{{` 会被下一个人当成 bug。所以规则是"**存档里永远不该有一个
     * 能被子替换的串**"，而不是"渲染时小心一点"：连名字都没得用（清洗后为空）就让她继续等，
     * 也好过把一串代码存成她念过的话。
     */
    fun sanitizeInput(raw: String, maxLen: Int): String =
        raw.replace(Regex("""[\r\n\t]+"""), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .replace(Regex("""[{}]"""), "")
            .take(maxLen.coerceAtLeast(1))
            .trim()

    /** 给某个日期变量用的可读日号：后门面板显示"她那边现在是 10-29" */
    fun dayLabel(atGameTime: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d: LocalDate = Instant.ofEpochMilli(atGameTime).atZone(zone).toLocalDate()
        return "%02d-%02d".format(d.monthValue, d.dayOfMonth)
    }
}
