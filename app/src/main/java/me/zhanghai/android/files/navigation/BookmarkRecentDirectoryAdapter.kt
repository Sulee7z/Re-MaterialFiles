/*
 * Copyright (c) 2026 Sulee7z <sulee7z@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.navigation

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java8.nio.file.Path
import me.zhanghai.android.files.databinding.BookmarkRecentDirectoryItemBinding
import me.zhanghai.android.files.filelist.toUserFriendlyString
import me.zhanghai.android.files.ui.SimpleAdapter
import me.zhanghai.android.files.util.layoutInflater

class BookmarkRecentDirectoryAdapter(
    private val listener: Listener
) : SimpleAdapter<BookmarkRecentDirectoryAdapter.Item, BookmarkRecentDirectoryAdapter.ViewHolder>() {
    override val hasStableIds: Boolean
        get() = true

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            BookmarkRecentDirectoryItemBinding.inflate(parent.context.layoutInflater, parent, false)
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val binding = holder.binding
        binding.root.setOnClickListener { listener.onItemClick(item) }
        binding.root.setOnLongClickListener { listener.onItemLongClick(item); true }
        binding.nameText.text = item.name
        binding.pathText.text = item.path.toUserFriendlyString()
    }

    data class Item(
        val name: String,
        val path: Path,
        val bookmarkDirectory: BookmarkDirectory? = null,
        val recentDirectory: RecentDirectory? = null
    ) {
        val id: Long
            get() = bookmarkDirectory?.id ?: recentDirectory?.path?.hashCode()?.toLong()
                ?: path.hashCode().toLong()
    }

    class ViewHolder(val binding: BookmarkRecentDirectoryItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    interface Listener {
        fun onItemClick(item: Item)
        fun onItemLongClick(item: Item)
    }
}