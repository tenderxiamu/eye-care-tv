package com.tenderxiamu.eyecaretv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 主界面的环形进度：一眼看出本次周期已经用掉多少。
 */
class RingProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val strokeWidth = dp(8f)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingProgressView.strokeWidth
        color = Color.parseColor("#22312F")
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@RingProgressView.strokeWidth
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#6FE3C4")
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F3F8F6")
        textSize = sp(24f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A5A8")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }

    private val arcRect = RectF()

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var valueText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var unitText: String = ""
        set(value) {
            field = value
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = dp(96f).toInt()
        setMeasuredDimension(
            resolveSize(size, widthMeasureSpec),
            resolveSize(size, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(width, height) / 2f - strokeWidth / 2 - dp(2f)
        canvas.drawCircle(cx, cy, radius, trackPaint)

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arcRect, -90f, 360f * progress, false, arcPaint)

        val centerY = cy + valuePaint.textSize / 3f
        canvas.drawText(valueText, cx, centerY, valuePaint)
        canvas.drawText(unitText, cx, centerY + dp(16f), unitPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
