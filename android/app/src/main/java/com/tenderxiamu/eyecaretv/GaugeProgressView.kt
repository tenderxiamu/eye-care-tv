package com.tenderxiamu.eyecaretv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 半圆仪表盘，与桌面端 FatigueGauge 视觉一致。
 * - 背景弧：灰色 #E4E4E7
 * - 数值弧：根据指数 绿 / 黄 / 红
 * - 中央大数字 + 下方状态标签
 */
class GaugeProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var score: Float = 0f
    private var statusText: String = ""

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    fun setScore(score: Float, status: String) {
        this.score = score.coerceIn(0f, 100f)
        this.statusText = status
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        val stroke = (min(w, h) * 0.08f).coerceAtLeast(6f)
        val cx = w / 2f
        val cy = h - paddingBottom.toFloat() - stroke
        val radius = min(w / 2f, h) - stroke - paddingTop.toFloat() / 2f

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // 背景弧（上半圆，180°）
        bgPaint.color = 0xFFE4E4E7.toInt()
        bgPaint.strokeWidth = stroke
        canvas.drawArc(arcRect, 180f, 180f, false, bgPaint)

        // 数值弧
        val ratio = score / 100f
        val arcSweep = 180f * ratio
        fgPaint.color = fatigueColor(score)
        fgPaint.strokeWidth = stroke
        canvas.drawArc(arcRect, 180f, arcSweep, false, fgPaint)

        // 中央大数字
        val valueText = "%.0f".format(score)
        val valueSize = radius * 0.55f
        valuePaint.color = 0xFF18181B.toInt()
        valuePaint.textSize = valueSize
        valuePaint.isFakeBoldText = true
        canvas.drawText(valueText, cx, cy - radius * 0.35f, valuePaint)

        // 状态标签
        if (statusText.isNotEmpty()) {
            labelPaint.color = fatigueColor(score)
            labelPaint.textSize = radius * 0.18f
            labelPaint.isFakeBoldText = true
            canvas.drawText(statusText, cx, cy - radius * 0.05f, labelPaint)
        }
    }

    private fun fatigueColor(score: Float): Int = when {
        score < 30f -> 0xFF16A34A.toInt()  // 绿
        score < 60f -> 0xFFF59E0B.toInt()  // 黄
        else -> 0xFFEF4444.toInt()         // 红
    }
}
