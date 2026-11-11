package com.tenderxiamu.eyecaretv

import android.content.Context
import java.util.Calendar
import java.util.Locale

/**
 * 本地护眼统计：今日 / 本周的打断次数与观看时长。
 * 只写在 SharedPreferences，不做任何上传。
 */
class StatsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Snapshot(
        val todayBreaks: Int,
        val todayWatchMs: Long,
        val weekBreaks: Int,
        val weekWatchMs: Long,
    )

    fun recordBreak(watchedMs: Long) {
        val now = System.currentTimeMillis()
        rollIfNeeded(now)
        val watch = watchedMs.coerceAtLeast(0L)
        prefs.edit()
            .putInt(KEY_DAY_BREAKS, prefs.getInt(KEY_DAY_BREAKS, 0) + 1)
            .putLong(KEY_DAY_WATCH, prefs.getLong(KEY_DAY_WATCH, 0L) + watch)
            .putInt(KEY_WEEK_BREAKS, prefs.getInt(KEY_WEEK_BREAKS, 0) + 1)
            .putLong(KEY_WEEK_WATCH, prefs.getLong(KEY_WEEK_WATCH, 0L) + watch)
            .apply()
    }

    fun snapshot(): Snapshot {
        rollIfNeeded(System.currentTimeMillis())
        return Snapshot(
            todayBreaks = prefs.getInt(KEY_DAY_BREAKS, 0),
            todayWatchMs = prefs.getLong(KEY_DAY_WATCH, 0L),
            weekBreaks = prefs.getInt(KEY_WEEK_BREAKS, 0),
            weekWatchMs = prefs.getLong(KEY_WEEK_WATCH, 0L),
        )
    }

    /** 跨天 / 跨周时清零，避免旧数据混进新周期。 */
    private fun rollIfNeeded(now: Long) {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val dayKey = String.format(
            Locale.US,
            "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH),
        )
        val weekKey = String.format(
            Locale.US,
            "%04d-W%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.WEEK_OF_YEAR),
        )
        val editor = prefs.edit()
        var changed = false
        if (prefs.getString(KEY_DAY_KEY, "") != dayKey) {
            editor.putString(KEY_DAY_KEY, dayKey)
                .putInt(KEY_DAY_BREAKS, 0)
                .putLong(KEY_DAY_WATCH, 0L)
            changed = true
        }
        if (prefs.getString(KEY_WEEK_KEY, "") != weekKey) {
            editor.putString(KEY_WEEK_KEY, weekKey)
                .putInt(KEY_WEEK_BREAKS, 0)
                .putLong(KEY_WEEK_WATCH, 0L)
            changed = true
        }
        if (changed) editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "eyecare_stats"
        private const val KEY_DAY_KEY = "day_key"
        private const val KEY_WEEK_KEY = "week_key"
        private const val KEY_DAY_BREAKS = "day_breaks"
        private const val KEY_DAY_WATCH = "day_watch_ms"
        private const val KEY_WEEK_BREAKS = "week_breaks"
        private const val KEY_WEEK_WATCH = "week_watch_ms"
    }
}
