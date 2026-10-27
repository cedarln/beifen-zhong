package cn.linan.backingup.sched

import android.content.Context

/**
 * 投递台账：这一次通知发过了没有。
 *
 * 它记在设备侧（SharedPreferences）而不是存档里，因为"发没发"是设备事实，不是剧情事实
 * （docs/01 的红线：存档只装三度量、flag、counter 和转写）。她说过的那句话已经在存档的
 * `pending_notifications` 里，至于系统有没有真的把它弹出来，是我们这一层的账。
 *
 * 为什么非要有这张表：一条等待有两条送达路径——`AlarmManager` 的精确闹钟（按时）和
 * `WorkManager` 的兜底（5 分钟后，或重启后）。两者都调 `ReminderScheduler.post()`，
 * 没有台账的话同一条"她该说的话"会在通知栏出现两次。对一款靠节奏恐怖吃饭的游戏，
 * 重复投递不是小瑕疵：它把她变成了一个会重播的提醒事项。
 *
 * 键是 [WakeGate.deliveryToken]（ref + 这一次的发生时刻），不是 ref：
 * 二周目重走 D1 时 W1-2 会原样再来一次，按 ref 记账等于把她永久消音。
 *
 * 单进程应用用 SharedPreferences 足够；`commit()` 同步落盘是因为闹钟和 worker
 * 可能几乎同时醒来，`apply()` 的异步写会让第二个读者看不到第一个读者的标记。
 */
object DeliveryLedger {

    private const val PREFS = "delivery"
    private const val KEY_PREFIX = "posted-"

    /** 三十天前的记录清掉：一局游戏的 token 也就几十条，不清也死不了，但别让它无限长 */
    private const val TTL_MS = 30L * 24 * 3600 * 1000

    fun alreadyPosted(context: Context, token: String): Boolean =
        prefs(context).getLong(KEY_PREFIX + token, 0L) > 0L

    fun markPosted(context: Context, token: String) {
        val now = System.currentTimeMillis()
        val store = prefs(context)
        val stale = store.all.entries
            .filter { it.key.startsWith(KEY_PREFIX) && (it.value as? Long ?: 0L) < now - TTL_MS }
            .map { it.key }
        store.edit().apply {
            putLong(KEY_PREFIX + token, now)
            stale.forEach { remove(it) }
            commit()
        }
    }

    /** 后门面板用：现在记住了几条"发过了"，以及是哪几条（token 里就带着 ref） */
    fun postedTokens(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith(KEY_PREFIX) }.map { it.removePrefix(KEY_PREFIX) }.sorted()

    /** 重置（后门面板"清台账"用：想重测一次同一条通知就得先忘掉发过） */
    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
