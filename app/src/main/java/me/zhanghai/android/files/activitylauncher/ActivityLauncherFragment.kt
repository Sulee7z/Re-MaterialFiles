/*
 * Copyright (c) 2026 Sulee7z <94352968+sulee7z@users.noreply.github.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.activitylauncher

import android.content.pm.ActivityInfo
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
import coil.load
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.databinding.ActivityLauncherAppItemBinding
import me.zhanghai.android.files.databinding.ActivityLauncherFragmentBinding
import me.zhanghai.android.files.ui.ListAdapter
import me.zhanghai.android.files.util.DataState
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.viewModels

class ActivityLauncherFragment : Fragment() {

    private lateinit var binding: ActivityLauncherFragmentBinding

    private val viewModel by viewModels { { ActivityLauncherViewModel(requireContext()) } }

    private lateinit var appsAdapter: AppListAdapter
    private var allApps: List<LauncherApp> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ActivityLauncherFragmentBinding.inflate(inflater, container, false).also {
        binding = it
    }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(binding.toolbar)
        activity.supportActionBar!!.apply {
            title = getString(R.string.activity_launcher_title)
            setDisplayHomeAsUpEnabled(true)
        }

        appsAdapter = AppListAdapter(::showActivities)
        binding.appsRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.appsRecyclerView.adapter = appsAdapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false

            override fun onQueryTextChange(newText: String?): Boolean {
                filterApps(newText.orEmpty())
                return false
            }
        })

        viewModel.appsLiveData.observe(viewLifecycleOwner) { state ->
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
                            ?: getString(R.string.activity_launcher_launch_failed))
                }
                is DataState.Success -> {
                    binding.progress.isVisible = false
                    binding.errorText.isVisible = false
                    allApps = state.data
                    filterApps(binding.searchView.query?.toString().orEmpty())
                }
            }
        }
    }

    private fun filterApps(query: String) {
        lifecycleScope.launch {
            val filtered = withContext(Dispatchers.Default) {
                if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.label.contains(query, ignoreCase = true) ||
                            it.packageName.contains(query, ignoreCase = true)
                    }
                }
            }
            appsAdapter.replace(filtered, true)
            binding.countText.text = getString(
                R.string.activity_launcher_apps_count_format, filtered.size
            )
        }
    }

    private fun showActivities(app: LauncherApp) {
        lifecycleScope.launch {
            val appActivities = withContext(Dispatchers.IO) {
                viewModel.loadActivities(app.packageName)
            }
            if (appActivities == null || appActivities.activities.isEmpty()) {
                showToast(getString(R.string.activity_launcher_no_activities))
                return@launch
            }
            val names = appActivities.activities.map { it.name }
            val launchable = appActivities.launchableActivityNames
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(app.label)
                .setItems(
                    names.toTypedArray()
                ) { _, which ->
                    launchActivity(appActivities.activities[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun launchActivity(activityInfo: ActivityInfo) {
        val success = viewModel.launchActivity(
            activityInfo.packageName, activityInfo.name
        )
        if (!success) {
            showToast(getString(R.string.activity_launcher_launch_failed))
        }
    }
}

private class AppListAdapter(
    private val onAppClick: (LauncherApp) -> Unit
) : ListAdapter<LauncherApp, AppListAdapter.ViewHolder>(
    object : DiffUtil.ItemCallback<LauncherApp>() {
        override fun areItemsTheSame(oldItem: LauncherApp, newItem: LauncherApp): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: LauncherApp, newItem: LauncherApp): Boolean =
            oldItem == newItem
    }
) {
    class ViewHolder(val binding: ActivityLauncherAppItemBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(
            ActivityLauncherAppItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.nameText.text = item.label
        holder.binding.packageText.text = item.packageName
        holder.binding.iconImage.load(AppIconPackageName(item.packageName))
        holder.binding.root.setOnClickListener { onAppClick(item) }
    }
}
