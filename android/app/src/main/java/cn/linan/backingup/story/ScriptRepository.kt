package cn.linan.backingup.story

import android.content.Context
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

/**
 * 剧本装载：assets 里的 act1..act7.json + flags.json（源头是仓库根的 script/ 目录，
 * 由 app/build.gradle.kts 的 assets.srcDir 挂进来，工程内不留第二份副本）。
 *
 * 装载策略：一次全量读进内存。理由——七章 JSON 合计 56KB，比一张位图还小；
 * 而 D6/D7 的跨章回指需要随时按 id 取任意章的节点，按需加载只会让后门面板的
 * "按 node id 传送"多一层异步。
 */
/** 剧本来源：真机是 assets，JVM 单测直接读仓库根的 script/ 目录——走查全篇节点不需要模拟器 */
fun interface ScriptSource {
    fun read(name: String): String
}

class ScriptRepository(private val source: ScriptSource) {

    constructor(context: Context) : this(
        ScriptSource { name -> context.assets.open(name).bufferedReader().use { it.readText() } }
    )

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    val chapters: List<Script> by lazy {
        (1..CHAPTER_COUNT).map { load(it) }.also { scripts ->
            require(scripts.map { it.meta.chapterId } == EXPECTED_IDS) {
                "章序对不上：读到 ${scripts.map { it.meta.chapterId }}，契约要 $EXPECTED_IDS"
            }
        }
    }

    val flags: JsonObject by lazy { readJson("flags.json").asJsonObject }

    fun load(chapter: Int): Script =
        gson.fromJson(readJson("act$chapter.json").toString(), Script::class.java)

    /** 跨章按节点 id 取：id 前缀就是章号，不需要遍历七章找 */
    fun node(nodeId: String): Node? {
        val chapter = nodeId.take(2).removePrefix("d").toIntOrNull() ?: return null
        return chapters.getOrNull(chapter - 1)?.nodeById?.get(nodeId)
    }

    fun chapter(id: String): Script? = chapters.firstOrNull { it.meta.chapterId == id }

    /**
     * 某个 `{{x}}` 在剧本里声明的兜底值（顶层 `variables.x.unset_value`）。
     * 七章都可能声明，取第一个声明了的——同名变量在两个章里给不同兜底值是剧本事故，
     * 由 `tools/validate_script.py` 拦，不在这里做仲裁。
     */
    fun variableUnsetValue(name: String): String? =
        chapters.firstNotNullOfOrNull { it.unsetValue(name) }

    /** 全篇声明过的变量名（各章顶层 `variables` 的键并集），给校验测试对着 `Template.KNOWN` 比 */
    val declaredVariables: Set<String> by lazy { chapters.flatMap { it.variables.keys }.toSet() }

    /**
     * 全篇节点总数，给设置页那行"关于这一版"用。
     * 不写死数字：主编复核把两个长气泡拆开那天，硬编码的 179 就在玩家面前变成了假话。
     */
    val totalNodes: Int by lazy { chapters.sumOf { it.nodes.size } }

    /**
     * 每章首个节点：正常入口是 nodes[0]，后门跳章要用它。
     * 章号越界返回 null（存档损坏或手改过）而不是抛——引擎拿到 null 会走 DeadEnd，
     * 玩家在屏幕上看到的是"读不到这一章"，不是闪退。
     */
    fun chapterEntry(chapter: Int): Node? = chapters.getOrNull(chapter - 1)?.nodes?.first()

    private fun readJson(name: String): JsonObject =
        gson.fromJson(source.read(name), JsonObject::class.java)

    companion object {
        /** 单测入口：直接指向仓库根的 script/，剧本改动立刻反映到走查测试里 */
        fun fromDirectory(dir: java.io.File): ScriptRepository =
            ScriptRepository(ScriptSource { name -> dir.resolve(name).readText() })

        const val CHAPTER_COUNT = 7
        val EXPECTED_IDS = (1..CHAPTER_COUNT).map { "d$it" }

        /** flag 登记表：engine_flags 由系统注入，story_flags 由剧本 flags_set 写入 */
        fun engineFlagNames(flags: JsonObject): Set<String> =
            flags.get("engine_flags")?.asJsonObject?.keySet() ?: emptySet()

        fun storyFlagNames(flags: JsonObject): Set<String> =
            flags.get("story_flags")?.asJsonObject?.keySet() ?: emptySet()
    }
}

/** 兼容 TypeToken 的写法保留给后面读 flags.json 的子表用，避免每次现写匿名类 */
internal fun JsonObject.optString(key: String): String? =
    if (has(key) && !get(key).isJsonNull) get(key).asString else null

internal val stringListType = object : TypeToken<List<String>>() {}.type
