package cn.linan.backingup.sched

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 周年触发：《紧急联系人》那句"每年 10 月 31 日 20:14，你会真的收到一条她的消息"。
 *
 * 它是整个作品里唯一一件玩家要在**真实世界**里等满一年的事，所以它刻意不走游戏时钟：
 * 倍率（RATE）能压缩 22 小时的等待，却不能压缩一年——把周年也算进倍率里，
 * 3600× 下三分钟就"过完十年"，这个结局就从承诺变成了进度条。
 * 因此这里全部用设备时间（`System.currentTimeMillis` 语义）与本地时区，[nextAnchor] 不认识 RATE。
 *
 * 20:14 是 307 那扇门最后一次被人从里面反锁的时刻（docs/02 第六节），不是随便挑的分钟数。
 *
 * 文案池在剧本里（`sample_yearly_lines`），引擎只按"这是第几年"取下标：
 * 第 13 年回到第一条（`sent % pool.size`），"不可重复，12 年后循环"这句契约就是这么兑现的。
 */
object YearlyTrigger {

    const val REF_PREFIX = "yearly-"

    /** 锚点：10 月 31 日 20:14（本地时区） */
    private const val MONTH = 10
    private const val DAY = 31
    private const val HOUR = 20
    private const val MINUTE = 14

    /**
     * 严格在未来（且至少还差一分钟）的下一个锚点。
     * 留一分钟是因为闹钟要求 `remainingMs >= 1000`，卡在 0 毫秒上排下去等于立刻不排。
     */
    fun nextAnchor(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone)
        val thisYear = anchorIn(now.year, zone)
        return if (thisYear - nowMillis >= MIN_WAIT_MS) thisYear else anchorIn(now.year + 1, zone)
    }

    private fun anchorIn(year: Int, zone: ZoneId): Long =
        LocalDateTime.of(year, MONTH, DAY, HOUR, MINUTE).atZone(zone).toInstant().toEpochMilli()

    /** 一次发生 = 一个 ref：锚点时刻本身就在里面，所以它天然就是投递台账的键 */
    fun ref(dueAtMillis: Long): String = REF_PREFIX + dueAtMillis

    fun dueAtOf(ref: String): Long? =
        if (!ref.startsWith(REF_PREFIX)) null else ref.removePrefix(REF_PREFIX).toLongOrNull()

    /** 第 sent 年（0 起）说哪一句；池子空就是没有，绝不编 */
    fun lineFor(pool: List<String>, sent: Int): String? =
        if (pool.isEmpty()) null else pool[((sent % pool.size) + pool.size) % pool.size]

    private const val MIN_WAIT_MS = 60_000L
}
