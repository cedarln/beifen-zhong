package cn.linan.backingup.engine

import cn.linan.backingup.sched.QuietHours
import cn.linan.backingup.story.ScriptRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 章与章之间的门（docs/02 第一节：七章 = 七个真实日）。
 *
 * 剧本里每章的最后一个节点带 `chapter_end: true` 且**没有出边**——这不是漏写，是设定：
 * 下一章不属于这一章的图，它属于"第二天"。所以跨章不靠 `next`，靠这里：
 *   1. 引擎走到 `chapter_end` 时，把那一刻（**游戏时间**）记进存档；
 *   2. 开章时刻 = 那一天的**次日早上 07:30**；
 *   3. 游戏时钟跨过它，任何一次重判（冷启动、她那条章间闹钟、屏上的秒级自判）都把下一章接上。
 *
 * 为什么是 07:30：那是 [QuietHours] 静默窗口结束的那一分钟。她醒来的时间和"她不该吵你的
 * 时间结束"是同一个时刻，两处写死迟早对不上，所以这里只引用、不另立常数。
 *
 * 为什么按**游戏时钟**算日子，而 QuietHours 按设备时钟：倍率是玩家自己拧的望远镜——
 * 3600× 下"过一天"就该是二十几秒，否则这个后门只能跳章内等待、跳不过章与章之间
 * （真机验证时撞上的正是这个）。而"现在几点不该吵人"是卧室里的事实，倍率不能参与，
 * 所以那条继续用设备时钟。两条时间源各自的理由见 [GameClock] 与 QuietHours 的类注释。
 *
 * 开章时**不注入** `meta.start_state`：那是后门跳章的作弊（不注入会让 needs 全线不满足）。
 * 自然跨章保留玩家自己攒出来的三度量，只按"现实过了几天"扣 integrity——
 * docs/02 第五节规则 2：玩家拖着不玩，她就提前散架。
 */
object ChapterGate {

    /** 章间闹钟的 ref 前缀（与等待、`post-`、`yearly-` 并列，见 [cn.linan.backingup.sched.WakeGate]） */
    const val REF_PREFIX = "chapter-"

    /** 每跨过一个真实日，integrity -3（docs/01 第五节衰减表） */
    const val INTEGRITY_DECAY_PER_DAY = 3.0

    /** 最后一章没有"下一章"，不许从它跨出去。跟着剧本仓库走，不另写一个 7 */
    val LAST_CHAPTER: Int = ScriptRepository.CHAPTER_COUNT

    fun refFor(nextChapter: Int): String = "$REF_PREFIX$nextChapter"

    fun chapterOfRef(ref: String): Int? =
        if (ref.startsWith(REF_PREFIX)) ref.removePrefix(REF_PREFIX).toIntOrNull() else null

    /**
     * 章末之后的开章时刻：**次日** 07:30（游戏时钟所指向的那个本地时刻）。
     *
     * 取"次日"而不是"下一个 07:30"：凌晨两点把一章读完，不该在五小时半后就看见她开新的一天。
     * 一天就是一天，这是这个作品最贵的一层设定，不该被"就近取整"磨掉。
     */
    fun openingAt(endAtGameTime: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        dateOf(endAtGameTime, zone).plusDays(1)
            .atTime(QuietHours.END_MINUTE_OF_DAY / 60, QuietHours.END_MINUTE_OF_DAY % 60)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    /**
     * 从章末到现在跨过了几个真实日（按本地日历日，不是 24 小时取整）。
     *
     * 玩家三天没打开游戏，她就在里面独自过了三天：`openingAt` 保证至少 1，
     * 这里再按日历日补上迟到的那几天。
     */
    fun daysElapsed(endAtGameTime: Long, nowGameTime: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
        ChronoUnit.DAYS.between(dateOf(endAtGameTime, zone), dateOf(nowGameTime, zone))
            .coerceAtLeast(1L)
            .toInt()

    private fun dateOf(millis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
