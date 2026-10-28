package cn.linan.backingup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import cn.linan.backingup.engine.StoryEngine
import cn.linan.backingup.engine.TranscriptLine
import cn.linan.backingup.story.Node
import kotlinx.coroutines.delay

/**
 * 会话页——玩家 90% 的时间都在这一个屏幕上。
 *
 * 三条演出铁律写在这里，不在剧本里（圣经规则 9 的界面侧对应物）：
 *   1. 她说话靠"正在输入"和逐条浮现，不靠动画、不靠音效；
 *   2. 结局页只有一个按钮『关掉它』，没有任何分享、成就、重开提示；
 *   3. 度量数字、节点 id、倍率这些机器语言只出现在后门面板，永远不出现在这一页。
 *
 * 逐条浮现的节拍来自 [TranscriptLine.typingMs]（剧本 delay_ms），并按时间倍率反比缩放：
 * 3600× 下开发走查不该被 20 秒的"正在输入"拖住，但玩家侧 1× 时一秒都不能少。
 */
@Composable
fun GameScreen(vm: GameViewModel) {
    val state by vm.state.collectAsState()
    var revealed by remember { mutableIntStateOf(0) }
    var typing by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showBackdoor by remember { mutableStateOf(false) }
    var askPasscode by remember { mutableStateOf(false) }
    var passcode by remember { mutableStateOf("") }
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    val entry = vm.uiEntryNode()
    var confirmHangup by remember { mutableStateOf(false) }

    // 冷启动先"已经看见"历史：读盘之前就在那儿的行一次性上屏。
    // 少了这一步，玩到 D6 之后每次重开 App 都会把前面几章逐条重演一遍——
    // 玩家看到的是"打开游戏先卡两分钟看回放"，而设计里那两分钟只属于第一次遇见她。
    // 这条必须排在下面那个逐条浮现的 effect **之前**：同一轮 recomposition 里
    // LaunchedEffect 按声明顺序起跑，晚一步历史就已经开始一条一条往外蹦了。
    LaunchedEffect(state.ready) {
        if (state.ready) {
            val history = minOf(state.seenAtLaunch, state.transcript.size)
            if (history > revealed) revealed = history
        }
    }

    // 新气泡按 delay_ms 逐条出现；点一下会话区 = 承认自己在跳演出，只跳节拍不跳时间
    LaunchedEffect(state.transcript.size, state.rate, state.ready) {
        while (revealed < state.transcript.size) {
            val line = state.transcript[revealed]
            val ms = (line.typingMs / state.rate.coerceAtLeast(1.0)).toLong()
            if (ms > 0 && line.kind != "you") {
                typing = true
                delay(ms.coerceAtMost(MAX_TYPING_MS))
                typing = false
            } else {
                delay(LINE_GAP_MS)
            }
            revealed++
        }
    }

    // 真实时间闸门（沉默四小时）：不靠推送，60 秒自重判一次
    LaunchedEffect(state.halt) {
        if (state.halt is StoryEngine.Halt.AwaitingChoice) {
            while (true) {
                delay(60_000)
                vm.recheck()
            }
        }
    }

    BackingUpTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // 贴边之后由我们自己让位：状态栏、刘海、虚拟键、键盘都算在 safeDrawing 里
                // （是并集，不会在底部叠出两段空白）。真机上第三个选项压在导航条上按不到，
                // 就是少了这一行——见 docs/07 第五节第 4 条。
                .safeDrawingPadding()
                .padding(horizontal = 14.dp),
        ) {
            TopBar(
                state = state,
                entry = entry,
                onTitleTap = {
                    val now = System.currentTimeMillis()
                    taps = if (now - lastTap > TAP_WINDOW_MS) 1 else taps + 1
                    lastTap = now
                    if (taps >= TAPS_FOR_BACKDOOR) { taps = 0; askPasscode = true }
                },
                onHangup = { confirmHangup = true },
                onSettings = { showSettings = true },
            )

            val listState = rememberLazyListState()
            var lastRevealed by remember { mutableIntStateOf(0) }
            LaunchedEffect(revealed, typing) {
                if (revealed > 0) {
                    val jump = revealed - lastRevealed
                    lastRevealed = revealed
                    val target = (revealed - 1).coerceAtLeast(0)
                    // 冷启动那一次是整段历史一起出现的，动画滚过去要好几秒；跳得远就直接落
                    if (jump > SCROLL_JUMP_TO_INSTANT) listState.scrollToItem(target)
                    else listState.animateScrollToItem(target)
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable { revealed = state.transcript.size },
            ) {
                // 往回翻的时候标出"这里空过一段时间"：纯表现层，靠相邻两条的时间差算，
                // 老存档装上就有分隔，不用清档（判据与三条设计决定见 TimeGaps 的文件头）
                val shown = state.transcript.take(revealed)
                val gaps = remember(shown.size) { TimeGaps.gaps(shown) }
                LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(shown) { index, line ->
                        val gap = gaps.getOrNull(index)
                        Column {
                            if (gap != null) GapDivider(gap)
                            Bubble(line)
                        }
                    }
                    if (typing) item { TypingIndicator() }
                }
            }

            HorizontalDivider(color = InkColors.panel)

            if (state.showAmbienceInvite) {
                Text(
                    "想让楼里的声音响起来？设置里有一行「楼里的声音」，默认是关的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkColors.dim,
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .clickable {
                            vm.dismissAmbienceInvite()
                            showSettings = true
                        },
                )
            }

            BottomArea(state = state, vm = vm)
            Spacer(Modifier.height(10.dp))
        }

        if (showSettings) {
            SettingsSheet(vm = vm, onDismiss = { showSettings = false })
        }
        if (showBackdoor) {
            BackdoorPanel(vm = vm, onDismiss = { showBackdoor = false })
        }
        if (askPasscode) {
            PasscodeDialog(
                value = passcode,
                onChange = { passcode = it },
                onDismiss = { askPasscode = false; passcode = "" },
                onSubmit = {
                    if (passcode.trim() == BACKDOOR_PASSCODE) {
                        askPasscode = false
                        passcode = ""
                        vm.unlockBackdoor()
                        showBackdoor = true
                    } else {
                        askPasscode = false
                        passcode = ""
                    }
                },
            )
        }
        if (confirmHangup && entry != null) {
            HangUpDialog(entry = entry, onDismiss = { confirmHangup = false }, onConfirm = {
                confirmHangup = false
                vm.openUiEntry(entry.id)
            })
        }
    }
}

