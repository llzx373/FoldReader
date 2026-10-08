package com.llzx373.foldreader.core.tts

/**
 * 睡眠定时档位（M26）：分钟档到点停队列并退出前台服务；
 * [CHAPTER_END] 与自然播完同口径（朗读范围本就截到章末），只多一条提示。
 */
enum class TtsSleepOption(val minutes: Int?) {
    OFF(null),
    MIN_15(15),
    MIN_30(30),
    MIN_45(45),
    MIN_60(60),
    CHAPTER_END(null),
}

/**
 * 睡眠定时的计时与文案纯逻辑（注入 nowMs 可测）。
 * 调度（Handler/协程）在 `ReaderTtsController` 里，这里只管算。
 */
object TtsSleepTimer {

    /** 分钟档的截止时刻；OFF / CHAPTER_END 没有截止时刻，返回 null。 */
    fun deadlineMs(option: TtsSleepOption, nowMs: Long): Long? =
        option.minutes?.let { nowMs + it * 60_000L }

    fun expired(deadlineMs: Long, nowMs: Long): Boolean = nowMs >= deadlineMs

    /** 剩余分钟（向上取整：刚设完 15 分钟档应显示 15，不是 14）。 */
    fun remainingMinutes(deadlineMs: Long, nowMs: Long): Int =
        (((deadlineMs - nowMs).coerceAtLeast(0L) + 59_999L) / 60_000L).toInt()

    /** 通知栏与阅读菜单共用的剩余时间文案；OFF 返回 null。 */
    fun remainingText(option: TtsSleepOption, deadlineMs: Long?, nowMs: Long): String? = when {
        option == TtsSleepOption.OFF -> null
        option == TtsSleepOption.CHAPTER_END -> "读完本章停止"
        deadlineMs == null -> null
        else -> "${remainingMinutes(deadlineMs, nowMs)} 分钟后停止"
    }
}
