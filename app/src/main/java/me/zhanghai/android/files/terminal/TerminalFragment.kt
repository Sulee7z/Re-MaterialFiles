/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.terminal

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
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
                // The shell is killed when this activity finishes, so this may fire after the
                // fragment is detached; guard every fragment/activity access.
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
        binding.ctrlKey.setOnClickListener {
            client.toggleCtrl()
            updateModifierKeyUi()
        }
        binding.altKey.setOnClickListener {
            client.toggleAlt()
            updateModifierKeyUi()
        }
        binding.shiftKey.setOnClickListener {
            client.toggleShift()
            updateModifierKeyUi()
        }
        binding.minusKey.setOnClickListener { send("\u002d") }
        binding.slashKey.setOnClickListener { send("/") }
        binding.pipeKey.setOnClickListener { send("|") }
        binding.upKey.setOnClickListener { send("\u001b[A") }
        binding.downKey.setOnClickListener { send("\u001b[B") }
        binding.leftKey.setOnClickListener { send("\u001b[D") }
        binding.rightKey.setOnClickListener { send("\u001b[C") }
    }

    /** Highlights the toggled-on modifier keys, like Termux's extra-keys row. */
    private fun updateModifierKeyUi() {
        binding.ctrlKey.isActivated = client.ctrlKey
        binding.altKey.isActivated = client.altKey
        binding.shiftKey.isActivated = client.shiftKey
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
}