package cn.linan.backingup

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import cn.linan.backingup.sched.ReminderScheduler
import cn.linan.backingup.sched.WakeGate
import cn.linan.backingup.ui.GameScreen
import cn.linan.backingup.ui.GameViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

/**
 * 唯一的一个 Activity。
 *
 * 它只负责三件事，全都属于"设备事实"而不属于剧情：
 *   1. 把前后台与灭屏这两个信号送进 [cn.linan.backingup.engine.DeviceSignals]
 *      ——这两个信号决定声床该不该响，也决定她那句"你不在的时候我不说话"；
 *   2. 通知权限的真实结果回灌给引擎（d1_n140 那条剧本线）；
 *   3. 把闹钟带回来的 `wait_ref` 交给 VM 判定是否逾期。
 *
 * 这里不许出现任何一句台词，也不许出现任何"剧情分支"。有就是放错了层。
 */
class MainActivity : ComponentActivity() {

    private lateinit var vm: GameViewModel

    private val notifyPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 她说过"你可以不给"——拒绝就是拒绝，不重试、不解释、不二次弹窗
            vm.resolveGrant(granted)
        }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> vm.onScreenOff(true)
                Intent.ACTION_SCREEN_ON -> vm.onScreenOff(false)
                Intent.ACTION_USER_PRESENT -> vm.onScreenOff(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ReminderScheduler.ensureChannel(this)
        vm = ViewModelProvider(this)[GameViewModel::class.java]

        // 引擎不许碰 Activity，但授权弹窗只有 Activity 能弹——所以把它作为回调交给 VM，
        // d1_n140 的『允许』按钮由此落到真实系统弹窗，而不是我们自己伪造的"已允许"
        vm.grantRequester = { requestNotifyThenResolve() }
        setContent { GameScreen(vm) }
        handleWakeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWakeIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground(true)
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            // 灭屏广播是系统广播，不能导给别的 App；不加这个标记，targetSdk 34 直接抛异常
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(screenReceiver) }
        vm.onForeground(false)
        vm.touchLastPlayed()
    }

    private fun handleWakeIntent(intent: Intent?) {
        val ref = intent?.getStringExtra(ReminderScheduler.EXTRA_REF) ?: return
        // 消费掉再走：Intent 会跟着任务活很久，不清的话从最近任务列表重建 Activity
        // 会把同一个 ref 再交给 vm.onWake 一次——她到点的那句话就成了可重放的按钮
        intent.removeExtra(ReminderScheduler.EXTRA_REF)
        setIntent(intent)
        // 退出后补投的那条只是把她说的话送到通知栏，不改剧情状态：点开它等同于自己打开应用
        if (ref.startsWith(WakeGate.POST_PREFIX)) return
        vm.onWake(ref)
    }

    /**
     * 由 d1_n140 的『允许』按钮调用，不是启动就弹。
     * Android 13 以下通知权限是安装时授予的，直接走"已允许"分支。
     */
    fun requestNotifyThenResolve() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.resolveGrant(true)
        } else {
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** 省电白名单引导：由同一个授权节点带出来，不做成第二个弹窗 */
    fun openBatteryExemption() {
        runCatching { startActivity(ReminderScheduler.batteryOptimizationIntent(this)) }
    }
}
