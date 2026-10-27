package cn.linan.backingup.sched

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 换包 / 改时间之后各做一次补投（docs/04 第四节）。
 *
 * 22 小时的等待要跨 Doze 与跨重启：due 存在存档里，进程死了也不要紧，
 * 冷启动和这里都调一次 catchUp()——逾期不补投 = 玩家以为游戏坏了。
 *
 * 现在只做最保守的一件事：把到期唤醒重新排上。真正的补投投递在 ReminderScheduler。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED 时 Direct Boot 还没解锁凭据存储，所以延迟到用户解锁再读存档；
        // 这里用 unlock 后的 ACTION_USER_PRESENT 之外的最省事做法：交给 WorkManager 兜底。
        ReminderScheduler.scheduleCatchUp(context)
    }
}
