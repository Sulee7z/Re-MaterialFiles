/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.elf

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
import java8.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DexStringItemBinding
import me.zhanghai.android.files.databinding.ElfAnalyzerFragmentBinding
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels

class ElfAnalyzerFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: ElfAnalyzerFragmentBinding

    private val viewModel by viewModels { { ElfFileViewModel(args.path) } }

    private lateinit var stringsAdapter: StringListAdapter
    private var allStrings: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ElfAnalyzerFragmentBinding.inflate(inflater, container, false).also {
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

        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                onTabChanged(tab.position)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        stringsAdapter = StringListAdapter(::copyString)
        binding.stringsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.stringsRecyclerView.adapter = stringsAdapter

        binding.stringSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterStrings(newText.orEmpty())
                return false
            }
        })

        viewModel.elfFileLiveData.observe(viewLifecycleOwner) { state ->
            when (state) {
                is DataState.Loading -> {
                    binding.progress.isVisible = true
                    binding.errorText.isVisible = false
                }
                is DataState.Error -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = true
                    binding.errorText.text = state.throwable.javaClass.simpleName + ": " +
                        (state.throwable.localizedMessage ?: getString(R.string.elf_analyze_error))
                }
                is DataState.Success -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = false
                    val elfFile = state.data
                    binding.infoText.text = formatInfo(elfFile)
                    allStrings = elfFile.strings
                    filterStrings(binding.stringSearchView.query?.toString().orEmpty())
                }
            }
        }
    }

    private fun onTabChanged(position: Int) {
        binding.infoView.isVisible = position == 0
        binding.stringsView.isVisible = position == 1
    }

    private fun formatInfo(elfFile: ElfFile): String {
        val builder = StringBuilder()
        builder.append("Class: ").append(elfFile.className).append('\n')
        builder.append("Endian: ").append(elfFile.endianness).append('\n')
        builder.append("OS/ABI: ").append(elfFile.osAbi).append('\n')
        builder.append("Type: ").append(elfFile.type).append('\n')
        builder.append("Machine: ").append(elfFile.machine).append('\n')
        builder.append("Entry point: 0x").append(elfFile.entryPoint.toString(16)).append('\n')
        builder.append('\n').append("Sections (").append(elfFile.sections.size).append("):\n")
        elfFile.sections.forEach { section ->
            if (section.name.isNotEmpty() || section.type != "NULL") {
                builder.append(
                    "  %-24s %-12s addr=0x%x offset=0x%x size=0x%x\n".format(
                        section.name, section.type, section.address, section.offset, section.size
                    )
                )
            }
        }
        builder.append('\n').append("Program headers (").append(elfFile.programHeaders.size)
            .append("):\n")
        elfFile.programHeaders.forEach { header ->
            builder.append(
                "  %-14s offset=0x%x vaddr=0x%x filesz=0x%x memsz=0x%x %s\n".format(
                    header.type, header.offset, header.virtualAddress, header.fileSize,
                    header.memorySize, header.flags
                )
            )
        }
        return builder.toString()
    }

    private fun filterStrings(query: String) {
        lifecycleScope.launch {
            val (filtered, total) = withContext(Dispatchers.Default) {
                val all = allStrings
                val filtered = if (query.isEmpty()) {
                    all
                } else {
                    all.filter { it.contains(query, ignoreCase = true) }
                }
                filtered to all.size
            }
            stringsAdapter.replace(filtered, true)
            binding.stringCountText.text = getString(
                R.string.apk_string_count_format, filtered.size, total
            )
        }
    }

    private fun copyString(string: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, string))
        showToast(getString(R.string.dex_string_copied))
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

private class StringListAdapter(
    private val onStringClick: (String) -> Unit
) : ListAdapter<String, StringListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: String, newItem: String): Boolean =
            oldItem == newItem
    }
) {
    class ViewHolder(val binding: DexStringItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            DexStringItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.stringText.text = item
        holder.binding.root.setOnClickListener { onStringClick(item) }
    }
}
