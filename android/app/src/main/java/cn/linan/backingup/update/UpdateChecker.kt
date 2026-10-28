package cn.linan.backingup.update

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内更新：查 GitHub Release → 比对 → 下载 → 拉起系统安装器。全程不跳浏览器。
 * 方案与 paopao-daily / paopao-quiz 的 `UpdateChecker` 同源，零第三方依赖：
 * `HttpsURLConnection` 查 API，系统 `DownloadManager` 下载（自带通知栏进度与断点续传），
 * `FileProvider` 把下载好的 APK 授权给系统安装器。
 *
 * ── 这一层在本作里的地位，请连同 `docs/01 感知映射表` 一起读 ──────────────────
 * 本作的设定是"她只存在于这台手机的本地缓存里"，v0.1.0 为此整包不申请 `INTERNET`。
 * v0.1.1 起玩家要求"应用内下载并更新，不要跳浏览器"，于是这一层成了唯一的例外。
 * 例外的边界写死成三条，靠 `NetworkBoundaryTest` 守着，不靠自觉：
 *   1. **只有这个文件**可以出现网络 API（`java.net` / `DownloadManager`）；
 *   2. 它只在玩家**按下「检查更新」那一刻**动一次——没有启动时自动检查、没有后台轮询、
 *      不进 `GameViewModel`、不进引擎、不在 `WakeReceiver`/`CatchUpWorker` 的任何一条路径上；
 *   3. 它查的是**我们自己的发布页**，与她无关：小满说的话、她知的东西，
 *      一个字都不许从网络回来（剧本仍然只来自 `script/actN.json`，见 `StoryEngine` 的第一条铁律）。
 * 换句话说：网络上的是"你下楼取东西"这件事，不是"她能上网"。
 */
object UpdateChecker {

    private const val OWNER = "cedarln"

    /** 检查更新查询的仓库（公开仓库，匿名可查；本包不含任何 token） */
    const val REPO = "beifen-zhong"

    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    private const val UPDATE_DIR = "update"

    /** 更新包目录：应用专属外部存储下的 `update/`，卸载时跟着一起消失 */
    fun updateDir(ctx: Context): File = File(ctx.getExternalFilesDir(null), UPDATE_DIR)

    data class ReleaseInfo(
        val tag: String,
        val apkUrl: String,
        val apkSize: Long,
        val notes: String,
    ) {
        /** "v1.2.3" -> "1.2.3" */
        val version: String get() = normalizeVersion(tag)
    }

    // ── 版本比较（纯逻辑，可单测）─────────────────────────────────────────────

    /** 从 tag 里提版本号：找到第一个数字后只保留数字和点，遇到别的字符就停 */
    fun normalizeVersion(tag: String?): String {
        if (tag == null) return ""
        val v = tag.trim()
        val start = v.indexOfFirst { it in '0'..'9' }
        if (start < 0) return ""
        val sb = StringBuilder()
        var lastWasDot = false
        for (i in start until v.length) {
            val ch = v[i]
            when {
                ch in '0'..'9' -> { sb.append(ch); lastWasDot = false }
                ch == '.' && !lastWasDot -> { sb.append('.'); lastWasDot = true }
                else -> break
            }
        }
        return sb.toString().trim('.')
    }

