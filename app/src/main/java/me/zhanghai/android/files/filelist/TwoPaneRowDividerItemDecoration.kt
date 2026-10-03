/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import kotlin.math.roundToInt

/**
 * Two-pane mode only: draws a thin inset hairline under every row, so the compact
 * side-by-side lists have clear row separation (the regular list keeps Material's
 * whitespace-only separators). The line is inset from both edges and drawn on top of the
 * rows, ending before the last item.
 */
class TwoPaneRowDividerItemDecoration(context: Context) : RecyclerView.ItemDecoration() {

    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.two_pane_row_divider)
    }

    private val density = context.resources.displayMetrics.density
    private val insetPx = (ROW_INSET_DP * density).toInt()
    private val thicknessPx = density.roundToInt().coerceAtLeast(1)

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (parent.itemAnimator?.isRunning == true) {
            return
        }
        val left = parent.paddingLeft + insetPx
        val right = parent.width - parent.paddingRight - insetPx
        if (right <= left) {
            return
        }
        val lastPosition = state.itemCount - 1
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            val position = parent.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION || position == lastPosition) {
                continue
            }
            val bottom = (child.bottom + child.translationY).roundToInt()
            if (bottom <= parent.paddingTop || bottom > parent.height - parent.paddingBottom) {
                continue
            }
            canvas.drawRect(
                left.toFloat(),
                (bottom - thicknessPx).toFloat(),
                right.toFloat(),
                bottom.toFloat(),
                paint
            )
        }
    }

    private companion object {
        const val ROW_INSET_DP = 16
    }
}
