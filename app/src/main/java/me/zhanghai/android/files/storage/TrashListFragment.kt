/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TrashListFragmentBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filejob.TrashManager
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe

class TrashListFragment : Fragment(), TrashListAdapter.Listener {
    private lateinit var binding: TrashListFragmentBinding

    private lateinit var adapter: TrashListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        TrashListFragmentBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(
            activity, RecyclerView.VERTICAL, false
        )
        adapter = TrashListAdapter(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView)
        )

        TrashManager.trashLiveData.observe(viewLifecycleOwner) { onTrashListChanged(it) }
    }

    private fun onTrashListChanged(entries: List<TrashManager.TrashEntry>) {
        binding.emptyView.fadeToVisibilityUnsafe(entries.isEmpty())
        adapter.replace(entries)
    }

    override fun restoreEntry(entry: TrashManager.TrashEntry) {
        val original = entry.toOriginalPath()
        val trash = entry.toTrashPath()
        FileJobService.restoreFromTrash(listOf(original to trash), requireContext())
    }

    override fun deletePermanently(entry: TrashManager.TrashEntry) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_delete_permanently_title)
            .setMessage(
                getString(
                    R.string.trash_delete_permanently_message_format, entry.originalFileName
                )
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                val trash = entry.toTrashPath()
                FileJobService.permanentDelete(listOf(trash), requireContext())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }
}
