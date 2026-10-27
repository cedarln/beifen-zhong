package cn.linan.backingup.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 美术的唯一一条规矩：**这里没有"漂亮"，只有"像手机"**。
 *
 * 全篇只有两种亮：走廊灯（#E8E3D8，她的话）和应急灯（#B04A3A，出事时）。
 * 背景是黑的，因为小满住在缓存里，UI 不该有装饰——任何装饰都会变成"游戏美术"，
 * 而我们要的是玩家以为自己在看一个真的会话页。
 *
 * 明确不用：渐变、彩色图标、圆角卡片阴影、任何品牌色。
 */
private val InkBlack = Color(0xFF0B0C0E)
private val PanelNear = Color(0xFF14161A)
private val BubbleHer = Color(0xFF1C1F24)
private val BubbleYou = Color(0xFF2A2E35)
private val LampText = Color(0xFFE8E3D8)
private val DimText = Color(0xFF6B6E75)
private val Emergency = Color(0xFFB04A3A)
private val PipeBlue = Color(0xFF4A6670)

internal val InkColors = InkPalette(
    background = InkBlack,
    panel = PanelNear,
    bubbleHer = BubbleHer,
    bubbleYou = BubbleYou,
    text = LampText,
    dim = DimText,
    emergency = Emergency,
    pipe = PipeBlue,
)

internal data class InkPalette(
    val background: Color,
    val panel: Color,
    val bubbleHer: Color,
    val bubbleYou: Color,
    val text: Color,
    val dim: Color,
    val emergency: Color,
    val pipe: Color,
)

private val scheme = darkColorScheme(
    primary = LampText,
    onPrimary = InkBlack,
    secondary = PipeBlue,
    onSecondary = InkBlack,
    background = InkBlack,
    onBackground = LampText,
    surface = PanelNear,
    onSurface = LampText,
    surfaceVariant = BubbleHer,
    onSurfaceVariant = DimText,
    error = Emergency,
    onError = InkBlack,
    outline = DimText,
)

/**
 * 字号刻意比常规聊天 App 小半号、行距大：她的话要读，不是刷。
 * 字体用系统默认无衬线——任何自带字体都会让包变大，而且"系统字体"本身就是"这是手机"的证据。
 */
private val type = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 18.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 15.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
        fontWeight = FontWeight.Normal, color = DimText,
    ),
)

@Composable
fun BackingUpTheme(content: @Composable () -> Unit) {
    // 不做亮色主题：她住在夜里 20:14 开始的楼里，白天不是她的时间
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