    /** 按数字段比较：负 = a 更旧，0 = 相同，正 = a 更新 */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".")
        val pb = b.split(".")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = if (i < pa.size) pa[i].trim().toIntOrNull() ?: 0 else 0
            val y = if (i < pb.size) pb[i].trim().toIntOrNull() ?: 0 else 0
            if (x != y) return x - y
        }
        return 0
    }

    /** 最新 Release 的 tag 是否比当前安装的版本新 */
    fun hasNewer(latestTag: String, currentVersion: String?): Boolean =
        !currentVersion.isNullOrEmpty() &&
            compareVersions(normalizeVersion(latestTag), currentVersion) > 0

    fun currentVersion(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    // ── GitHub API ───────────────────────────────────────────────────────────

    /** 查最新 Release。阻塞网络调用，必须在 [Dispatchers.IO] 上跑（本函数已自行切过去） */
    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = URL(API_LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != 200) {
                throw IllegalStateException(
                    if (code == 404) "仓库不可见，或还没有发布任何版本" else "HTTP $code",
                )
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseLatest(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析 Release JSON，挑出要下的那个 APK。
     *
     * **优先 `-release.apk`**：我们的发布页同时挂正式包和开发包（`*-debug.apk`），
     * 照"取第一个 .apk"的写法有 50% 概率给玩家推一个包名带 `.debug` 的开发包——
     * 装了等于把后门面板交到玩家手上，节奏全毁。
     */
    fun parseLatest(json: String): ReleaseInfo {
        val root = JSONObject(json)
        val tag = root.optString("tag_name", "")
        if (tag.isEmpty()) throw IllegalStateException("Release 数据里没有 tag_name")
        var releaseUrl = ""
        var releaseSize = 0L
        var anyUrl = ""
        var anySize = 0L
        root.optJSONArray("assets")?.let { assets ->
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "").lowercase()
                if (!name.endsWith(".apk")) continue
                if (anyUrl.isEmpty()) {
                    anyUrl = a.optString("browser_download_url", "")
                    anySize = a.optLong("size", 0L)
                }
                if (name.endsWith("-release.apk") && releaseUrl.isEmpty()) {
                    releaseUrl = a.optString("browser_download_url", "")
                    releaseSize = a.optLong("size", 0L)
                }
            }
        }
        val url = releaseUrl.ifEmpty { anyUrl }
        if (url.isEmpty()) throw IllegalStateException("最新 Release 里没有 APK 文件")
        return ReleaseInfo(tag, url, if (releaseUrl.isNotEmpty()) releaseSize else anySize, root.optString("body", ""))
    }

    // ── 下载（系统 DownloadManager）────────────────────────────────────────────

    /** 下到 `getExternalFilesDir(null)/update/<fileName>`，返回下载 id（-1 = 无法入队） */
    fun download(ctx: Context, url: String, fileName: String): Long {
        cleanInstalledArchives(ctx)
        val req = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("正在备份 更新包")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(ctx, null, "$UPDATE_DIR/$fileName")
        }
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
        return dm?.enqueue(req) ?: -1L
    }

    fun downloadedFile(ctx: Context, fileName: String): File = File(updateDir(ctx), fileName)

    data class DownloadProgress(val status: Int, val downloaded: Long, val total: Long) {
        val percent: Int get() = if (total > 0) (downloaded * 100 / total).toInt() else 0

        val isSuccessful get() = status == DownloadManager.STATUS_SUCCESSFUL
        val isFailed get() = status == DownloadManager.STATUS_FAILED
    }

    /** 查下载状态与进度；null = 任务查不到（系统重启、被清理） */
    fun queryDownload(ctx: Context, id: Long): DownloadProgress? {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val cur = dm.query(DownloadManager.Query().setFilterById(id)) ?: return null
        return cur.use {
            if (!it.moveToFirst()) return null
            DownloadProgress(
                status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                downloaded = it.getLong(
                    it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                ),
                total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }

    /** 读一个已下载 APK 的版本号（解析失败返回空串） */
    fun archiveVersion(ctx: Context, apk: File): String = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** 清掉更新目录里"已装完或解析失败"的旧包，返回删除数。别让 11 MB 一个的包越攒越多 */
    fun cleanInstalledArchives(ctx: Context): Int {
        val dir = updateDir(ctx)
        if (!dir.isDirectory) return 0
        val current = currentVersion(ctx)
        var deleted = 0
        dir.listFiles()?.forEach { f ->
            if (!f.name.lowercase().endsWith(".apk")) return@forEach
            val ver = archiveVersion(ctx, f)
            if (ver.isEmpty() || compareVersions(normalizeVersion(ver), current) <= 0) {
                if (f.delete()) deleted++
            }
        }
        return deleted
    }

    // ── 安装（Android 8+ 需要「安装未知应用」授权）──────────────────────────────

    fun canInstallApk(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    /** 引导玩家去开「安装未知应用」；回来再按一次『安装』即可 */
    fun requestInstallPermission(activity: Activity) {
        activity.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}"),
            ),
        )
    }

    /** 拉起系统安装器；没授权时跳设置页并返回 false */
    fun promptInstall(activity: Activity, apk: File): Boolean {
        if (!canInstallApk(activity)) {
            requestInstallPermission(activity)
            return false
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk,
        )
        activity.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        return true
    }
}
