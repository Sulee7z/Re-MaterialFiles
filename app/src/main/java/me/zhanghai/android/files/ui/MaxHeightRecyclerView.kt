/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.annotation.AttrRes
import androidx.recyclerview.widget.RecyclerView

/**
 * A [RecyclerView] whose measured height never exceeds [maxHeight].
 * The dialog list grows with its content up to the cap, and shrinks
 * when there are few items (or none), instead of reserving a fixed
 * height regardless of the content.
 */
open class MaxHeightRecyclerView : RecyclerView {
    var maxHeight: Int = 0

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(
        context: Context,
        attrs: AttributeSet?,
        @AttrRes defStyleAttr: Int
    ) : super(context, attrs, defStyleAttr)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (maxHeight > 0) {
            val cappedHeightSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
            super.onMeasure(widthSpec, cappedHeightSpec)
        } else {
            super.onMeasure(widthSpec, heightSpec)
        }
    }
}
