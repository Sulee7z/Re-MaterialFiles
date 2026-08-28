/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.ViewGroup
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import me.zhanghai.android.files.util.fadeInUnsafe
import me.zhanghai.android.files.util.fadeOutUnsafe

class OverlayToolbarActionMode(
    bar: ViewGroup,
    toolbar: Toolbar,
    private val siblingView: ViewGroup? = null
) : ToolbarActionMode(bar, toolbar) {
    constructor(toolbar: Toolbar) : this(toolbar, toolbar)

    /** OverlayToolbar is both a Toolbar and a ViewGroup, so a (Toolbar, ViewGroup)
     *  secondary constructor would be ambiguous with the primary (ViewGroup, Toolbar)
     *  signature. Use the primary constructor at call sites. */

    init {
        bar.isVisible = false
    }

    override fun show(bar: ViewGroup, animate: Boolean) {
        // The overlay toolbar overlaps the regular toolbar; while it is active its sibling's
        // children must not participate in focus search (TV D-pad navigation would otherwise
        // jump to the hidden toolbar's buttons instead of the overlay's actions).
        siblingView?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        if (animate) {
            bar.fadeInUnsafe()
        } else {
            bar.isVisible = true
        }
    }

    override fun hide(bar: ViewGroup, animate: Boolean) {
        siblingView?.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        if (animate) {
            bar.fadeOutUnsafe()
        } else {
            bar.isVisible = false
        }
    }
}