// ── 顶栏 ────────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    state: GameViewModel.ViewState,
    entry: Node?,
    onTitleTap: () -> Unit,
    onHangup: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
    ) {
        Column {
            Text(
                "正在备份",
                style = MaterialTheme.typography.titleMedium,
                color = InkColors.text,
                modifier = Modifier.clickable(onClick = onTitleTap),
            )
            Text(
                "307 · D${state.chapter}",
                style = MaterialTheme.typography.labelSmall,
                color = InkColors.dim,
            )
        }
        Spacer(Modifier.weight(1f))
        if (entry != null) {
            // 全篇唯一的显式结束入口。它不是选项，点了就不可回退——圣经把它单列出来就是这个原因
            TextButton(onClick = onHangup) {
                Text("结束通话", color = InkColors.emergency, style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onSettings) {
            Text("设置", color = InkColors.dim, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── 时间分隔 ────────────────────────────────────────────────────────────────

/**
 * 「过了 3 分钟」那一行。
 *
 * 它故意**不是**系统消息：系统消息来自剧本，是剧情里的机器在说话，有框有边；
 * 这一行是界面的读尺，只负责让玩家往回翻时知道中间断过一次。
 * 所以它比系统消息更淡、没有边框，两条细线夹在中间——一眼能看见，又不抢她的话。
 */
@Composable
private fun GapDivider(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(InkColors.panel))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = InkColors.dim,
        )
        Box(Modifier.weight(1f).height(1.dp).background(InkColors.panel))
    }
}

// ── 气泡 ────────────────────────────────────────────────────────────────────

@Composable
private fun Bubble(line: TranscriptLine) {
    val palette = InkColors
    when (line.kind) {
        "system" -> Box(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                line.text + if (line.debug) "〔DBG〕" else "",
                style = MaterialTheme.typography.labelSmall,
                color = if (line.debug) palette.emergency else palette.dim,
                textAlign = TextAlign.Center,
            )
        }

        "letter" -> LetterCard(line)

        "wait_summary" -> Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, palette.panel, RoundedCornerShape(6.dp))
                .padding(10.dp),
        ) {
            Text(
                "（这段时间她那边：${line.text}）",
                style = MaterialTheme.typography.bodySmall,
                color = palette.dim,
            )
        }

        "ending" -> Text(
            line.text,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.text,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )

        else -> {
            val you = line.kind == "you"
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (you) Arrangement.End else Arrangement.Start,
            ) {
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .background(if (you) palette.bubbleYou else palette.bubbleHer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    Column {
                        if (!you) {
                            Text(
                                (if (line.speaker == "xiaoman") "小满" else line.speaker) +
                                        if (line.debug) " 〔DBG〕" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (line.debug) palette.emergency else palette.dim,
                            )
                        }
                        Text(line.text, style = MaterialTheme.typography.bodyMedium, color = palette.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun LetterCard(line: TranscriptLine) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(InkColors.panel, RoundedCornerShape(4.dp))
            .padding(14.dp),
    ) {
        Text("灭屏信", style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
        Spacer(Modifier.height(6.dp))
        Text(
            line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = InkColors.text,
            fontFamily = FontFamily.Serif,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.15f,
        )
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("对方正在输入…", style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
    }
}

// ── 底部：引擎停在哪，就给什么 ───────────────────────────────────────────────

@Composable
private fun BottomArea(state: GameViewModel.ViewState, vm: GameViewModel) {
    when (val halt = state.halt) {
        null -> if (state.ready) Text("…", color = InkColors.dim, modifier = Modifier.alpha(0f))
        is StoryEngine.Halt.AwaitingChoice -> ChoiceArea(halt, vm)
        is StoryEngine.Halt.AwaitingGrant -> GrantArea(halt.node, vm)
        is StoryEngine.Halt.AwaitingWait -> WaitArea(state, halt, vm)
        is StoryEngine.Halt.AwaitingInput -> InputArea(halt, onSend = vm::sendPlayerLine)
        is StoryEngine.Halt.Paused -> PausedArea(halt.node, vm, state)
        is StoryEngine.Halt.DeadEnd -> DeadEndArea(halt, vm)
    }
}

@Composable
private fun ChoiceArea(halt: StoryEngine.Halt.AwaitingChoice, vm: GameViewModel) {
    if (halt.options.isEmpty()) {
        // d6 的沉默：她没有可回的话，玩家只能退出、等真实时间。这句是界面事实，不是台词
        Text(
            "没有可说的话。你可以先退出，等她。",
            style = MaterialTheme.typography.bodySmall,
            color = InkColors.dim,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
        halt.options.forEach { option ->
            Button(
                onClick = { vm.choose(option) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkColors.bubbleHer,
                    contentColor = InkColors.text,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) {
                Text(option.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Start)
            }
        }
    }
}

@Composable
private fun GrantArea(node: Node, vm: GameViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            node.signalLine ?: "她需要你给一个能叫醒她的权利。",
            style = MaterialTheme.typography.bodySmall,
            color = InkColors.dim,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.acceptGrant() }, modifier = Modifier.weight(1f)) {
                Text(node.actionButton ?: "允许")
            }
            TextButton(onClick = { vm.resolveGrant(false) }) {
                Text("不用", color = InkColors.dim)
            }
        }
    }
}

@Composable
private fun WaitArea(
    state: GameViewModel.ViewState,
    halt: StoryEngine.Halt.AwaitingWait,
    vm: GameViewModel,
) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        halt.node.activity?.let {
            Text("她那边：$it", style = MaterialTheme.typography.bodySmall, color = InkColors.dim)
        }
        Text(
            "还要等 ${humanRemaining(state.remainingMs)}",
            style = MaterialTheme.typography.titleMedium,
            color = InkColors.text,
        )
        val allowance = halt.node.sendAllowance()
        if (allowance > 0) {
            InputRow(
                value = text,
                limit = WAIT_INPUT_LIMIT,
                placeholder = "留一条就好，不要一直发",
                onChange = { text = it },
                onSend = { vm.sendDuringWait(text); text = "" },
            )
        }
        // 玩家层快速模式：通关一次后才出现，且 W5-2（灭屏 30 分钟）永不出现
        if (state.quickMode && halt.node.fastModeSkippable != false) {
            TextButton(onClick = { vm.skipCurrentWait() }) {
                Text("跳到她回来", color = InkColors.dim)
            }
        }
    }
}

@Composable
private fun InputArea(halt: StoryEngine.Halt.AwaitingInput, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (halt.node.farewellRequired == true) {
            // d6_w650：22 小时的等待由玩家这句话才开始。界面事实，不是台词
            Text(
                "她要走了。这句话得由你说完，计时才开始。",
                style = MaterialTheme.typography.bodySmall,
                color = InkColors.dim,
            )
        }
        InputRow(
            value = text,
            limit = halt.wordLimit,
            placeholder = "你能说的就这些（${halt.wordLimit} 个字）",
            onChange = { if (it.length <= halt.wordLimit) text = it },
            onSend = { onSend(text.trim()); text = "" },
        )
    }
}

