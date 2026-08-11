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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.DexAnalyzerFragmentBinding
import me.zhanghai.android.files.databinding.DexClassItemBinding
import me.zhanghai.android.files.databinding.DexMemberItemBinding
import me.zhanghai.android.files.databinding.DexReferenceItemBinding
import me.zhanghai.android.files.databinding.DexReferencesDialogBinding
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

class DexAnalyzerFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: DexAnalyzerFragmentBinding

    private val viewModel by viewModels {
        { DexFileViewModel(args.path, File(requireContext().cacheDir, "dex-analyze")) }
    }

    private lateinit var classesAdapter: ClassListAdapter
    private lateinit var stringsAdapter: StringListAdapter
    private var allStrings: List<Pair<Int, String>> = emptyList()

    // MT-style two-level navigation: the class list swaps to the selected class's member
    // list, and back navigation restores the previous list and its scroll position.
    private lateinit var membersAdapter: MemberListAdapter
    private val navigationStack = ArrayDeque<DexNavState>()
    private var memberDexClass: DexClass? = null
    private var allMembers: List<MemberItem> = emptyList()
    private var memberQuery: String = ""
    private var currentFilteredClasses: List<DexClass> = emptyList()

    private data class DexNavState(val filteredClasses: List<DexClass>, val scrollPosition: Int)

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

        classesAdapter = ClassListAdapter(::openClassMembers)
        binding.classesRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.classesRecyclerView.adapter = classesAdapter

        membersAdapter = MemberListAdapter(
            onHeaderClick = { dexClass ->
                val superclassName = dexClass.superclassName ?: return@MemberListAdapter
                val owner = viewModel.classByName(superclassName)
                if (owner != null) {
                    openClassMembers(owner)
                }
            },
            onMemberClick = { member ->
                val methodDef = member.methodDef
                if (methodDef != null) {
                    openMethodSmali(member.dexClass, methodDef)
                    return@MemberListAdapter
                }
                // A field click jumps to its type when the type is a class descriptor.
                val fieldType = member.fieldDef?.field?.type
                if (fieldType != null && fieldType.startsWith("L") && fieldType.endsWith(";")) {
                    val owner = viewModel.classByName(fieldType)
                    if (owner != null) {
                        openClassMembers(owner)
                    }
                }
            },
            onMemberLongClick = ::showMemberReferences
        )

            stringsAdapter = StringListAdapter(::copyString, ::copyStringId)
        binding.stringsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.stringsRecyclerView.adapter = stringsAdapter

        binding.classSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                if (memberDexClass != null) {
                    memberQuery = newText.orEmpty()
                    filterMembers(newText.orEmpty())
                } else {
                    filterClasses(newText.orEmpty())
                }
                return false
            }
        })
        // System back button pops the member-list navigation (MT style); at the root it
        // falls through to the default (finishing the activity).
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!navigateBack()) {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
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
                    currentFilteredClasses = dexFile.classes.sortedBy { it.className }
                    classesAdapter.replace(currentFilteredClasses, true)
                    allStrings = dexFile.strings.withIndex().map { (index, string) ->
                        index to string
                    }
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
            currentFilteredClasses = filtered
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
                    all.filter { matchesQuery(query, it.second, regex) }
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
                var skipped = 0
                // Case-insensitive file systems (Windows/Android FAT) collide class names
                // that differ only in case ("La/b/C;" vs "La/B/c;"); track lowercase paths
                // so the second class is skipped instead of silently overwriting the first.
                val writtenLowercasePaths = HashSet<String>()
                for (cls in dexFile.classes) {
                    val smali = buildSmaliFile(cls)
                    val relativePath = cls.className.removePrefix("L").removeSuffix(";")
                        .replace('.', '/')
                    val filePath = directory.resolve("$relativePath.smali").normalize()
                    // Path-traversal guard: a crafted DEX can embed ".." segments or an
                    // absolute path in the class name; never write outside the export dir.
                    if (!filePath.startsWith(directory)) {
                        skipped++
                        continue
                    }
                    if (!writtenLowercasePaths.add(filePath.toString().lowercase())) {
                        skipped++
                        continue
                    }
                    Files.createDirectories(filePath.parent!!)
                    Files.write(filePath, smali.toByteArray())
                    count++
                }
                if (skipped > 0) {
                    "${count}\n(${getString(R.string.dex_export_smali_skipped_format, skipped)})"
                } else {
                    count.toString()
                }
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

    /**
     * MT-style navigation: swaps the list to [dexClass]'s members. The previous list
     * (with its scroll position) is pushed onto the navigation stack so the system back
     * button / toolbar up arrow restores it.
     */
    private fun openClassMembers(dexClass: DexClass) {
        val layoutManager = binding.classesRecyclerView.layoutManager as? LinearLayoutManager
        navigationStack.addLast(
            DexNavState(
                currentFilteredClasses,
                layoutManager?.findFirstVisibleItemPosition() ?: 0
            )
        )
        memberDexClass = dexClass
        allMembers = dexClass.fields.map { MemberItem(dexClass, it, null) } +
            dexClass.methods.map { MemberItem(dexClass, null, it) }
        binding.classesRecyclerView.adapter = membersAdapter
        binding.classSearchView.setQuery("", false)
        binding.classSearchView.queryHint = getString(R.string.dex_search_members)
        filterMembers("")
        updateTitle(dexClass.className)
    }

    private fun updateTitle(title: String) {
        (requireActivity() as AppCompatActivity).supportActionBar?.title = title
    }

    private fun filterMembers(query: String) {
        val dexClass = memberDexClass ?: return
        lifecycleScope.launch {
            val members = withContext(Dispatchers.Default) {
                val filtered = if (query.isEmpty()) {
                    allMembers
                } else {
                    allMembers.filter { it.text.contains(query, ignoreCase = true) }
                }
                listOf(DexMemberItem.Header(dexClass)) +
                    filtered.map { DexMemberItem.Member(it) }
            }
            membersAdapter.replace(members, true)
        }
    }

    /**
     * Pops the navigation stack, restoring the previous list and scroll position.
     *
     * @return true when a level was popped; false when already at the root list.
     */
    private fun navigateBack(): Boolean {
        val state = navigationStack.removeLastOrNull() ?: return false
        memberDexClass = null
        binding.classesRecyclerView.adapter = classesAdapter
        classesAdapter.replace(state.filteredClasses, true)
        binding.classesRecyclerView.scrollToPosition(state.scrollPosition)
        binding.classSearchView.queryHint = getString(R.string.dex_search_classes)
        binding.classSearchView.setQuery("", false)
        updateTitle(args.path.fileName.toString())
        return true
    }

    /** Public entry for the activity's toolbar up arrow. */
    fun navigateUp(): Boolean = navigateBack()

    private fun showMemberReferences(member: MemberItem) {
        if (member.methodDef != null) {
            val key = member.methodDef.method.toString()
            findReferencesAsync(key) { viewModel.findMethodReferences(it) }
        } else if (member.fieldDef != null) {
            val field = member.fieldDef.field
            val key = "${field.className}->${field.name}:${field.type}"
            findReferencesAsync(key) { viewModel.findFieldReferences(it) }
        }
    }

    /** Runs the (potentially expensive) reference scan off the main thread. */
    private fun findReferencesAsync(
        target: String,
        find: (String) -> List<Pair<String, String>>
    ) {
        lifecycleScope.launch {
            val references = withContext(Dispatchers.Default) { find(target) }
            showFindReferencesDialog(target, references)
        }
    }

    private fun showFindReferencesDialog(
        target: String,
        references: List<Pair<String, String>>
    ) {
        val dialogBinding = DexReferencesDialogBinding.inflate(layoutInflater)
        val referenceAdapter = ReferenceListAdapter { reference ->
            val owner = viewModel.classByName(reference.ownerClass)
            if (owner != null) {
                openClassMembers(owner)
            }
        }
        dialogBinding.referencesRecyclerView.layoutManager = LinearLayoutManager(context)
        dialogBinding.referencesRecyclerView.adapter = referenceAdapter
        val items = references.map { ReferenceItem(it.first, it.second) }
        referenceAdapter.replace(items, true)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dex_find_references)
            .setMessage(target)
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
        // The dex++ smali editor: opens the method's baksmali output for editing and can
        // reassemble + write it back (into the .dex or the APK entry, re-signed).
        val intent = DexSmaliEditorActivity::class.createIntent()
        intent.extraPath = args.path
        intent.putExtra(DexSmaliEditorFragment.EXTRA_SOURCE_DEX, dexClass.sourceDex)
        intent.putExtra(DexSmaliEditorFragment.EXTRA_CLASS_NAME, dexClass.className)
        intent.putExtra(
            DexSmaliEditorFragment.EXTRA_METHOD_KEY,
            method.method.name + method.method.shortDescriptor
        )
        startActivity(intent)
    }

    private fun copyString(string: Pair<Int, String>) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, string.second))
        showToast(getString(R.string.dex_string_copied))
    }

    private fun copyStringId(id: Int) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(null, id.toString()))
        showToast(getString(R.string.dex_string_id_copied, id))
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
    private val onStringClick: (Pair<Int, String>) -> Unit,
    private val onStringIdLongClick: (Int) -> Unit
) : ListAdapter<Pair<Int, String>, StringListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<Pair<Int, String>>() {
        override fun areItemsTheSame(oldItem: Pair<Int, String>, newItem: Pair<Int, String>): Boolean =
            oldItem.first == newItem.first

        override fun areContentsTheSame(oldItem: Pair<Int, String>, newItem: Pair<Int, String>): Boolean =
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
        val (id, string) = getItem(position)
        val context = holder.binding.root.context
        holder.binding.stringText.text = context.getString(
            R.string.dex_string_item_format, id, string
        )
        holder.binding.root.setOnClickListener { onStringClick(id to string) }
        holder.binding.root.setOnLongClickListener {
            onStringIdLongClick(id)
            true
        }
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
            // Standard smali names <init>/<clinit> carry no class-name prefix.
            val m = methodDef!!.method
            "${DexAccessFlags.forMethod(methodDef!!.accessFlags)} ${m.name}${m.shortDescriptor}"
        }
}

