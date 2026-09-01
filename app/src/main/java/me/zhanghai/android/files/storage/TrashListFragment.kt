/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
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
        activity.supportActionBar!!.setTitle(R.string.navigation_trash)
        binding.recyclerView.layoutManager = LinearLayoutManager(
            activity, RecyclerView.VERTICAL, false
        )
        adapter = TrashListAdapter(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView)
        )

        setHasOptionsMenu(true)
        TrashManager.trashLiveData.observe(viewLifecycleOwner) { onTrashListChanged(it) }

        // Enforce the auto-delete setting when the user opens the Trash screen.
        FileJobService.trashCleanup(requireContext())
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.trash, menu)
        updateEmptyTrashMenuItem(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        updateEmptyTrashMenuItem(menu)
    }

    private fun updateEmptyTrashMenuItem(menu: Menu) {
        val item = menu.findItem(R.id.action_empty_trash) ?: return
        item.isEnabled = TrashManager.getAll().isNotEmpty()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_empty_trash -> {
                confirmEmptyTrash()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmEmptyTrash() {
        val count = TrashManager.getAll().size
        if (count == 0) {
            return
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.trash_empty_title)
            .setMessage(getString(R.string.trash_empty_message_format, count))
            .setPositiveButton(R.string.empty) { _, _ ->
                FileJobService.clearTrash(requireContext())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun onTrashListChanged(entries: List<TrashManager.TrashEntry>) {
        binding.emptyView.fadeToVisibilityUnsafe(entries.isEmpty())
        adapter.replace(entries)
        activity?.invalidateOptionsMenu()
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
