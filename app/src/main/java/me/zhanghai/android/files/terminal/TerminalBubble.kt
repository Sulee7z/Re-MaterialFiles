/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.databinding.TerminalBubbleBinding
import me.zhanghai.android.files.databinding.TerminalBubbleMenuBinding
import me.zhanghai.android.files.util.putArgs

/**
 * A draggable floating bubble that keeps the terminal reachable while it runs in the
 * background. Shown by [TerminalActivity.onDestroy] when its session is still running
 * (closing the terminal returns to the file list); tapping the bubble opens a small menu
 * to return to the terminal or close it completely.
 *
 * The bubble is a plain WindowManager overlay ([TYPE_APPLICATION_OVERLAY]) owned by the
 * process, not by any activity, so it keeps working after the activity is destroyed; it is
 * removed when the session ends (bubble "close" menu, or `exit` in the shell).
 */
object TerminalBubble {

    private const val BUBBLE_SIZE_DP = 48f
    private const val BUBBLE_MARGIN_DP = 16f
    private const val PREFS_NAME = "terminal_bubble"
    private const val KEY_BUBBLE_X = "x"
    private const val KEY_BUBBLE_Y = "y"

    private var windowManager: WindowManager? = null

    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var menuView: View? = null
    private var menuParams: WindowManager.LayoutParams? = null

    private var savedArgs: TerminalArgs? = null

    /** Whether the bubble should be on screen (false after an explicit hide/close). */
    private var shouldShow = false

    private var isMenuVisible = false
    private var isDragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    /** Shows the bubble (replacing any existing one, e.g. for a newer terminal instance). */
    fun show() {
        shouldShow = true
        savedArgs = TerminalSessionManager.sessionArgs()
            ?: TerminalArgs("/", false)
        addBubbleView()
    }

    /** Removes the bubble. */
    fun hide() {
        shouldShow = false
        savedArgs = null
        removeBubbleView()
    }

    /**
     * The app went to the background and the "only in app" setting is on: take the bubble
     * off the screen but keep its state, so it reappears when the app comes back.
     */
    fun onAppBackgrounded() {
        removeBubbleView()
    }

    /** The app came back to the foreground: re-show the bubble if it should be shown. */
    fun onAppForegrounded() {
        if (shouldShow && TerminalSessionManager.isRunning()) {
            addBubbleView()
        }
    }

    private fun addBubbleView() {
        if (!Settings.canDrawOverlays(application)) {
            // No overlay permission: never show the bubble (the terminal still runs in the
            // background and is reachable from the recents screen).
            return
        }
        val wm = application.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        if (bubbleView == null) {
            val binding = TerminalBubbleBinding.inflate(LayoutInflater.from(application))
            bubbleView = binding.root
            val size = BUBBLE_SIZE_DP.dpToPx(application)
            val displayWidth = application.resources.displayMetrics.widthPixels
            val displayHeight = application.resources.displayMetrics.heightPixels
            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                // Restore the last position the user dragged the bubble to, clamped into the
                // screen (the display size may have changed since it was saved).
                val prefs = bubblePrefs()
                x = prefs.getInt(KEY_BUBBLE_X, Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE }
                    ?.coerceIn(0, displayWidth - size)
                    ?: (displayWidth - size - BUBBLE_MARGIN_DP.dpToPx(application))
                y = prefs.getInt(KEY_BUBBLE_Y, Int.MIN_VALUE)
                    .takeIf { it != Int.MIN_VALUE }
                    ?.coerceIn(0, displayHeight - size)
                    ?: (displayHeight / 2 - size / 2)
            }
            bubbleParams = params
            binding.root.setOnTouchListener { _, event -> onBubbleTouch(event) }
            wm.addView(binding.root, params)
        }
    }

    private fun removeBubbleView() {
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        bubbleParams = null
        menuView?.let { windowManager?.removeView(it) }
        menuView = null
        menuParams = null
        isMenuVisible = false
    }

    private fun onBubbleTouch(event: MotionEvent): Boolean {
        val view = bubbleView ?: return false
        val params = bubbleParams ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = downRawX
                lastRawY = downRawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    val touchSlop = ViewConfiguration.get(application).scaledTouchSlop
                    if (kotlin.math.abs(event.rawX - downRawX) > touchSlop
                        || kotlin.math.abs(event.rawY - downRawY) > touchSlop
                    ) {
                        isDragging = true
                    }
                }
                if (isDragging) {
                    params.x += (event.rawX - lastRawX).toInt()
                    params.y += (event.rawY - lastRawY).toInt()
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    windowManager?.updateViewLayout(view, params)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) {
                    toggleMenu(view, params)
                } else {
                    // Remember the position the user dragged the bubble to, so the next
                    // session restores it instead of resetting to the default spot.
                    bubblePrefs().edit()
                        .putInt(KEY_BUBBLE_X, params.x)
                        .putInt(KEY_BUBBLE_Y, params.y)
                        .apply()
                }
                isDragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    private fun toggleMenu(bubble: View, params: WindowManager.LayoutParams) {
        if (isMenuVisible) {
            hideMenu()
        } else {
            showMenu(bubble, params)
        }
    }

    private fun showMenu(bubble: View, bubbleParams: WindowManager.LayoutParams) {
        val wm = windowManager ?: return
        if (menuView != null) {
            return
        }
        val binding = TerminalBubbleMenuBinding.inflate(LayoutInflater.from(application))
        binding.terminalBubbleReturn.setOnClickListener {
            removeBubbleView()
            returnToTerminal()
        }
        binding.terminalBubbleHide.setOnClickListener {
            // Hide the bubble only: the session keeps running (reopening the terminal from
            // the file list re-shows the bubble on the next close).
            hide()
        }
        binding.terminalBubbleClose.setOnClickListener {
            removeBubbleView()
            closeTerminal()
        }
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuWidth = binding.root.measuredWidth
        val menuHeight = binding.root.measuredHeight
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Place the menu just to the right of the bubble, vertically centered; flip it
            // to the left when the bubble sits too close to the right edge.
            x = bubbleParams.x + bubble.width + BUBBLE_MARGIN_DP.dpToPx(application)
            y = bubbleParams.y + bubble.height / 2 - menuHeight / 2
            if (x + menuWidth > application.resources.displayMetrics.widthPixels) {
                x = bubbleParams.x - menuWidth - BUBBLE_MARGIN_DP.dpToPx(application)
            }
        }
        menuParams = params
        menuView = binding.root
        isMenuVisible = true
        binding.root.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                hideMenu()
                return@setOnTouchListener true
            }
            false
        }
        wm.addView(binding.root, params)
    }

    private fun hideMenu() {
        menuView?.let { windowManager?.removeView(it) }
        menuView = null
        menuParams = null
        isMenuVisible = false
    }

    private fun returnToTerminal() {
        val context = application
        val intent = Intent(context, TerminalActivity::class.java)
            .putArgs(savedArgs ?: TerminalArgs("/", false))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    private fun closeTerminal() {
        TerminalSessionManager.close()
    }

    private fun bubblePrefs(): android.content.SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

private fun Float.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()
