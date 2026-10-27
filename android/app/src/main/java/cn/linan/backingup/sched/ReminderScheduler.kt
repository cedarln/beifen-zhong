package cn.linan.backingup.sched

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cn.linan.backingup.MainActivity
import cn.linan.backingup.R
import java.util.concurrent.TimeUnit

/**
 * 本地调度：AlarmManager 精确闹钟（真正按时）+ WorkManager（可延迟，只兜底）。
 * 没有 FCM，也没有 INTERNET 权限——她只存在于这台手机上，这条既是设定也是红线。
 *
 * 文案一律由调用方从剧本带进来（wait 节点的 first_line_after_wait / signal_line）。
 * 这个类不许自己写任何一句她的话：引擎不发明内容，通知也不行。
 *
 * 时间参数用"还要等多久（毫秒）"而不是"游戏时间戳"：游戏时钟与设备时钟之间隔着
 * RATE/OFFSET，把换算留在引擎层做完，这一层只认识真实世界的唤醒时刻。
 *
 * 闹钟的落点是 [WakeReceiver] 而不是 Activity（它先照存档判断这句还该不该说，再投通知），
 * 通知被点下去才进 [MainActivity]——"到点"与"玩家回来了"是两件事，不能混在一个 PendingIntent 里。
 */
object ReminderScheduler {

    const val CHANNEL_ID = "corridor"
    const val EXTRA_REF = "cn.linan.backingup.ref"
    const val EXTRA_TEXT = "cn.linan.backingup.text"

    private const val CATCH_UP_WORK = "catch_up"

