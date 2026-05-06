package com.rezerv.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.rezerv.app.R
import kotlin.math.min

class RoundVideoProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val ringBounds = RectF()
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0x55303A31
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = context.getColor(R.color.ghost_neon)
    }
    private var progressFraction: Float = 0f

    fun setProgressFraction(fraction: Float) {
        val sanitized = fraction.coerceIn(0f, 1f)
        if (progressFraction == sanitized) return
        progressFraction = sanitized
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val strokeWidth = (size * 0.022f).coerceAtLeast(2f)
        trackPaint.strokeWidth = strokeWidth
        progressPaint.strokeWidth = strokeWidth
        val inset = strokeWidth / 2f
        val left = (width - size) / 2f + inset
        val top = (height - size) / 2f + inset
        ringBounds.set(left, top, left + size - strokeWidth, top + size - strokeWidth)
        canvas.drawOval(ringBounds, trackPaint)
        if (progressFraction > 0f) {
            canvas.drawArc(ringBounds, -90f, progressFraction * 360f, false, progressPaint)
        }
    }
}
