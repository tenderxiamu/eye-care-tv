package com.tenderxiamu.eyecaretv

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

/**
 * 主界面：ViewPager2 + TabLayout 三页容器。
 * - HomeFragment：护眼提醒状态 + 立即休息大按钮
 * - SettingsFragment：权限 + 提醒设置 + 免打扰
 * - ReportFragment：AI 疲劳报告 + 洞察
 *
 * 原来的单页 ScrollView 已拆分为三个 Tab，
 * 避免功能过长需要下滑的问题，同时植入 AI 报告功能。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var store: ConfigStore
    private lateinit var statsStore: StatsStore
    private lateinit var smart: SmartEngine

    private lateinit var homeFragment: HomeFragment
    private lateinit var settingsFragment: SettingsFragment
    private lateinit var reportFragment: ReportFragment

    private val handler = Handler(Looper.getMainLooper())
    private var refreshPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        store = ConfigStore(this)
        statsStore = StatsStore(this)
        smart = SmartEngine(this)

        setContentView(R.layout.activity_main)

        homeFragment = HomeFragment()
        settingsFragment = SettingsFragment()
        reportFragment = ReportFragment()

        homeFragment.bindConfig(store, smart)
        settingsFragment.bindConfig(store)
        reportFragment.bindServices(smart, store)

        setupViewPager()
        maybeShowOnboarding()
    }

    private fun setupViewPager() {
        val viewPager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.view_pager)
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)

        val fragments = listOf(homeFragment, settingsFragment, reportFragment)

        viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = fragments.size
            override fun createFragment(position: Int) = fragments[position]
        }
        viewPager.isUserInputEnabled = true

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            val (titleRes, iconRes) = when (position) {
                0 -> R.string.tab_home to android.R.drawable.ic_menu_myplaces
                1 -> R.string.tab_settings to android.R.drawable.ic_menu_manage
                2 -> R.string.tab_report to android.R.drawable.ic_menu_day
                else -> R.string.tab_home to android.R.drawable.ic_menu_myplaces
            }
            tab.setText(titleRes)
            tab.setIcon(iconRes)
        }.attach()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        if (store.enabled) {
            runCatching { EyeCareService.start(this) }
                .onFailure { toast(getString(R.string.toast_service_failed)) }
        }
        // 注册 SmartEngine 事件回调，把 Service 的 tick 喂给 AI
        bindSmartEngine()
    }

    override fun onPause() {
        super.onPause()
        unbindSmartEngine()
    }

    fun refreshAll() {
        // ViewPager2 的 Fragment 视图可能尚未创建（onResume 早于首次布局），此时跳过刷新避免 lateinit 崩溃
        if (homeFragment.isAdded && homeFragment.view != null) homeFragment.refresh()
        if (settingsFragment.isAdded && settingsFragment.view != null) settingsFragment.refresh()
        if (reportFragment.isAdded && reportFragment.view != null) reportFragment.refresh()
    }

    fun onConfigChanged() { refreshAll() }

    /**
     * 把 EyeCareService 的 tick 接到 SmartEngine。
     * 由于 Service 和 Activity 是分离的进程组件，
     * 这里通过 ConfigStore.accumulatedMs 的变化间接驱动 SmartEngine 累积。
     * 真正的 tick 事件（active vs idle）在 Service 内记录，
     * 这里只做最终的 snapshot 驱动。
     */
    private fun bindSmartEngine() {
        if (refreshPending) return
        refreshPending = true
        handler.post(object : Runnable {
            override fun run() {
                if (!refreshPending) return
                // 每 5 秒喂一次累计屏幕时长给 SmartEngine
                // 真正的 active/idle 拆分暂时简化：按 interval 平均分配
                val accumulatedMin = store.accumulatedMs / 60_000.0
                smart.recordTick(5_000L, 0L) // 粗略估算：每秒 active 5s（简化实现）
                // 每分钟刷新一次报告（避免过度刷新）
                refreshAll()
                handler.postDelayed(this, 30_000L)
            }
        })
    }

    private fun unbindSmartEngine() {
        refreshPending = false
        handler.removeCallbacksAndMessages(null)
    }

    // ================== 首次启动授权向导 ==================

    private fun maybeShowOnboarding() {
        if (store.onboardingDone) return
        val steps = listOf(
            OnboardingStep(
                getString(R.string.label_notification_permission),
                getString(R.string.onboarding_notification_desc),
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATIONS_ONBOARD,
                    )
                } else {
                    openNotificationSettings()
                }
            },
            OnboardingStep(
                getString(R.string.label_overlay_permission),
                getString(R.string.onboarding_overlay_desc),
            ) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"),
                )
                runCatching { startActivity(intent) }
            },
            OnboardingStep(
                getString(R.string.label_battery_permission),
                getString(R.string.onboarding_battery_desc),
            ) {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName"),
                )
                runCatching { startActivity(intent) }
            },
        )
        showOnboardingStep(steps, 0)
    }

    private fun showOnboardingStep(steps: List<OnboardingStep>, index: Int) {
        if (index >= steps.size) {
            store.onboardingDone = true
            refreshAll()
            return
        }
        val step = steps[index]
        android.app.AlertDialog.Builder(this)
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

    private fun openNotificationSettings() {
        runCatching {
            startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private data class OnboardingStep(
        val title: String,
        val description: String,
        val action: () -> Unit,
    )

    companion object {
        const val CUSTOM_VIDEO_NAME = "custom_reminder.mp4"
        private const val REQUEST_NOTIFICATIONS_ONBOARD = 3101
    }
}
