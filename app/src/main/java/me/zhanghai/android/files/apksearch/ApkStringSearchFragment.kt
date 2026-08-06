/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.apksearch

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ApkStringItemBinding
import me.zhanghai.android.files.databinding.ApkStringSearchFragmentBinding
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels

class ApkStringSearchFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: ApkStringSearchFragmentBinding

    private val viewModel by viewModels {
        { ApkStringSearchViewModel(args.path, File(requireContext().cacheDir, "apk-string-search")) }
    }

    private lateinit var stringsAdapter: StringListAdapter
    private var allStrings: List<ApkString> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ApkStringSearchFragmentBinding.inflate(inflater, container, false).also {
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

        stringsAdapter = StringListAdapter(::copyString)
        binding.stringsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.stringsRecyclerView.adapter = stringsAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterStrings(newText.orEmpty())
                return false
            }
        })

        viewModel.stringsLiveData.observe(viewLifecycleOwner) { state ->
            when (state) {
                is DataState.Loading -> {
                    binding.progress.isVisible = true
                    binding.errorText.isVisible = false
                }
                is DataState.Error -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = true
                    binding.errorText.text = state.throwable.javaClass.simpleName + ": " +
                        (state.throwable.localizedMessage
                            ?: getString(R.string.apk_string_search_error))
                }
                is DataState.Success -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = false
                    allStrings = state.data
                    filterStrings(binding.searchView.query?.toString().orEmpty())
                }
            }
        }
    }

    private fun filterStrings(query: String) {
        lifecycleScope.launch {
            val (filtered, total) = withContext(Dispatchers.Default) {
                val all = allStrings
                val filtered = if (query.isEmpty()) {
                    all
                } else {
                    all.filter { it.string.contains(query, ignoreCase = true) }
                }
                filtered to all.size
            }
            stringsAdapter.replace(filtered, true)
            binding.stringCountText.text = getString(
                R.string.apk_string_count_format, filtered.size, total
            )
        }
    }

    private fun copyString(item: ApkString) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, item.string))
        showToast(getString(R.string.dex_string_copied))
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

private class StringListAdapter(
    private val onStringClick: (ApkString) -> Unit
) : ListAdapter<ApkString, StringListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<ApkString>() {
        override fun areItemsTheSame(oldItem: ApkString, newItem: ApkString): Boolean =
            oldItem.entryName == newItem.entryName && oldItem.string == newItem.string

        override fun areContentsTheSame(oldItem: ApkString, newItem: ApkString): Boolean =
            oldItem == newItem
    }
) {
    class ViewHolder(val binding: ApkStringItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ApkStringItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.stringText.text = item.string
        holder.binding.fileText.text = item.entryName
        holder.binding.root.setOnClickListener { onStringClick(item) }
    }
}
