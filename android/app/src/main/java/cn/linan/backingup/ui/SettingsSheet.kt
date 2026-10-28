package cn.linan.backingup.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.linan.backingup.BuildConfig
import cn.linan.backingup.audio.AmbienceDirector

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
                "通知只用于「到她该说话的时候」：不响铃、不震动、不亮屏，没有推送服务，本包没有网络权限。" +
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
            // 更新入口。她上不了网——本包没有 INTERNET 权限，所以"自动检查更新"这件事做不了，
            // 也不该做：一个声称只活在这台手机缓存里的角色，不该在背后悄悄联网问服务器"有新版吗"。
            // 能做的只有把出处交给你：点下去走的是**系统浏览器**，网络是你的手机在走，不是她。
            var updateNote by remember { mutableStateOf<String?>(null) }
            val context = LocalContext.current
            TextButton(
                onClick = {
                    val sent = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                    updateNote = if (sent) null
                        else "这台机器上没有能打开网页的应用。出处是 $RELEASES_URL"
                },
            ) { Text("看有没有新版", color = InkColors.pipe) }
            Text(
                "覆盖安装即可，进度不会丢（卸载才会）。",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
            updateNote?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = InkColors.emergency)
            }
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
 * 发布页。本包没有 INTERNET 权限，这个链接只是交给**系统浏览器**去打开——
 * 走网络的是你的手机，不是她。这也是"更新入口"能做到的全部：
 * 没有联网检查，只有把出处放在你找得到的地方。
 */
private const val RELEASES_URL = "https://github.com/cedarln/beifen-zhong/releases"
