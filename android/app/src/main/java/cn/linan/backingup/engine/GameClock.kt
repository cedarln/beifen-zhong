package cn.linan.backingup.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 游戏时钟（docs/04 第三节）。
 *
 * 唯一被引擎信任的时间源：
 *     gameTime = installAt + (deviceNow - installAt) * RATE + OFFSET
 *
 * 为什么不是"每个 wait 一个立即完成按钮"：那样度量随真实时间衰减这条线会断，
 * 而它正是本作最重要的道德压力——玩家拖着不玩，她就提前散架。倍率作用在时钟层，
 * 于是等待、每日衰减、周年触发、深夜窗口在 3600× 下依然一致，不会出现
 * "跳过了等待但 D5 还认为只过了一天"的假象。
 *
 * RATE / OFFSET 只由后门面板写；正常游玩时 RATE=1.0、OFFSET=0。
 */
class GameClock(
    /** 安装时刻的设备时间戳：存档里持久化，重启后不能漂 */
    val installAt: Long = System.currentTimeMillis(),
    initialRate: Double = 1.0,
    initialOffset: Long = 0L,
    /**
     * 设备时间的来源。真机就是 `System::currentTimeMillis`，单测里换成一个可拨的假时钟——
     * 倍率重锚这类算式只能"给定此刻"才验得对，拿真实墙上时间跑测试会随机飘。
     */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _rate = MutableStateFlow(initialRate)
    val rateFlow: StateFlow<Double> = _rate

    var offset: Long = initialOffset
        private set

    val rate: Double get() = _rate.value

    fun gameTime(): Long = gameTime(now())

    fun gameTime(deviceNow: Long): Long =
        installAt + ((deviceNow - installAt) * _rate.value).toLong() + offset

    /**
     * 设备时间：深夜窗口（01:00–04:00）默认按它判定。
     * 3600× 下每章都会路过凌晨，若按游戏时钟判，D3 的深夜节点会廉价地天天触发。
     * 面板上那个 `夜间窗口按设备时钟判定` 的开关就是这两个方法的切换。
     */
    fun deviceTime(): Long = now()

    /**
     * 换倍率：当前 gameTime 不动，之后的流逝速度变。
     * 直接把 RATE 换掉会让所有在途等待瞬间重算（3× 的 22 小时变成 7 小时），
     * 所以切之前先记下锚点，切之后用 OFFSET 把这一刻补回原位。
     */
    fun setRate(newRate: Double) {
        val anchor = gameTime()
        _rate.value = newRate
        offset = anchor - installAt - ((now() - installAt) * newRate).toLong()
    }

    /** 跳章 / 伪造一天：OFFSET += 86_400_000 × N，等待与衰减一起被推过去 */
    fun jump(ms: Long) {
        offset += ms
    }

    /** 距某个游戏时刻还要多久（<=0 表示已到期）。等待节点用它判 due */
    fun remaining(untilGameTime: Long): Long = untilGameTime - gameTime()

    /** 到点了吗。等待在途与否都用它判，避免"两处比较、一处写反" */
    fun isDue(untilGameTime: Long): Boolean = gameTime() >= untilGameTime

    fun gameMinutesBetween(from: Long, to: Long): Double = (to - from) / 60_000.0

    companion object {
        const val DAY_MS = 86_400_000L

        /** 存档恢复：三个数缺一不可，否则倍率与逾期都会算错 */
        fun restore(installAt: Long, rate: Double, offset: Long, now: () -> Long = System::currentTimeMillis) =
            GameClock(installAt, rate, offset, now)
    }
}
