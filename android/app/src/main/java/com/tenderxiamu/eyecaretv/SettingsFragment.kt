package com.tenderxiamu.eyecaretv

import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import java.io.File

/**
 * 设置页：权限 + 提醒配置 + 免打扰。
 * 原来全堆在 MainActivity，现在独立一个 Tab。
 */
class SettingsFragment : Fragment() {

    private var store: ConfigStore? = null

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
    private lateinit var textDndStart: TextView
    private lateinit var textDndEnd: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSettings(view)
        setupPermissions(view)
    }

    private fun bindViews(view: View) {
        textPermNotification = view.findViewById(R.id.text_perm_notification)
        textPermOverlay = view.findViewById(R.id.text_perm_overlay)
        textPermBattery = view.findViewById(R.id.text_perm_battery)
        textInterval = view.findViewById(R.id.text_interval)
        seekInterval = view.findViewById(R.id.seek_interval)
        textVolume = view.findViewById(R.id.text_volume)
        seekVolume = view.findViewById(R.id.seek_volume)
        textSnooze = view.findViewById(R.id.text_snooze)
        textVideo = view.findViewById(R.id.text_video)
        textWhitelist = view.findViewById(R.id.text_whitelist)
        textDndStart = view.findViewById(R.id.text_dnd_start)
        textDndEnd = view.findViewById(R.id.text_dnd_end)
    }

    private fun setupSettings(view: View) {
        val s = store ?: return

        seekInterval.max = (ConfigStore.MAX_INTERVAL - ConfigStore.MIN_INTERVAL) / STEP_MINUTES
        seekInterval.progress = (s.intervalMinutes - ConfigStore.MIN_INTERVAL) / STEP_MINUTES
        seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val minutes = ConfigStore.MIN_INTERVAL + progress * STEP_MINUTES
                textInterval.text = getString(R.string.duration_minutes, minutes)
                if (fromUser) {
                    store?.intervalMinutes = minutes
                    refreshStatus()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = refreshStatus()
        })
        seekInterval.labelProvider = { progress ->
            getString(R.string.duration_minutes, ConfigStore.MIN_INTERVAL + progress * STEP_MINUTES)
        }

        seekVolume.progress = s.volume
        seekVolume.labelProvider = { "$it%" }
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                textVolume.text = "$progress%"
                if (fromUser) store?.volume = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        view.findViewById<Switch>(R.id.switch_dnd).setOnCheckedChangeListener { _, checked ->
            store?.dndEnabled = checked
            refreshDnd()
        }
        view.findViewById<Switch>(R.id.switch_audio).setOnCheckedChangeListener { _, checked ->
            store?.audioOnly = checked
        }

        view.findViewById<View>(R.id.row_dnd_start).setOnClickListener {
            pickTime(store?.dndStart ?: "12:00") { store?.dndStart = it; refresh() }
        }
        view.findViewById<View>(R.id.row_dnd_end).setOnClickListener {
            pickTime(store?.dndEnd ?: "13:00") { store?.dndEnd = it; refresh() }
        }

        view.findViewById<View>(R.id.row_whitelist).setOnClickListener {
            startActivity(Intent(requireContext(), WhitelistActivity::class.java))
        }
        view.findViewById<View>(R.id.row_rom_guide).setOnClickListener { showRomGuide() }

        view.findViewById<TextView>(R.id.btn_pick_video).setOnClickListener { pickVideo() }
        view.findViewById<TextView>(R.id.btn_reset_video).setOnClickListener {
            store?.videoPath = ""
            File(requireContext().filesDir, MainActivity.CUSTOM_VIDEO_NAME).delete()
            refreshVideo()
        }
    }

    private fun setupPermissions(view: View) {
        view.findViewById<TextView>(R.id.btn_perm_notification).setOnClickListener {
            requestNotificationPermission()
        }
        view.findViewById<TextView>(R.id.btn_perm_overlay).setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${requireContext().packageName}"),
            )
            runCatching { startActivity(intent) }
        }
        view.findViewById<TextView>(R.id.btn_perm_battery).setOnClickListener { openBatterySettings() }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            requireContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun openBatterySettings() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${requireContext().packageName}"),
        )
        runCatching { startActivity(intent) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
    }

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName),
            )
        }
    }

    private fun showRomGuide() {
        val brand = Build.MANUFACTURER.lowercase()
        val text = when {
            brand.contains("xiaomi") || brand.contains("redmi") -> R.string.rom_guide_xiaomi
            brand.contains("huawei") || brand.contains("honor") -> R.string.rom_guide_huawei
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> R.string.rom_guide_oppo
            brand.contains("vivo") || brand.contains("iqoo") -> R.string.rom_guide_vivo
            else -> R.string.rom_guide_generic
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.rom_guide_title)
            .setMessage(getString(text))
            .setPositiveButton(R.string.btn_ok, null)
            .show()
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        if (store?.dndEnabled != true) return
        val parts = current.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: 12
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
        TimePickerDialog(requireContext(), { _, h, m ->
            onPicked(String.format("%02d:%02d", h, m))
        }, hour, minute, true).show()
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_VIDEO) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VIDEO || resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val target = File(requireContext().filesDir, MainActivity.CUSTOM_VIDEO_NAME)
        Thread {
            runCatching {
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
        }.start()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            refreshPermissions()
        }
    }

    fun bindConfig(configStore: ConfigStore) { this.store = configStore }

    fun refresh() {
        val s = store ?: return
        textInterval.text = getString(R.string.duration_minutes, s.intervalMinutes)
        seekInterval.progress = (s.intervalMinutes - ConfigStore.MIN_INTERVAL) / STEP_MINUTES
        textVolume.text = "${s.volume}%"
        seekVolume.progress = s.volume
        textSnooze.text = getString(R.string.snooze_fixed, s.snoozeMinutes)
        textDndStart.text = s.dndStart
        textDndEnd.text = s.dndEnd
        refreshPermissions()
        refreshVideo()
        refreshDnd()
        refreshWhitelist()
    }

    private fun refreshStatus() { /* 首页负责 */ }

    private fun refreshPermissions() {
        val ctx = requireContext()
        val enabled = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        textPermNotification.text = getString(
            if (enabled) R.string.perm_granted else R.string.perm_notification_missing,
        )
        view?.findViewById<TextView>(R.id.btn_perm_notification)?.text =
            getString(if (enabled) R.string.perm_granted else R.string.btn_grant)

        val overlay = Settings.canDrawOverlays(ctx)
        textPermOverlay.text = getString(
            if (overlay) R.string.perm_granted else R.string.perm_overlay_missing,
        )
        view?.findViewById<TextView>(R.id.btn_perm_overlay)?.text =
            getString(if (overlay) R.string.perm_granted else R.string.btn_grant)

        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        val ignoring = pm.isIgnoringBatteryOptimizations(ctx.packageName)
        textPermBattery.text = getString(
            if (ignoring) R.string.perm_battery_granted else R.string.perm_battery_missing,
        )
        view?.findViewById<TextView>(R.id.btn_perm_battery)?.text =
            getString(if (ignoring) R.string.perm_granted else R.string.btn_grant)
    }

    private fun refreshVideo() {
        val s = store ?: return
        val custom = s.videoPath.isNotBlank() && File(s.videoPath).isFile
        textVideo.text = getString(if (custom) R.string.video_custom else R.string.video_builtin)
        view?.findViewById<TextView>(R.id.btn_reset_video)?.visibility =
            if (custom) View.VISIBLE else View.GONE
    }

    private fun refreshDnd() {
        val s = store ?: return
        val enabled = s.dndEnabled
        textDndStart.isEnabled = enabled
        textDndEnd.isEnabled = enabled
        val alpha = if (enabled) 1f else 0.45f
        view?.findViewById<View>(R.id.row_dnd_start)?.alpha = alpha
        view?.findViewById<View>(R.id.row_dnd_end)?.alpha = alpha
    }

    private fun refreshWhitelist() {
        val s = store ?: return
        val count = s.whitelist.size
        textWhitelist.text = if (count == 0) {
            getString(R.string.whitelist_value_empty)
        } else {
            getString(R.string.whitelist_value_count, count)
        }
    }

    companion object {
        private const val STEP_MINUTES = 5
        private const val REQUEST_NOTIFICATIONS = 3101
        private const val REQUEST_VIDEO = 3102
    }
}
