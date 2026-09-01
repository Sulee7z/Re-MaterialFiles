/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.graphics.Canvas
import android.util.Log
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.PopupTextProvider
import me.zhanghai.android.fastscroll.Predicate

/**
 * ViewHelper for the SHARED two-pane fast scrollbar: a single scrollbar pinned to the
 * RIGHT pane's right screen edge that scrolls whichever pane is currently active.
 *
 * The bar's host spans the FULL right pane (negative margins undo the pane's screen-edge
 * padding), so the thumb draws exactly at the right screen edge, adapting to whatever
 * padding the system insets impose. Touches are registered on the HOST (not the list), so
 * the touch coordinate space perfectly matches the thumb/track layout: the host is the
 * pane's top-most child covering the full pane (including the padding area where the thumb
 * lives), and its OnTouchListener consumes all DOWN events within the scrollbar zone
 * (~48 dp from the right edge), forwarding them to the FastScroller so both direct thumb
 * hits and track-drag work. Touches outside the zone fall through to the list underneath
 * (the listener returns false, so the parent dispatch continues to the list).
 *
 * Pre-draws and scroll events come from the right list; scroll math is delegated to the
 * wrapped original helper of the ACTIVE list, so dragging the bar scrolls the active pane
 * and tapping the other pane switches the bar's target.
 */
