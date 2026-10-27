package cn.linan.backingup

import cn.linan.backingup.engine.SignalReader
import cn.linan.backingup.story.Node

/**
 * 引擎在 JVM 里跑剧本时用的假设备。
 *
 * 存在的唯一理由：`StoryEngine` 只认 [SignalReader]，于是全篇节点的全量走查
 * 不需要模拟器、不需要 Robolectric，也不需要有人下午三点把手机屏幕按灭。
 *
 * [satisfied] 里的信号名一律算成立；不在里面的算不成立。走查测试通常把白名单全填进去
 * （"如果玩家每次都配合"），个别测试故意留空（"如果玩家从来没灭过屏"）。
 */
class FakeSignals(
    val satisfied: MutableSet<String> = mutableSetOf(),
    /** needs_signal 的时间窗（"01:00-04:00"）是否命中 */
    var windowHit: Boolean = true,
) : SignalReader {

    override val whitelist = setOf(
        "foreground", "background_gt_90s", "screen_off", "night_window",
        "battery_low", "power_save_on", "reenter_3x", "notify_denied",
        "exact_alarm_granted", "device_date", "ended_in_app",
    )

    var batteryLow = false
    var powerSaveOn = false
    var lastGrant: Boolean? = null

    override fun matches(node: Node, gameTime: Long): Boolean {
        val name = node.signal ?: return true
        require(name in whitelist) { "signal 不在白名单：$name" }
        return name in satisfied
    }

    override fun inWindow(spec: String, at: Long): Boolean = windowHit
    override fun inNightWindow(at: Long): Boolean = windowHit
    override fun isOverridden(name: String): Boolean = false
    override fun batteryLow(): Boolean = batteryLow
    override fun powerSaveOn(): Boolean = powerSaveOn
    override var nightUsesDeviceClock: Boolean = false

    override fun recordGrant(granted: Boolean) {
        lastGrant = granted
        satisfied += if (granted) "exact_alarm_granted" else "notify_denied"
    }

    companion object {
        /** 全部信号成立：走查"玩家配合到底"这条路 */
        fun allIn(): FakeSignals = FakeSignals(mutableSetOf(
            "foreground", "background_gt_90s", "screen_off", "night_window",
            "battery_low", "power_save_on", "reenter_3x", "notify_denied",
            "exact_alarm_granted", "device_date", "ended_in_app",
        ))
    }
}
