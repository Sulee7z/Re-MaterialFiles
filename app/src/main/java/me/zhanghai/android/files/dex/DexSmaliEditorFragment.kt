/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.dex

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.schemes.SchemeDarcula
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
import me.zhanghai.android.files.databinding.DexSmaliEditorFragmentBinding
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.theme.night.NightModeHelper
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.ParcelableParceler
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.showToast

/**
 * The dex++ smali editor. Shows a single method's baksmali output (so the round-trip is
 * guaranteed to reassemble) in a Rosemoe editor; on save the edited block is swapped back
 * into the class, the whole dex is reassembled with the smali library and written back:
 *  - for a standalone .dex: the file is overwritten (through the file-job service),
 *  - for an APK: the classesN.dex entry is replaced and the APK is re-signed with the
 *    auto-generated key into a new "-smali.apk" next to the original.
 */
class DexSmaliEditorFragment : Fragment() {

    private val args by args<Args>()

    private lateinit var binding: DexSmaliEditorFragmentBinding
    private lateinit var codeEditor: CodeEditor

    // The working directory holding the baksmali output for the source dex.
    private var workDirectory: File? = null
    private var loadedClassSmali: String? = null
    private var originalBlock: String? = null
    private var loadError: String? = null
    // The source dex bytes, kept for the save-time merge (the edited class replaces its
    // original inside a copy of this dex).
    private var originalDexBytes: ByteArray? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = DexSmaliEditorFragmentBinding.inflate(inflater, container, false).also {
        binding = it
        codeEditor = it.codeEditor
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = getString(R.string.dex_smali_edit_title_format, args.methodKey)
            setDisplayHomeAsUpEnabled(true)
        }

        codeEditor.setEditorLanguage(EmptyLanguage())
        codeEditor.colorScheme =
            if (NightModeHelper.isInNightMode(activity)) SchemeDarcula() else EditorColorScheme()

