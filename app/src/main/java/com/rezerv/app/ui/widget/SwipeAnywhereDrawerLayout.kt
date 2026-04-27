package com.rezerv.app.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs
import kotlin.math.max

class SwipeAnywhereDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : DrawerLayout(context, attrs, defStyleAttr) {

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var gestureHandled: Boolean = false
    private val touchSlop: Float = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val openThreshold: Float
        get() = max(touchSlop * 2f, 24f * resources.displayMetrics.density)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled) return super.onInterceptTouchEvent(ev)
        if (isDrawerOpen(GravityCompat.START)) return super.onInterceptTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                gestureHandled = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!gestureHandled) {
                    val dx = ev.x - downX
                    val dy = abs(ev.y - downY)
                    if (dx > openThreshold && dx > dy * 1.1f) {
                        gestureHandled = true
                        openDrawer(GravityCompat.START)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                gestureHandled = false
            }
        }

        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (gestureHandled) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureHandled = false
            }
            return true
        }
        return super.onTouchEvent(ev)
    }
}
