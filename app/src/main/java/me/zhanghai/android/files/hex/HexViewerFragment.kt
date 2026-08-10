/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hex

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.HexViewerFragmentBinding
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels

class HexViewerFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: HexViewerFragmentBinding

    private val viewModel by viewModels { { HexViewerViewModel(args.path) } }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = HexViewerFragmentBinding.inflate(inflater, container, false).also {
        binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back key with unsaved edits asks before leaving, like the text editor does.
        val backCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                confirmDiscardUnsavedEdits {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        addOnBackPressedCallback(backCallback)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = args.path.fileName.toString()
            setDisplayHomeAsUpEnabled(true)
        }

        binding.goButton.setOnClickListener { goToOffset() }
        binding.prevButton.setOnClickListener {
            confirmDiscardUnsavedEdits {
                viewModel.loadPage(viewModel.currentPageOffset - HexViewerViewModel.PAGE_SIZE)
            }
        }
        binding.nextButton.setOnClickListener {
            confirmDiscardUnsavedEdits {
                viewModel.loadPage(viewModel.currentPageOffset + HexViewerViewModel.PAGE_SIZE)
            }
        }
        binding.editCheckBox.setOnCheckedChangeListener { _, isChecked ->
            backCallback.isEnabled = isChecked
            if (isChecked) {
                binding.editorEditText.setText(viewModel.editableText())
                binding.editorEditText.isVisible = true
                // Hide the whole hex scroll view (not just its text) so the editor takes
                // the full content area instead of shrinking into the bottom half.
                binding.hexScrollView.isVisible = false
                binding.saveButton.isEnabled = true
            } else {
                binding.editorEditText.isVisible = false
                binding.hexScrollView.isVisible = true
                binding.saveButton.isEnabled = false
            }
        }
        binding.saveButton.setOnClickListener {
            saveEdits()
        }

        viewModel.pageLiveData.observe(viewLifecycleOwner) { page ->
            binding.progress.isVisible = page.loading
            binding.errorText.isVisible = page.error != null
            binding.errorText.text = page.error
            binding.hexScrollView.isVisible = !page.loading && page.error == null &&
                !binding.editCheckBox.isChecked
            binding.hexText.text = page.hexText
            binding.rangeText.text = page.rangeText
            if (binding.editCheckBox.isChecked) {
                binding.editorEditText.setText(viewModel.editableText())
            }
        }
    }

    /** Runs [action] unless the edit box holds unsaved changes, in which case it asks first. */
    private fun confirmDiscardUnsavedEdits(action: () -> Unit) {
        val hasUnsavedEdits = binding.editCheckBox.isChecked &&
            binding.editorEditText.text?.toString() != viewModel.editableText()
        if (!hasUnsavedEdits) {
            action()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.hex_discard_title)
            .setMessage(R.string.hex_discard_message)
            .setPositiveButton(R.string.hex_discard_confirm) { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveEdits() {
        val text = binding.editorEditText.text?.toString().orEmpty()
        lifecycleScope.launch(Dispatchers.IO) {
            val success = try {
                viewModel.saveEditableText(text)
                true
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                if (success) {
                    showToast(getString(R.string.hex_save_success))
                    viewModel.loadPage(viewModel.currentPageOffset)
                } else {
                    showToast(getString(R.string.hex_save_error))
                }
            }
        }
    }

    private fun goToOffset() {
        val text = binding.offsetEditText.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            return
        }
        val offset = try {
            if (text.startsWith("0x", ignoreCase = true)) {
                text.substring(2).toLong(16)
            } else {
                text.toLong()
            }
        } catch (e: NumberFormatException) {
            showToast(getString(R.string.hex_viewer_error))
            return
        }
        viewModel.loadPage(offset)
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}