    /**
     * 通知 id 由 ref 派生：同一条等待永远落在同一张通知上。
     * 于是"闹钟投过一次、兜底 worker 又投一次"这种事故（见 [DeliveryLedger]）
     * 最坏也只是原地刷新，不会在通知栏叠出两条一样的话。
     */
    private fun notiId(ref: String) = 307 * 31 + ref.hashCode()

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            // IMPORTANCE_LOW：她会响，但不会用一声脆响打断玩家正在做的事
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, context.getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "到她该说话的时候" }
            )
        }
    }

    /** Android 13+ 用户可以在系统设置里禁掉精确闹钟；被禁时我们必须知道并标红，别让它变成"她没来" */
    fun canScheduleExact(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
        } else true

    /**
     * 排一次按时唤醒。返回 false 表示这台设备给不了精确唤醒，已降级为 WorkManager 兜底，
     * 后门面板要把它标红（docs/04 第四节）。
     *
     * [allowNightDelivery] = false（默认）时，唤醒点若落在 [QuietHours] 的 23:30–07:30，
     * 会被推到次日 07:30：**存档与游戏时钟不动**，动的只是"这句什么时候进通知栏"。
     * 她的准时是剧情事实，你的睡眠是设备事实，两条各归各。
     */
    fun schedule(
        context: Context,
        remainingMs: Long,
        ref: String,
        text: String,
        allowNightDelivery: Boolean = false,
    ): Boolean {
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val triggerAt = QuietHours.clamp(now + remainingMs.coerceAtLeast(1_000L), allowNightDelivery)
        // 同一个点排两次 = 把兜底 worker 的 5 分钟一直往后推，玩家每点一下屏幕她就永远迟到
        // 5 分钟。判"排没排过"用 [ScheduledAlarms]，不用 PendingIntent 表（理由写在那儿）。
        if (ScheduledAlarms.alreadyArmed(context, ref, triggerAt)) return canScheduleExact(context)
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val exact = alarmManager != null && canScheduleExact(context)
        if (exact) {
            // +1 秒：跨 Doze 时按整分对齐的唤醒会被系统合并，多给一秒减少"提前醒来说错话"
            alarmManager!!.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAt + 1_000L,
                wakeIntent(context, ref, text),
            )
        } else if (alarmManager != null) {
            // 精确唤醒被这台设备撤了（Android 13+ 用户可以在设置里禁掉它）。这里**不能**只靠
            // 下面那个 WorkManager 兜底：长延迟的 JobScheduler 在 Doze 里能被推到几个小时之后，
            // 而她说的"晚上九点"就变成"不知道哪天"。setAndAllowWhileIdle 不要精确权限，
            // 在打盹里也照样给一次唤醒，只是不保证准时——这正是那条授权文案的语义：
            // 她可能迟到，不是她的问题。
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, wakeIntent(context, ref, text))
        }
        ScheduledAlarms.mark(context, ref, triggerAt)
        // 兜底：Doze / 省电模式可能吞掉 exact，WorkManager 在唤醒点之后 5 分钟再检查一次是否逾期。
        // 拿不到精确闹钟时也要**等到那个点**（不给 0）：0 延迟的 worker 会在排程那一刻就跑，
        // 发现还没到点、按设计不重排长等待，于是这条消息再也没人管——"她说了几点就几点"变成空话。
        val fallbackDelay = (triggerAt - now).coerceAtLeast(0L) + if (exact) 5 * 60_000L else 0L
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_WORK + ref, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CatchUpWorker>()
                .setInitialDelay(fallbackDelay, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(EXTRA_REF to ref))
                .build(),
        )
        return exact
    }

    fun cancel(context: Context, ref: String) {
        wakeIntent(context, ref, "").let { pi ->
            context.getSystemService(AlarmManager::class.java)?.cancel(pi)
            pi.cancel()
        }
        ScheduledAlarms.forget(context, ref)
        WorkManager.getInstance(context).cancelUniqueWork(CATCH_UP_WORK + ref)
        NotificationManagerCompat.from(context).cancel(notiId(ref))
    }

    /** 冷启动与开机都调它：把逾期消息按顺序补投 */
    fun scheduleCatchUp(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_WORK, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CatchUpWorker>().build(),
        )
    }

    /** 省电白名单引导：由 D1 的授权节点带出来（"你给了我一个能叫醒你的权利"），不做成独立弹窗 */
    fun batteryOptimizationIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * 实际投递。正文必须来自剧本字段，调用方负责传；这里只做展示。
     *
     * `POST_NOTIFICATIONS` 在 13+ 可能被用户关掉，`notify()` 会静默无果——不抛异常，
     * 所以这里用 runCatching 兜住别的失败（渠道被删、通知被系统限流），
     * 但**不**替玩家做"弹个窗让她去开权限"这种事：她说过"你可以不给"。
     */
    fun post(context: Context, ref: String, text: String) {
        ensureChannel(context)
        val manager = NotificationManagerCompat.from(context)
        runCatching {
            manager.notify(
                notiId(ref),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    // 不能用 @mipmap/ic_launcher：自适应图标当小图标会被裁成一块白，
                    // 状态栏要的是一张单色 silhouette（res/drawable/ic_stat_corridor.xml）
                    .setSmallIcon(R.drawable.ic_stat_corridor)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentIntent(openAppIntent(context, ref))
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    private fun wakeIntent(context: Context, ref: String, text: String): PendingIntent {
        val intent = Intent(context, WakeReceiver::class.java)
            .putExtra(EXTRA_REF, ref)
            .putExtra(EXTRA_TEXT, text)
        return PendingIntent.getBroadcast(
            context, ref.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 这个 ref 现在有没有一笔还没到点的排程。
     *
     * 名字留着（调用方读起来更直白），实现换到 [ScheduledAlarms]：
     * 上一版这里用 `PendingIntent.getBroadcast(FLAG_NO_CREATE)` 探系统表，
     * 但凭条活得比闹钟久——强停之后它会留下，闹钟却没了，于是"有闹钟"变成假的，
     * 谁都不去补排，她的话就再也不会来。
     */
    fun hasPendingAlarm(context: Context, ref: String): Boolean =
        ScheduledAlarms.armedAt(context, ref) != null

    private fun openAppIntent(context: Context, ref: String): PendingIntent =
        PendingIntent.getActivity(
            context, ref.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_REF, ref)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
