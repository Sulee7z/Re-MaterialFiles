/*
 * Copyright (c) 2019 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.ui

import android.view.ViewGroup
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.compat.getDrawableCompat

object ThemedFastScroller {
    fun create(
        view: ViewGroup,
        paddingLeft: Int = 0,
        paddingTop: Int = 0,
        paddingRight: Int = 0,
        paddingBottom: Int = 0
    ): FastScroller = FastScrollerBuilder(view)
        .useMd2Style()
        // Replace the MD2 accent-colored (theme blue) thumb with a Material 3
        // neutral-grey thumb: M3 scrollbars use a neutral colour rather than the
        // brand/accent colour, so the bar stays unobtrusive (esp. in two-pane UI).
        .setThumbDrawable(view.context.getDrawableCompat(R.drawable.fast_scroll_thumb_m3))
        .apply {
            if (paddingLeft != 0 || paddingTop != 0 || paddingRight != 0 || paddingBottom != 0) {
                setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
            }
        }
        .build()
}
