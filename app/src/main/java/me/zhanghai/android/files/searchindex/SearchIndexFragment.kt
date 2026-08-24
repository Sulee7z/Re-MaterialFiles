/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.searchindex

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SearchIndexFragmentBinding
import me.zhanghai.android.files.databinding.SearchIndexItemBinding
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.filelist.BuiltInFileOpeners
import me.zhanghai.android.files.filelist.OpenFileActivity
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.provider.common.AndroidFileTypeDetector
import me.zhanghai.android.files.util.createInstallPackageIntent
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.startActivitySafe
import java8.nio.file.Files
import java8.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchIndexFragment : Fragment() {

    private lateinit var binding: SearchIndexFragmentBinding

    private val viewModel by viewModels<SearchIndexViewModel>()

    private lateinit var resultsAdapter: ResultListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = SearchIndexFragmentBinding.inflate(inflater, container, false)
        .also { binding = it }
        .root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = getString(R.string.search_index_title)
            setDisplayHomeAsUpEnabled(true)
        }

        activity.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.search_index, menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when (item.itemId) {
                    R.id.action_rebuild_index -> {
                        rebuildIndex()
                        true
                    }
                    else -> false
                }
        }, viewLifecycleOwner)

        resultsAdapter = ResultListAdapter(::openFile)
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.resultsRecyclerView.adapter = resultsAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.search(newText.orEmpty())
                return false
            }
        })

        viewModel.resultsLiveData.observe(viewLifecycleOwner) { results ->
            resultsAdapter.replace(results, true)
            binding.resultCountText.text = getString(
                R.string.search_index_result_count_format, results.size
            )
        }

        viewModel.indexingLiveData.observe(viewLifecycleOwner) { indexing ->
            binding.progress.isVisible = indexing
            binding.searchView.isEnabled = !indexing
        }

        viewModel.indexInfoLiveData.observe(viewLifecycleOwner) { (count, timeMillis) ->
            binding.indexInfoText.text = if (count > 0) {
                getString(
                    R.string.search_index_info_format, count,
                    java.text.DateFormat.getDateTimeInstance().format(timeMillis)
                )
            } else {
                getString(R.string.search_index_not_built)
            }
        }

        // Restore the previous index state from the database (off the main thread) before
        // deciding whether a build is needed: an existing index must stay valid and
        // searchable right away, instead of a cold start mistaking it for "not indexed"
        // and forcing a full (clearing!) rebuild on the first entry.
        val appContext = requireContext().applicationContext
        lifecycleScope.launch {
            val (count, timeMillis) = withContext(Dispatchers.IO) {
                FileIndexer.restoreStateFromDatabase(appContext)
            }
            viewModel.postIndexInfo(count, timeMillis)
            if (!FileIndexer.isIndexed()) {
                rebuildIndex()
            }
        }
    }

    private fun rebuildIndex() {
        viewModel.rebuildIndex(viewModel.getIndexRoots())
    }

    private fun openFile(file: SearchIndexDb.IndexedFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            val path = java8.nio.file.Paths.get(file.path)
            val mimeType = try {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
                AndroidFileTypeDetector.getMimeType(path, attributes).asMimeType()
            } catch (e: Exception) {
                MimeType.GENERIC
            }
            withContext(Dispatchers.Main) {
                if (file.isDir) {
                    // Open the directory in the file list.
                    startActivity(
                        FileListActivity::class.createIntent().apply { extraPath = path }
                    )
                } else if (mimeType.isApk) {
                    // Hand the APK to the installer like the file list does. A plain
                    // ACTION_VIEW would land in third-party installers that fail to parse
                    // system APK copies ("unsupported file format").
                    startActivitySafe(path.fileProviderUri.createInstallPackageIntent())
                } else {
                    // Prefer the built-in opener (MT Manager style) like the file list does,
                    // falling back to the system default only when no built-in opener applies.
                    // DEX/ELF openers verify the file header first (we are on an IO thread),
                    // so a misnamed file falls back instead of failing in the analyzer.
                    val builtInIntent = BuiltInFileOpeners.createOpenIntent(
                        path, mimeType, verifyBinaryMagic = true
                    )
                    if (builtInIntent != null) {
                        startActivity(builtInIntent)
                    } else {
                        startActivity(OpenFileActivity.createIntent(path, mimeType))
                    }
                }
            }
        }
    }
}

private class ResultListAdapter(
    private val onResultClick: (SearchIndexDb.IndexedFile) -> Unit
) : ListAdapter<SearchIndexDb.IndexedFile, ResultListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<SearchIndexDb.IndexedFile>() {
        override fun areItemsTheSame(
            oldItem: SearchIndexDb.IndexedFile, newItem: SearchIndexDb.IndexedFile
        ): Boolean = oldItem.path == newItem.path

        override fun areContentsTheSame(
            oldItem: SearchIndexDb.IndexedFile, newItem: SearchIndexDb.IndexedFile
        ): Boolean = oldItem == newItem
    }
) {
    class ViewHolder(val binding: SearchIndexItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            SearchIndexItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.nameText.text = item.name
        holder.binding.pathText.text = item.path
        holder.binding.root.setOnClickListener { onResultClick(item) }
    }
}
