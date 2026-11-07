package com.tenderxiamu.eyecaretv

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 没有悬浮窗权限时的降级提醒页，由全屏通知拉起。
 */
class ReminderActivity : Activity() {

    private var reminderView: ReminderView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        val accumulated = intent.getLongExtra(EXTRA_ACCUMULATED_MS, store.accumulatedMs)
        val allowSnooze = intent.getBooleanExtra(EXTRA_ALLOW_SNOOZE, !store.snoozeUsed)
        val spec = ReminderSpec(
            accumulatedMs = accumulated,
            allowSnooze = allowSnooze,
            snoozeMinutes = store.snoozeMinutes,
            volume = store.volume,
            customVideoPath = store.videoPath,
        )
        val view = ReminderView(this, spec).apply {
            onFinished = { close(finished = true) }
            onSnooze = { close(finished = false) }
        }
        reminderView = view
        setContentView(view)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        reminderView?.start()
    }

    @Deprecated("提醒期间不允许返回")
    override fun onBackPressed() = Unit

    private fun close(finished: Boolean) {
        val action = if (finished) {
            EyeCareService.ACTION_REMINDER_FINISHED
        } else {
            EyeCareService.ACTION_REMINDER_SNOOZED
        }
        runCatching {
            startService(
                android.content.Intent(this, EyeCareService::class.java).setAction(action),
            )
        }
        finish()
    }

    companion object {
        const val EXTRA_ACCUMULATED_MS = "accumulated_ms"
        const val EXTRA_ALLOW_SNOOZE = "allow_snooze"
    }
}