@Composable
private fun InputRow(
    value: String,
    limit: Int,
    placeholder: String,
    onChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
            modifier = Modifier.weight(1f),
            maxLines = 3,
        )
        Button(onClick = onSend, enabled = value.isNotBlank()) { Text("发送") }
        Text("$limit", style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
    }
}

@Composable
private fun PausedArea(node: Node, vm: GameViewModel, state: GameViewModel.ViewState) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (node.isEnding) {
            Text(
                node.endingName ?: "",
                style = MaterialTheme.typography.titleMedium,
                color = InkColors.text,
            )
            // 结局页只有一个按钮。没有"再来一次"、没有"分享"、没有成就提示
            Button(
                onClick = { vm.closeEnding() },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InkColors.panel, contentColor = InkColors.text),
            ) { Text("关掉它") }
            if (node.audioRef != null && vm.voiceReplayAllowed()) {
                TextButton(onClick = { vm.playVoice() }) {
                    Text("再听一遍", color = InkColors.dim, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text(
                "这一章到这里。她那边还在过时间。",
                style = MaterialTheme.typography.bodySmall,
                color = InkColors.dim,
            )
        }
    }
}

@Composable
private fun DeadEndArea(halt: StoryEngine.Halt.DeadEnd, vm: GameViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(
            "剧情卡住了：${halt.nodeId}\n${halt.why}",
            style = MaterialTheme.typography.bodySmall,
            color = InkColors.emergency,
        )
        Text(
            "（这是剧本事故，不是你的错。开发面板可以传送到任意节点。）",
            style = MaterialTheme.typography.labelSmall,
            color = InkColors.dim,
        )
    }
}

