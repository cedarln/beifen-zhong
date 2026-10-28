package cn.linan.backingup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.linan.backingup.audio.AmbienceDirector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后门面板（docs/04 第二节八项）。
 *
 * 一句话原则：**后门允许我们不作弊地评估**。所以每一格都在制造失真日志，而不是消除它——
 * 顶部那一行"本局跳过多少小时"是硬约束（第⑧项），不许做成可折叠或可关闭的。
 *
 * 面板只在 `debug_used` 之后出现，正式包要靠 307 口令；玩家侧看不到任何入口提示。
 */
@Composable
fun BackdoorPanel(vm: GameViewModel, onDismiss: () -> Unit) {
    val state by vm.state.collectAsState()
    val data = remember(state.nodeId, state.chapter, state.metrics) { vm.backdoorData() }
    var teleportTarget by remember { mutableStateOf("") }
    var customRate by remember { mutableStateOf("") }
    var nightByDevice by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 让位写在滚动之前：面板要能滚，但滚动视口本身不许钻到状态栏/导航条底下
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开发面板", style = MaterialTheme.typography.titleMedium, color = InkColors.text)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("收起", color = InkColors.dim) }
        }

        // ── ⑧ 失真日志：常驻顶部，不许折叠 ────────────────────────────────────
        Section("⑧ 失真日志（硬约束）") {
            Text(
                "本局跳过 %.1f 小时，其中 %s 未完整体验。倍率 %s×，debug_used=%s".format(
                    state.skippedHours,
                    state.skippedWaits.ifEmpty { listOf("无") }.joinToString(" / "),
                    trimRate(state.rate),
                    state.debugUsed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = InkColors.emergency,
            )
            Text(
                "当前段落点：${data.ambienceNote ?: "（不在任何编排段内）"}",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("导出转写") {
                    val f = vm.exportTranscript()
                    teleportTarget = "已导出 ${f.name}"
                }
                SmallButton("清空重开") { vm.wipeAndRestart() }
            }
        }

        HorizontalDivider(color = InkColors.panel)

        // ── ① 时间倍率 ────────────────────────────────────────────────────────
        Section("① 时间倍率（作用在时钟层，不是逐节点打补丁）") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.0, 60.0, 3600.0).forEach { r ->
                    SmallButton("${trimRate(r)}×", selected = state.rate == r) { vm.setRate(r) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = customRate,
                    onValueChange = { customRate = it.filter { c -> c.isDigit() || c == '.' } },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("自定义倍率", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                )
                SmallButton("套用") { customRate.toDoubleOrNull()?.let { vm.setRate(it) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallButton("伪造 +1 天") { vm.jumpDays(1) }
                SmallButton("伪造 +7 天") { vm.jumpDays(7) }
                Checkbox(
                    checked = nightByDevice,
                    onCheckedChange = { nightByDevice = it; vm.setNightUsesDeviceClock(it) },
                )
                Text("夜间窗口按设备时钟", style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
            }
        }

        // ── ② 跳章 + 注入的起点度量 ───────────────────────────────────────────
        Section("② 跳章（会注入该章起点度量，面板显示注入后的实际值）") {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { c ->
                    SmallButton("D$c", selected = state.chapter == c) { vm.startChapter(c) }
                }
            }
            data.startStates.forEach { (id, start) ->
                if (start.isNotEmpty()) {
                    Text(
                        "$id 起点 " + start.entries.joinToString(" ") { "${it.key}=${it.value.toInt()}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = InkColors.dim,
                    )
                }
            }
            Text(
                "当前 ${state.metrics}｜章 ${state.chapter}｜节点 ${state.nodeId ?: "-"}",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.text,
            )
        }

        // ── ③ 按 node id 传送 ─────────────────────────────────────────────────
        Section("③ 传送（跳章需要注入，传送同样会注入起点）") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = teleportTarget,
                    onValueChange = { teleportTarget = it.trim() },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("d5_n540", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                )
                SmallButton("落位") {
                    val id = teleportTarget.trim()
                    if (id.startsWith("d")) vm.teleport(id)
                    teleportTarget = ""
                }
            }
            SmallButton("上一个 choice") {
                val at = data.chapterOrder.indexOf(state.nodeId ?: "")
                data.choiceNodes.lastOrNull { (data.chapterOrder.indexOf(it)).let { i -> i in 0 until at } }
                    ?.let { vm.teleport(it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                data.peaks.filter { it.first.startsWith("d${state.chapter}") }.take(4).forEach { (id, note) ->
                    SmallButton(id) { vm.teleport(id) }
                    Text(note.take(18), style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
                }
            }
        }

        // ── ④ 度量实时读写 ────────────────────────────────────────────────────
        Section("④ 度量（可达性检查靠这个做，不靠读代码推）") {
            val m = data.metrics
            MetricSlider("trust", m.first, data.metricRange) { vm.setMetric("trust", it) }
            MetricSlider("awareness", m.second, data.metricRange) { vm.setMetric("awareness", it) }
            MetricSlider("integrity", m.third, data.metricRange) { vm.setMetric("integrity", it) }
            SmallButton("回到本章起点（D${state.chapter} 声明的那组值）") { vm.resetMetricsToChapterStart() }
            Text(
                "隐藏结局《紧急联系人》要 awareness ∈ [70,76] 且 integrity ≥ 38 且 quick_mode=false；" +
                        "三封灭屏信完整阅读那条是写给作者的中文注记，引擎不猜。",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
        }

        // ── ⑤ 伪造 signal ─────────────────────────────────────────────────────
        Section("⑤ 伪造 signal（伪造出来的气泡带 DBG 角标）") {
            data.signals.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name, style = MaterialTheme.typography.labelSmall, color = InkColors.text, modifier = Modifier.weight(1f))
                    SmallButton("真") { vm.overrideSignal(name, true) }
                    SmallButton("假") { vm.overrideSignal(name, false) }
                    SmallButton("清") { vm.overrideSignal(name, null) }
                }
            }
        }

        // ── ⑥ 结局直达 ───────────────────────────────────────────────────────
        Section("⑥ 七个结局（旁边是自然达成需要的前置条件）") {
            data.endings.forEach { (node, meta) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallButton(node.endingName ?: node.id) { vm.jumpToEnding(node.id) }
                        Text(
                            (if (node.hidden == true) "隐藏 " else "") +
                                    (if (node.isTrueEnding == true) "真结局 " else "") +
                                    (if (node.recoverable == true) "可回溯" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = InkColors.dim,
                        )
                    }
                    val pre = listOfNotNull(node.requires, node.requiresAll?.toString()?.take(120))
                    if (pre.isNotEmpty()) {
                        Text(
                            "前置：" + pre.joinToString("；"),
                            style = MaterialTheme.typography.labelSmall,
                            color = InkColors.dim,
                        )
                    }
                    Text(
                        "所属章：${meta.chapterId}｜节点：${node.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkColors.panel,
                    )
                }
            }
        }

        // ── ⑦ 强停 / 崩溃模拟 ─────────────────────────────────────────────────
        Section("⑦ 强停（先落盘再 kill，然后重启验不重复投递、不丢消息）") {
            // 台账在设备侧的 SharedPreferences 里，读它是磁盘 I/O，别在组合里直接读
            var deliveries by remember { mutableStateOf<List<String>>(emptyList()) }
            LaunchedEffect(Unit) { deliveries = withContext(Dispatchers.IO) { vm.postedDeliveries() } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallButton("现在强停") { vm.forceKill() }
                SmallButton(if (state.quickMode) "关掉快速模式" else "打开快速模式") {
                    vm.setQuickMode(!state.quickMode)
                }
                SmallButton("清投递台账（${deliveries.size}）") {
                    vm.clearDeliveryLedger()
                    deliveries = emptyList()
                }
            }
            Text(
                "同一条等待有闹钟与兜底 worker 两个入口：不清台账强停重开，她绝不重说（这就是「不重复投递」）；" +
                    "清了再重开，她必须再说一次（这就是「不丢消息」）。两句话都得当场验。",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
            deliveries.forEach { token ->
                Text(token, style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
            }
        }

        // ── 声床现场读数（docs/06 验收 4：d7_n711 必须实测四条全 0） ──────────
        Section("声床读数（要「楼里的声音」开着）") {
            if (state.ambience.isEmpty()) {
                Text("未开启或未装载", style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
            } else {
                state.ambience.forEach { (bed, info) ->
                    Text(
                        "%-20s %s".format(bed, info),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (bed == AmbienceDirector.ANOMALY) InkColors.emergency else InkColors.dim,
                    )
                }
            }
            SmallButton(if (state.ambienceOn) "声床：开" else "声床：关") { vm.setAmbienceEnabled(!state.ambienceOn) }
        }

        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = InkColors.text)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun SmallButton(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) InkColors.pipe else InkColors.panel,
            contentColor = InkColors.text,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}

/**
 * 度量滑块。
 *
 * **`valueRange` 必须显式给。** Material3 的 `Slider` 默认量程是 `0f..1f`，
 * 上一版这里只写了 `value = value.coerceIn(0f, 100f)` 却没给 range，于是两条一起坏：
 * 界面上任何 ≥1 的度量都等于"拖到头"（三条全钉在最右端，看着像 100），
 * 而 `onValueChange` 只能回调出 0.0~1.0——玩家拖一下，trust 从 62 直接变成 0 或 1，
 * **而且再也拖不回去**。真机玩家报的"设置里面的度量调整不回来了"就是这一条。
 *
 * range 从 [BackdoorData.metricRange] 传进来：那个字段本来就是为这件事声明的，
 * 之前它被写在数据类里一次都没被用过——这就是为什么单看面板代码"逻辑没错"却整个是坏的。
 */
@Composable
private fun MetricSlider(
    name: String,
    value: Double,
    range: ClosedRange<Double>,
    onChange: (Double) -> Unit,
) {
    val from = range.start.toFloat()
    val to = range.endInclusive.toFloat()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelSmall, color = InkColors.text, modifier = Modifier.weight(1f))
            Text("%.0f".format(value), style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
        }
        Slider(
            value = value.toFloat().coerceIn(from, to),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = from..to,
            // 剧本里的 needs 全按整数写（70、38 这种）。不给整步就会拖出 61.7：
            // 界面显示 62、闸门按 61 判，是个查不出来的假朋友
            steps = (to - from).toInt() - 1,
            colors = SliderDefaults.colors(thumbColor = InkColors.pipe, activeTrackColor = InkColors.pipe),
        )
    }
}

private fun trimRate(rate: Double): String = if (rate % 1.0 == 0.0) rate.toLong().toString() else "%.2f".format(rate)
