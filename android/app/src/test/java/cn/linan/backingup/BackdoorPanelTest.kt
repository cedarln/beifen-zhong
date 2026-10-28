package cn.linan.backingup

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/**
 * 开发面板与「恢复默认」的源码级护栏。
 *
 * 为什么是扫源码而不是跑 UI：这两条都是**编译器不会管、单测跑不到、但真机上一定看得见**
 * 的东西——Compose 的 `Slider` 少写一个 `valueRange` 参数照样编译通过、照样"看起来是个滑块"，
 * 而它的默认量程是 `0f..1f`。真机玩家就是因此把三度量拖成了 1 并且拖不回来。
 * 这类"少传一个参数就静默错位"的坑，唯一便宜的守法是把那一行本身变成判据
 * （同一手法已有先例：`NetworkBoundaryTest` 扫网络 API）。
 *
 * 三条：
 *   1. 度量滑块必须显式给 `valueRange`，并且量程来自 `BackdoorData.metricRange`；
 *   2. 只做"重判闸门"的后门入口（改度量、伪造 signal）必须带 `reEmitEntry = false`，
 *      否则拖一下滑块她就当前那段话再说一遍、那一笔度量再加一次；
 *   3. 设置页那颗「恢复默认」不许碰存档——它只能回滚设置。
 */
class BackdoorPanelTest {

    private fun src(vararg path: String): String {
        val f = File("src/main/java/cn/linan/backingup", path.joinToString("/"))
        assertTrue("找不到源码文件：$f（本测试必须在 android/app 下跑）", f.isFile)
        return f.readText()
    }

    /** 取某个函数从签名到"顶格缩进的右花括号"那一段。够用，且不引依赖。 */
    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到 `$signature`——被改名了？那条护栏也就失效了", start >= 0)
        val rest = source.substring(start)
        val end = rest.indexOf("\n    }")
        assertTrue("`$signature` 的函数体结束位置找不到", end > 0)
        return rest.substring(0, end)
    }

    @Test
    fun `度量滑块必须显式给量程`() {
        val panel = src("ui/BackdoorPanel.kt")
        val slider = bodyOf(panel, "private fun MetricSlider(")
        assertTrue(
            "Material3 的 Slider 默认量程是 0f..1f。这里不写 valueRange，" +
                "度量就会被钉在右端、而且只能回调出 0~1（玩家拖一次 trust 变 1，再也拖不回去）。" +
                "实测记录见 docs/07 第 19 条：\n$slider",
            slider.contains("valueRange"),
        )
        assertTrue("量程要从 BackdoorData.metricRange 来，不许在控件里再写死一个 100", slider.contains("range."))
        // 调用点也必须真的把 range 传进去——只有形参写了、调用点漏了，同样静默回落到默认值
        val calls = Regex("""MetricSlider\("[a-z]+",\s*m\.\w+""").findAll(panel).toList()
        assertTrue("三张滑块一个都没找到：面板改了？", calls.isNotEmpty())
        calls.forEach {
            assertTrue("MetricSlider 调用少了量程参数：${it.value}", panel.substring(it.range.last).take(80).contains("data.metricRange"))
        }
    }

    @Test
    fun `只重判闸门的后门入口不许重新演出当前节点`() {
        val vm = src("ui/GameViewModel.kt")
        listOf(
            "fun setMetric(",
            "fun resetMetricsToChapterStart(",
            "fun overrideSignal(",
        ).forEach { sig ->
            val body = bodyOf(vm, sig)
            val advances = body.lines().filter { it.contains("eng.advance(") }
            assertTrue("$sig 里没有 advance 调用，闸门不会重判：$body", advances.isNotEmpty())
            advances.forEach {
                assertTrue(
                    "$sig 重走了当前节点的 emit：拖一下滑块，她会把这段话再说一遍、" +
                        "这一笔度量再加一次（和 recheck() 修过的是同一类坑）。$it",
                    it.contains("reEmitEntry = false"),
                )
            }
        }
    }

    @Test
    fun `恢复默认只回滚设置不许碰存档`() {
        val vm = src("ui/GameViewModel.kt")
        val body = bodyOf(vm, "fun resetSettings(")
        listOf("store.write", "copyEmpty", "save.metrics", "save.transcript", "save.nodeId", "clearSave").forEach {
            assertFalse("「恢复默认」里出现了 `$it`——那颗按钮会毁掉玩家的进度：\n$body", body.contains(it))
        }
        assertTrue("恢复默认必须真的落盘（否则重启就又被 DataStore 拉回旧值）：\n$body", body.contains("settings.resetAll()"))
        assertTrue("恢复默认必须把声床停下来（只翻布尔等于继续偷偷放声音）：\n$body", body.contains("player.release()"))
    }
}
