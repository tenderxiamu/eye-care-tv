package com.tenderxiamu.eyecaretv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.SeekBar

/**
 * 拖动时在滑块上方显示数值气泡的 SeekBar，
 * 避免用户只能盯着页面上方的数字猜当前值。
 */
class BubbleSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SeekBar(context, attrs) {

    var labelProvider: ((Int) -> String)? = null

    private var bubbleVisible = false
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22393A")
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6FE3C4")
        textSize = sp(12f)
        textAlign = Paint.Align.CENTER
    }
    private val textBounds = Rect()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> bubbleVisible = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> bubbleVisible = false
        }
        val handled = super.onTouchEvent(event)
        invalidate()
        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!bubbleVisible) return
        val label = labelProvider?.invoke(progress) ?: return
        textPaint.getTextBounds(label, 0, label.length, textBounds)

        val padH = dp(8f)
        val padV = dp(4f)
        val bubbleWidth = textBounds.width() + padH * 2
        val bubbleHeight = textBounds.height() + padV * 2

        val trackLeft = paddingLeft.toFloat()
        val trackWidth = (width - paddingLeft - paddingRight).toFloat()
        val ratio = if (max > 0) progress.toFloat() / max else 0f
        val thumbX = trackLeft + trackWidth * ratio
        val centerX = thumbX.coerceIn(bubbleWidth / 2, width - bubbleWidth / 2)
        // 气泡限制在控件内部，避免被 View 边界裁剪
        val top = (height / 2f - bubbleHeight - dp(2f)).coerceAtLeast(0f)

        val rect = RectF(
            centerX - bubbleWidth / 2,
            top,
            centerX + bubbleWidth / 2,
            top + bubbleHeight,
        )
        canvas.drawRoundRect(rect, dp(7f), dp(7f), bubblePaint)
        canvas.drawText(label, centerX, rect.centerY() - textBounds.exactCenterY(), textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
