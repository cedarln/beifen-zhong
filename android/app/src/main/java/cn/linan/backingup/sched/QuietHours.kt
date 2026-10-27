package cn.linan.backingup.sched

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 夜间静默窗口：**她到点，但不吵你。**
 *
 * 先把一件事说清楚，因为它经常被误解：这套东西不是"系统闹钟"。我们不进时钟 App、
 * 不发 `SET_ALARM`、不用 `setFullScreenIntent`、通知渠道是 `IMPORTANCE_LOW`（不响、不震动、
 * 不亮屏、不弹 heads-up）。`AlarmManager` 在这里只是一个"到点把一句话放进通知栏"的定时器。
 *
 * 但即便是静音通知，23:47 让一台手机在枕边亮一下通知栏、凌晨把 CPU 叫醒一次，
 * 也足够让人生气了——而且生气得很对：一款讲"她只在合法信号里活着"的游戏，
 * 最不该做的就是不打招呼地闯进玩家的睡眠。所以：
 *
 *   - 排程时把落在静默窗口里的唤醒点**推到窗口结束**（07:30 的那一句，早上说）；
 *   - 投递侧再兜一次底：万一还是撞上了（Doze 把唤醒压后、旧存档里已经排好的闹钟），不投，
 *     重排到窗口结束；
 *   - 玩家可以自己把这条打开（设置页「夜里也让她说」，默认关）。这条设定本身还是游戏的意思：
 *     她要不要在你睡着的时候说话，是你给的权限，不是她偷的能力。
 *
 * 时间一律按**设备本地时间**判，不按游戏时钟：倍率会把 22 小时压成几十秒，
 * 按游戏时钟判"现在几点"等于没有判断（同 [cn.linan.backingup.engine.DeviceSignals] 里
 * `nightUsesDeviceClock` 的理由）。游戏剧情里的"凌晨"是另一条信号 `night_window`
 * （01:00–04:00，由剧本用），别跟这条产品礼貌混用——一个是她的世界，一个是你的卧室。
 *
 * 窗口按"跨过午夜"处理：23:30 → 次日 07:30。端点取 `in [start, end)`，
 * 所以 07:30:00 整已经不算静默（重排到这里就一定会投出去，不存在自循环）。
 */
object QuietHours {

    /** 23:30 */
    const val START_MINUTE_OF_DAY: Int = 23 * 60 + 30

    /** 次日 07:30：静默窗口在这一分钟结束，整点起恢复正常投递 */
    const val END_MINUTE_OF_DAY: Int = 7 * 60 + 30

    /**
     * 这个时刻是不是"该睡觉的时候"。
     * [allowNightDelivery] = true 时永远返回 false：玩家亲手授权过"夜里也让她说"。
     */
    fun isQuiet(
        atMillis: Long,
        allowNightDelivery: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Boolean {
        if (allowNightDelivery) return false
        return isQuietMinute(minuteOfDay(atMillis, zone))
    }

    /** 纯分钟判据，给单测和 [clamp] 复用 */
    fun isQuietMinute(minuteOfDay: Int): Boolean =
        minuteOfDay >= START_MINUTE_OF_DAY || minuteOfDay < END_MINUTE_OF_DAY

    /** 离窗口结束还有多少毫秒；不在窗口里返回 0 */
    fun millisUntilWindowEnd(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        if (!isQuietMinute(minuteOfDay(atMillis, zone))) 0L
        else nextWindowEnd(atMillis, zone) - atMillis

    /**
     * 唤醒点落在静默窗口里就推到窗口结束（07:30 整）。
     * 不在窗口里、或玩家授权了夜间投递，原样返回。
     *
     * 只做"往后推"：绝不把提前的东西挪到更靠前，也不做秒级对齐——早到的通知是礼貌，
     * 晚到的通知只是礼貌迟到，两者都不该被改。
     */
    fun clamp(
        triggerAtMillis: Long,
        allowNightDelivery: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long =
        if (isQuiet(triggerAtMillis, allowNightDelivery, zone)) nextWindowEnd(triggerAtMillis, zone)
        else triggerAtMillis

    /**
     * [atMillis] 之后第一个"窗口结束"的时刻。
     * 已经在 07:30 之后（清醒时段）时，返回**明天**的 07:30——调用方只在静默窗口里用它，
     * 但把这个边界写死，免得有人拿它当"下一次可投递时刻"用出反直觉的结果。
     */
    fun nextWindowEnd(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = atMillis.atZone(zone)
        val today = now.toLocalDate().atStartOfDay(zone)
            .plusMinutes(END_MINUTE_OF_DAY.toLong())
        return if (today.isAfter(now)) today.toInstant().toEpochMilli()
        else today.plusDays(1).toInstant().toEpochMilli()
    }

    private fun minuteOfDay(atMillis: Long, zone: ZoneId): Int {
        val now = atMillis.atZone(zone)
        return now.hour * 60 + now.minute
    }

    private fun Long.atZone(zone: ZoneId): ZonedDateTime =
        Instant.ofEpochMilli(this).atZone(zone)
}
