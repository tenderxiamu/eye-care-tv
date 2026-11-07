package com.tenderxiamu.eyecaretv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.math.ceil

/**
 * 常驻前台服务：亮屏且解锁时累计使用时长，到点弹出全屏提醒。
 */
class EyeCareService : Service() {

    private lateinit var store: ConfigStore
    private lateinit var engine: TimerEngine
    private lateinit var windowManager: WindowManager
    private lateinit var powerManager: android.os.PowerManager
    private lateinit var keyguardManager: android.app.KeyguardManager

    private var reminderView: ReminderView? = null
    private var screenOn = true
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, SAMPLE_INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    reminderView?.resumePlayback()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    reminderView?.pausePlayback()
                }
                Intent.ACTION_USER_PRESENT -> screenOn = true
            }
            tick()
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = ConfigStore(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
        keyguardManager = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
        screenOn = powerManager.isInteractive
        engine = TimerEngine().apply {
            restore(store.accumulatedMs, store.snoozeUsed, store.snoozeDeadline)
        }

        createChannels()
        startForegroundCompat(buildTimerNotification(store.toConfig()))

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!store.enabled && intent?.action != ACTION_FORCE_REMIND) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_FORCE_REMIND -> forceRemind()
            ACTION_REMINDER_FINISHED -> finishReminder()
            ACTION_REMINDER_SNOOZED -> snoozeReminder()
            else -> tick()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        removeOverlay()
        persist()
        cancelReminderNotification()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 计时 ----

    private fun tick() {
        if (!store.enabled) {
            stopSelf()
            return
        }
        val config = store.toConfig()
        val active = screenOn && powerManager.isInteractive && !keyguardManager.isKeyguardLocked
        val remind = engine.tick(active, config)
        persist()
        if (remind && reminderView == null) showReminder(config)
        notifyTimer(config)
    }

    private fun forceRemind() {
        if (reminderView != null) return
        engine.forceRemind()
        persist()
        showReminder(store.toConfig())
        notifyTimer(store.toConfig())
    }

    private fun persist() {
        store.accumulatedMs = engine.accumulatedMs
        store.snoozeUsed = engine.snoozeUsed
        store.snoozeDeadline = engine.snoozeDeadlineMillis()
    }

    // ---- 提醒 ----

    private fun showReminder(config: ReminderConfig) {
        val spec = ReminderSpec(
            accumulatedMs = engine.accumulatedMs,
            allowSnooze = !engine.snoozeUsed,
            snoozeMinutes = config.snoozeMinutes,
            volume = store.volume,
            customVideoPath = store.videoPath,
        )
        if (!Settings.canDrawOverlays(this)) {
            showFallbackNotification(spec)
            return
        }
        val view = ReminderView(this, spec).apply {
            onFinished = { finishReminder() }
            onSnooze = { snoozeReminder() }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = getString(R.string.reminder_window_title)
            // 提醒期间强制横屏，视频按横屏比例展示
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // 不做导航栏避让，悬浮窗铺满全屏
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        try {
            windowManager.addView(view, params)
            reminderView = view
            view.start()
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗创建失败", e)
            showFallbackNotification(spec)
        }
    }

    private fun finishReminder() {
        removeOverlay()
        engine.reminderFinished()
        persist()
        cancelReminderNotification()
        notifyTimer(store.toConfig())
    }

    private fun snoozeReminder() {
        removeOverlay()
        val config = store.toConfig()
        if (engine.snooze(config)) {
            persist()
        } else {
            engine.reminderFinished()
            persist()
        }
        cancelReminderNotification()
        notifyTimer(config)
    }

    private fun removeOverlay() {
        reminderView?.let { view ->
            runCatching { windowManager.removeViewImmediate(view) }
        }
        reminderView = null
    }

    /** 没有悬浮窗权限时，退化为带全屏意图的高优先级通知。 */
    private fun showFallbackNotification(spec: ReminderSpec) {
        val intent = Intent(this, ReminderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(ReminderActivity.EXTRA_ACCUMULATED_MS, spec.accumulatedMs)
            putExtra(ReminderActivity.EXTRA_ALLOW_SNOOZE, spec.allowSnooze)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_REMINDER,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val overlayIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val overlayPending = PendingIntent.getActivity(
            this,
            REQUEST_OVERLAY_SETTINGS,
            overlayIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = getString(
            if (spec.accumulatedMs >= 60_000L) R.string.reminder_notification_text_used else R.string.reminder_notification_text,
            (spec.accumulatedMs / 60_000L).toString(),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_REMINDER)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(getString(R.string.reminder_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true)
            .addAction(0, getString(R.string.action_grant_overlay), overlayPending)
            .build()
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_REMINDER_ID, notification)
        }
    }

    private fun cancelReminderNotification() {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_REMINDER_ID)
    }

    // ---- 通知 ----

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TIMER,
                getString(R.string.channel_timer),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_timer_desc)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                getString(R.string.channel_reminder),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.channel_reminder_desc)
            },
        )
    }

    private fun buildTimerNotification(config: ReminderConfig): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            REQUEST_SETTINGS,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val restIntent = PendingIntent.getService(
            this,
            REQUEST_FORCE_REMIND,
            Intent(this, EyeCareService::class.java).setAction(ACTION_FORCE_REMIND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(timerText(config))
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(0, getString(R.string.action_rest_now), restIntent)
            .build()
    }

    private fun notifyTimer(config: ReminderConfig) {
        if (NotificationManagerCompat.from(this).areNotificationsEnabled().not()) return
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_TIMER_ID, buildTimerNotification(config))
        }
    }

    private fun timerText(config: ReminderConfig): String {
        val accumulatedMinutes = engine.accumulatedMs / 60_000L
        return when (engine.state) {
            TimerEngine.State.PENDING -> getString(R.string.status_pending)
            TimerEngine.State.SNOOZED -> getString(R.string.status_snoozed)
            TimerEngine.State.REMINDING -> getString(R.string.status_reminding)
            TimerEngine.State.PAUSED -> getString(R.string.status_paused)
            TimerEngine.State.TRACKING -> {
                val remaining = (config.intervalMs - engine.accumulatedMs).coerceAtLeast(0L)
                getString(
                    R.string.status_tracking,
                    ceil(remaining / 60_000.0).toInt(),
                    accumulatedMinutes,
                )
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_TIMER_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_TIMER_ID, notification)
        }
    }

    companion object {
        private const val TAG = "EyeCareService"
        private const val CHANNEL_TIMER = "eyecare_timer"
        private const val CHANNEL_REMINDER = "eyecare_reminder"
        private const val NOTIFICATION_TIMER_ID = 1001
        private const val NOTIFICATION_REMINDER_ID = 1002
        private const val REQUEST_SETTINGS = 2001
        private const val REQUEST_FORCE_REMIND = 2002
        private const val REQUEST_REMINDER = 2003
        private const val REQUEST_OVERLAY_SETTINGS = 2004
        private const val SAMPLE_INTERVAL_MS = 30_000L

        const val ACTION_START = "com.tenderxiamu.eyecaretv.action.START"
        const val ACTION_FORCE_REMIND = "com.tenderxiamu.eyecaretv.action.FORCE_REMIND"
        const val ACTION_REMINDER_FINISHED = "com.tenderxiamu.eyecaretv.action.REMINDER_FINISHED"
        const val ACTION_REMINDER_SNOOZED = "com.tenderxiamu.eyecaretv.action.REMINDER_SNOOZED"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EyeCareService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EyeCareService::class.java))
        }

        fun forceRemind(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EyeCareService::class.java).setAction(ACTION_FORCE_REMIND),
            )
        }

        fun notifyReminderFinished(context: Context) {
            context.startService(
                Intent(context, EyeCareService::class.java).setAction(ACTION_REMINDER_FINISHED),
            )
        }

        fun notifyReminderSnoozed(context: Context) {
            context.startService(
                Intent(context, EyeCareService::class.java).setAction(ACTION_REMINDER_SNOOZED),
            )
        }
    }
}
