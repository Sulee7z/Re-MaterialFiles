/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.storage

import android.view.ViewGroup
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.TrashItemBinding
import me.zhanghai.android.files.filejob.TrashManager
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater
import java.text.DateFormat
import java.util.Date

class TrashListAdapter(
    private val listener: Listener
) : SimpleAdapter<TrashManager.TrashEntry, TrashListAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(TrashItemBinding.inflate(parent.context.layoutInflater, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val binding = holder.binding
        binding.root.setOnClickListener { listener.restoreEntry(entry) }
        binding.root.setOnLongClickListener {
            listener.deletePermanently(entry)
            true
        }
        binding.iconImage.setImageResource(R.drawable.delete_icon_control_normal_24dp)
        binding.nameText.text = entry.originalFileName
        val dateString = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(entry.deletedAtMillis))
        binding.descriptionText.text = binding.root.context.getString(
            R.string.trash_item_description_format, dateString, entry.originalParentPath
        )
    }

    class ViewHolder(val binding: TrashItemBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(
        binding.root
    )

    interface Listener {
        fun restoreEntry(entry: TrashManager.TrashEntry)
        fun deletePermanently(entry: TrashManager.TrashEntry)
    }
}