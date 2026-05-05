package com.rezerv.app.ui.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.rezerv.app.R
import kotlin.math.min

object ActionSheetDialog {
    fun show(context: Context, actions: List<Action>) {
        showInternal(context, actions, Placement.Center)
    }

    fun showAtPoint(context: Context, actions: List<Action>, rawX: Float, rawY: Float) {
        showInternal(context, actions, Placement.Point(rawX, rawY))
    }

    fun showAnchoredBelow(context: Context, actions: List<Action>, anchor: View, alignEnd: Boolean = true) {
        showInternal(context, actions, Placement.Anchor(anchor, alignEnd))
    }

    private fun showInternal(context: Context, actions: List<Action>, placement: Placement) {
        if (actions.isEmpty()) return

        val dialog = Dialog(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).toInt()
        val rowWidth = calculateRowWidth(context, actions, horizontalPaddingPx = dp(36f))
        val rowHeight = dp(50f)
        val estimatedHeight = dp(12f) + actions.size * rowHeight + (actions.size - 1).coerceAtLeast(0) * dp(1f)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_message_action_sheet)
            setPadding(0, dp(6f), 0, dp(6f))
        }
        actions.forEachIndexed { index, action ->
            val row = TextView(context).apply {
                text = action.label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = rowHeight
                setPadding(dp(18f), 0, dp(18f), 0)
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (action.destructive) R.color.ghost_action_danger else R.color.ghost_text_primary
                    )
                )
                background = ContextCompat.getDrawable(context, R.drawable.bg_message_action_item)
                setOnClickListener {
                    dialog.dismiss()
                    action.onClick()
                }
            }
            container.addView(
                row,
                LinearLayout.LayoutParams(rowWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            if (index < actions.lastIndex) {
                container.addView(
                    View(context).apply { setBackgroundColor(Color.parseColor("#334A7A44")) },
                    LinearLayout.LayoutParams(rowWidth, dp(1f))
                )
            }
        }

        dialog.setContentView(container)
        dialog.show()
        dialog.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = window.attributes
            params.width = WindowManager.LayoutParams.WRAP_CONTENT
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            applyPlacement(context, placement, rowWidth, estimatedHeight, dp(8f), params)
            params.dimAmount = 0.35f
            window.attributes = params
        }
    }

    private fun applyPlacement(
        context: Context,
        placement: Placement,
        width: Int,
        height: Int,
        gapPx: Int,
        params: WindowManager.LayoutParams
    ) {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        val margin = (16f * context.resources.displayMetrics.density).toInt()
        when (placement) {
            Placement.Center -> {
                params.gravity = Gravity.CENTER
            }

            is Placement.Point -> {
                params.gravity = Gravity.TOP or Gravity.START
                val tapX = placement.rawX.toInt()
                val tapY = placement.rawY.toInt()
                val opensLeft = tapX + width + gapPx > screenWidth - margin
                val opensUp = tapY + height + gapPx > screenHeight - margin
                val desiredX = if (opensLeft) {
                    tapX - width - gapPx
                } else {
                    tapX + gapPx
                }
                val desiredY = if (opensUp) {
                    tapY - height - gapPx
                } else {
                    tapY + gapPx
                }
                params.x = desiredX.coerceIn(margin, screenWidth - width - margin)
                params.y = desiredY.coerceIn(margin, screenHeight - height - margin)
            }

            is Placement.Anchor -> {
                val location = IntArray(2)
                placement.anchor.getLocationOnScreen(location)
                val left = location[0]
                val top = location[1]
                val right = left + placement.anchor.width
                val bottom = top + placement.anchor.height
                params.gravity = Gravity.TOP or Gravity.START
                params.x = if (placement.alignEnd) {
                    (right - width).coerceIn(margin, screenWidth - width - margin)
                } else {
                    left.coerceIn(margin, screenWidth - width - margin)
                }
                params.y = (bottom + gapPx).coerceAtLeast(margin)
            }
        }
    }

    private fun calculateRowWidth(context: Context, actions: List<Action>, horizontalPaddingPx: Int): Int {
        val paint = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }.paint
        val widestText = actions.maxOf { paint.measureText(it.label).toInt() }
        val minWidth = (128f * context.resources.displayMetrics.density).toInt()
        val maxWidth = context.resources.displayMetrics.widthPixels -
            (48f * context.resources.displayMetrics.density).toInt()
        return min(maxWidth, maxOf(minWidth, widestText + horizontalPaddingPx))
    }

    data class Action(
        val label: String,
        val destructive: Boolean = false,
        val onClick: () -> Unit
    )

    private sealed interface Placement {
        data object Center : Placement
        data class Point(val rawX: Float, val rawY: Float) : Placement
        data class Anchor(val anchor: View, val alignEnd: Boolean) : Placement
    }
}
