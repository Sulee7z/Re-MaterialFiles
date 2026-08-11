/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.arsc

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java8.nio.file.Files
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.WriteWith
import me.zhanghai.android.files.R
import me.zhanghai.android.files.apksign.AutoSigner
import me.zhanghai.android.files.apkutil.ApkRebuilder
import me.zhanghai.android.files.databinding.ArscEditorFragmentBinding
import me.zhanghai.android.files.databinding.ArscPackageItemBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast

/**
 * The ARSC resource-table editor. Parses resources.arsc (directly or from inside an APK)
 * with [ArscParser], lets the user drill down packages -> type/config -> entries and edit
 * simple values (strings, ints, hex, booleans, colors), then serializes the whole table
 * with [ArscWriter]:
 *  - for a standalone .arsc: the file is overwritten through the file-job service,
 *  - for an APK: resources.arsc is replaced and the APK is re-signed into a new
 *    "-edited.apk" next to the original.
 */
class ArscEditorFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: ArscEditorFragmentBinding
    private lateinit var rowsAdapter: ArscRowAdapter

    private var arscFile: ArscFile? = null
    private var isDirty = false
    private var loadError: String? = null

    // MT-style page navigation: package list -> type list -> entry list, with the system
    // back button / toolbar up arrow stepping back through the levels.
    private val navigationStack = ArrayDeque<ArscNavState>()
    private var currentTitle: String? = null

    private data class ArscNavState(val rows: List<ArscRow>, val scrollPosition: Int)

    private val isApkPath: Boolean
        get() = args.path.toString().endsWith(".apk", ignoreCase = true)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ArscEditorFragmentBinding.inflate(inflater, container, false).also {
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

        rowsAdapter = ArscRowAdapter { row ->
            when (row) {
                is ArscRow.PackageRow -> showTypes(row.pkg)
                is ArscRow.TypeRow -> showEntries(row.pkg, row.type)
                is ArscRow.EntryRow -> showEntry(row.pkg, row.type, row.entry)
            }
        }
        binding.packagesRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.packagesRecyclerView.adapter = rowsAdapter

        // System back button pops the page navigation; at the root it finishes.
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

        activity.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.arsc_editor, menu)
                menu.findItem(R.id.action_arsc_save).isEnabled =
                    arscFile != null && isDirty && loadError == null
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when (item.itemId) {
                    R.id.action_arsc_save -> {
                        save()
                        true
                    }
                    else -> false
                }
        }, viewLifecycleOwner)

        load()
    }

    private fun load() {
        binding.progress.isVisible = true
        binding.errorText.isVisible = false
        binding.packagesRecyclerView.isVisible = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val cacheDirectory = File(requireContext().cacheDir, "arsc-edit")
                cacheDirectory.mkdirs()
                val bytes = if (isApkPath) {
                    val inputFile = ApkRebuilder.copyToCache(
                        args.path, cacheDirectory, "edit-source.apk"
                    )
                    ApkRebuilder.readEntry(inputFile, "resources.arsc")
                        ?: throw ArscParseException("resources.arsc not found in APK")
                } else {
                    Files.newInputStream(args.path).use { it.readBytes() }
                }
                ArscParser.parse(bytes)
            } catch (e: Throwable) {
                loadError = e.localizedMessage ?: e.javaClass.simpleName
                null
            }
            withContext(Dispatchers.Main) {
                binding.progress.isVisible = false
                if (result == null) {
                    binding.errorText.text = loadError
                    binding.errorText.isVisible = true
                } else {
                    arscFile = result
                    rowsAdapter.globalPool = result.globalPool
                    showPackages(result)
                    binding.packagesRecyclerView.isVisible = true
                }
                requireActivity().invalidateOptionsMenu()
            }
        }
    }

    private fun showPackages(arsc: ArscFile) {
        setRows(
            arsc.packages.map { ArscRow.PackageRow(it) },
            args.path.fileName.toString()
        )
    }

    private fun showTypes(pkg: ArscPackage) {
        val rows = pkg.types.map { ArscRow.TypeRow(pkg, it) }
        if (rows.isEmpty()) {
            showToast(getString(R.string.arsc_no_types))
            return
        }
        pushAndSetRows(rows, getString(R.string.arsc_package_title_format, pkg.name))
    }

    private fun showEntries(pkg: ArscPackage, type: ArscType) {
        val rows = type.entries.mapNotNull { entry ->
            if (entry == null) null else ArscRow.EntryRow(pkg, type, entry)
        }
        if (rows.isEmpty()) {
            showToast(getString(R.string.arsc_no_entries))
            return
        }
        pushAndSetRows(
            rows,
            getString(
                R.string.arsc_type_title_format,
                pkg.typeName(type.id), configLabel(type.config)
            )
        )
    }

    private fun pushAndSetRows(rows: List<ArscRow>, title: String) {
        val layoutManager = binding.packagesRecyclerView.layoutManager as? LinearLayoutManager
        navigationStack.addLast(
            ArscNavState(
                currentRows,
                layoutManager?.findFirstVisibleItemPosition() ?: 0
            )
        )
        setRows(rows, title)
    }

    private var currentRows: List<ArscRow> = emptyList()

    private fun setRows(rows: List<ArscRow>, title: String) {
        currentRows = rows
        rowsAdapter.replace(rows, true)
        currentTitle = title
        updateTitle(title)
    }

    private fun updateTitle(title: String) {
        (requireActivity() as AppCompatActivity).supportActionBar?.title = title
    }

    /** Pops the navigation stack; true when a level was popped. */
    private fun navigateBack(): Boolean {
        val state = navigationStack.removeLastOrNull() ?: return false
        rowsAdapter.replace(state.rows, true)
        binding.packagesRecyclerView.scrollToPosition(state.scrollPosition)
        val title = currentTitle
        if (title != null) {
            updateTitle(title)
        }
        return true
    }

    /** Public entry for the activity's toolbar up arrow. */
    fun navigateUp(): Boolean = navigateBack()

    private fun showEntry(pkg: ArscPackage, type: ArscType, entry: ArscEntry) {
        if (entry.isComplex) {
            showComplexEntry(pkg, type, entry)
        } else {
            showEditEntryDialog(pkg, type, entry)
        }
    }

    private fun showComplexEntry(pkg: ArscPackage, type: ArscType, entry: ArscEntry) {
        val lines = buildList {
            add(pkg.keyName(entry.keyIndex))
            add(getString(R.string.arsc_entry_type_format, pkg.typeName(type.id)))
            entry.mapItems?.forEach { item ->
                add(String.format("0x%08x: %s", item.name, formatMapValue(item.value)))
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.arsc_entry_complex))
            .setMessage(lines.joinToString("\n"))
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun formatMapValue(value: ArscValue): String {
        val arsc = arscFile ?: return ""
        return if (value.isString) {
            val index = value.data.toInt()
            if (index in arsc.globalPool.strings.indices) {
                arsc.globalPool.strings[index]
            } else {
                ""
            }
        } else {
            String.format("0x%08x (%d)", value.data, value.data)
        }
    }

    /** Renders an entry value for display. */
    private fun formatValue(value: ArscValue, globalPool: ArscStringPool): String {
        if (value.isString) {
            val index = value.data.toInt()
            return if (index in globalPool.strings.indices) {
                globalPool.strings[index]
            } else {
                "(string#$index)"
            }
        }
        return when (value.dataType) {
            ArscValue.TYPE_NULL -> "null"
            ArscValue.TYPE_REFERENCE, ArscValue.TYPE_ATTRIBUTE ->
                String.format("@0x%08x", value.data)
            ArscValue.TYPE_INT_DEC -> value.data.toString()
            ArscValue.TYPE_INT_HEX -> String.format("0x%08x", value.data)
            ArscValue.TYPE_INT_BOOLEAN -> if (value.data != 0L) "true" else "false"
            ArscValue.TYPE_INT_COLOR_ARGB8, ArscValue.TYPE_INT_COLOR_RGB8,
            ArscValue.TYPE_INT_COLOR_ARGB4, ArscValue.TYPE_INT_COLOR_RGB4 ->
                String.format("#%08x", value.data)
            else -> String.format("0x%08x", value.data)
        }
    }

    private fun showEditEntryDialog(pkg: ArscPackage, type: ArscType, entry: ArscEntry) {
        val arsc = arscFile ?: return
        val value = entry.value ?: return
        val field = EditText(requireContext()).apply {
            setText(formatValue(value, arsc.globalPool))
            selectAll()
        }
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(field)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.arsc_entry_edit_title_format, pkg.keyName(entry.keyIndex)))
            .setMessage(
                getString(
                    R.string.arsc_entry_value_type_format, ArscValue.typeName(value.dataType)
                )
            )
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyEdit(pkg, type, entry, value, field.text?.toString().orEmpty())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Parses the edited text back into the entry value, mutating the model. */
    private fun applyEdit(
        pkg: ArscPackage, type: ArscType, entry: ArscEntry, value: ArscValue, text: String
    ) {
        val arsc = arscFile ?: return
        when (value.dataType) {
            ArscValue.TYPE_STRING -> {
                // Append the new string to the global pool and point the entry at it.
                val index = arsc.globalPool.strings.size
                arsc.globalPool.strings.add(text)
                value.data = index.toLong()
            }
            ArscValue.TYPE_INT_DEC -> {
                val parsed = text.trim().toLongOrNull()
                if (parsed == null) {
                    showToast(getString(R.string.arsc_value_invalid))
                    return
                }
                value.data = parsed
            }
            ArscValue.TYPE_INT_HEX -> {
                val parsed = text.trim().removePrefix("0x").toLongOrNull(16)
                if (parsed == null) {
                    showToast(getString(R.string.arsc_value_invalid))
                    return
                }
                value.data = parsed
            }
            ArscValue.TYPE_INT_BOOLEAN -> {
                val parsed = when (text.trim().lowercase()) {
                    "true", "1", "yes" -> 1L
                    "false", "0", "no" -> 0L
                    else -> {
                        showToast(getString(R.string.arsc_value_invalid))
                        return
                    }
                }
                value.data = parsed
            }
            ArscValue.TYPE_INT_COLOR_ARGB8, ArscValue.TYPE_INT_COLOR_RGB8,
            ArscValue.TYPE_INT_COLOR_ARGB4, ArscValue.TYPE_INT_COLOR_RGB4 -> {
                var hex = text.trim().removePrefix("#")
                val parsed = when (hex.length) {
                    6 -> 0xff000000L or (hex.toLongOrNull(16) ?: run {
                        showToast(getString(R.string.arsc_value_invalid)); return
                    })
                    8 -> hex.toLongOrNull(16) ?: run {
                        showToast(getString(R.string.arsc_value_invalid)); return
                    }
                    else -> {
                        showToast(getString(R.string.arsc_value_invalid))
                        return
                    }
                }
                value.data = parsed
            }
            else -> {
                showToast(getString(R.string.arsc_value_not_editable))
                return
            }
        }
        isDirty = true
        requireActivity().invalidateOptionsMenu()
        showToast(getString(R.string.arsc_value_edited))
    }

    /** Decodes a ResTable_config blob into a resource-qualifier style label. */
    private fun configLabel(config: ByteArray): String =
        configLabel(config, requireContext())

    companion object {
        /** Decodes a ResTable_config blob into a resource-qualifier style label. */
        fun configLabel(config: ByteArray, context: Context): String {
            if (config.size < 28 || config.all { it == 0.toByte() }) {
                return context.getString(R.string.arsc_config_default)
            }
            val parts = ArrayList<String>()
            val mcc = u16At(config, 4)
            val mnc = u16At(config, 6)
            if (mcc != 0) parts += "mcc$mcc"
            if (mnc != 0) parts += "mnc$mnc"
            val language = String(config, 8, 2)
            val country = String(config, 10, 2)
            if (language.isNotBlank() && language != "\u0000\u0000") {
                parts += if (country.isNotBlank() && country != "\u0000\u0000") {
                    "${language}-r$country"
                } else {
                    language
                }
            }
            val orientation = config[12].toInt() and 0xff
            if (orientation == 1) parts += "port"
            if (orientation == 2) parts += "land"
            val density = u16At(config, 14)
            val densityLabel = when (density) {
                0 -> ""
                0xfffe -> "anydpi"
                0xffff -> "nodpi"
                120 -> "ldpi"
                160 -> "mdpi"
                213 -> "tvdpi"
                240 -> "hdpi"
                320 -> "xhdpi"
                480 -> "xxhdpi"
                640 -> "xxxhdpi"
                else -> "density$density"
            }
            if (densityLabel.isNotEmpty()) parts += densityLabel
            val screenWidth = u16At(config, 20)
            val screenHeight = u16At(config, 22)
            if (screenWidth != 0 && screenHeight != 0) parts += "${screenWidth}x$screenHeight"
            val sdkVersion = u16At(config, 24)
            if (sdkVersion != 0) parts += "v$sdkVersion"
            val screenLayout = config[28].toInt() and 0xff
            val screenSize = screenLayout and 0x0f
            if (screenSize == 1) parts += "small"
            if (screenSize == 2) parts += "normal"
            if (screenSize == 3) parts += "large"
            if (screenSize == 4) parts += "xlarge"
            if (screenLayout and 0x40 != 0) parts += "ldrtl"
            val uiMode = config[29].toInt() and 0xff
            when (uiMode and 0x0f) {
                1 -> parts += "car"
                2 -> parts += "desk"
                3 -> parts += "television"
                4 -> parts += "appliance"
                5 -> parts += "watch"
                6 -> parts += "vrheadset"
            }
            if (uiMode and 0x10 != 0) parts += "night"
            val smallestWidth = u16At(config, 30)
            if (smallestWidth != 0) parts += "sw${smallestWidth}dp"
            val widthDp = u16At(config, 32)
            val heightDp = u16At(config, 34)
            if (widthDp != 0) parts += "w${widthDp}dp"
            if (heightDp != 0) parts += "h${heightDp}dp"
            return if (parts.isEmpty()) {
                context.getString(R.string.arsc_config_default)
            } else {
                parts.joinToString("-")
            }
        }

        private fun u16At(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    /** Serializes the model and writes it back (overwriting .arsc or re-signing the APK). */
    private fun save() {
        val arsc = arscFile ?: return
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.arsc_editor_save_progress)
            .setView(android.widget.ProgressBar(requireContext()))
            .setCancelable(false)
            .show()
        lifecycleScope.launch(Dispatchers.IO) {
            val bytes = try {
                ArscWriter.write(arsc)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showToast(e.localizedMessage ?: getString(R.string.arsc_editor_save_error))
                }
                return@launch
            }
            if (isApkPath) {
                val cacheDirectory = File(requireContext().cacheDir, "arsc-edit")
                val outputName = args.path.fileName.toString()
                    .substringBeforeLast('.', args.path.fileName.toString()) + "-edited.apk"
                try {
                    val inputFile = ApkRebuilder.copyToCache(
                        args.path, cacheDirectory, "save-source.apk"
                    )
                    val rebuilt = File(cacheDirectory, "rebuilt.apk")
                    val outputFile = File(cacheDirectory, "output.apk")
                    try {
                        ApkRebuilder.rebuild(
                            inputFile, rebuilt, mapOf("resources.arsc" to bytes)
                        )
                        AutoSigner.sign(requireContext(), rebuilt, outputFile)
                        val outputPath = args.path.parent.resolve(outputName)
                        Files.newInputStream(Paths.get(outputFile.absolutePath)).use { input ->
                            Files.newOutputStream(outputPath).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } finally {
                        inputFile.delete()
                        rebuilt.delete()
                        outputFile.delete()
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        showToast(
                            e.localizedMessage ?: getString(R.string.arsc_editor_save_error)
                        )
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showToast(getString(R.string.arsc_editor_save_done_format, outputName))
                    isDirty = false
                    requireActivity().invalidateOptionsMenu()
                }
            } else {
                FileJobService.write(args.path, bytes, requireContext()) { success ->
                    progressDialog.dismiss()
                    if (success) {
                        isDirty = false
                        requireActivity().invalidateOptionsMenu()
                        showToast(getString(R.string.arsc_editor_save_done_format, args.path.fileName.toString()))
                    } else {
                        showToast(getString(R.string.arsc_editor_save_error))
                    }
                }
            }
        }
    }

    @Parcelize
    class Args(val path: @WriteWith<ParcelableParceler> Path) : ParcelableArgs
}

/** A navigation row: a package, a type (with config), or an entry (with value). */
private sealed class ArscRow {
    class PackageRow(val pkg: ArscPackage) : ArscRow()
    class TypeRow(val pkg: ArscPackage, val type: ArscType) : ArscRow()
    class EntryRow(val pkg: ArscPackage, val type: ArscType, val entry: ArscEntry) : ArscRow()
}

private class ArscRowAdapter(
    private val onRowClick: (ArscRow) -> Unit
) : ListAdapter<ArscRow, ArscRowAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<ArscRow>() {
        override fun areItemsTheSame(oldItem: ArscRow, newItem: ArscRow): Boolean =
            when {
                oldItem is ArscRow.PackageRow && newItem is ArscRow.PackageRow ->
                    oldItem.pkg.id == newItem.pkg.id
                oldItem is ArscRow.TypeRow && newItem is ArscRow.TypeRow ->
                    oldItem.type.id == newItem.type.id &&
                        oldItem.type.config.contentEquals(newItem.type.config)
                oldItem is ArscRow.EntryRow && newItem is ArscRow.EntryRow ->
                    oldItem.entry.keyIndex == newItem.entry.keyIndex &&
                        oldItem.type.config.contentEquals(newItem.type.config)
                else -> false
            }

        override fun areContentsTheSame(oldItem: ArscRow, newItem: ArscRow): Boolean =
            oldItem == newItem
    }
) {
    /** The parsed global string pool, set by the fragment so string values can render. */
    var globalPool: ArscStringPool? = null

    class ViewHolder(val binding: ArscPackageItemBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ArscPackageItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val context = holder.binding.root.context
        when (item) {
            is ArscRow.PackageRow -> {
                holder.binding.nameText.text = item.pkg.name
                holder.binding.descriptionText.text = context.getString(
                    R.string.arsc_package_count_format, item.pkg.types.size
                )
            }
            is ArscRow.TypeRow -> {
                holder.binding.nameText.text = item.pkg.typeName(item.type.id)
                holder.binding.descriptionText.text =
                    ArscEditorFragment.configLabel(item.type.config, context)
            }
            is ArscRow.EntryRow -> {
                holder.binding.nameText.text = item.pkg.keyName(item.entry.keyIndex)
                holder.binding.descriptionText.text =
                    if (item.entry.isComplex) {
                        context.getString(R.string.arsc_entry_complex)
                    } else {
                        formatValueFor(item, context)
                    }
            }
        }
        holder.binding.root.setOnClickListener { onRowClick(item) }
    }

    private fun formatValueFor(row: ArscRow.EntryRow, context: android.content.Context): String {
        val value = row.entry.value ?: return ""
        if (value.isString) {
            val index = value.data.toInt()
            val pool = globalPool ?: return "(string#$index)"
            return if (index in pool.strings.indices) {
                pool.strings[index]
            } else {
                "(string#$index)"
            }
        }
        return when (value.dataType) {
            ArscValue.TYPE_NULL -> "null"
            ArscValue.TYPE_REFERENCE, ArscValue.TYPE_ATTRIBUTE ->
                String.format("@0x%08x", value.data)
            ArscValue.TYPE_INT_DEC -> value.data.toString()
            ArscValue.TYPE_INT_HEX -> String.format("0x%08x", value.data)
            ArscValue.TYPE_INT_BOOLEAN -> if (value.data != 0L) "true" else "false"
            ArscValue.TYPE_INT_COLOR_ARGB8, ArscValue.TYPE_INT_COLOR_RGB8,
            ArscValue.TYPE_INT_COLOR_ARGB4, ArscValue.TYPE_INT_COLOR_RGB4 ->
                String.format("#%08x", value.data)
            else -> String.format("0x%08x", value.data)
        }
    }
}
