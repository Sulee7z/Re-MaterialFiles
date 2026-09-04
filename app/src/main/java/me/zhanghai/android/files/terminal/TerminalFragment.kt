/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.termux.terminal.TerminalSession
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.databinding.TerminalFragmentBinding
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.primaryText
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels

class TerminalFragment : Fragment() {

    private lateinit var binding: TerminalFragmentBinding

    private val args by args<TerminalArgs>()

    private val viewModel by viewModels { { TerminalViewModel(args.cwd, args.asRoot) } }

    private lateinit var client: TerminalClient

    private var session: TerminalSession? = null

    // Long-press auto-repeat for the arrow keys, like Termux's mRepetitiveKeys: a press
    // repeats after mLongPressTimeout, then every mLongPressRepeatDelay until release.
    private val handler = Handler(Looper.getMainLooper())
    private val repeatRunnable = object : Runnable {
        var text: String = ""
        var button: View? = null
        override fun run() {
            send(text)
            // Keep the pressed highlight while repeating.
            button?.isActivated = true
            handler.postDelayed(this, REPEAT_DELAY)
        }
    }

    private fun getPrefs() = requireContext().getSharedPreferences(
        "terminal", android.content.Context.MODE_PRIVATE
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = TerminalFragmentBinding.inflate(inflater, container, false).also {
        binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = getString(R.string.terminal_title)
            subtitle = args.cwd
            setDisplayHomeAsUpEnabled(true)
        }

        client = TerminalClient(
            viewProvider = { binding.terminalView.takeIf { isAdded } },
            onTitleChanged = { title ->
                if (title != null) {
                    activity.supportActionBar?.subtitle = title
                }
            },
            onSessionFinished = { success ->
                // The shell exited on its own (e.g. `exit`): forget the app-scoped session so
                // the bubble goes away. This may fire after the fragment is detached; guard
                // every fragment/activity access.
                TerminalSessionManager.onSessionEnded()
                if (isAdded) {
                    if (!success) {
                        showToast(R.string.terminal_exit_status_failed)
                    }
                    activity.finish()
                }
            },
            onCopyText = { text -> clipboardManager.copyText(text, requireContext()) },
            onPasteText = { clipboardManager.primaryText.toString() }
        )
        binding.terminalView.setTerminalViewClient(client)
        // The renderer (and thus font metrics) is only created by setTextSize(), and updateSize()
        // dereferences it, so it must be called before attachSession() to avoid an NPE.
        binding.terminalView.setTextSize(
            (12 * resources.displayMetrics.scaledDensity).toInt()
        )
        session = viewModel.getOrCreateSession(client)
        binding.terminalView.attachSession(session)
        // TerminalView.performClick() is invoked through TerminalClient.onSingleTapUp() on every
        // tap, so this is the hook to raise the soft keyboard.
        binding.terminalView.setOnClickListener { showSoftKeyboard() }
        setupExtraKeys()
        binding.terminalView.requestFocus()
        // Auto-raise the keyboard on entry, like Termux does.
        binding.terminalView.post { showSoftKeyboard() }
    }

    private fun setupExtraKeys() {
        binding.escKey.setOnClickListener { send(0x1b.toByte()) }
        binding.tabKey.setOnClickListener { send(0x09.toByte()) }
        setupModifierKey(binding.ctrlKey, TerminalClient.ModifierKey.CTRL)
        setupModifierKey(binding.altKey, TerminalClient.ModifierKey.ALT)
        setupModifierKey(binding.shiftKey, TerminalClient.ModifierKey.SHIFT)
        binding.minusKey.setOnClickListener { send("\u002d") }
        binding.slashKey.setOnClickListener { send("/") }
        binding.pipeKey.setOnClickListener { send("|") }
        setupRepeatingKey(binding.upKey, "\u001b[A")
        setupRepeatingKey(binding.downKey, "\u001b[B")
        setupRepeatingKey(binding.leftKey, "\u001b[D")
        setupRepeatingKey(binding.rightKey, "\u001b[C")
        setupCollapseKey()
    }

    /** Modifier keys: tap toggles (temporary, applies to the next key), long press locks. */
    private fun setupModifierKey(button: View, key: TerminalClient.ModifierKey) {
        button.setOnClickListener {
            client.toggleModifier(key)
            updateModifierKeyUi()
        }
        button.setOnLongClickListener {
            client.lockModifier(key)
            updateModifierKeyUi()
            true
        }
    }

    /** Arrow keys send once on tap and auto-repeat while held down. */
    private fun setupRepeatingKey(button: View, text: String) {
        button.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Send immediately, then auto-repeat on long hold.
                    send(text)
                    repeatRunnable.text = text
                    repeatRunnable.button = v
                    v.isActivated = true
                    handler.postDelayed(repeatRunnable, LONG_PRESS_TIMEOUT)
                    true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeatRunnable)
                    v.isActivated = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupCollapseKey() {
        val collapsed = getPrefs().getBoolean(KEY_EXTRA_KEYS_COLLAPSED, false)
        setExtraKeysCollapsed(collapsed)
        binding.collapseKey.setOnClickListener {
            setExtraKeysCollapsed(!isExtraKeysCollapsed)
            getPrefs().edit().putBoolean(
                KEY_EXTRA_KEYS_COLLAPSED, isExtraKeysCollapsed
            ).apply()
        }
    }

    private var isExtraKeysCollapsed = false

    private fun setExtraKeysCollapsed(collapsed: Boolean) {
        isExtraKeysCollapsed = collapsed
        // Hide every key button except the collapse toggle itself, so it stays
        // reachable to re-expand the row.
        val childCount = binding.extraKeysGrid.childCount
        for (i in 0 until childCount) {
            val child = binding.extraKeysGrid.getChildAt(i)
            if (child.id != R.id.collapseKey) {
                child.isVisible = !collapsed
            }
        }
        binding.collapseKey.setText(
            if (collapsed) R.string.terminal_key_expand else R.string.terminal_key_collapse
        )
    }

    /** Highlights the toggled-on modifier keys, like Termux's extra-keys row. */
    private fun updateModifierKeyUi() {
        binding.ctrlKey.isActivated = client.isModifierActive(TerminalClient.ModifierKey.CTRL)
        binding.altKey.isActivated = client.isModifierActive(TerminalClient.ModifierKey.ALT)
        binding.shiftKey.isActivated = client.isModifierActive(TerminalClient.ModifierKey.SHIFT)
    }

    private fun send(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    private fun send(byte: Byte) {
        session?.write(byteArrayOf(byte), 0, 1)
    }

    /** Show the soft keyboard when the terminal is tapped. */
    private fun showSoftKeyboard() {
        val terminalView = binding.terminalView
        terminalView.requestFocus()
        val imm = requireContext().getSystemService<InputMethodManager>() ?: return
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_FORCED)
    }

    /** Forwards physical keyboard events to the TerminalView (called by the Activity). */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isAdded) {
            return false
        }
        val terminalView = binding.terminalView
        terminalView.requestFocus()
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> terminalView.onKeyDown(event.keyCode, event)
            KeyEvent.ACTION_UP -> terminalView.onKeyUp(event.keyCode, event)
            else -> terminalView.dispatchKeyEvent(event)
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private companion object {
        const val KEY_EXTRA_KEYS_COLLAPSED = "extra_keys_collapsed"
        const val LONG_PRESS_TIMEOUT = 400L
        const val REPEAT_DELAY = 80L
    }
}
