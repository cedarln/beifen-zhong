package cn.linan.backingup.engine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.PowerManager
import cn.linan.backingup.story.Node
import java.util.Calendar

/**
 * 设备信号（docs/01 感知映射表的执行端）。
 *
 * 这一层是整个隐私红线的技术落点，所以它只读四类合法信号：前台/灭屏、电量、省电、日期。
 * 明确不做的事，写在这里而不是散落在各处：
 *   ✗ 不读通知内容（NotificationListenerService 不申请）
 *   ✗ 不用无障碍服务
 *   ✗ 不读通讯录 / 短信 / 其他 App
 *   ✗ 不做真实联网推送（全本地调度）
 * 如果哪天有人想加"读取玩家的消息记录来生成台词"，先回到这张白名单来。
 *
 * [overrides] 是后门面板的伪造入口（docs/04 第二节第 5 条）：命中时气泡打 DBG 角标，
 * 因为我们必须能区分"它自然触发了"和"我们让它触发的"。
 *
 * 实现的是 [SignalReader]——StoryEngine 只认接口，于是全篇节点的全量走查可以在
 * JVM 单测里跑完（tests/FakeSignals），不需要 Robolectric，也不需要真机。
 */
class DeviceSignals(
    private val context: Context,
    private val overrides: MutableMap<String, Boolean> = mutableMapOf(),
) : SignalReader {

    /** 白名单：新增 signal 必须先回圣经补感知映射表，再改这里 */
    override val whitelist = setOf(
        "foreground", "background_gt_90s", "screen_off", "night_window",
        "battery_low", "power_save_on", "reenter_3x", "notify_denied",
        "exact_alarm_granted", "device_date", "ended_in_app",
    )

    var screenOff: Boolean = false
    var foreground: Boolean = true
    var backgroundSince: Long = 0L
    var reenterCount: Int = 0
    var notifyDenied: Boolean = false
    var exactAlarmGranted: Boolean = true
    var endedInApp: Boolean = false

    fun override(name: String, value: Boolean?) {
        if (value == null) overrides.remove(name) else overrides[name] = value
    }

    override fun isOverridden(name: String): Boolean = overrides.containsKey(name)

    /** 节点声明的 signal 是否成立。signal=null 的节点恒成立 */
    override fun matches(node: Node, gameTime: Long): Boolean {
        val name = node.signal ?: return true
        require(name in whitelist) { "signal 不在白名单：$name（先补感知映射表）" }
        overrides[name]?.let { return it }
        return when (name) {
            "foreground" -> foreground
            "background_gt_90s" -> !foreground && gameTime - backgroundSince > 90_000L
            "screen_off" -> screenOff
            "night_window" -> inNightWindow(if (nightUsesDeviceClock) System.currentTimeMillis() else gameTime)
            "battery_low" -> batteryLow()
            "power_save_on" -> powerSaveOn()
            "reenter_3x" -> reenterCount >= 3
            "notify_denied" -> notifyDenied
            "exact_alarm_granted" -> exactAlarmGranted
            "device_date" -> true    // device_date 是取值不是开关，见 [deviceDate]
            "ended_in_app" -> endedInApp
            else -> false
        }
    }

    /** needs_signal 的时间窗，形如 "01:00-04:00" */
    override fun inWindow(spec: String, at: Long): Boolean {
        val (from, to) = spec.split("-").map { it.trim() }
        val minutes = minuteOfDay(at)
        val f = toMinutes(from)
        val t = toMinutes(to)
        return if (f <= t) minutes in f until t else minutes >= f || minutes < t
    }

    override fun inNightWindow(at: Long): Boolean = minuteOfDay(at) in (60 until 240)   // 01:00–04:00

    /** D1 规则 10：结尾必须落在真实日期，这条不允许被游戏时钟伪造 */
    fun deviceDate(format: String = "yyyy-MM-dd"): String =
        java.text.SimpleDateFormat(format, java.util.Locale.CHINA).format(java.util.Date(System.currentTimeMillis()))

    fun weekdayCn(): String =
        arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")[
            Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
        ]

    private fun minuteOfDay(at: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    private fun toMinutes(hhmm: String): Int {
        val (h, m) = hhmm.split(":").map { it.trim().toInt() }
        return h * 60 + m
    }

    /** 面板与声床直接问的两个设备事实；后门伪造优先（overrides 命中即返回它） */
    override fun batteryLow(): Boolean = overrides["battery_low"] ?: (batteryPct() <= 0.20)

    override fun powerSaveOn(): Boolean = overrides["power_save_on"] ?: powerSave()

    private fun batteryPct(): Double {
        // 电量走 sticky 广播，不需要任何权限；这是唯一被允许的电量读取方式
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level < 0 || scale <= 0) {
            // 拿不到就当满电：低电量台词宁可不出，也不要骗玩家说她在省着活
            1.0
        } else level.toDouble() / scale
    }

    private fun powerSave(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    /** 夜间窗口默认按设备时钟判：3600× 下每章都会路过凌晨，按游戏时钟判会廉价（docs/04 第三节） */
    override var nightUsesDeviceClock: Boolean = true

    /**
     * d1_n140 的真实授权结果。注意这不是 overrides——玩家的真实选择不是"伪造"，
     * 所以它不能带 DBG 角标；它改变的是设备事实本身。
     */
    override fun recordGrant(granted: Boolean) {
        notifyDenied = !granted
        exactAlarmGranted = granted
    }

    companion object {
        /** 省电模式还包含"我们主动退出过"这种非设备事实，交给 flags 管 */
        fun isDebuggable(app: ApplicationInfo): Boolean =
            (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
}

/**
 * StoryEngine 需要的最小信号面。设备事实（电量/省电/灭屏）与伪造开关都从这里进，
 * 于是引擎层可以脱离 Android 跑全量剧本走查（docs/04 第六节验收 1 的前提）。
 */
interface SignalReader {
    val whitelist: Set<String>
    fun matches(node: Node, gameTime: Long): Boolean
    fun inWindow(spec: String, at: Long): Boolean
    fun inNightWindow(at: Long): Boolean
    fun isOverridden(name: String): Boolean
    fun batteryLow(): Boolean = false
    fun recordGrant(granted: Boolean) {}
    fun powerSaveOn(): Boolean = false
    var nightUsesDeviceClock: Boolean
}