        activity.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.dex_smali_editor, menu)
                menu.findItem(R.id.action_dex_smali_save).isEnabled =
                    originalBlock != null && loadError == null && textChanged()
                menu.findItem(R.id.action_dex_smali_undo).isEnabled = codeEditor.canUndo()
                menu.findItem(R.id.action_dex_smali_redo).isEnabled = codeEditor.canRedo()
                menu.findItem(R.id.action_dex_smali_word_wrap).isChecked = codeEditor.isWordwrap
            }

            override fun onMenuItemSelected(item: MenuItem): Boolean =
                when (item.itemId) {
                    R.id.action_dex_smali_save -> {
                        save()
                        true
                    }
                    R.id.action_dex_smali_undo -> {
                        codeEditor.undo()
                        true
                    }
                    R.id.action_dex_smali_redo -> {
                        codeEditor.redo()
                        true
                    }
                    R.id.action_dex_smali_word_wrap -> {
                        codeEditor.isWordwrap = !codeEditor.isWordwrap
                        item.isChecked = codeEditor.isWordwrap
                        true
                    }
                    else -> false
                }
        }, viewLifecycleOwner)

        codeEditor.subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
            requireActivity().invalidateOptionsMenu()
        }

        load()
    }

    private fun textChanged(): Boolean =
        originalBlock != null && codeEditor.text.toString() != originalBlock

    private fun load() {
        binding.progress.isVisible = true
        binding.errorText.isVisible = false
        binding.codeEditor.isVisible = false
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val cacheDirectory = File(requireContext().cacheDir, "dex-smali-edit")
                cacheDirectory.mkdirs()
                val sourceDex = args.sourceDex
                val inputBytes = if (sourceDex != null) {
                    val inputFile = ApkRebuilder.copyToCache(
                        args.path, cacheDirectory, "edit-source.apk"
                    )
                    ApkRebuilder.readEntry(inputFile, sourceDex)
                        ?: throw DexSmaliCompiler.CompileException(
                            "$sourceDex not found in APK"
                        )
                } else {
                    Files.newInputStream(args.path).use { it.readBytes() }
                }
                val workDirectory = File(
                    cacheDirectory, "work-${args.path.toString().hashCode()}-${args.methodKey.hashCode()}"
                )
                if (workDirectory.exists()) {
                    workDirectory.deleteRecursively()
                }
                workDirectory.mkdirs()
                // Only the edited class is disassembled (memory-friendly; a full dex
                // round trip can OOM on-device).
                DexSmaliCompiler.disassembleClass(inputBytes, workDirectory, args.className)
                val classFile = classSmaliPath(args.className, workDirectory)
                val classSmali = String(Files.readAllBytes(classFile), Charsets.UTF_8)
                val block = DexSmaliCompiler.findMethodBlock(classSmali, args.methodKey)
                    ?: throw DexSmaliCompiler.CompileException(
                        getString(R.string.dex_smali_method_not_found)
                    )
                val blockText = classSmali.split("\n")
                    .subList(block.first, block.second).joinToString("\n")
                this@DexSmaliEditorFragment.workDirectory = workDirectory
                this@DexSmaliEditorFragment.originalDexBytes = inputBytes
                originalBlock = blockText
                loadedClassSmali = classSmali
                blockText
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
                    binding.errorText.isVisible = false
                    codeEditor.setText(result)
                    binding.codeEditor.isVisible = true
                }
                requireActivity().invalidateOptionsMenu()
            }
        }
    }

    private fun classSmaliPath(className: String, workDirectory: File): Path {
        if (!className.startsWith("L") || !className.endsWith(";")) {
            throw DexSmaliCompiler.CompileException("Invalid class name: $className")
        }
        val relative = className.removePrefix("L").removeSuffix(";") + ".smali"
        val base = Paths.get(workDirectory.absolutePath)
        val path = base.resolve(relative).normalize()
        if (!path.startsWith(base)) {
            throw DexSmaliCompiler.CompileException("Unsafe class name: $className")
        }
        if (!Files.exists(path)) {
            throw DexSmaliCompiler.CompileException(
                getString(R.string.dex_smali_class_not_found_format, className)
            )
        }
        return path
    }

    private fun save() {
        val edited = codeEditor.text.toString()
        if (!textChanged()) {
            showToast(getString(R.string.dex_smali_no_changes))
            return
        }
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dex_smali_save_progress)
            .setView(android.widget.ProgressBar(requireContext()))
            .setCancelable(false)
            .show()
        lifecycleScope.launch(Dispatchers.IO) {
            // Swap the edited block back into the class smali, assemble just that class,
            // and merge it into the original dex (memory-friendly; the old full-dex
            // round trip could OOM on large dexes).
            val dexBytes: ByteArray
            try {
                val workDirectory = this@DexSmaliEditorFragment.workDirectory
                    ?: throw DexSmaliCompiler.CompileException("Editor not loaded")
                val classSmali = loadedClassSmali
                    ?: throw DexSmaliCompiler.CompileException("Editor not loaded")
                val originalDex = originalDexBytes
                    ?: throw DexSmaliCompiler.CompileException("Editor not loaded")
                val newClassSmali = DexSmaliCompiler.replaceMethodBlock(
                    classSmali, args.methodKey, edited
                ) ?: throw DexSmaliCompiler.CompileException(
                    getString(R.string.dex_smali_method_not_found)
                )
                val classFile = classSmaliPath(args.className, workDirectory)
                Files.write(classFile, newClassSmali.toByteArray())
                val classDex = DexSmaliCompiler.assembleClass(File(classFile.toString()))
                dexBytes = DexSmaliCompiler.mergeDex(originalDex, classDex)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showToast(
                        getString(
                            R.string.dex_smali_compile_error_format,
                            e.localizedMessage ?: e.javaClass.simpleName
                        )
                    )
                }
                return@launch
            }
            // Write-back step.
            val sourceDex = args.sourceDex
            if (sourceDex != null) {
                val cacheDirectory = File(requireContext().cacheDir, "dex-smali-edit")
                val outputName = args.path.fileName.toString()
                    .substringBeforeLast('.', args.path.fileName.toString()) + "-smali.apk"
                try {
                    val inputFile = ApkRebuilder.copyToCache(
                        args.path, cacheDirectory, "save-source.apk"
                    )
                    val rebuilt = File(cacheDirectory, "rebuilt.apk")
                    val outputFile = File(cacheDirectory, "output.apk")
                    try {
                        ApkRebuilder.rebuild(inputFile, rebuilt, mapOf(sourceDex to dexBytes))
                        AutoSigner.sign(requireContext(), rebuilt, outputFile)
                        val outputPath = args.path.parent.resolve(outputName)
                        Files.newInputStream(Paths.get(outputFile.absolutePath)).use { input ->
                            Files.newOutputStream(outputPath).use { output -> input.copyTo(output) }
                        }
                    } finally {
                        inputFile.delete()
                        rebuilt.delete()
                        outputFile.delete()
                    }
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) {
                        progressDialog.dismiss()
                        showToast(e.localizedMessage ?: getString(R.string.dex_smali_save_error))
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    showToast(getString(R.string.dex_smali_save_done_format, outputName))
                }
            } else {
                FileJobService.write(args.path, dexBytes, requireContext()) { success ->
                    progressDialog.dismiss()
                    showToast(
                        if (success) getString(R.string.dex_smali_save_success)
                        else getString(R.string.dex_smali_save_error)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        codeEditor.release()
    }

    @Parcelize
    class Args(
        val path: @WriteWith<ParcelableParceler> Path,
        /** The classesN.dex entry inside the APK; null when [path] is a .dex file itself. */
        val sourceDex: String?,
        val className: String,
        val methodKey: String
    ) : ParcelableArgs

    companion object {
        const val EXTRA_SOURCE_DEX = "source_dex"
        const val EXTRA_CLASS_NAME = "class_name"
        const val EXTRA_METHOD_KEY = "method_key"
    }
}
