/*
 * Copyright (c) 2024 Material Files (Sora-Editor) contributors
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.files.R
import me.zhanghai.android.files.databinding.SignApkDialogBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.filejob.ApkSigningKeySpec
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.showToast

class SignApkDialogFragment : AppCompatDialogFragment() {
    private val args by args<Args>()

    private lateinit var binding: SignApkDialogBinding

    private val listener: Listener
        get() = requireParentFragment() as Listener

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext(), theme)
            .setTitle(R.string.file_sign_apk_title)
            .apply {
                binding = SignApkDialogBinding.inflate(context.layoutInflater)
                binding.keyTypeGroup.setOnCheckedChangeListener { _, checkedId ->
                    binding.keyStoreFields.visibility =
                        if (checkedId == R.id.keyStoreRadio) View.VISIBLE else View.GONE
                }
                setView(binding.root)
            }
            .setPositiveButton(R.string.file_sign_apk_positive_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .apply {
                window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onOk() }
                }
            }

    private fun onOk() {
        val keySpec = if (binding.keyStoreRadio.isChecked) {
            val keyStorePath = binding.keyStorePathEdit.text.toString().trim()
            if (keyStorePath.isEmpty()) {
                requireContext().showToast(R.string.file_sign_apk_keystore_path_empty)
                return
            }
            ApkSigningKeySpec.UserKeyStore(
                keyStorePath,
                binding.keyStorePasswordEdit.text.toString(),
                binding.keyAliasEdit.text.toString().trim().takeIf { it.isNotEmpty() },
                binding.keyPasswordEdit.text.toString().takeIf { it.isNotEmpty() }
            )
        } else {
            ApkSigningKeySpec.TestKey
        }
        listener.signApk(args.file, keySpec)
        dismiss()
    }

    companion object {
        fun show(file: FileItem, fragment: Fragment) {
            SignApkDialogFragment().putArgs(Args(file)).show(fragment)
        }
    }

    @Parcelize
    class Args(val file: FileItem) : ParcelableArgs

    interface Listener {
        fun signApk(file: FileItem, keySpec: ApkSigningKeySpec)
    }
}
