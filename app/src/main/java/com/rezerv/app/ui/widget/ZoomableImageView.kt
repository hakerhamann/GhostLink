package com.rezerv.app.ui.widget

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onSwipeUpDismiss: (() -> Unit)? = null
    var onDismissDragProgress: ((Float) -> Unit)? = null

    private val drawMatrix = Matrix()
    private val baseMatrix = Matrix()
    private val displayRect = RectF()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var normalizedScale = 1f
    private var activePointerId = INVALID_POINTER_ID
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private var hadMultiTouch = false
    private var isDismissDragging = false
    private var isAnimatingDismiss = false
    private var dismissDragOffsetY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val previous = normalizedScale
            val next = (normalizedScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
            val delta = next / previous
            normalizedScale = next
            drawMatrix.postScale(delta, delta, detector.focusX, detector.focusY)
            fixTranslation()
            imageMatrix = drawMatrix
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
        imageMatrix = drawMatrix
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        configureBaseMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configureBaseMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)
        if (isAnimatingDismiss) return true
        if (!isDismissDragging) {
            scaleDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                lastTouchX = event.x
                lastTouchY = event.y
                downX = event.x
                downY = event.y
                downRawX = event.rawX
                downRawY = event.rawY
                hadMultiTouch = false
                isDismissDragging = false
                resetDismissTransform(animated = false)
                parent?.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hadMultiTouch = true
                if (isDismissDragging) {
                    resetDismissTransform(animated = true)
                    isDismissDragging = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return true
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                val totalDx = event.rawX - downRawX
                val totalDy = event.rawY - downRawY

                if (isDismissDragging) {
                    applyDismissDrag(totalDy.coerceAtMost(0f))
                    lastTouchX = x
                    lastTouchY = y
                    return true
                }

                if (normalizedScale > MIN_SCALE + SCALE_EPS) {
                    drawMatrix.postTranslate(dx, dy)
                    fixTranslation()
                    imageMatrix = drawMatrix
                } else if (!scaleDetector.isInProgress && !hadMultiTouch) {
                    val canStartDismiss = totalDy < -touchSlop &&
                        abs(totalDy) > abs(totalDx) * SWIPE_DIRECTION_RATIO

                    if (canStartDismiss) {
                        animate().cancel()
                        isDismissDragging = true
                        applyDismissDrag(totalDy.coerceAtMost(0f))
                    }
                }
                lastTouchX = x
                lastTouchY = y
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newPointerIndex = if (pointerIndex == 0) 1 else 0
                    if (newPointerIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newPointerIndex)
                        lastTouchX = event.getX(newPointerIndex)
                        lastTouchY = event.getY(newPointerIndex)
                    } else {
                        activePointerId = INVALID_POINTER_ID
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDismissDragging) {
                    val shouldDismiss = event.actionMasked == MotionEvent.ACTION_UP &&
                        -dismissDragOffsetY >= dismissDistancePx()
                    if (shouldDismiss) {
                        animateDismissAndNotify()
                    } else {
                        resetDismissTransform(animated = true)
                    }
                    isDismissDragging = false
                } else if (!scaleDetector.isInProgress &&
                    !hadMultiTouch &&
                    normalizedScale <= MIN_SCALE + SCALE_EPS
                ) {
                    val deltaY = event.rawY - downRawY
                    val deltaX = event.rawX - downRawX
                    if (deltaY < -dismissDistancePx() &&
                        abs(deltaY) > abs(deltaX) * SWIPE_DIRECTION_RATIO
                    ) {
                        animateDismissAndNotify()
                    }
                }
                activePointerId = INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }

        return true
    }

    private fun applyDismissDrag(offsetY: Float) {
        if (abs(offsetY - dismissDragOffsetY) < DRAG_POSITION_EPS_PX) {
            return
        }
        dismissDragOffsetY = offsetY
        translationY = offsetY
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        val progress = (-offsetY / dismissDistancePx()).coerceIn(0f, 1f)
        onDismissDragProgress?.invoke(progress)
    }

    private fun configureBaseMatrix() {
        val localDrawable = drawable ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val drawableW = localDrawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val drawableH = localDrawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        if (viewW <= 0f || viewH <= 0f) return

        val baseScale = min(viewW / drawableW, viewH / drawableH)
        val dx = (viewW - drawableW * baseScale) / 2f
        val dy = (viewH - drawableH * baseScale) / 2f

        baseMatrix.reset()
        baseMatrix.postScale(baseScale, baseScale)
        baseMatrix.postTranslate(dx, dy)

        drawMatrix.set(baseMatrix)
        normalizedScale = MIN_SCALE
        imageMatrix = drawMatrix
        resetDismissTransform(animated = false)
    }

    private fun fixTranslation() {
        val localDrawable = drawable ?: return
        displayRect.set(
            0f,
            0f,
            localDrawable.intrinsicWidth.toFloat().coerceAtLeast(1f),
            localDrawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        )
        drawMatrix.mapRect(displayRect)

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        var deltaX = 0f
        var deltaY = 0f

        if (displayRect.width() <= viewW) {
            deltaX = viewW / 2f - displayRect.centerX()
        } else {
            if (displayRect.left > 0f) {
                deltaX = -displayRect.left
            } else if (displayRect.right < viewW) {
                deltaX = viewW - displayRect.right
            }
        }

        if (displayRect.height() <= viewH) {
            deltaY = viewH / 2f - displayRect.centerY()
        } else {
            if (displayRect.top > 0f) {
                deltaY = -displayRect.top
            } else if (displayRect.bottom < viewH) {
                deltaY = viewH - displayRect.bottom
            }
        }

        if (deltaX != 0f || deltaY != 0f) {
            drawMatrix.postTranslate(deltaX, deltaY)
        }
    }

    private fun dismissDistancePx(): Float {
        return SWIPE_DISMISS_DISTANCE_DP * resources.displayMetrics.density
    }

    private fun animateDismissAndNotify() {
        if (isAnimatingDismiss) return
        isAnimatingDismiss = true
        val targetTranslationY = -max(height.toFloat(), dismissDistancePx() * 1.6f)
        animate().cancel()
        animate()
            .translationY(targetTranslationY)
            .setDuration(150L)
            .withEndAction {
                onDismissDragProgress?.invoke(1f)
                onSwipeUpDismiss?.invoke()
                isAnimatingDismiss = false
            }
            .start()
    }

    private fun resetDismissTransform(animated: Boolean) {
        isAnimatingDismiss = false
        dismissDragOffsetY = 0f
        if (animated) {
            animate()
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .alpha(1f)
                .setDuration(170L)
                .withEndAction { onDismissDragProgress?.invoke(0f) }
                .start()
        } else {
            animate().cancel()
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            onDismissDragProgress?.invoke(0f)
        }
    }

    private companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4.5f
        const val SCALE_EPS = 0.02f
        const val SWIPE_DISMISS_DISTANCE_DP = 96f
        const val SWIPE_DIRECTION_RATIO = 1.2f
        const val DRAG_POSITION_EPS_PX = 0.5f
        const val INVALID_POINTER_ID = -1
    }
}
