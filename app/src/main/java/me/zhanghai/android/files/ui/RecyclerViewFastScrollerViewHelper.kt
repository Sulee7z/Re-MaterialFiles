/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.graphics.Canvas
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.PopupTextProvider
import me.zhanghai.android.fastscroll.Predicate

/**
 * A public re-implementation of AndroidFastScroll's package-private RecyclerViewHelper:
 * the library's helper cannot be instantiated from app code, but a custom
 * [FastScroller.ViewHelper] is required to host the fast scrollbar on a wrapper view
 * (e.g. the two-pane left pane's left-edge scrollbar).
 */
class RecyclerViewFastScrollerViewHelper(
    private val recyclerView: RecyclerView,
    private val popupTextProvider: PopupTextProvider? = null
) : FastScroller.ViewHelper {

    private val tempRect = Rect()

    override fun addOnPreDrawListener(onPreDraw: Runnable) {
        recyclerView.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun onDraw(
                canvas: Canvas,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                onPreDraw.run()
            }
        })
    }

    override fun addOnScrollChangedListener(onScrollChanged: Runnable) {
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                onScrollChanged.run()
            }
        })
    }

    override fun addOnTouchEventListener(onTouchEvent: Predicate<MotionEvent>) {
        recyclerView.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(
                recyclerView: RecyclerView,
                event: MotionEvent
            ): Boolean = onTouchEvent.test(event)

            override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
                onTouchEvent.test(event)
            }
        })
    }

    override fun getScrollRange(): Int {
        val itemCount = itemCount
        if (itemCount == 0) {
            return 0
        }
        val itemHeight = itemHeight
        if (itemHeight == 0) {
            return 0
        }
        return recyclerView.paddingTop + itemCount * itemHeight + recyclerView.paddingBottom
    }

    override fun getScrollOffset(): Int {
        val firstItemPosition = firstItemPosition
        if (firstItemPosition == RecyclerView.NO_POSITION) {
            return 0
        }
        val itemHeight = itemHeight
        val firstItemTop = firstItemOffset
        return recyclerView.paddingTop + firstItemPosition * itemHeight - firstItemTop
    }

    override fun scrollTo(offset: Int) {
        // Stop any scroll in progress for RecyclerView.
        recyclerView.stopScroll()
        var adjustedOffset = offset - recyclerView.paddingTop
        val itemHeight = itemHeight
        // firstItemPosition should be non-negative even if paddingTop is greater than the
        // item height.
        val firstItemPosition = maxOf(0, adjustedOffset / itemHeight)
        val firstItemTop = firstItemPosition * itemHeight - adjustedOffset
        scrollToPositionWithOffset(firstItemPosition, firstItemTop)
    }

    override fun getPopupText(): CharSequence? {
        val provider = popupTextProvider
            ?: (recyclerView.adapter as? PopupTextProvider)
            ?: return null
        val position = firstItemAdapterPosition
        if (position == RecyclerView.NO_POSITION) {
            return null
        }
        return provider.getPopupText(recyclerView, position)
    }

    private val itemCount: Int
        get() {
            val linearLayoutManager = verticalLinearLayoutManager ?: return 0
            var itemCount = linearLayoutManager.itemCount
            if (linearLayoutManager is GridLayoutManager) {
                val gridLayoutManager = linearLayoutManager
                itemCount = (itemCount - 1) / gridLayoutManager.spanCount + 1
            }
            return itemCount
        }

    private val itemHeight: Int
        get() {
            if (recyclerView.childCount == 0) {
                return 0
            }
            val itemView = recyclerView.getChildAt(0)
            recyclerView.getDecoratedBoundsWithMargins(itemView, tempRect)
            return tempRect.height()
        }

    private val firstItemPosition: Int
        get() {
            var position = firstItemAdapterPosition
            val linearLayoutManager = verticalLinearLayoutManager ?: return RecyclerView.NO_POSITION
            if (linearLayoutManager is GridLayoutManager) {
                val gridLayoutManager = linearLayoutManager
                position /= gridLayoutManager.spanCount
            }
            return position
        }

    private val firstItemAdapterPosition: Int
        get() {
            if (recyclerView.childCount == 0) {
                return RecyclerView.NO_POSITION
            }
            val itemView = recyclerView.getChildAt(0)
            val linearLayoutManager = verticalLinearLayoutManager
                ?: return RecyclerView.NO_POSITION
            return linearLayoutManager.getPosition(itemView)
        }

    private val firstItemOffset: Int
        get() {
            if (recyclerView.childCount == 0) {
                return 0
            }
            val itemView = recyclerView.getChildAt(0)
            recyclerView.getDecoratedBoundsWithMargins(itemView, tempRect)
            return tempRect.top
        }

    private fun scrollToPositionWithOffset(position: Int, offset: Int) {
        val linearLayoutManager = verticalLinearLayoutManager ?: return
        var adjustedPosition = position
        if (linearLayoutManager is GridLayoutManager) {
            val gridLayoutManager = linearLayoutManager
            adjustedPosition *= gridLayoutManager.spanCount
        }
        // LinearLayoutManager actually takes offset from paddingTop instead of the top
        // of the RecyclerView.
        val adjustedOffset = offset - recyclerView.paddingTop
        linearLayoutManager.scrollToPositionWithOffset(adjustedPosition, adjustedOffset)
    }

    private val verticalLinearLayoutManager: LinearLayoutManager?
        get() {
            val layoutManager = recyclerView.layoutManager
            if (layoutManager !is LinearLayoutManager) {
                return null
            }
            if (layoutManager.orientation != RecyclerView.VERTICAL) {
                return null
            }
            return layoutManager
        }
}
