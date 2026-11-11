package com.tenderxiamu.eyecaretv

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

data class ReminderSpec(
    val accumulatedMs: Long,
    val allowSnooze: Boolean,
    val snoozeMinutes: Int,
    val volume: Int,
    val customVideoPath: String,
    val audioOnly: Boolean = false,
)

/**
 * 全屏提醒视图，悬浮窗与降级 Activity 共用一套 UI。
 */
class ReminderView(
    context: Context,
    private val spec: ReminderSpec,
) : FrameLayout(context) {

    var onFinished: ((watchedMs: Long) -> Unit)? = null
    var onSnooze: (() -> Unit)? = null

    private val playerView = VideoPlayerView(context)
    private val hintView = TextView(context).apply {
        text = context.getString(R.string.reminder_preparing)
        setTextColor(COLOR_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }
    private val handler = Handler(Looper.getMainLooper())
    private var finished = false
    private var startedOnce = false
    private var fallbackMode = false
    private var fallbackLeft = FALLBACK_SECONDS
    private var layoutOrientation = Configuration.ORIENTATION_UNDEFINED
    private var watchedMs = 0L

    /** 长按 3 秒后跳过本次提醒，给抢票、看急救信息等紧急场景留一条通道。 */
    private val skipRunnable = Runnable { skipNow() }

    private val progressTicker = object : Runnable {
        override fun run() {
            if (finished) return
            updateProgress()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackground(
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(COLOR_BACKGROUND, COLOR_BACKGROUND_BOTTOM),
            ),
        )
        buildContent(resources.configuration.orientation)
    }

    /** 窗口旋转后重建布局，并从断点续播。 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val orientation = resources.configuration.orientation
        if (orientation == Configuration.ORIENTATION_UNDEFINED || orientation == layoutOrientation) return
        val resumePosition = if (startedOnce && !finished && !fallbackMode) {
            playerView.positionMs().coerceAtLeast(0)
        } else {
            0
        }
        buildContent(orientation)
        if (resumePosition > 0) {
            resolveSource()?.let {
                playerView.play(it, spec.volume, resumePosition, spec.audioOnly)
            }
        }
    }

    private fun buildContent(orientation: Int) {
        layoutOrientation = orientation
        removeAllViews()
        (playerView.parent as? ViewGroup)?.removeView(playerView)
        (hintView.parent as? ViewGroup)?.removeView(hintView)
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            buildLandscape()
        } else {
            buildPortrait()
        }
    }

    private fun buildPortrait() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(44), dp(28), dp(32))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        content.addView(titleView(28f))
        content.addView(messageView(15f).apply { setPadding(0, dp(12), 0, dp(24)) })

        content.addView(
            if (spec.audioOnly) {
                audioPlaceholder()
            } else {
                playerView.visibility = View.VISIBLE
                playerView
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
        )

        content.addView(
            hintView.apply {
                setPadding(0, dp(16), 0, dp(8))
            },
        )

        if (spec.allowSnooze) {
            content.addView(
                snoozeButton(),
                LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT,
                    LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        content.addView(
            skipView(),
            LinearLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )
    }

    /** 横屏紧凑布局：顶部标题行、中间视频、底部进度与延后。 */
    private fun buildLandscape() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(top, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        top.addView(titleView(18f))
        top.addView(
            messageView(12f).apply {
                maxLines = 2
                setPadding(dp(16), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        content.addView(
            if (spec.audioOnly) {
                audioPlaceholder()
            } else {
                playerView.visibility = View.VISIBLE
                playerView
            },
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(8)
                bottomMargin = dp(8)
            },
        )

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        content.addView(bottom, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bottom.addView(
            hintView,
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        bottom.addView(skipView())
        if (spec.allowSnooze) {
            bottom.addView(snoozeButton())
        }
    }

    /** 纯音频模式下的占位区，避免整屏只剩黑底。 */
    private fun audioPlaceholder() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(
            TextView(context).apply {
                text = context.getString(R.string.audio_mode_title)
                setTextColor(COLOR_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
            },
        )
        addView(
            TextView(context).apply {
                text = context.getString(R.string.audio_mode_tip)
                setTextColor(COLOR_MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            },
        )
    }

    private fun titleView(sizeSp: Float) = TextView(context).apply {
        text = context.getString(R.string.reminder_title)
        setTextColor(COLOR_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }

    private fun messageView(sizeSp: Float) = TextView(context).apply {
        text = buildMessage()
        setTextColor(COLOR_MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setLineSpacing(dp(4).toFloat(), 1.2f)
    }

    private fun snoozeButton() = TextView(context).apply {
        text = context.getString(R.string.reminder_snooze, spec.snoozeMinutes)
        setTextColor(COLOR_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(10), dp(20), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(COLOR_BUTTON_BG)
            setStroke(dp(1), COLOR_BUTTON_STROKE)
        }
        isClickable = true
        setOnClickListener { snooze() }
    }

    /**
     * 不显眼的逃生入口：按住 3 秒跳过，松手即取消，
     * 既能应对紧急情况，也不会导致随手一点就跳过。
     */
    private fun skipView() = TextView(context).apply {
        text = context.getString(R.string.reminder_skip_hint)
        setTextColor(COLOR_SKIP)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    (view as TextView).text = context.getString(R.string.reminder_skip_holding)
                    handler.postDelayed(skipRunnable, SKIP_HOLD_MS)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(skipRunnable)
                    (view as TextView).text = context.getString(R.string.reminder_skip_hint)
                    true
                }

                else -> true
            }
        }
    }

    /** 视图挂到窗口后调用，开始播放。 */
    fun start() {
        if (startedOnce) return
        startedOnce = true
        requestFocus()
        val source = resolveSource()
        if (source == null) {
            enterFallback()
        } else {
            playerView.onEnded = { finish() }
            playerView.onError = { enterFallback() }
            playerView.play(source, spec.volume, audioOnly = spec.audioOnly)
            handler.postDelayed(progressTicker, 1000)
            handler.postDelayed({
                if (!finished && !fallbackMode && !playerView.isStarted()) enterFallback()
            }, START_TIMEOUT_MS)
        }
    }

    fun pausePlayback() {
        playerView.pausePlayback()
    }

    fun resumePlayback() {
        if (startedOnce && !finished) playerView.resumePlayback()
    }

    private fun resolveSource(): VideoPlayerView.Source? {
        val custom = spec.customVideoPath
        if (custom.isNotBlank() && File(custom).isFile) {
            return VideoPlayerView.Source.File(custom)
        }
        val resId = resources.getIdentifier(RAW_VIDEO_NAME, "raw", context.packageName)
        return if (resId != 0) VideoPlayerView.Source.Raw(resId) else null
    }

    private fun enterFallback() {
        if (finished || fallbackMode) return
        fallbackMode = true
        playerView.release()
        playerView.visibility = View.GONE
        fallbackLeft = FALLBACK_SECONDS
        hintView.text = context.getString(R.string.reminder_fallback, clockText(fallbackLeft))
    }

    private fun updateProgress() {
        if (fallbackMode) {
            fallbackLeft -= 1
            if (fallbackLeft <= 0) {
                finish()
                return
            }
            hintView.text = context.getString(R.string.reminder_fallback, clockText(fallbackLeft))
            return
        }
        val duration = playerView.durationMs()
        val position = playerView.positionMs()
        if (duration > 0 && position >= 0) {
            hintView.text = context.getString(
                R.string.reminder_playing,
                clockText(((duration - position) / 1000).coerceAtLeast(0)),
            )
        }
    }

    private fun snooze() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        playerView.release()
        onSnooze?.invoke()
    }

    private fun finish() {
        if (finished) return
        finished = true
        watchedMs = if (fallbackMode) {
            (FALLBACK_SECONDS - fallbackLeft).coerceAtLeast(0).toLong() * 1000L
        } else {
            playerView.positionMs().coerceAtLeast(0).toLong()
        }
        handler.removeCallbacksAndMessages(null)
        playerView.release()
        onFinished?.invoke(watchedMs)
    }

    /** 长按满 3 秒：跳过本次提醒，按已观看时长计入统计。 */
    private fun skipNow() {
        finish()
    }

    private fun buildMessage(): String {
        val duration = formatDuration(spec.accumulatedMs)
        return if (duration != null) {
            context.getString(R.string.reminder_message, duration)
        } else {
            context.getString(R.string.reminder_message_short)
        }
    }

    private fun formatDuration(ms: Long): String? {
        if (ms < 60_000L) return null
        val totalMinutes = ms / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0L -> context.getString(R.string.duration_hours, hours, minutes)
            else -> context.getString(R.string.duration_minutes, minutes)
        }
    }

    private fun clockText(seconds: Int): String {
        val minutes = seconds / 60
        val rest = seconds % 60
        return String.format("%02d:%02d", minutes, rest)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** 提醒期间吞掉返回键，避免随手退出；音量键等仍然可用。 */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> true
            else -> super.dispatchKeyEvent(event)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        playerView.release()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val RAW_VIDEO_NAME = "default_eye_exercise"
        private const val FALLBACK_SECONDS = 300
        private const val START_TIMEOUT_MS = 8000L
        private const val SKIP_HOLD_MS = 3000L

        private val COLOR_BACKGROUND = Color.parseColor("#060A0B")
        private val COLOR_BACKGROUND_BOTTOM = Color.parseColor("#0D1B1C")
        private val COLOR_TEXT = Color.parseColor("#F3F8F6")
        private val COLOR_MUTED = Color.parseColor("#94A5A8")
        private val COLOR_BUTTON_BG = Color.parseColor("#1C2A2B")
        private val COLOR_BUTTON_STROKE = Color.parseColor("#3A4C4D")
        private val COLOR_SKIP = Color.parseColor("#5F7173")
        private val COLOR_ACCENT = Color.parseColor("#6FE3C4")
    }
}
