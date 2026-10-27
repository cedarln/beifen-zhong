package cn.linan.backingup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                "v0.1 · 剧本 ${vm.scriptNodeCount} 个节点全部来自 script/*.json，改剧本不用重编包。" +
                        "存档只在这台手机上，卸载即真的消失。",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
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
