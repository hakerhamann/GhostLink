package com.rezerv.app.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout

class CircularClipFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val clipPath = Path()

    init {
        clipChildren = true
        clipToPadding = true
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        clipPath.reset()
        clipPath.addOval(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        invalidateOutline()
    }

    override fun draw(canvas: Canvas) {
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restoreToCount(saveCount)
    }
}
