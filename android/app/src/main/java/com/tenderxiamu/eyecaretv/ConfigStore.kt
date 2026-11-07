package com.tenderxiamu.eyecaretv

import android.content.Context
import android.content.SharedPreferences

/**
 * 所有可调参数与计时进度都保存在 SharedPreferences，
 * 服务被系统回收后能接着计时。
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var intervalMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, DEFAULT_INTERVAL).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        set(value) = prefs.edit()
            .putInt(KEY_INTERVAL, value.coerceIn(MIN_INTERVAL, MAX_INTERVAL))
            .apply()

    var snoozeMinutes: Int
        get() = prefs.getInt(KEY_SNOOZE, DEFAULT_SNOOZE).coerceIn(1, 30)
        set(value) = prefs.edit().putInt(KEY_SNOOZE, value.coerceIn(1, 30)).apply()

    var volume: Int
        get() = prefs.getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)
        set(value) = prefs.edit().putInt(KEY_VOLUME, value.coerceIn(0, 100)).apply()

    /** 自定义视频的本地副本路径，空字符串表示使用内置视频。 */
    var videoPath: String
        get() = prefs.getString(KEY_VIDEO, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VIDEO, value).apply()

    var dndEnabled: Boolean
        get() = prefs.getBoolean(KEY_DND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_DND_ENABLED, value).apply()

    var dndStart: String
        get() = prefs.getString(KEY_DND_START, DEFAULT_DND_START) ?: DEFAULT_DND_START
        set(value) = prefs.edit().putString(KEY_DND_START, value).apply()

    var dndEnd: String
        get() = prefs.getString(KEY_DND_END, DEFAULT_DND_END) ?: DEFAULT_DND_END
        set(value) = prefs.edit().putString(KEY_DND_END, value).apply()

    // ---- 运行时状态，用于服务重启后恢复 ----
    var accumulatedMs: Long
        get() = prefs.getLong(KEY_ACCUMULATED, 0L)
        set(value) = prefs.edit().putLong(KEY_ACCUMULATED, value.coerceAtLeast(0L)).apply()

    var snoozeUsed: Boolean
        get() = prefs.getBoolean(KEY_SNOOZE_USED, false)
        set(value) = prefs.edit().putBoolean(KEY_SNOOZE_USED, value).apply()

    var snoozeDeadline: Long
        get() = prefs.getLong(KEY_SNOOZE_DEADLINE, 0L)
        set(value) = prefs.edit().putLong(KEY_SNOOZE_DEADLINE, value).apply()

    fun toConfig(): ReminderConfig = ReminderConfig(
        intervalMinutes = intervalMinutes,
        snoozeMinutes = snoozeMinutes,
        dndEnabled = dndEnabled,
        dndStart = dndStart,
        dndEnd = dndEnd,
    )

    companion object {
        const val MIN_INTERVAL = 5
        const val MAX_INTERVAL = 180
        const val DEFAULT_INTERVAL = 30
        const val DEFAULT_SNOOZE = 5
        const val DEFAULT_VOLUME = 50
        const val DEFAULT_DND_START = "12:00"
        const val DEFAULT_DND_END = "13:00"

        private const val PREFS_NAME = "eyecare"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_INTERVAL = "interval_minutes"
        private const val KEY_SNOOZE = "snooze_minutes"
        private const val KEY_VOLUME = "volume"
        private const val KEY_VIDEO = "video_path"
        private const val KEY_DND_ENABLED = "dnd_enabled"
        private const val KEY_DND_START = "dnd_start"
        private const val KEY_DND_END = "dnd_end"
        private const val KEY_ACCUMULATED = "accumulated_ms"
        private const val KEY_SNOOZE_USED = "snooze_used"
        private const val KEY_SNOOZE_DEADLINE = "snooze_deadline"
    }
}