class ActivePaneScrollbarHelper(
    private val touchHost: ViewGroup,
    private val touchList: RecyclerView,
    private val rightHelper: RecyclerViewFastScrollerViewHelper
) : FastScroller.ViewHelper {

    private val tag = "SharedScrollbar"

    /**
     * Width (dp) of the grab zone at the right screen edge claimed by the scrollbar:
     * the thumb's own width plus one "space" (4 dp) to the left, so the bar is easy to
     * grab without an exact hit on the thumb.
     */
    private val GRAB_ZONE_DP = 8

    /** Thumb drawable height, matching fast_scroll_thumb_m3 (52 dp). */
    private val thumbHeightPx by lazy {
        (52 * touchHost.resources.displayMetrics.density).toInt()
    }

    /**
     * Jumps the active list to the scroll position corresponding to the given touch Y
     * (host coordinates), mirroring FastScroller's thumb-offset mapping: the thumb's
     * center lands on the finger, and the list scrolls proportionally.
     */
    private fun jumpToY(y: Float) {
        val hostHeight = touchHost.height
        if (hostHeight <= 0) {
            return
        }
        val scrollOffsetRange = activeHelper.getScrollRange() - hostHeight
        if (scrollOffsetRange <= 0) {
            return
        }
        val thumbOffsetRange = hostHeight - thumbHeightPx
        if (thumbOffsetRange <= 0) {
            return
        }
        val thumbOffset =
            (y - thumbHeightPx / 2f).toInt().coerceIn(0, thumbOffsetRange)
        val scrollOffset = scrollOffsetRange.toLong() * thumbOffset / thumbOffsetRange
        activeHelper.scrollTo(scrollOffset.toInt())
    }

    /** Offset from the RecyclerView coordinate space to the host coordinate space. */
    private val hostRvDeltaX by lazy {
        val hostPos = IntArray(2); touchHost.getLocationOnScreen(hostPos)
        val rvPos = IntArray(2); touchList.getLocationOnScreen(rvPos)
        rvPos[0] - hostPos[0]
    }
    private val hostRvDeltaY by lazy {
        val hostPos = IntArray(2); touchHost.getLocationOnScreen(hostPos)
        val rvPos = IntArray(2); touchList.getLocationOnScreen(rvPos)
        rvPos[1] - hostPos[1]
    }

    @Volatile
    private var activeHelper: RecyclerViewFastScrollerViewHelper = rightHelper

    private var leftHelper: RecyclerViewFastScrollerViewHelper? = null
    private var onScrollChanged: Runnable? = null
    private var onPreDrawRunnable: Runnable? = null

    /**
     * Attaches the LEFT pane's list so the shared scrollbar can drive it. Called by the
     * left pane fragment when its view is ready (the right pane's own creation path also
     * retries, covering both fragment creation orders).
     */
    fun attachLeftList(leftList: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        leftHelper = RecyclerViewFastScrollerViewHelper(
            leftList, adapter as? PopupTextProvider
        )
        Log.d(tag, "attachLeftList: leftHelper=$leftHelper")
        // Register the pre-draw decoration on the left list too, so the popup text
        // updates live when the left list scrolls (not just when the right list draws).
        onPreDrawRunnable?.let { runnable ->
            leftList.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun onDraw(
                    canvas: Canvas,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    runnable.run()
                }
            })
        }
        leftList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Only the ACTIVE list drives the shared scrollbar.
                if (activeHelper === leftHelper) {
                    onScrollChanged?.run()
                    // The thumb lives in the host overlay; force a redraw so the
                    // pre-draw callback repositions it while the left list scrolls.
                    touchList.invalidate()
                }
            }
        })
    }

    /** Switches the scroll target to the newly active pane. */
    fun setActivePaneSecondary(secondary: Boolean) {
        activeHelper = if (secondary) {
            rightHelper
        } else {
            leftHelper ?: rightHelper
        }
        Log.d(
            tag,
            "setActivePaneSecondary: secondary=$secondary activeHelper=" +
                (if (activeHelper === rightHelper) "RIGHT" else "LEFT")
        )
        touchList.invalidate()
    }

    override fun addOnPreDrawListener(onPreDraw: Runnable) {
        onPreDrawRunnable = onPreDraw
        touchList.addItemDecoration(object : RecyclerView.ItemDecoration() {
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
        this.onScrollChanged = onScrollChanged
        touchList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Only forward the RIGHT list's scrolls when it is the active target.
                if (activeHelper === rightHelper) {
                    onScrollChanged.run()
                }
            }
        })
    }

    override fun addOnTouchEventListener(onTouchEvent: Predicate<MotionEvent>) {
        // TWO touch sources feeding the same FastScroller predicate (events are
        // mutually exclusive — whichever source consumes a DOWN owns the gesture):
        //  1. The HOST (full pane, same coordinate space as the thumb): lets the user
        //     grab the bar at the screen edge, which lies in the pane's padding area
        //     that the RecyclerView does not cover. A DOWN within the grab zone (the
        //     rightmost thumb width + one space, ~8 dp) is claimed unconditionally so
        //     the gesture stays on the host and the FastScroller's track-drag
        //     (triggered by a MOVE) takes over, while taps further left fall through.
        //  2. The RIGHT RecyclerView (like the original bar): handles the track-drag
        //     for the rest of the track that lies over the list. Its events are
        //     translated into HOST coordinates before being forwarded, because the
        //     FastScroller's track/thumb bounds live in host space (the RecyclerView
        //     is inset by the pane's padding). Item taps keep working: a non-thumb
        //     DOWN returns false, and the RecyclerView only intercepts the gesture
        //     once the MOVE starts a track-drag.
        val grabZonePx = (GRAB_ZONE_DP * touchHost.resources.displayMetrics.density).toInt()
        touchHost.setOnTouchListener { _, event ->
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN &&
                event.x >= touchHost.width - grabZonePx
            ) {
                // First let the FastScroller record the DOWN (and start a thumb drag if
                // the thumb was hit directly). If the thumb was NOT hit, jump the list
                // straight to the touched position (tap-to-seek on the whole bar); the
                // subsequent MOVE is handled by the FastScroller's track-drag, which
                // keeps working from the new position.
                val consumed = onTouchEvent.test(event)
                if (!consumed) {
                    jumpToY(event.y)
                }
                true
            } else {
                onTouchEvent.test(event)
            }
        }
        fun translate(event: MotionEvent): MotionEvent {
            val translated = MotionEvent.obtain(event)
            translated.setLocation(
                event.x + hostRvDeltaX,
                event.y + hostRvDeltaY
            )
            return translated
        }
        touchList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(
                recyclerView: RecyclerView,
                event: MotionEvent
            ): Boolean {
                val translated = translate(event)
                val result = onTouchEvent.test(translated)
                translated.recycle()
                return result
            }

            override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
                val translated = translate(event)
                onTouchEvent.test(translated)
                translated.recycle()
            }
        })
    }

    override fun getScrollRange(): Int = activeHelper.getScrollRange()

    override fun getScrollOffset(): Int = activeHelper.getScrollOffset()

    override fun scrollTo(offset: Int) {
        Log.d(
            tag,
            "scrollTo($offset) target=${if (activeHelper === rightHelper) "RIGHT" else "LEFT"}"
        )
        activeHelper.scrollTo(offset)
    }

    override fun getPopupText(): CharSequence? = activeHelper.getPopupText()
}
