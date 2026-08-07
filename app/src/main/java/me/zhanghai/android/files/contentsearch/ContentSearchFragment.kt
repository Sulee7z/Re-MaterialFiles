/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.contentsearch

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
import java8.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.ContentSearchFragmentBinding
import me.zhanghai.android.files.databinding.ContentSearchItemBinding
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.viewer.text.TextEditorActivity

class ContentSearchFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: ContentSearchFragmentBinding

    private val viewModel by viewModels { { ContentSearchViewModel(args.directory) } }

    private lateinit var resultsAdapter: ResultListAdapter

    private var searchDebounce: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ContentSearchFragmentBinding.inflate(inflater, container, false).also {
        binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = getString(R.string.content_search_title)
            setDisplayHomeAsUpEnabled(true)
        }

        resultsAdapter = ResultListAdapter(::openFile)
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.resultsRecyclerView.adapter = resultsAdapter

        fun startSearch() {
            viewModel.search(
                binding.searchView.query?.toString().orEmpty(),
                binding.caseCheckBox.isChecked,
                binding.textOnlyCheckBox.isChecked
            )
        }

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                startSearch()
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Search as the user types, with a small debounce.
                searchDebounce?.cancel()
                searchDebounce = lifecycleScope.launch {
                    delay(300)
                    startSearch()
                }
                return false
            }
        })
        binding.caseCheckBox.setOnCheckedChangeListener { _, _ -> startSearch() }
        binding.textOnlyCheckBox.setOnCheckedChangeListener { _, _ -> startSearch() }

        viewModel.progressLiveData.observe(viewLifecycleOwner) { scanned ->
            binding.progressText.text = getString(
                R.string.content_search_file_count_format, scanned
            )
        }

        viewModel.resultsLiveData.observe(viewLifecycleOwner) { state ->
            when (state) {
                is DataState.Loading -> {
                    binding.progress.isVisible = true
                    binding.errorText.isVisible = false
                    binding.progressText.text = getString(R.string.content_search_scanning)
                }
                is DataState.Error -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = true
                    binding.errorText.text = state.throwable.javaClass.simpleName + ": " +
                        (state.throwable.localizedMessage
                            ?: getString(R.string.content_search_error))
                }
                is DataState.Success -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = false
                    resultsAdapter.replace(state.data, true)
                    if (state.data.isEmpty()) {
                        binding.errorText.isVisible = true
                        binding.errorText.text = getString(R.string.content_search_no_results)
                    }
                }
            }
        }
    }

    private fun openFile(result: ContentSearchResult) {
        startActivity(
            TextEditorActivity::class.createIntent().apply {
                extraPath = result.path
            }
        )
    }

    @Parcelize
    class Args(val directory: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

private class ResultListAdapter(
    private val onResultClick: (ContentSearchResult) -> Unit
) : ListAdapter<ContentSearchResult, ResultListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<ContentSearchResult>() {
        override fun areItemsTheSame(
            oldItem: ContentSearchResult, newItem: ContentSearchResult
        ): Boolean = oldItem.path == newItem.path

        override fun areContentsTheSame(
            oldItem: ContentSearchResult, newItem: ContentSearchResult
        ): Boolean = oldItem == newItem
    }
) {
    class ViewHolder(val binding: ContentSearchItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ContentSearchItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        holder.binding.nameText.text = item.fileName
        holder.binding.pathText.text = item.relativePath
        holder.binding.countText.text = context.getString(
            R.string.content_search_result_count_format, item.matchCount
        )
        holder.binding.root.setOnClickListener { onResultClick(item) }
    }
}