/** A member-list row: the class header (tappable to jump to the superclass) or a member. */
private sealed class DexMemberItem {
    class Header(val dexClass: DexClass) : DexMemberItem()
    class Member(val item: MemberItem) : DexMemberItem()
}

private class MemberListAdapter(
    private val onHeaderClick: (DexClass) -> Unit,
    private val onMemberClick: (MemberItem) -> Unit,
    private val onMemberLongClick: (MemberItem) -> Unit
) : ListAdapter<DexMemberItem, MemberListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<DexMemberItem>() {
        override fun areItemsTheSame(oldItem: DexMemberItem, newItem: DexMemberItem): Boolean =
            when {
                oldItem is DexMemberItem.Header && newItem is DexMemberItem.Header ->
                    oldItem.dexClass.className == newItem.dexClass.className
                oldItem is DexMemberItem.Member && newItem is DexMemberItem.Member ->
                    oldItem.item == newItem.item
                else -> false
            }

        override fun areContentsTheSame(oldItem: DexMemberItem, newItem: DexMemberItem): Boolean =
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
        when (val item = getItem(position)) {
            is DexMemberItem.Header -> {
                val dexClass = item.dexClass
                val superclass = dexClass.superclassName?.let {
                    "extends $it"
                } ?: "extends <none>"
                holder.binding.memberText.text =
                    "${dexClass.className}\n${DexAccessFlags.forClass(dexClass.accessFlags)} · $superclass"
                holder.binding.root.setOnClickListener {
                    val superclassName = dexClass.superclassName ?: return@setOnClickListener
                    onHeaderClick(item.dexClass)
                }
                holder.binding.root.setOnLongClickListener {
                    onHeaderClick(item.dexClass)
                    true
                }
            }
            is DexMemberItem.Member -> {
                val member = item.item
                holder.binding.memberText.text = member.text
                holder.binding.root.setOnClickListener { onMemberClick(member) }
                holder.binding.root.setOnLongClickListener {
                    onMemberLongClick(member)
                    true
                }
            }
        }
    }
}

private data class ReferenceItem(
    val ownerClass: String,
    val kind: String
)

private class ReferenceListAdapter(
    private val onReferenceClick: (ReferenceItem) -> Unit
) : ListAdapter<ReferenceItem, ReferenceListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<ReferenceItem>() {
        override fun areItemsTheSame(oldItem: ReferenceItem, newItem: ReferenceItem): Boolean =
            oldItem == newItem

        override fun areContentsTheSame(oldItem: ReferenceItem, newItem: ReferenceItem): Boolean =
            oldItem == newItem
    }
) {
    class ViewHolder(val binding: DexReferenceItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            DexReferenceItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.ownerText.text = item.ownerClass
        holder.binding.kindText.text = item.kind
        holder.binding.root.setOnClickListener { onReferenceClick(item) }
    }
}
