/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.hex

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import java8.nio.file.Files
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

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = args.path.fileName.toString()
            setDisplayHomeAsUpEnabled(true)
        }

        binding.goButton.setOnClickListener { goToOffset() }
        binding.prevButton.setOnClickListener {
            viewModel.loadPage(viewModel.currentPageOffset - HexViewerViewModel.PAGE_SIZE)
        }
        binding.nextButton.setOnClickListener {
            viewModel.loadPage(viewModel.currentPageOffset + HexViewerViewModel.PAGE_SIZE)
        }

        viewModel.pageLiveData.observe(viewLifecycleOwner) { page ->
            binding.progress.isVisible = page.loading
            binding.errorText.isVisible = page.error != null
            binding.errorText.text = page.error
            binding.hexText.isVisible = !page.loading && page.error == null
            binding.hexText.text = page.hexText
            binding.rangeText.text = page.rangeText
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