// ── 弹窗 ────────────────────────────────────────────────────────────────────

@Composable
private fun PasscodeDialog(
    value: String,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(InkColors.panel, RoundedCornerShape(10.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("房间号", style = MaterialTheme.typography.labelSmall, color = InkColors.dim)
            OutlinedTextField(value = value, onValueChange = onChange, singleLine = true, modifier = Modifier.width(160.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text("算了", color = InkColors.dim) }
                Button(onClick = onSubmit) { Text("进去") }
            }
        }
    }
}

@Composable
private fun HangUpDialog(entry: Node, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.uiElement ?: "结束通话", color = InkColors.text) },
        text = {
            Column {
                entry.confirmText.forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = InkColors.text)
                }
                Spacer(Modifier.height(8.dp))
                Text("这一步没有回退。", style = MaterialTheme.typography.labelSmall, color = InkColors.emergency)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("挂断", color = InkColors.emergency) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("再等等", color = InkColors.dim) } },
        containerColor = InkColors.panel,
    )
}

// ── 小工具 ──────────────────────────────────────────────────────────────────

private fun humanRemaining(ms: Long): String {
    if (ms <= 0) return "她快回来了"
    val totalMin = ms / 60_000
    val d = totalMin / (60 * 24)
    val h = (totalMin / 60) % 24
    val m = totalMin % 60
    return buildString {
        if (d > 0) append("${d}天")
        if (h > 0) append("${h}小时")
        if (d == 0L) append("${m}分")
    }.ifBlank { "${m}分" }
}

private const val MAX_TYPING_MS = 12_000L
private const val LINE_GAP_MS = 600L
private const val TAP_WINDOW_MS = 1_500L
/** 一次新增超过这么多行就不做滚动动画（冷启动把历史一次性上屏就是这种跳变） */
private const val SCROLL_JUMP_TO_INSTANT = 6
private const val TAPS_FOR_BACKDOOR = 5
private const val BACKDOOR_PASSCODE = "307"

/** d6_w640 断开期间能打字，但她只收得到一条：界面不承诺"发出去就等于她看见" */
private const val WAIT_INPUT_LIMIT = 40
