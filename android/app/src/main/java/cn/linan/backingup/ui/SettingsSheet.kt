package cn.linan.backingup.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.linan.backingup.BuildConfig
import cn.linan.backingup.audio.AmbienceDirector
import cn.linan.backingup.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页——只有四块，且第一块默认是关的。
 *
 * 「楼里的声音」这条开关是 docs/06 红线 3 的 UI 落点：恐怖游戏不许偷偷放声音。
 * 打开之后现场显示四条声床的实时增益，一是给我们自己排查用（验收 4 要实测 d7_n711 全 0），
 * 二是让玩家看得见"它在响什么"——一个不肯说自己在放什么声音的氛围层不该上线。
 */
@Composable
fun SettingsSheet(vm: GameViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 让位必须在滚动容器**外面**：写进滚动内容里，第一行一滚就滑到状态栏底下了
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("设置", style = MaterialTheme.typography.titleMedium, color = InkColors.text)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("关闭", color = InkColors.dim) }
        }
        HorizontalDivider(color = InkColors.panel)

        Block("楼里的声音") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("水管、工频、日光灯。没有音乐。", style = MaterialTheme.typography.bodySmall, color = InkColors.dim)
                    Text(
                        "默认关闭。她住在手机里，声音只在她该在的时候响；你退出到后台 90 秒后它会完全停下来。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkColors.dim,
                    )
                }
                Switch(
                    checked = state.ambienceOn,
                    onCheckedChange = { vm.setAmbienceEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = InkColors.pipe),
                )
            }
            if (state.ambienceOn) {
                state.ambience.forEach { (bed, info) ->
                    Text("$bed  $info", style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
                }
            }
        }

        Block("她能不能叫醒你") {
            // 界面事实，不是台词：这一行只描述这台手机现在的开关状态。
            // D1 那句"你给了我一个能叫醒你的权利"之前，13+ 的新装机这里本来就是"关着"——
            // 与其让玩家在等待里猜，不如把系统现状写给他看。
            Text(
                if (state.notifyBlocked) "通知：这台手机现在不让她敲门。她到点的话会留在游戏里，你回来才看得见。"
                else "通知：已开。她到点会敲门（不响铃、不震动）。",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.notifyBlocked) InkColors.emergency else InkColors.dim,
            )
            Text(
                if (state.exactAlarmBlocked) "精确唤醒被这台设备拦了——她可能迟到，这不是她的问题。"
                else "精确唤醒已开：她说过几点，就几点。",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.exactAlarmBlocked) InkColors.emergency else InkColors.dim,
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "夜里（23:30–07:30）她把话留到早上 07:30 再说。默认关着：她到你的手机里，不是到你的卧室里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkColors.dim,
                    )
                    Text(
                        "打开后她照夜里原定时间说。游戏时钟不动——推后的只是通知，不是她那边的时间。",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkColors.dim,
                    )
                }
                Switch(
                    checked = state.nightDeliveryOn,
                    onCheckedChange = { vm.setNightDeliveryEnabled(it) },
                    colors = SwitchDefaults.colors(checkedTrackColor = InkColors.pipe),
                )
            }
            Text(
                "通知只用于「到她该说话的时候」：不响铃、不震动、不亮屏，没有推送服务。" +
                        "她不是系统闹钟，也不进时钟 App。",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
        }

        if (state.quickMode) {
            Block("快速模式") {
                Text(
                    "把等待压成文字摘要。灭屏那三十分钟（W5-2）不在可跳之列——那段必须真等。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkColors.dim,
                )
            }
        }

        Block("关于这一版") {
            Text(
                // 版本号从 BuildConfig 来：写死一次，下一次打补丁就忘了改，玩家看到的永远是 v0.1
                "v${BuildConfig.VERSION_NAME} · 剧本 ${vm.scriptNodeCount} 个节点全部来自 script/*.json，改剧本不用重编包。" +
                        "存档只在这台手机上，卸载即真的消失。",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
            UpdateSection()
            if (state.ambienceOn) {
                Text(
                    "声床节拍 ${"%.1f".format(AmbienceDirector.TICK_MS / 1000.0)}s 一次，斜坡最多 " +
                            "${AmbienceDirector.FADE_MAX_MS / 1000}s。",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkColors.dim,
                )
            }
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = InkColors.text)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

/**
 * 更新入口。
 *
 * v0.1.0 的立场是"本包没有网络权限，所以只能把发布页链接交给你"。
 * 玩家的要求把它推回来了：**在应用里检查、在应用里下、在应用里装，不要跳浏览器**——
 * 跳出去一次，回来就要重新找包、重新装、还可能被系统提示劝退。这条要求是对的。
 *
 * 于是 `INTERNET` 进来了，代价是"整包不申请网络权限"这句话不能再说了。
 * 剩下能说的是边界，而且它写在代码里、由 `NetworkBoundaryTest` 判失败，不靠自觉：
 *   1. 全工程只有 [UpdateChecker] 可以碰网络 API；
 *   2. 只有玩家按下这一颗按钮才动一次——没有启动时自动检查、没有后台轮询，
 *      引擎、唤醒链路、`WakeReceiver` 都不引用它；
 *   3. 查的是我们自己的发布页，小满的话仍然一个字都不从网络回来。
 */
@Composable
private fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { UpdateChecker.currentVersion(context) }
    var ui by remember { mutableStateOf<UpdateUi>(UpdateUi.Idle) }
    var note by remember { mutableStateOf<String?>(null) }
    var downloadId by remember { mutableStateOf(-1L) }
    var expected by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }

    // 下载进度 500ms 一轮。离开这页时协程取消，但任务在系统 DownloadManager 里，
    // 回来按同一个 id 还能查到——所以进度条不必假装是应用自己在下载。
    LaunchedEffect(downloadId) {
        if (downloadId <= 0L) return@LaunchedEffect
        while (isActive) {
            val p = withContext(Dispatchers.IO) { UpdateChecker.queryDownload(context, downloadId) }
            val info = expected
            if (p == null) {
                ui = UpdateUi.Failed("下载任务不在了，重新检查一次")
                downloadId = -1L
                break
            }
            when {
                p.isSuccessful -> {
                    // 下完不等于能装：大小和 sha256 都对得上才说"已就绪"
                    ui = if (info != null && withContext(Dispatchers.IO) {
                            UpdateChecker.verifyArchive(context, info)
                        }
                    ) UpdateUi.Ready(fromDisk = false)
                    else UpdateUi.Failed("下载的文件校验不过，已删掉。重新按一次「检查更新」再下")
                    downloadId = -1L
                    break
                }
                p.isFailed -> {
                    ui = UpdateUi.Failed("下载失败：网络不通，或发布页不可达")
                    downloadId = -1L
                    break
                }
                else -> {
                    ui = UpdateUi.Downloading(p.percent, p.isWaiting)
                    delay(500)
                }
            }
        }
    }

    TextButton(
        onClick = {
            note = null
            val s = ui
            when {
                s is UpdateUi.Available -> {
                    expected = s.info
                    ui = UpdateUi.Preparing
                    scope.launch {
                        // 校验一个 11 MB 的文件要几十到两百毫秒，别把它放在主线程上
                        val cached = withContext(Dispatchers.IO) {
                            UpdateChecker.cachedArchiveReady(context, s.info)
                        }
                        if (cached) {
                            ui = UpdateUi.Ready(fromDisk = true)
                        } else {
                            val id = UpdateChecker.download(
                                context,
                                s.info.apkUrl,
                                UpdateChecker.archiveName(s.info),
                            )
                            downloadId = id
                            ui = if (id <= 0L) UpdateUi.Failed("这台设备上没有可用的下载服务")
                            else UpdateUi.Downloading(0, waiting = true)
                        }
                    }
                }
                s is UpdateUi.Ready -> {
                    val activity = context as? Activity
                    val info = expected
                    val apk = if (info == null) File("") else UpdateChecker.downloadedFile(
                        context,
                        UpdateChecker.archiveName(info),
                    )
                    when {
                        activity == null -> note = "拿不到当前窗口，装不了"
                        !apk.isFile -> ui = UpdateUi.Failed("更新包不在了，重新检查一次")
                        // false = 系统不让装，已经把玩家送到设置页；回来再按一次就行
                        !UpdateChecker.promptInstall(activity, apk) ->
                            note = "已跳到系统设置：允许「安装未知应用」之后，回来再按一次『安装』"
                    }
                }
                else -> {
                    ui = UpdateUi.Checking
                    scope.launch {
                        ui = runCatching { UpdateChecker.fetchLatest() }.fold(
                            onSuccess = { info ->
                                if (UpdateChecker.hasNewer(info.tag, current))
                                    UpdateUi.Available(info, current)
                                else UpdateUi.Latest(current)
                            },
                            onFailure = { UpdateUi.Failed("查不到新版：${it.message ?: "网络不通"}") },
                        )
                    }
                }
            }
        },
        enabled = ui !is UpdateUi.Checking && ui !is UpdateUi.Preparing && ui !is UpdateUi.Downloading,
    ) {
        Text(
            when (val s = ui) {
                is UpdateUi.Checking -> "检查中…"
                is UpdateUi.Preparing -> "核对更新包…"
                is UpdateUi.Available ->
                    "下载 v${s.info.version}（${"%.1f".format(s.info.apkSize / 1048576.0)} MB）"
                is UpdateUi.Downloading ->
                    if (s.waiting) "等待网络" else "下载中 ${s.percent}%"
                is UpdateUi.Ready -> "安装"
                else -> "检查更新"
            },
            color = InkColors.pipe,
        )
    }

    Text(
        when (val s = ui) {
            is UpdateUi.Latest -> "已经是最新版 v${s.version}。"
            is UpdateUi.Available ->
                "发现新版 v${s.info.version}（当前 v${s.current}）。下载走系统下载器，进度在通知栏；" +
                        "装完是覆盖安装，进度不会丢（卸载才会）。"
            is UpdateUi.Downloading ->
                if (s.waiting) {
                    "下载还没开始传：系统在等一个可用网络（离线、连的网没过系统校验、或在排队等 Wi‑Fi 都会这样）。" +
                            "任务没丢，可以先退出这一页，网通了它自己继续。"
                } else {
                    "下载中 ${s.percent}%，可以先退出这一页。"
                }
            is UpdateUi.Ready ->
                if (s.fromDisk) {
                    "更新包已经在手机上（上次下过没装），这次没有再走网络。" +
                            "安装器会问你一次「安装未知应用」，只问这一次。"
                } else {
                    "更新包已就绪。安装器会问你一次「安装未知应用」，只问这一次。"
                }
            is UpdateUi.Failed -> s.why
            is UpdateUi.Checking -> "正在查发布页…"
            is UpdateUi.Preparing -> "先看一眼手机上有没有已经下好的这一版；没有才走网络。"
            else -> "本包唯一的网络调用就是这一颗按钮：不自动检查、不后台轮询，" +
                    "她的台词一个字都不从网络来。出处 $RELEASES_HINT"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (ui is UpdateUi.Failed) InkColors.emergency else InkColors.dim,
    )
    note?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = InkColors.emergency)
    }
}

/** 更新入口的状态机。注意没有 "AutoCheck" 这一项：它只能由玩家按出来。 */
private sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi

    /** 按了「下载」之后先核一遍本地有没有现成的包，这一步不走网络 */
    data object Preparing : UpdateUi

    /** fromDisk = 这个包本来就在更新目录里（上次下过没装），没有再走一次网络 */
    data class Ready(val fromDisk: Boolean) : UpdateUi
    data class Latest(val version: String) : UpdateUi
    data class Available(val info: UpdateChecker.ReleaseInfo, val current: String) : UpdateUi
    data class Downloading(val percent: Int, val waiting: Boolean) : UpdateUi
    data class Failed(val why: String) : UpdateUi
}

/**
 * 发布页。检查更新走的是 API，这里只是给玩家留一个能自己核对出处的地方——
 * 点设置页那颗按钮不需要它。
 */
private const val RELEASES_HINT = "github.com/cedarln/beifen-zhong"
