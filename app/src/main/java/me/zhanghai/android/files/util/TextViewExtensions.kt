/*
 * Copyright (c) 2020 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.util

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.textfield.TextInputLayout

fun TextView.hideTextInputLayoutErrorOnTextChange(vararg textInputLayouts: TextInputLayout) {
    doAfterTextChanged { textInputLayouts.forEach { it.error = null } }
}

/**
 * Makes sure that this text view has a non-null typeface.
 *
 * Drawing a text whose [android.graphics.Paint] has a null typeface crashes the render thread
 * with a native SIGSEGV inside Skia (sktext::GlyphRunBuilder::blobToGlyphRunList), so we always
 * fall back to [Typeface.DEFAULT].
 */
fun TextView.ensureTypeface() {
    if (typeface == null) {
        setTypeface(Typeface.DEFAULT)
    }
}

/**
 * Makes sure that this view and all its descendants have a non-null typeface.
 *
 * @see ensureTypeface
 */
fun View.ensureTypefaces() {
    when (this) {
        is TextView -> ensureTypeface()
        is ViewGroup -> for (index in 0 until childCount) {
            getChildAt(index).ensureTypefaces()
        }
    }
}

var TextView.isBold: Boolean
    get() = (typeface ?: Typeface.DEFAULT).isBold
    set(value) {
        val typeface = typeface ?: Typeface.DEFAULT
        if (typeface.isBold == value) {
            return
        }
        val style = if (value) {
            typeface.style or Typeface.BOLD
        } else {
            typeface.style andInv Typeface.BOLD
        }
        // Workaround insane behavior in TextView#setTypeface(Typeface, int).
        if (style > 0) {
            setTypeface(typeface, style)
        } else {
            setTypeface(Typeface.create(typeface, style), style)
        }
    }

var TextView.isItalic: Boolean
    get() = (typeface ?: Typeface.DEFAULT).isItalic
    set(value) {
        val typeface = typeface ?: Typeface.DEFAULT
        if (typeface.isItalic == value) {
            return
        }
        val style = if (value) {
            typeface.style or Typeface.ITALIC
        } else {
            typeface.style andInv Typeface.ITALIC
        }
        // Workaround insane behavior in TextView#setTypeface(Typeface, int).
        if (style > 0) {
            setTypeface(typeface, style)
        } else {
            setTypeface(Typeface.create(typeface, style), style)
        }
    }

/** @see com.android.keyguard.KeyguardPasswordView#onEditorAction */
fun TextView.setOnEditorConfirmActionListener(listener: (TextView) -> Unit) {
    setOnEditorActionListener { view, actionId, event ->
        val isConfirmAction = if (event != null) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> true
                else -> false
            } && event.action == KeyEvent.ACTION_DOWN
        } else {
            when (actionId) {
                EditorInfo.IME_NULL, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_NEXT -> true
                else -> false
            }
        }
        if (isConfirmAction) {
            listener(view)
            true
        } else {
            false
        }
    }
}

fun TextView.setSpanClickable() {
    val wasClickable = isClickable
    val wasLongClickable = isLongClickable
    movementMethod = ClickableMovementMethod
    // Reset for TextView.fixFocusableAndClickableSettings(). We don't want View.onTouchEvent()
    // to consume touch events.
    isClickable = wasClickable
    isLongClickable = wasLongClickable
}

fun TextView.setSpanClickableAndTextSelectable() {
    setTextIsSelectable(true)
    movementMethod = ClickableArrowKeyMovementMethod
}
