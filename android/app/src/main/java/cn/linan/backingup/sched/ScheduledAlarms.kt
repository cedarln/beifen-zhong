package cn.linan.backingup.sched

import android.content.Context
import android.os.SystemClock

/**
 * "这一条我已经排过了没有"——我们自己记的账。
 *
 * 为什么不用系统的答案：`PendingIntent.getBroadcast(FLAG_NO_CREATE)` 只能证明**那张凭条还在**，
 * 不能证明闹钟还挂着。真机上量到过这样一幕（模拟器 `pm clear` 复现）：
 * 进程被强停后 PendingIntentRecord 仍留在系统表里，AlarmManager 的闹钟却已经没了——
 * 于是 `hasPendingAlarm` 说"有"，[GameViewModel] 就不重排，[CatchUpWorker] 也不重排，
 * 她的话从此没有人负责。这一层记的账必须比那张凭条诚实。
 *
 * 为什么不能只记"排过"：重启会清空 AlarmManager，但 SharedPreferences 活得比它久。
 * 所以每条账都带上**本次开机的墙上时刻**（`now - elapsedRealtime`）：
 * 开机之后这个数一定变，账自动作废，兜底路径就会照存档补排一次。
 * 玩家手动改系统时间同样会让它作废——那时候闹钟本来也不可信，重新排一次是对的。
 */
object ScheduledAlarms {

    private const val PREFS = "scheduled"

    /** ref → "触发时刻|开机标记" */
    fun armedAt(context: Context, ref: String): Long? {
        val raw = prefs(context).getString(ref, null) ?: return null
        val (at, mark) = raw.split('|').let {
            if (it.size != 2) return null
            it[0].toLongOrNull() to it[1].toLongOrNull()
        }
        if (at == null || mark == null || mark != bootMark()) return null
        if (at <= System.currentTimeMillis()) return null      // 已经响过了，账作废
        return at
    }

    /** 同一个 ref、同一个触发点、同一次开机 = 已经排好了，别再排一遍 */
    fun alreadyArmed(context: Context, ref: String, triggerAtMillis: Long): Boolean =
        armedAt(context, ref) == triggerAtMillis

    fun mark(context: Context, ref: String, triggerAtMillis: Long) {
        prefs(context).edit()
            .putString(ref, "$triggerAtMillis|${bootMark()}")
            .apply()
    }

    fun forget(context: Context, ref: String) {
        prefs(context).edit().remove(ref).apply()
    }

    /** 面板与日志用：现在还在账上的所有排程 */
    fun live(context: Context): Map<String, Long> {
        val now = System.currentTimeMillis()
        val mark = bootMark()
        return prefs(context).all.mapNotNull { (ref, value) ->
            val parts = (value as? String)?.split('|') ?: return@mapNotNull null
            val at = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            if (parts.getOrNull(1)?.toLongOrNull() != mark || at <= now) null else ref to at
        }.toMap()
    }

    /** 本次开机的墙上时刻：重启、改时间都会让它变掉 */
    private fun bootMark(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
