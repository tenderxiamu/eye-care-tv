package com.tenderxiamu.eyecaretv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import java.io.File
import kotlin.math.ceil

class MainActivity : Activity() {

    private lateinit var store: ConfigStore

    private lateinit var switchEnabled: Switch
    private lateinit var switchDnd: Switch
    private lateinit var switchAudio: Switch
    private lateinit var ringProgress: RingProgressView
    private lateinit var textStatus: TextView
    private lateinit var textNext: TextView
    private lateinit var textPermNotification: TextView
    private lateinit var textPermOverlay: TextView
    private lateinit var textPermBattery: TextView
    private lateinit var textInterval: TextView
    private lateinit var seekInterval: BubbleSeekBar
    private lateinit var textVolume: TextView
    private lateinit var seekVolume: BubbleSeekBar
    private lateinit var textSnooze: TextView
    private lateinit var textVideo: TextView
    private lateinit var textWhitelist: TextView
    private lateinit var textStatToday: TextView
    private lateinit var textStatWeek: TextView
    private lateinit var textStatWatch: TextView
    private lateinit var textDndStart: TextView
    private lateinit var textDndEnd: TextView

    private var suppressEnabledListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ConfigStore(this)
        setContentView(R.layout.activity_main)
        bindViews()
        setupSettings()
        setupPermissions()
        setupActions()
        maybeShowOnboarding()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (store.enabled) {
            runCatching { EyeCareService.start(this) }
                .onFailure { toast(getString(R.string.toast_service_failed)) }
        }
    }

    private fun bindViews() {
        switchEnabled = findViewById(R.id.switch_enabled)
        switchDnd = findViewById(R.id.switch_dnd)
        switchAudio = findViewById(R.id.switch_audio)
        ringProgress = findViewById(R.id.ring_progress)
        textStatus = findViewById(R.id.text_status)
        textNext = findViewById(R.id.text_next)
        textPermNotification = findViewById(R.id.text_perm_notification)
        textPermOverlay = findViewById(R.id.text_perm_overlay)
        textPermBattery = findViewById(R.id.text_perm_battery)
        textInterval = findViewById(R.id.text_interval)
        seekInterval = findViewById(R.id.seek_interval)
        textVolume = findViewById(R.id.text_volume)
        seekVolume = findViewById(R.id.seek_volume)
        textSnooze = findViewById(R.id.text_snooze)
        textVideo = findViewById(R.id.text_video)
        textWhitelist = findViewById(R.id.text_whitelist)
        textStatToday = findViewById(R.id.text_stat_today)
        textStatWeek = findViewById(R.id.text_stat_week)
        textStatWatch = findViewById(R.id.text_stat_watch)
        textDndStart = findViewById(R.id.text_dnd_start)
        textDndEnd = findViewById(R.id.text_dnd_end)
    }

    private fun setupSettings() {
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (suppressEnabledListener) return@setOnCheckedChangeListener
            store.enabled = checked
            if (checked) {
                runCatching { EyeCareService.start(this) }
                    .onFailure { toast(getString(R.string.toast_service_failed)) }
            } else {
                EyeCareService.stop(this)
            }
            refresh()
        }

        seekInterval.max = (ConfigStore.MAX_INTERVAL - ConfigStore.MIN_INTERVAL) / STEP_MINUTES
        seekInterval.progress = (store.intervalMinutes - ConfigStore.MIN_INTERVAL) / STEP_MINUTES
        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = ConfigStore.MIN_INTERVAL + progress * STEP_MINUTES
                textInterval.text = getString(R.string.duration_minutes, minutes)
                if (fromUser) {
                    store.intervalMinutes = minutes
                    // 拖动时环形进度实时跟随，避免松手才刷新
                    refreshStatus()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                refreshStatus()
            }
        })

        seekInterval.labelProvider = { progress ->
            getString(R.string.duration_minutes, ConfigStore.MIN_INTERVAL + progress * STEP_MINUTES)
        }

        seekVolume.progress = store.volume
        seekVolume.labelProvider = { progress -> "$progress%" }
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textVolume.text = "$progress%"
                if (fromUser) store.volume = progress
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        switchDnd.setOnCheckedChangeListener { _, checked ->
            store.dndEnabled = checked
            refreshDnd()
        }

        findViewById<android.view.View>(R.id.row_dnd_start).setOnClickListener {
            pickTime(store.dndStart) { store.dndStart = it; refresh() }
        }
        findViewById<android.view.View>(R.id.row_dnd_end).setOnClickListener {
            pickTime(store.dndEnd) { store.dndEnd = it; refresh() }
        }

        switchAudio.setOnCheckedChangeListener { _, checked ->
            store.audioOnly = checked
        }

        findViewById<View>(R.id.row_whitelist).setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        findViewById<View>(R.id.row_rom_guide).setOnClickListener { showRomGuide() }

        findViewById<TextView>(R.id.btn_pick_video).setOnClickListener { pickVideo() }
        findViewById<TextView>(R.id.btn_reset_video).setOnClickListener {
            store.videoPath = ""
            File(filesDir, CUSTOM_VIDEO_NAME).delete()
            refresh()
        }
    }

    private fun setupPermissions() {
        findViewById<TextView>(R.id.btn_perm_notification).setOnClickListener {
            requestNotificationPermission()
        }

        findViewById<TextView>(R.id.btn_perm_overlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            openSafely(intent)
        }

        findViewById<TextView>(R.id.btn_perm_battery).setOnClickListener {
            openBatterySettings()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(intent) }
            .onFailure { openSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun setupActions() {
        findViewById<TextView>(R.id.btn_rest_now).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.confirm_rest_title)
                .setMessage(R.string.confirm_rest_message)
                .setPositiveButton(R.string.btn_confirm) { _, _ ->
                    runCatching { EyeCareService.forceRemind(this) }
                        .onFailure { toast(getString(R.string.toast_service_failed)) }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    /** 首次启动的三步授权向导，逐项跳转系统设置。 */
    private fun maybeShowOnboarding() {
        if (store.onboardingDone) return
        val steps = listOf(
            OnboardingStep(
                getString(R.string.label_notification_permission),
                getString(R.string.onboarding_notification_desc),
            ) { requestNotificationPermission() },
            OnboardingStep(
                getString(R.string.label_overlay_permission),
                getString(R.string.onboarding_overlay_desc),
            ) {
                openSafely(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            },
            OnboardingStep(
                getString(R.string.label_battery_permission),
                getString(R.string.onboarding_battery_desc),
            ) { openBatterySettings() },
        )
        showOnboardingStep(steps, 0)
    }

    private fun showOnboardingStep(steps: List<OnboardingStep>, index: Int) {
        if (index >= steps.size) {
            store.onboardingDone = true
            refreshPermissions()
            return
        }
        val step = steps[index]
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.onboarding_title, index + 1, steps.size, step.title))
            .setMessage(step.description)
            .setCancelable(false)
            .setPositiveButton(
                if (index == steps.lastIndex) R.string.onboarding_finish else R.string.btn_settings,
            ) { _, _ ->
                step.action()
                showOnboardingStep(steps, index + 1)
            }
            .setNegativeButton(R.string.btn_skip) { _, _ ->
                showOnboardingStep(steps, index + 1)
            }
            .show()
    }

    private fun showRomGuide() {
        val brand = Build.MANUFACTURER.lowercase()
        val text = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> R.string.rom_guide_xiaomi
            brand.contains("huawei") || brand.contains("honor") -> R.string.rom_guide_huawei
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                R.string.rom_guide_oppo
            brand.contains("vivo") || brand.contains("iqoo") -> R.string.rom_guide_vivo
            else -> R.string.rom_guide_generic
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rom_guide_title)
            .setMessage(getString(text))
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    private data class OnboardingStep(
        val title: String,
        val description: String,
        val action: () -> Unit,
    )

    private fun refresh() {
        suppressEnabledListener = true
        switchEnabled.isChecked = store.enabled
        suppressEnabledListener = false

        switchDnd.isChecked = store.dndEnabled
        switchAudio.isChecked = store.audioOnly
        textInterval.text = getString(R.string.duration_minutes, store.intervalMinutes)
        seekInterval.progress = (store.intervalMinutes - ConfigStore.MIN_INTERVAL) / STEP_MINUTES
        textVolume.text = "${store.volume}%"
        seekVolume.progress = store.volume
        textSnooze.text = getString(R.string.snooze_fixed, store.snoozeMinutes)
        textDndStart.text = store.dndStart
        textDndEnd.text = store.dndEnd

        refreshStatus()
        refreshPermissions()
        refreshVideo()
        refreshDnd()
        refreshWhitelist()
        refreshStats()
    }

    private fun refreshStatus() {
        if (!store.enabled) {
            textStatus.text = getString(R.string.status_disabled)
            textNext.text = getString(R.string.status_start_hint)
            ringProgress.progress = 0f
            ringProgress.valueText = "—"
            ringProgress.unitText = getString(R.string.ring_disabled)
            return
        }
        val accumulatedMinutes = store.accumulatedMs / 60_000L
        textStatus.text = getString(R.string.status_accumulated, accumulatedMinutes)
        val config = store.toConfig()
        val remaining = (config.intervalMs - store.accumulatedMs).coerceAtLeast(0L)
        val remainingMinutes = ceil(remaining / 60_000.0).toInt()
        textNext.text = getString(R.string.status_next, remainingMinutes)

        ringProgress.progress = if (config.intervalMs > 0) {
            (store.accumulatedMs.toFloat() / config.intervalMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        ringProgress.valueText = remainingMinutes.toString()
        ringProgress.unitText = getString(R.string.ring_unit)
    }

    private fun refreshWhitelist() {
        val count = store.whitelist.size
        textWhitelist.text = if (count == 0) {
            getString(R.string.whitelist_value_empty)
        } else {
            getString(R.string.whitelist_value_count, count)
        }
    }

    private fun refreshStats() {
        val snapshot = StatsStore(this).snapshot()
        textStatToday.text = getString(R.string.stats_value, snapshot.todayBreaks)
        textStatWeek.text = getString(R.string.stats_value, snapshot.weekBreaks)
        textStatWatch.text = getString(
            R.string.stats_watch,
            (snapshot.weekWatchMs / 60_000L).toInt(),
        )
    }

    private fun refreshPermissions() {
        val notificationEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        textPermNotification.text = getString(
            if (notificationEnabled) R.string.perm_granted else R.string.perm_notification_missing,
        )
        findViewById<TextView>(R.id.btn_perm_notification).text = getString(
            if (notificationEnabled) R.string.perm_granted else R.string.btn_grant,
        )

        val overlayGranted = Settings.canDrawOverlays(this)
        textPermOverlay.text = getString(
            if (overlayGranted) R.string.perm_granted else R.string.perm_overlay_missing,
        )
        findViewById<TextView>(R.id.btn_perm_overlay).text = getString(
            if (overlayGranted) R.string.perm_granted else R.string.btn_grant,
        )

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val ignoring = powerManager.isIgnoringBatteryOptimizations(packageName)
        textPermBattery.text = getString(
            if (ignoring) R.string.perm_battery_granted else R.string.perm_battery_missing,
        )
        findViewById<TextView>(R.id.btn_perm_battery).text = getString(
            if (ignoring) R.string.perm_granted else R.string.btn_grant,
        )
    }

    private fun refreshVideo() {
        val custom = store.videoPath.isNotBlank() && File(store.videoPath).isFile
        textVideo.text = getString(if (custom) R.string.video_custom else R.string.video_builtin)
        findViewById<TextView>(R.id.btn_reset_video).visibility =
            if (custom) TextView.VISIBLE else TextView.GONE
    }

    private fun refreshDnd() {
        val enabled = store.dndEnabled
        textDndStart.isEnabled = enabled
        textDndEnd.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.45f
        findViewById<android.view.View>(R.id.row_dnd_start).alpha = alpha
        findViewById<android.view.View>(R.id.row_dnd_end).alpha = alpha
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        if (!store.dndEnabled) return
        val parts = current.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 12
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(
            this,
            { _, h, m ->
                onPicked(String.format("%02d:%02d", h, m))
            },
            hour,
            minute,
            true,
        ).show()
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_VIDEO) }
            .onFailure { toast(getString(R.string.toast_video_failed)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val target = File(filesDir, CUSTOM_VIDEO_NAME)
        Thread {
            val saved = runCatching {
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            runOnUiThread {
                if (saved) {
                    store.videoPath = target.absolutePath
                    toast(getString(R.string.toast_video_saved))
                    refreshVideo()
                } else {
                    target.delete()
                    toast(getString(R.string.toast_video_failed))
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        refreshPermissions()
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        openSafely(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun openSafely(intent: Intent) {
        runCatching { startActivity(intent) }
            .onFailure { toast(getString(R.string.toast_service_failed)) }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val STEP_MINUTES = 5
        private const val REQUEST_NOTIFICATIONS = 3101
        private const val REQUEST_VIDEO = 3102
        private const val CUSTOM_VIDEO_NAME = "custom_reminder.mp4"
    }
}
