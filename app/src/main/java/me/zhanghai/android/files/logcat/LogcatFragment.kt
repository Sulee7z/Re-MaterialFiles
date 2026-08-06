/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.logcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.LogcatFragmentBinding
import me.zhanghai.android.files.databinding.LogcatItemBinding
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels

class LogcatFragment : Fragment() {

    private lateinit var binding: LogcatFragmentBinding

    private val viewModel by viewModels { { LogcatViewModel() } }

    private lateinit var logsAdapter: LogListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = LogcatFragmentBinding.inflate(inflater, container, false).also {
        binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = getString(R.string.logcat_title)
            setDisplayHomeAsUpEnabled(true)
        }

        val levels = listOf(
            getString(R.string.logcat_level_all), "V", "D", "I", "W", "E", "F"
        )
        binding.levelSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, levels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        logsAdapter = LogListAdapter(::copyLog)
        binding.logsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.logsRecyclerView.adapter = logsAdapter

        binding.refreshButton.setOnClickListener { load() }
        binding.levelSpinner.setOnItemSelectedListener(object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (position > 0) {
                    load(levels[position])
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        binding.hintText.text = getString(R.string.logcat_no_root_hint)

        viewModel.logsLiveData.observe(viewLifecycleOwner) { state ->
            when (state) {
                is DataState.Loading -> {
                    binding.progress.isVisible = true
                    binding.errorText.isVisible = false
                }
                is DataState.Error -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = true
                    binding.errorText.text = state.throwable.javaClass.simpleName + ": " +
                        (state.throwable.localizedMessage ?: getString(R.string.logcat_error))
                }
                is DataState.Success -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = false
                    logsAdapter.replace(state.data, true)
                    binding.countText.text = getString(
                        R.string.logcat_count_format, state.data.size
                    )
                }
            }
        }
    }

    private fun load(level: String? = null) {
        viewModel.load(level)
    }

    private fun copyLog(log: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, log))
        showToast(getString(R.string.dex_string_copied))
    }
}

private class LogListAdapter(
    private val onLogClick: (String) -> Unit
) : ListAdapter<String, LogListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem
    }
) {
    class ViewHolder(val binding: LogcatItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            LogcatItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.logText.text = item
        holder.binding.root.setOnClickListener { onLogClick(item) }
    }
}
