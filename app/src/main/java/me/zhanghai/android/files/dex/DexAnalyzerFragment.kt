/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

import android.content.ClipData
import android.content.ClipboardManager
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DexAnalyzerFragmentBinding
import me.zhanghai.android.files.databinding.DexClassDetailDialogBinding
import me.zhanghai.android.files.databinding.DexClassItemBinding
import me.zhanghai.android.files.databinding.DexMemberItemBinding
import me.zhanghai.android.files.databinding.DexStringItemBinding
import me.zhanghai.android.files.filelist.FileListActivity
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.viewer.text.TextEditorActivity

class DexAnalyzerFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: DexAnalyzerFragmentBinding

    private val viewModel by viewModels { { DexFileViewModel(args.path) } }

    private lateinit var classesAdapter: ClassListAdapter
    private lateinit var stringsAdapter: StringListAdapter
    private var allStrings: List<String> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = DexAnalyzerFragmentBinding.inflate(inflater, container, false).also {
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

        activity.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.dex_analyzer, menu)
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when (item.itemId) {
                    R.id.action_export_smali -> {
                        showExportSmaliDialog()
                        true
                    }
                    else -> false
                }
        }, viewLifecycleOwner)

        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab) {
                onTabChanged(tab.position)
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}

            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab) {}
        })

        classesAdapter = ClassListAdapter(::showClassDetail)
        binding.classesRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.classesRecyclerView.adapter = classesAdapter

        stringsAdapter = StringListAdapter(::copyString)
        binding.stringsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.stringsRecyclerView.adapter = stringsAdapter

        binding.classSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterClasses(newText.orEmpty())
                return false
            }
        })
        binding.stringSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterStrings(newText.orEmpty())
                return false
            }
        })

        viewModel.dexFileLiveData.observe(viewLifecycleOwner) { state ->
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
                            ?: getString(R.string.dex_analyze_error_unknown))
                }
                is DataState.Success -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = false
                    val dexFile = state.data
                    classesAdapter.replace(dexFile.classes.sortedBy { it.className }, true)
                    allStrings = dexFile.strings
                    filterStrings(binding.stringSearchView.query?.toString().orEmpty())
                }
            }
        }
    }

    private fun onTabChanged(position: Int) {
        binding.classesView.isVisible = position == 0
        binding.stringsView.isVisible = position == 1
    }

    private fun filterClasses(query: String) {
        lifecycleScope.launch {
            val classes = (viewModel.dexFileLiveData.value as? DataState.Success)?.data?.classes
                ?: return@launch
            val filtered = withContext(Dispatchers.Default) {
                if (query.isEmpty()) {
                    classes.sortedBy { it.className }
                } else {
                    classes.filter { it.className.contains(query, ignoreCase = true) }
                        .sortedBy { it.className }
                }
            }
            classesAdapter.replace(filtered, true)
        }
    }

    private fun filterStrings(query: String) {
        val regex = binding.regexCheckBox.isChecked
        lifecycleScope.launch {
            val (filtered, total) = withContext(Dispatchers.Default) {
                val all = allStrings
                val filtered = if (query.isEmpty()) {
                    all
                } else {
                    all.filter { matchesQuery(query, it, regex) }
                }
                filtered to all.size
            }
            stringsAdapter.replace(filtered, true)
            binding.stringCountText.text = getString(
                R.string.dex_string_count_format, filtered.size, total
            )
        }
    }

    private fun matchesQuery(query: String, value: String, regex: Boolean): Boolean {
        if (!regex) {
            return value.contains(query, ignoreCase = true)
        }
        return try {
            Regex(query, RegexOption.IGNORE_CASE).containsMatchIn(value)
        } catch (e: Exception) {
            false
        }
    }

    private val exportSmaliLauncher = registerForActivityResult(
        FileListActivity.OpenDirectoryContract()
    ) { directory ->
        if (directory != null) {
            exportSmali(directory)
        }
    }

    private fun showExportSmaliDialog() {
        val dexFile = viewModel.dexFile() ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dex_export_smali)
            .setMessage(getString(R.string.dex_class_description_format, "", dexFile.classes.size))
            .setPositiveButton(R.string.dex_export_smali) { _, _ ->
                exportSmaliLauncher.launch(null)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportSmali(directory: Path) {
        val dexFile = viewModel.dexFile() ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                var count = 0
                for (cls in dexFile.classes) {
                    val smali = buildSmaliFile(cls)
                    val relativePath = cls.className.removePrefix("L").removeSuffix(";")
                        .replace('.', '/')
                    val filePath = directory.resolve("$relativePath.smali")
                    Files.createDirectories(filePath.parent!!)
                    Files.write(filePath, smali.toByteArray())
                    count++
                }
                count
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    showToast(e.localizedMessage ?: getString(R.string.dex_export_smali_error))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                showToast(getString(R.string.dex_export_smali_done_format, result))
            }
        }
    }

    private fun buildSmaliFile(dexClass: DexClass): String {
        val builder = StringBuilder()
        builder.append(".class ")
            .append(DexAccessFlags.forClass(dexClass.accessFlags))
            .append(' ').append(dexClass.className).append('\n')
        dexClass.superclassName?.let {
            builder.append(".super ").append(it).append('\n')
        }
        dexClass.interfaces.forEach {
            builder.append(".implements ").append(it).append('\n')
        }
        if (dexClass.sourceFile != null) {
            builder.append(".source \"").append(dexClass.sourceFile).append("\"\n")
        }
        builder.append('\n')
        dexClass.fields.forEach { fieldDef ->
            builder.append(".field ")
                .append(DexAccessFlags.forField(fieldDef.accessFlags)).append(' ')
                .append(fieldDef.field.name).append(':').append(fieldDef.field.type).append('\n')
        }
        dexClass.methods.forEach { methodDef ->
            builder.append('\n').append(viewModel.disassemble(methodDef)).append('\n')
        }
        return builder.toString()
    }

    private fun showClassDetail(dexClass: DexClass) {
        val dialogBinding = DexClassDetailDialogBinding.inflate(layoutInflater)
        dialogBinding.classNameText.text = dexClass.className
        dialogBinding.accessText.text = DexAccessFlags.forClass(dexClass.accessFlags)
        dialogBinding.superclassText.text = dexClass.superclassName?.let {
            getString(R.string.dex_class_superclass_format, it)
        } ?: getString(R.string.dex_class_superclass_none)
        val members = dexClass.fields.map { MemberItem(dexClass, it, null) } +
            dexClass.methods.map { MemberItem(dexClass, null, it) }
        val membersAdapter = MemberListAdapter { member ->
            member.methodDef?.let { openMethodSmali(member.dexClass, it) }
        }
        dialogBinding.membersRecyclerView.layoutManager = LinearLayoutManager(context)
        dialogBinding.membersRecyclerView.adapter = membersAdapter
        membersAdapter.replace(members, true)
        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openMethodSmali(dexClass: DexClass, method: DexMethodDef) {
        if (method.code == null) {
            showToast(getString(R.string.dex_method_no_code))
            return
        }
        val smali = viewModel.disassemble(method)
        if (smali.isEmpty()) {
            showToast(getString(R.string.dex_method_no_code))
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val path = try {
                writeSmali(smali, dexClass)
            } catch (e: IOException) {
                withContext(Dispatchers.Main) {
                    showToast(e.localizedMessage ?: getString(R.string.dex_smali_write_error))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val intent = TextEditorActivity::class.createIntent()
                intent.extraPath = path
                startActivity(intent)
            }
        }
    }

    private fun writeSmali(smali: String, dexClass: DexClass): Path {
        val directory = File(requireContext().cacheDir, "dex-smali")
        directory.mkdirs()
        val safeClassName = dexClass.className.removePrefix("L")
            .removeSuffix(";").replace('/', '_')
        val file = File(directory, "$safeClassName.smali")
        Files.write(Paths.get(file.absolutePath), smali.toByteArray())
        return Paths.get(file.absolutePath)
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

private class ClassListAdapter(
    private val onClassClick: (DexClass) -> Unit
) : ListAdapter<DexClass, ClassListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<DexClass>() {
        override fun areItemsTheSame(oldItem: DexClass, newItem: DexClass): Boolean =
            oldItem.className == newItem.className

        override fun areContentsTheSame(oldItem: DexClass, newItem: DexClass): Boolean =
            oldItem == newItem
    }
) {
    class ViewHolder(val binding: DexClassItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            DexClassItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        holder.binding.nameText.text = item.className
        holder.binding.descriptionText.text = context.getString(
            R.string.dex_class_description_format,
            DexAccessFlags.forClass(item.accessFlags), item.methods.size
        )
        holder.binding.root.setOnClickListener { onClassClick(item) }
    }
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

private data class MemberItem(
    val dexClass: DexClass,
    val fieldDef: DexFieldDef?,
    val methodDef: DexMethodDef?
) {
    val text: String
        get() = if (fieldDef != null) {
            val f = fieldDef.field
            "${DexAccessFlags.forField(fieldDef.accessFlags)} ${f.type} ${f.name}"
        } else {
            val m = methodDef!!.method
            val prefix = if (m.name == "<init>" || m.name == "<clinit>") {
                "${m.className}->"
            } else {
                ""
            }
            "${DexAccessFlags.forMethod(methodDef!!.accessFlags)} $prefix${m.name}${m.shortDescriptor}"
        }
}

private class MemberListAdapter(
    private val onMemberClick: (MemberItem) -> Unit
) : ListAdapter<MemberItem, MemberListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<MemberItem>() {
        override fun areItemsTheSame(oldItem: MemberItem, newItem: MemberItem): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: MemberItem, newItem: MemberItem): Boolean =
            oldItem == newItem
    }
) {
    class ViewHolder(val binding: DexMemberItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            DexMemberItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.memberText.text = item.text
        holder.binding.root.setOnClickListener { onMemberClick(item) }
    }
}
