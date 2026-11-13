package com.tenderxiamu.eyecaretv

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import kotlin.math.ceil

/**
 * 首页：护眼提醒状态 + 立即休息大按钮。
 * 所有设置项都挪到 SettingsFragment 了。
 */
class HomeFragment : Fragment() {

    private var store: ConfigStore? = null
    private var smart: SmartEngine? = null

    private lateinit var switchEnabled: Switch
    private lateinit var ringProgress: RingProgressView
    private lateinit var textStatus: TextView
    private lateinit var textNext: TextView

    private var suppressEnabledListener = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        setupSettings()
        setupActions(view)
        // 视图创建后立即刷新一次，避免切回本页时环形进度仍是初始状态
        refresh()
    }

    private fun bindViews(view: View) {
        switchEnabled = view.findViewById(R.id.switch_enabled)
        ringProgress = view.findViewById(R.id.ring_progress)
        textStatus = view.findViewById(R.id.text_status)
        textNext = view.findViewById(R.id.text_next)
    }

    private fun setupSettings() {
        switchEnabled.setOnCheckedChangeListener { _, checked ->
            if (suppressEnabledListener) return@setOnCheckedChangeListener
            store?.enabled = checked
            if (checked) {
                runCatching { EyeCareService.start(requireContext()) }
                    .onFailure { toast(getString(R.string.toast_service_failed)) }
            } else {
                EyeCareService.stop(requireContext())
            }
            refresh()
            (activity as? MainActivity)?.onConfigChanged()
        }
    }

    private fun setupActions(view: View) {
        view.findViewById<TextView>(R.id.btn_rest_now).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirm_rest_title)
                .setMessage(R.string.confirm_rest_message)
                .setPositiveButton(R.string.btn_confirm) { _, _ ->
                    runCatching { EyeCareService.forceRemind(requireContext()) }
                        .onFailure { toast(getString(R.string.toast_service_failed)) }
                }
                .setNegativeButton(R.string.btn_cancel, null)
                .show()
        }
    }

    fun bindConfig(configStore: ConfigStore, smartEngine: SmartEngine) {
        this.store = configStore
        this.smart = smartEngine
    }

    fun refresh() {
        val s = store ?: return
        suppressEnabledListener = true
        switchEnabled.isChecked = s.enabled
        suppressEnabledListener = false
        refreshStatus()
    }

    private fun refreshStatus() {
        val s = store ?: return
        if (!s.enabled) {
            textStatus.text = getString(R.string.status_disabled)
            textNext.text = getString(R.string.status_start_hint)
            ringProgress.progress = 0f
            ringProgress.valueText = "—"
            ringProgress.unitText = getString(R.string.ring_disabled)
            return
        }
        val accumulatedMinutes = s.accumulatedMs / 60_000L
        textStatus.text = getString(R.string.status_accumulated, accumulatedMinutes)
        val config = s.toConfig()
        val remaining = (config.intervalMs - s.accumulatedMs).coerceAtLeast(0L)
        val remainingMinutes = ceil(remaining / 60_000.0).toInt()
        textNext.text = getString(R.string.status_next, remainingMinutes)

        ringProgress.progress = if (config.intervalMs > 0) {
            (s.accumulatedMs.toFloat() / config.intervalMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        ringProgress.valueText = remainingMinutes.toString()
        ringProgress.unitText = getString(R.string.ring_unit)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
