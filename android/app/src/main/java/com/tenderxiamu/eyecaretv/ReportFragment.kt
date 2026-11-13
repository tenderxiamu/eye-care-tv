package com.tenderxiamu.eyecaretv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

/**
 * AI 护眼报告页：疲劳指数仪表盘 + 今日统计 + AI 学习到的节奏 + 洞察列表。
 * 对应桌面端 app/ui/report.py 的功能，使用 SmartEngine 核心逻辑。
 */
class ReportFragment : Fragment() {

    private var smart: SmartEngine? = null
    private var store: ConfigStore? = null

    private lateinit var gaugeFatigue: GaugeProgressView
    private lateinit var textTodayBreaks: TextView
    private lateinit var textTodaySnoozes: TextView
    private lateinit var textTodayScreen: TextView
    private lateinit var textBestInterval: TextView
    private lateinit var insightsContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.fragment_report, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gaugeFatigue = view.findViewById(R.id.gauge_fatigue)
        textTodayBreaks = view.findViewById(R.id.text_today_breaks)
        textTodaySnoozes = view.findViewById(R.id.text_today_snoozes)
        textTodayScreen = view.findViewById(R.id.text_today_screen)
        textBestInterval = view.findViewById(R.id.text_best_interval)
        insightsContainer = view.findViewById(R.id.insights_container)
        // 视图创建后立即刷新一次，避免切回本页时显示空白/默认值
        refresh()
    }

    fun bindServices(
        smartEngine: SmartEngine,
        configStore: ConfigStore,
    ) {
        this.smart = smartEngine
        this.store = configStore
    }

    fun refresh() {
        val ctx = context ?: return
        val sm = smart ?: return
        val cs = store ?: return

        // 1. 疲劳指数仪表盘
        val accumulatedMin = cs.accumulatedMs / 60_000.0
        val score = sm.fatigueScore(accumulatedMin).toFloat().coerceIn(0f, 100f)
        val status = when {
            score < 30f -> getString(R.string.report_fatigue_good)
            score < 60f -> getString(R.string.report_fatigue_mild)
            else -> getString(R.string.report_fatigue_need_rest)
        }
        gaugeFatigue.setScore(score, status)

        // 2. 今日统计
        val daily = sm.dailyStats()
        textTodayBreaks.text = getString(R.string.report_value_count, daily.breaks)
        textTodaySnoozes.text = getString(R.string.report_value_count, daily.snoozes)
        textTodayScreen.text = getString(R.string.report_value_minutes, daily.totalScreenMinutes.toInt())

        // 3. AI 最优间隔
        val best = daily.bestInterval
        val samples = daily.fatigueTrainingSamples
        textBestInterval.text = if (samples >= 3) {
            getString(R.string.report_best_interval, best, samples)
        } else {
            getString(R.string.report_best_interval_learning, samples)
        }

        // 4. AI 洞察（动态创建，颜色/圆角用资源引用）
        val insightList = sm.insights()
        rebuildInsights(ctx, insightList)
    }

    private fun rebuildInsights(ctx: android.content.Context, insightList: List<String>) {
        insightsContainer.removeAllViews()

        // 取主题色资源（兼容深浅主题）
        val cardColor = ContextCompat.getColor(ctx, R.color.card)
        val textColor = ContextCompat.getColor(ctx, R.color.text_primary)
        val dividerColor = ContextCompat.getColor(ctx, R.color.divider)
        val cornerRadius = 18 * resources.displayMetrics.density  // 与 card_bg.xml 一致

        insightList.forEachIndexed { index, text ->
            val bg = GradientDrawable().apply {
                setColor(cardColor)
                setCornerRadius(cornerRadius)
                setStroke(1, dividerColor)  // 与 card_bg.xml 的 1dp 描边一致
            }
            val label = TextView(ctx).apply {
                this.text = "• $text"
                textSize = 14f
                setTextColor(textColor)
                setPadding(16, 14, 16, 14)  // 与 Card 样式的 16dp padding 一致
                background = bg
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = 12  // 与 Card 的 marginBottom 一致
            }
            insightsContainer.addView(label, lp)
        }
    }
}
