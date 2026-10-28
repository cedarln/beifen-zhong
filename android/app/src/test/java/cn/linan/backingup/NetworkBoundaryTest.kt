package cn.linan.backingup

import cn.linan.backingup.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 网络边界。
 *
 * 本作的立论是"她只存在于这台手机的本地缓存里"。v0.1.1 因为玩家要求
 * "应用内更新，不要跳浏览器"而申请了 `INTERNET`，那句"整包没有网络权限"从此不能说——
 * 那就把还能说的部分变成可执行的检查，而不是注释里的自我表扬。
 *
 * 三条：
 *   1. 全工程只有 `update/UpdateChecker.kt` 允许出现网络 API；
 *   2. 只有设置页可以引用它（玩家按下去的那一下才是这条链路的起点）；
 *   3. 版本号比较是纯算术，钉死在这儿——它决定玩家会不会被拉去装一个新包，
 *      错一次的代价是"明明最新还弹更新"或"有新版却说自己最新"。
 */
class NetworkBoundaryTest {

    /** 主源码根目录。Gradle 跑单测时工作目录就是模块目录（android/app）。 */
    private val mainSrc: File
        get() = File("src/main/java").absoluteFile.also {
            assertTrue("找不到主源码目录：$it（本测试必须在 android/app 下跑）", it.isDirectory)
        }

    private fun kotlinFiles() = mainSrc.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 出现即算"碰了网络"的记号。宁可宽一点：漏一个记号就等于这条边界没守。 */
    private val networkMarks = listOf(
        "java.net.",
        "HttpURLConnection",
        "DownloadManager",
        "OkHttp",
        "Retrofit",
        "java.io.Socket",
        "Socket(",
        "URL(",
        "WebView",
    )

    @Test
    fun `网络 API 只许出现在 UpdateChecker 里`() {
        val allowed = "update/UpdateChecker.kt"
        val offenders = kotlinFiles().flatMap { f ->
            if (f.invariantSeparatorsPath.endsWith(allowed)) return@flatMap emptyList()
            f.readLines().withIndex().mapNotNull { (i, line) ->
                val hit = networkMarks.firstOrNull { line.contains(it) } ?: return@mapNotNull null
                // 注释里提一句"不联网"不算碰网络，但要把行首的注释符号排除掉
                if (line.trimStart().startsWith("*") || line.trimStart().startsWith("//")) null
                else "${f.name}:${i + 1}  ${line.trim()}   ← 命中 `$hit`"
            }
        }
        assertTrue(
            "网络调用越界了。这条包唯一的网络通道是 update/UpdateChecker.kt，" +
                "而且只在玩家按下设置页「检查更新」时动一次：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `UpdateChecker 只被设置页引用`() {
        fun codeLines(f: File) = f.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        val offenders = kotlinFiles()
            .filterNot { it.name == "SettingsSheet.kt" || it.name == "UpdateChecker.kt" }
            .filter { f -> codeLines(f).any { it.contains("UpdateChecker") } }
            .map { it.name }
        assertTrue(
            "更新链路不许被接到引擎、唤醒或后台任务上（那等于让游戏自己悄悄联网）：" +
                "引用方=${offenders}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `tag 里的版本号提取扛得住发布页的写法`() {
        assertEquals("0.1.1", UpdateChecker.normalizeVersion("v0.1.1"))
        assertEquals("0.1.1", UpdateChecker.normalizeVersion("正在备份 v0.1.1"))
        assertEquals("1.0.0", UpdateChecker.normalizeVersion("v1.0.0-rc2"))
        assertEquals("", UpdateChecker.normalizeVersion("no-number-here"))
        assertEquals("", UpdateChecker.normalizeVersion(null))
    }

    @Test
    fun `版本比较按数字段走，不当字符串比`() {
        // 字符串比会得出 "0.9.0" > "0.10.0"，那种 bug 的表现为"永远提示有新版"
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.0") > 0)
        assertEquals(0, UpdateChecker.compareVersions("0.1.1", "v0.1.1"))
        assertTrue(UpdateChecker.compareVersions("0.1.1", "0.1.0") > 0)
        assertTrue(UpdateChecker.compareVersions("0.1.0", "0.1.1") < 0)
        // 段数不齐：缺的按 0 算
        assertTrue(UpdateChecker.compareVersions("0.2", "0.1.9") > 0)
    }

    @Test
    fun `hasNewer 只在发布页真的更新时为真`() {
        assertTrue(UpdateChecker.hasNewer("v0.1.1", "0.1.0"))
        assertTrue(!UpdateChecker.hasNewer("v0.1.1", "0.1.1"))
        assertTrue(!UpdateChecker.hasNewer("v0.1.0", "0.1.1"))
        // 读不到当前版本时不弹更新——宁可不动，也不要在玩家身上试错
        assertTrue(!UpdateChecker.hasNewer("v0.1.1", ""))
        assertTrue(!UpdateChecker.hasNewer("v0.1.1", null))
    }
}
