package com.tenderxiamu.eyecaretv

import java.util.Calendar

/**
 * 纯 JVM 配置对象，便于单元测试复用。
 */
data class ReminderConfig(
    val intervalMinutes: Int = 30,
    val snoozeMinutes: Int = 5,
    val dndEnabled: Boolean = false,
    val dndStart: String = "12:00",
    val dndEnd: String = "13:00",
) {
    val intervalMs: Long get() = intervalMinutes.toLong() * 60_000L
    val snoozeMs: Long get() = snoozeMinutes.toLong() * 60_000L

    /** 免打扰时段判断，支持跨午夜（如 22:30–07:00）。 */
    fun isDndActive(nowMillis: Long): Boolean {
        if (!dndEnabled) return false
        val start = minutesOfDay(dndStart) ?: return false
        val end = minutesOfDay(dndEnd) ?: return false
        if (start == end) return false
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMillis
        val now = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return if (start < end) now >= start && now < end else now >= start || now < end
    }

    private fun minutesOfDay(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
}

/**
 * 与 Windows 端一致的计时状态机：
 * TRACKING 计时中 / PAUSED 暂停（息屏、锁屏）/ PENDING 免打扰挂起 /
 * REMINDING 提醒中 / SNOOZED 延后等待。
 */
class TimerEngine(private val clock: () -> Long = { System.currentTimeMillis() }) {

    enum class State { TRACKING, PAUSED, PENDING, REMINDING, SNOOZED }

    var state: State = State.TRACKING
        private set

    var accumulatedMs: Long = 0L
        private set

    var snoozeUsed: Boolean = false
        private set

    private var snoozeDeadline: Long = 0L
    private var lastTick: Long = clock()

    /** 服务重启后恢复累计时长，提醒中的状态不沿用，直接回到计时。 */
    fun restore(accumulatedMs: Long, snoozeUsed: Boolean, snoozeDeadline: Long) {
        this.accumulatedMs = accumulatedMs.coerceAtLeast(0L)
        this.snoozeUsed = snoozeUsed
        this.snoozeDeadline = snoozeDeadline
        this.lastTick = clock()
        this.state = State.TRACKING
    }

    /**
     * 每次采样调用一次。返回 true 表示此刻需要弹出提醒。
     */
    fun tick(
        screenActive: Boolean,
        config: ReminderConfig,
        now: Long = clock(),
    ): Boolean {
        val delta = (now - lastTick).coerceIn(0L, SAMPLE_CLAMP_MS)
        lastTick = now

        when (state) {
            State.TRACKING -> {
                if (!screenActive) {
                    state = State.PAUSED
                    return false
                }
                accumulatedMs += delta
                if (accumulatedMs >= config.intervalMs) {
                    if (config.isDndActive(now)) {
                        state = State.PENDING
                    } else {
                        return trigger()
                    }
                }
            }

            State.PAUSED -> if (screenActive) {
                state = State.TRACKING
            }

            State.PENDING -> if (screenActive && !config.isDndActive(now)) {
                return trigger()
            }

            State.SNOOZED -> if (screenActive && now >= snoozeDeadline) {
                return trigger()
            }

            State.REMINDING -> Unit
        }
        return false
    }

    /** 仅第一次提醒可延后。 */
    fun snooze(config: ReminderConfig): Boolean {
        if (state != State.REMINDING || snoozeUsed) return false
        snoozeUsed = true
        snoozeDeadline = clock() + config.snoozeMs
        state = State.SNOOZED
        return true
    }

    /** 视频播放结束或提醒被关闭：累计清零，重新开始计时。 */
    fun reminderFinished() {
        accumulatedMs = 0L
        snoozeUsed = false
        snoozeDeadline = 0L
        state = State.TRACKING
        lastTick = clock()
    }

    /** 立即提醒，返回 true 表示本次仍允许延后。 */
    fun forceRemind(): Boolean {
        state = State.REMINDING
        return !snoozeUsed
    }

    fun snoozeDeadlineMillis(): Long = snoozeDeadline

    internal fun trigger(): Boolean {
        state = State.REMINDING
        return true
    }

    companion object {
        /** 息屏、休眠后时间差可能极大，钳制避免一次性跳过阈值。 */
        const val SAMPLE_CLAMP_MS = 31_000L
    }
}
