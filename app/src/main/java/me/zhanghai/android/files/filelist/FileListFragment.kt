/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import me.zhanghai.android.fastscroll.FastScroller
import me.zhanghai.android.fastscroll.FastScrollerBuilder
import me.zhanghai.android.fastscroll.PopupTextProvider
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Button
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.leinardi.android.speeddial.SpeedDialView
import java8.nio.file.Path
import java8.nio.file.Paths
import kotlinx.parcelize.Parcelize
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageInfo
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java8.nio.file.attribute.FileTime
import java8.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.android.files.app.packageManager
import com.topjohnwu.superuser.Shell
import me.zhanghai.android.files.compat.longVersionCodeCompat
import me.zhanghai.android.files.util.getPackageArchiveInfoCompat
import me.zhanghai.android.files.util.sha1Digest
import me.zhanghai.android.files.util.toHexString
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.application
import me.zhanghai.android.files.app.clipboardManager
import me.zhanghai.android.files.compat.checkSelfPermissionCompat
import me.zhanghai.android.files.compat.setGroupDividerEnabledCompat
import me.zhanghai.android.files.databinding.FileListFragmentAppBarIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentBinding
import me.zhanghai.android.files.databinding.FileListFragmentBottomBarIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentContentIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentIncludeBinding
import me.zhanghai.android.files.databinding.FileListFragmentSpeedDialIncludeBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.file.asMimeType
import me.zhanghai.android.files.file.asMimeTypeOrNull
import me.zhanghai.android.files.file.extension
import me.zhanghai.android.files.file.fileProviderUri
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.file.isImage
import me.zhanghai.android.files.file.isTextOrCode
import me.zhanghai.android.files.filejob.FileJobService
import me.zhanghai.android.files.filelist.FileSortOptions.By
import me.zhanghai.android.files.filelist.FileSortOptions.Order
import me.zhanghai.android.files.dex.DexAnalyzerActivity
import me.zhanghai.android.files.apksearch.ApkStringSearchActivity
import me.zhanghai.android.files.elf.ElfAnalyzerActivity
import me.zhanghai.android.files.hex.HexViewerActivity
import me.zhanghai.android.files.logcat.LogcatActivity
import me.zhanghai.android.files.activitylauncher.ActivityLauncherActivity
import me.zhanghai.android.files.apkmanifest.AndroidManifestDecoder
import me.zhanghai.android.files.apkkiller.ApkSignatureKiller
import me.zhanghai.android.files.apksign.ApkSigner
import me.zhanghai.android.files.apksign.AutoSigner
import me.zhanghai.android.files.arsc.ArscEditorActivity
import me.zhanghai.android.files.fileproperties.FilePropertiesDialogFragment
import me.zhanghai.android.files.navigation.BookmarkDirectories
import me.zhanghai.android.files.navigation.BookmarkDirectory
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.navigation.NavigationRootMapLiveData
import me.zhanghai.android.files.navigation.RecentDirectories
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.createArchiveRootPath
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.linux.isLinuxPath
import me.zhanghai.android.files.searchindex.FileIndexer
import me.zhanghai.android.files.settings.HiddenPaths
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.terminal.TerminalActivity
import me.zhanghai.android.files.terminal.TerminalArgs
import me.zhanghai.android.files.ui.AppBarLayoutExpandHackListener
import me.zhanghai.android.files.compat.getDrawableCompat
import me.zhanghai.android.files.ui.CoordinatorAppBarLayout
import me.zhanghai.android.files.ui.DrawerLayoutOnBackPressedCallback
import me.zhanghai.android.files.ui.FixQueryChangeSearchView
import me.zhanghai.android.files.ui.OverlayToolbar
import me.zhanghai.android.files.ui.OverlayToolbarActionMode
import me.zhanghai.android.files.ui.PersistentBarLayout
import me.zhanghai.android.files.ui.PersistentBarLayoutToolbarActionMode
import me.zhanghai.android.files.ui.PersistentDrawerLayout
import me.zhanghai.android.files.ui.RecyclerViewFastScrollerViewHelper
import me.zhanghai.android.files.ui.ScrollingViewOnApplyWindowInsetsListener
import me.zhanghai.android.files.ui.SpeedDialViewOnBackPressedCallback
import me.zhanghai.android.files.ui.ThemedFastScroller
import me.zhanghai.android.files.ui.ToolbarActionMode
import me.zhanghai.android.files.util.DebouncedRunnable
import me.zhanghai.android.files.util.Failure
import me.zhanghai.android.files.util.Loading
import me.zhanghai.android.files.util.ParcelableArgs
import me.zhanghai.android.files.util.Stateful
import me.zhanghai.android.files.util.Success
import me.zhanghai.android.files.util.addOnBackPressedCallback
import me.zhanghai.android.files.util.args
import me.zhanghai.android.files.util.asFileName
import me.zhanghai.android.files.util.asFileNameOrNull
import me.zhanghai.android.files.util.checkSelfPermission
import me.zhanghai.android.files.util.copyText
import me.zhanghai.android.files.util.create
import me.zhanghai.android.files.util.createInstallPackageIntent
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.createManageAppAllFilesAccessPermissionIntent
import me.zhanghai.android.files.util.createSendStreamIntent
import me.zhanghai.android.files.util.createViewIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.extraPathList
import me.zhanghai.android.files.util.fadeToVisibilityUnsafe
import me.zhanghai.android.files.util.getDimensionDp
import me.zhanghai.android.files.util.getQuantityString
import me.zhanghai.android.files.util.hasSw600Dp
import me.zhanghai.android.files.util.isOrientationLandscape
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.setOnEditorConfirmActionListener
import me.zhanghai.android.files.util.showToast
import me.zhanghai.android.files.util.show
import me.zhanghai.android.files.util.startActivitySafe
import me.zhanghai.android.files.util.supportsExternalStorageManager
import me.zhanghai.android.files.util.takeIfNotEmpty
import me.zhanghai.android.files.util.valueCompat
import me.zhanghai.android.files.util.viewModels
import me.zhanghai.android.files.viewer.text.TextEditorActivity
import me.zhanghai.android.files.util.withChooser
import me.zhanghai.android.files.viewer.image.ImageViewerActivity
import kotlin.math.roundToInt

class FileListFragment : Fragment(), BreadcrumbLayout.Listener, FileListAdapter.Listener,
    ConfirmReplaceFileDialogFragment.Listener, OpenApkDialogFragment.Listener,
    ConfirmDeleteFilesDialogFragment.Listener, CreateArchiveDialogFragment.Listener,
    RenameFileDialogFragment.Listener, CreateFileDialogFragment.Listener,
    CreateDirectoryDialogFragment.Listener, NavigateToPathDialogFragment.Listener,
    NavigationFragment.Listener, ShowRequestAllFilesAccessRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionRationaleDialogFragment.Listener,
    ShowRequestNotificationPermissionInSettingsRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionRationaleDialogFragment.Listener,
    ShowRequestStoragePermissionInSettingsRationaleDialogFragment.Listener {
    private val requestAllFilesAccessLauncher = registerForActivityResult(
        RequestAllFilesAccessContract(), this::onRequestAllFilesAccessResult
    )
    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(), this::onRequestStoragePermissionResult
    )
    private val requestStoragePermissionInSettingsLauncher = registerForActivityResult(
        RequestPermissionInSettingsContract(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
        this::onRequestStoragePermissionInSettingsResult
    )
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(), this::onRequestNotificationPermissionResult
    )
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requestNotificationPermissionInSettingsLauncher = registerForActivityResult(
        RequestPermissionInSettingsContract(android.Manifest.permission.POST_NOTIFICATIONS),
        this::onRequestNotificationPermissionInSettingsResult
    )

    private val args by args<Args>()
    private val argsPath by lazy { args.intent.extraPath }

    /** True when this fragment is the secondary (right) pane of two-pane browsing. */
    private val isSecondaryPane: Boolean
        get() = args.secondaryPane

    internal val viewModel by viewModels { { FileListViewModel() } }

    /** The effective two-pane mode of the hosting Activity. Differs from the setting when
     *  this Activity is a picker (open file/directory/create), which always runs single-pane
     *  (two-pane hides the toolbar carrying the picker's confirm action). */
    private val isTwoPaneMode: Boolean
        get() = (activity as? FileListActivity)?.isTwoPaneMode
            ?: me.zhanghai.android.files.settings.Settings.FILE_LIST_TWO_PANE.valueCompat

    private lateinit var binding: Binding

    private lateinit var navigationFragment: NavigationFragment

    private lateinit var menuBinding: MenuBinding

    private lateinit var overlayActionMode: ToolbarActionMode

    private lateinit var bottomActionMode: ToolbarActionMode

    private lateinit var layoutManager: GridLayoutManager

    internal lateinit var adapter: FileListAdapter

    /** Forwards the divider's screen position to this pane's adapter (two-pane mode),
     *  so a drag starting on the divider resize handle never triggers a cross-pane move. */
    fun setDividerScreenCenterX(centerX: Float) {
        if (::adapter.isInitialized) {
            adapter.dividerScreenCenterX = centerX
        }
    }

    private var onListScrolledListener: ((Int) -> Unit)? = null

    /** Registers a listener invoked when this pane's list scrolls, with the dy delta. */
    fun setOnListScrolledListener(listener: ((Int) -> Unit)?) {
        onListScrolledListener = listener
    }

    private fun onListScrolled(dy: Int) {
        if (dy == 0) {
            return
        }
        onListScrolledListener?.invoke(dy)
    }
    private val debouncedSearchRunnable = DebouncedRunnable(Handler(Looper.getMainLooper()), 400) {
        if (!isResumed) {
            return@DebouncedRunnable
        }
        // The shared search bar follows the ACTIVE pane (like the FAB): the search state
        // and results belong to whichever pane the user is working on.
        val targetViewModel = activePaneFragment().viewModel
        if (!targetViewModel.isSearchViewExpanded) {
            return@DebouncedRunnable
        }
        val query = targetViewModel.searchViewQuery
        if (query.isEmpty()) {
            return@DebouncedRunnable
        }
        targetViewModel.search(query)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View =
        Binding.inflate(inflater, container, false)
            .also { binding = it }
            .root

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        val activity = requireActivity() as AppCompatActivity
        val isTwoPane = isTwoPaneMode
        if (!isTwoPane) {
            // Single-pane mode: each pane owns its own drawer (the Activity-level drawer is
            // only used in two-pane mode).
            if (savedInstanceState == null) {
                navigationFragment = NavigationFragment()
                childFragmentManager.commit { add(R.id.navigationFragment, navigationFragment) }
            } else {
                navigationFragment = childFragmentManager.findFragmentById(R.id.navigationFragment)
                    as NavigationFragment
            }
            navigationFragment.listener = this
        }
        if (!isSecondaryPane && !isTwoPane) {
            activity.setTitle(R.string.file_list_title)
            activity.setSupportActionBar(binding.toolbar)
        } else if (isTwoPane) {
            // In two-pane mode the navigation drawer lives at the Activity level; the
            // per-pane DrawerLayouts must not intercept any edge swipes (a swipe on the
            // left pane would otherwise try to open the hidden per-pane drawer and jam
            // the list). Hide the navigation panel and lock the drawer closed.
            binding.root.findViewById<View>(R.id.navigationFragment)?.isVisible = false
            binding.drawerLayout?.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            // The Activity's shared top bar already consumes the status-bar inset, so the
            // pane's AppBarLayout must not inflate for it again (that would pad the pane
            // content by the status-bar height on devices where the window insets reach
            // the pane). Turning off fitsSystemWindows on the pane's CoordinatorLayout
            // stops the AppBarLayout from consuming the top inset, while PersistentBarLayout
            // still routes the bottom inset to the pane's bottom bar.
            val coordinatorLayout = binding.persistentBarLayout.getChildAt(0) as? CoordinatorLayout
            coordinatorLayout?.fitsSystemWindows = false
            // The pane AppBarLayout (FitsSystemWindowsAppBarLayout forces
            // fitsSystemWindows=true) would otherwise consume the top inset as padding,
            // pushing the breadcrumb bar down by the status-bar height below the shared
            // top bar. Block its inset handling entirely: the shared top bar owns the
            // status-bar area in two-pane mode.
            binding.appBarLayout.fitsSystemWindows = false
            ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { _, insets ->
                insets
            }
        }
        // Two-pane mode shares ONE multi-select action bar rendered over the shared top
        // bar (the same layer as the three-line menu button): selecting files in either
        // pane turns the top bar into the action bar (delete/copy/cross-pane). Single-pane
        // mode uses the pane's own overlay toolbar.
        overlayActionMode = if (isTwoPaneMode) {
            (activity as FileListActivity).getSharedOverlayActionMode()
        } else {
            OverlayToolbarActionMode(
                binding.overlayToolbar, binding.overlayToolbar, binding.toolbar
            )
        }
        bottomActionMode = PersistentBarLayoutToolbarActionMode(
            binding.persistentBarLayout, binding.bottomBarLayout, binding.bottomToolbar
        )
        val contentLayoutInitialPaddingBottom = binding.contentLayout.paddingBottom
        binding.appBarLayout.addOnOffsetChangedListener { _, verticalOffset ->
            binding.contentLayout.updatePaddingRelative(
                bottom = contentLayoutInitialPaddingBottom +
                    binding.appBarLayout.totalScrollRange + verticalOffset
            )
        }
        binding.appBarLayout.syncBackgroundColorTo(binding.overlayToolbar)
        binding.breadcrumbLayout.setListener(this)
        if (!(activity.hasSw600Dp && activity.isOrientationLandscape)) {
            binding.swipeRefreshLayout.setProgressViewEndTarget(
                true, binding.swipeRefreshLayout.progressViewEndOffset
            )
        }
        binding.swipeRefreshLayout.setOnRefreshListener { this.refresh() }
        layoutManager = GridLayoutManager(activity, 1)
        binding.recyclerView.layoutManager = layoutManager
        adapter = FileListAdapter(this).apply {
            this.isSecondaryPane = args.secondaryPane
        }
        binding.recyclerView.adapter = adapter
        // Report scrolling so two-pane mode can hide/show the shared FAB: hide while
        // scrolling down, show while scrolling up. The FAB stays visible when the list
        // cannot scroll (or is idle), so short lists can always reach the + button.
        binding.recyclerView.addOnScrollListener(object :
            androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                onListScrolled(dy)
            }
        })
        // NOTE: the active-pane tracking does NOT live here. View.setOnTouchListener on
        // the RecyclerView never fires for touches consumed by item children (click/ripple
        // consume ACTION_DOWN first), so tapping files/folders would not switch the active
        // pane. Tracking is centralized in FileListActivity.dispatchTouchEvent() using the
        // touch X coordinate (each pane is exactly half the screen), which cannot miss any
        // touch anywhere in the pane (items, breadcrumb, empty state, toolbar, …).
        val fastScroller = if (isTwoPaneMode && isSecondaryPane.not()) {
            createLeftPaneFastScroller()
        } else {
            ThemedFastScroller.create(binding.recyclerView)
        }
        binding.recyclerView.setOnApplyWindowInsetsListener(
            ScrollingViewOnApplyWindowInsetsListener(binding.recyclerView, fastScroller)
        )
        if (isTwoPaneMode) {
            // Two-pane layout adjustments: the shared top bar (search/sort/three dots)
            // lives in the Activity above both panes and is always visible, so both pane
            // toolbars are hidden (the breadcrumb path bars stay). The FAB is owned by the
            // Activity (fixed bottom-right, like Amaze) and targets the active pane, so the
            // per-pane FABs are hidden. Shrink icons, hide folder icons and remove the
            // per-item three-dot buttons.
            binding.toolbar.isVisible = false
            binding.speedDialView.isVisible = false
            // The shared breadcrumb bar (MT Manager style) replaces the per-pane bars.
            binding.breadcrumbLayout.isVisible = false
            adapter.hideMenuButtons = true
            // Folder rows collapse their icon area to zero width so names span the full
            // narrow pane; file rows keep their icon. MT Manager style compactness.
            adapter.hideFolderIcons = true
            // Compactness (small font/icons/dense rows) is driven ENTIRELY by the
            // FILE_LIST_TWO_PANE_DENSE switch via updateDenseLayout(), so the setting
            // and the rendered list can never disagree (and toggling it takes effect
            // immediately). The DENSE observers run on view creation, so this initial
            // state is covered too.
            // Horizontal drag on a row starts a cross-pane drag-and-drop (drop on the
            // other pane moves the files there).
            adapter.isCrossPaneDragEnabled = true
            // Long-press on empty space (below the last item or in gaps) creates a
            // folder here. A strict stationary press is required:
            // - the press must land within the MIDDLE third of the pane width, and NOT
            //   inside the system back-gesture zone (WindowInsets.getSystemGestureInsets);
            // - the finger must stay within touch slop (~8dp) for 800ms. Any horizontal
            //   intent (scroll/fling/back swipe, however the ROM starts it) moves past
            //   slop immediately and cancels, so only a truly still press fires.
            val density = resources.displayMetrics.density
            val touchSlop = android.view.ViewConfiguration.get(binding.recyclerView.context)
                .scaledTouchSlop.toFloat()
            var longPressPending = false
            var downX = 0f
            var downY = 0f
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val longPressRunnable = Runnable {
                val pending = longPressPending
                longPressPending = false
                if (!pending || !isAdded) {
                    return@Runnable
                }
                // Re-check with the original down point: only blank space triggers.
                val loc = IntArray(2)
                binding.recyclerView.getLocationOnScreen(loc)
                val localX = downX - loc[0]
                val localY = downY - loc[1]
                if (binding.recyclerView.findChildViewUnder(localX, localY) != null) {
                    return@Runnable
                }
                showCreateDirectoryDialog()
            }
            val cancelLongPress = {
                longPressPending = false
                handler.removeCallbacks(longPressRunnable)
            }
            binding.recyclerView.addOnItemTouchListener(
                object :
                    androidx.recyclerview.widget.RecyclerView.SimpleOnItemTouchListener() {
                    override fun onInterceptTouchEvent(
                        rv: androidx.recyclerview.widget.RecyclerView,
                        e: android.view.MotionEvent
                    ): Boolean {
                        when (e.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                // A deliberate, centered gesture is required. The press
                                // must land on BLANK space (not an item row), within the
                                // middle third of the pane, and NOT inside the system
                                // back-gesture zone (WindowInsets.getSystemGestureInsets).
                                // Checking the item hit NOW (not after the delay) means a
                                // tap on a file can never be misread as a long-press, even
                                // when the list refreshes mid-gesture (FTP connections
                                // repopulate rows on every network round-trip).
                                val loc = IntArray(2)
                                rv.getLocationOnScreen(loc)
                                val localX = e.rawX - loc[0]
                                val localY = e.rawY - loc[1]
                                val onItem = rv.findChildViewUnder(localX, localY) != null
                                val minX = loc[0] + rv.width / 3
                                val maxX = loc[0] + rv.width * 2 / 3
                                val windowInsets =
                                    androidx.core.view.ViewCompat.getRootWindowInsets(rv)
                                val gestureLeft = windowInsets?.systemGestureInsets?.left
                                    ?.takeIf { it > 0 } ?: (48 * density).toInt()
                                val gestureRight = windowInsets?.systemGestureInsets?.right
                                    ?.takeIf { it > 0 } ?: (48 * density).toInt()
                                val screenWidth = resources.displayMetrics.widthPixels
                                val inBackGestureZone =
                                    e.rawX < gestureLeft || e.rawX > screenWidth - gestureRight
                                if (onItem || inBackGestureZone ||
                                    e.rawX < minX || e.rawX > maxX
                                ) {
                                    cancelLongPress()
                                } else {
                                    longPressPending = true
                                    downX = e.rawX
                                    downY = e.rawY
                                    handler.postDelayed(longPressRunnable, 800)
                                }
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                if (longPressPending) {
                                    val dx = e.rawX - downX
                                    val dy = e.rawY - downY
                                    val maxDist = touchSlop
                                    if (dx * dx + dy * dy > maxDist * maxDist) {
                                        cancelLongPress()
                                    }
                                }
                            }
                            else -> cancelLongPress()
                        }
                        return false
                    }
                }
            )
        }
        if (!isTwoPaneMode) {
            binding.speedDialView.inflate(R.menu.file_list_speed_dial)
            binding.speedDialView.setOnActionSelectedListener {
                val target = this
                when (it.id) {
                    R.id.action_create_file -> target.showCreateFileDialog()
                    R.id.action_create_directory -> target.showCreateDirectoryDialog()
                }
                // Returning false causes the speed dial to close without animation.
                //return false
                binding.speedDialView.close()
                true
            }
            // Show/hide the add button per the setting. (In two-pane mode the Activity
            // owns the FAB and this per-pane one stays hidden, so only observe here.)
            me.zhanghai.android.files.settings.Settings.SHOW_ADD_BUTTON.observe(viewLifecycleOwner) {
                binding.speedDialView.isVisible = it
            }
        }

        val viewLifecycleOwner = viewLifecycleOwner
        if (!isTwoPaneMode) {
            addOnBackPressedCallback(
                object : OnBackPressedCallback(false) {
                    override fun handleOnBackPressed() {
                        // The expanded search bar takes the back key first: collapse it
                        // (hiding the keyboard) instead of navigating up, so the user can
                        // scroll through results without leaving them.
                        if (viewModel.isSearchViewExpanded) {
                            collapseSearchView()
                        } else {
                            viewModel.navigateUp()
                        }
                    }
                }
                    .also { callback ->
                        viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) {
                            callback.isEnabled = viewModel.canNavigateUpBreadcrumb ||
                                viewModel.isSearchViewExpanded
                        }
                    }
            )
        }
        // In two-pane mode neither pane registers its own back callback: the Activity tracks
        // the active pane via its container touch listeners and routes the back key to it.
        addOnBackPressedCallback(overlayActionMode.onBackPressedCallback)
        addOnBackPressedCallback(SpeedDialViewOnBackPressedCallback(binding.speedDialView))
        binding.drawerLayout?.let {
            addOnBackPressedCallback(DrawerLayoutOnBackPressedCallback(it))
        }

        if (!viewModel.hasTrail) {
            var path = argsPath
            val intent = args.intent
            var pickOptions: PickOptions? = null
            when (val action = intent.action) {
                Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT,
                Intent.ACTION_CREATE_DOCUMENT -> {
                    val mode = if (action == Intent.ACTION_CREATE_DOCUMENT) {
                        PickOptions.Mode.CREATE_FILE
                    } else {
                        PickOptions.Mode.OPEN_FILE
                    }
                    val mimeType = intent.type?.asMimeTypeOrNull() ?: MimeType.ANY
                    val fileName = if (mode == PickOptions.Mode.CREATE_FILE) {
                        intent.getStringExtra(Intent.EXTRA_TITLE)?.asFileNameOrNull()?.value
                            ?: mimeType.extension?.let { "file.$it" } ?: "file"
                    } else {
                        null
                    }
                    val readOnly = action == Intent.ACTION_GET_CONTENT
                    val extraMimeTypes = if (mode == PickOptions.Mode.OPEN_FILE) {
                        intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
                            ?.mapNotNull { it.asMimeTypeOrNull() }?.takeIfNotEmpty()
                    } else {
                        null
                    }
                    val mimeTypes = extraMimeTypes ?: listOf(mimeType)
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    val allowMultiple = mode != PickOptions.Mode.CREATE_FILE &&
                        intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    pickOptions =
                        PickOptions(mode, fileName, readOnly, mimeTypes, localOnly, allowMultiple)
                }
                Intent.ACTION_OPEN_DOCUMENT_TREE -> {
                    val localOnly = intent.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false)
                    pickOptions = PickOptions(
                        PickOptions.Mode.OPEN_DIRECTORY, null, false, emptyList(), localOnly, false
                    )
                }
                ACTION_VIEW_DOWNLOADS ->
                    path = Paths.get(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS
                        ).path
                    )
                else ->
                    if (path != null) {
                        val mimeType = intent.type?.asMimeTypeOrNull()
                        if (mimeType != null && path.isArchiveFile(mimeType)) {
                            path = path.createArchiveRootPath()
                        }
                    }
            }
            if (path == null) {
                path = Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
            }
            viewModel.resetTo(path)
            if (pickOptions != null) {
                viewModel.pickOptions = pickOptions
            }
        }
        viewModel.currentPathLiveData.observe(viewLifecycleOwner) { onCurrentPathChanged(it) }
        viewModel.searchViewExpandedLiveData.observe(viewLifecycleOwner) {
            onSearchViewExpandedChanged(it)
        }
        viewModel.breadcrumbLiveData.observe(viewLifecycleOwner) {
            if (isTwoPaneMode) {
                // Two-pane mode: the shared breadcrumb bar in the Activity shows the
                // ACTIVE pane's path, so report our data up (it only renders when this
                // pane is the active one).
                (activity as? FileListActivity)?.onPaneBreadcrumbChanged(this, it)
            } else {
                binding.breadcrumbLayout.setData(it)
            }
        }
        viewModel.viewTypeLiveData.observe(viewLifecycleOwner) { onViewTypeChanged(it) }
        // Live data only calls observeForever() on its sources when it is active, so we have to
        // make view type live data active first (so that it can load its initial value) before we
        // register another observer that needs to get the view type.
        if (binding.persistentDrawerLayout != null) {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.observe(viewLifecycleOwner) {
                onPersistentDrawerOpenChanged(it)
            }
        }
        viewModel.sortOptionsLiveData.observe(viewLifecycleOwner) { onSortOptionsChanged(it) }
        viewModel.viewSortPathSpecificLiveData.observe(viewLifecycleOwner) {
            onViewSortPathSpecificChanged(it)
        }
        viewModel.pickOptionsLiveData.observe(viewLifecycleOwner) { onPickOptionsChanged(it) }
        viewModel.selectedFilesLiveData.observe(viewLifecycleOwner) { onSelectedFilesChanged(it) }
        Settings.FILE_LIST_DENSE_LAYOUT.observe(viewLifecycleOwner) { updateDenseLayout() }
        Settings.FILE_LIST_TWO_PANE_DENSE.observe(viewLifecycleOwner) { updateDenseLayout() }
        viewModel.pasteStateLiveData.observe(viewLifecycleOwner) { onPasteStateChanged(it) }
        Settings.FILE_NAME_ELLIPSIZE.observe(viewLifecycleOwner) { onFileNameEllipsizeChanged(it) }
        Settings.FILE_LIST_WRAP_LONG_FILE_NAMES.observe(viewLifecycleOwner) {
            onWrapLongFileNamesChanged(it)
        }
        viewModel.fileListLiveData.observe(viewLifecycleOwner) { onFileListChanged(it) }
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.observe(viewLifecycleOwner) {
            onShowHiddenFilesChanged(it)
        }
    }

    override fun onResume() {
        super.onResume()

        if (!viewModel.isNotificationPermissionRequested) {
            ensureStorageAccess()
        }
        if (!viewModel.isStorageAccessRequested) {
            ensureNotificationPermission()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        val isTwoPane = isTwoPaneMode
        if (isSecondaryPane != isTwoPane) {
            // Single-pane mode: only the primary pane contributes menus. Two-pane mode:
            // only the right pane renders the shared top bar and contributes menus.
            return
        }

        menuBinding = MenuBinding.inflate(menu, inflater)
        menuBinding.viewSortItem.subMenu!!.setGroupDividerEnabledCompat(true)
        setUpSearchView()
    }

    private fun setUpSearchView() {
        val searchView = menuBinding.searchItem.actionView as FixQueryChangeSearchView
        // MenuItem.OnActionExpandListener.onMenuItemActionExpand() is called before SearchView
        // resets the query. The search bar follows the ACTIVE pane (like the FAB): opening
        // it targets the pane the user is working on, and results show in that pane.
        searchView.setOnSearchClickListener {
            val targetViewModel = activePaneFragment().viewModel
            targetViewModel.isSearchViewExpanded = true
            searchView.setQuery(targetViewModel.searchViewQuery, false)
            debouncedSearchRunnable()
        }
        // SearchView.OnCloseListener.onClose() is not always called.
        menuBinding.searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean = true

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                val targetViewModel = activePaneFragment().viewModel
                targetViewModel.isSearchViewExpanded = false
                targetViewModel.stopSearching()
                return true
            }
        })
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                debouncedSearchRunnable.cancel()
                activePaneFragment().viewModel.search(query)
                return true
            }

            override fun onQueryTextChange(query: String): Boolean {
                if (searchView.shouldIgnoreQueryChange) {
                    return false
                }
                activePaneFragment().viewModel.searchViewQuery = query
                debouncedSearchRunnable()
                return false
            }
        })
        if (activePaneFragment().viewModel.isSearchViewExpanded) {
            menuBinding.searchItem.expandActionView()
        }
    }

    private fun collapseSearchView() {
        if (this::menuBinding.isInitialized && menuBinding.searchItem.isActionViewExpanded) {
            menuBinding.searchItem.collapseActionView()
        }
    }

    /** Opens the navigation drawer (used by the shared top bar's three-line button). */
    fun openNavigationDrawer() {
        if (isTwoPaneMode) {
            // Two-pane mode: the drawer lives in the Activity's full-screen DrawerLayout.
            (requireActivity() as FileListActivity).openNavigationDrawer()
            return
        }
        val drawerLayout = binding.drawerLayout
        if (drawerLayout != null) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }
        if (binding.persistentDrawerLayout != null) {
            Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.putValue(
                !Settings.FILE_LIST_PERSISTENT_DRAWER_OPEN.valueCompat
            )
        }
    }

    /**
     * The pane the shared actions (search, FAB) act on: the pane the user last touched.
     * In two-pane mode the shared search bar follows this pane, exactly like the FAB.
     */
    private fun activePaneFragment(): FileListFragment {
        if (isSecondaryPane && !TwoPaneState.activePaneSecondary) {
            return (requireActivity() as FileListActivity)
                .findFileListFragment(secondaryPane = false) ?: this
        }
        if (!isSecondaryPane && TwoPaneState.activePaneSecondary) {
            return (requireActivity() as FileListActivity)
                .findFileListFragment(secondaryPane = true) ?: this
        }
        return this
    }

    /**
     * The view model the shared top-bar menu reflects and acts on: in two-pane mode the
     * menu (check states for sort/view type, search state) follows the ACTIVE pane, in
     * single-pane mode it is this fragment's own.
     */
    private val menuViewModel: FileListViewModel
        get() = if (isTwoPaneMode) activePaneFragment().viewModel else viewModel

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        updateViewSortMenuItems()
        updateSelectAllMenuItem()
        updateShowHiddenFilesMenuItem()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // In two-pane mode the shared top bar follows the active pane: whoever receives a
        // menu event while the OTHER pane is active must forward it to the active pane.
        if (isTwoPaneMode) {
            val activeIsSecondary = TwoPaneState.activePaneSecondary
            if (isSecondaryPane != activeIsSecondary) {
                val activeFragment = (requireActivity() as FileListActivity)
                    .findFileListFragment(secondaryPane = activeIsSecondary)
                if (activeFragment != null && activeFragment.performMenuAction(item.itemId)) {
                    return true
                }
            }
        }
        return performMenuAction(item.itemId)
    }

    /**
     * Executes a top-bar menu action on THIS pane's list. Uses only this fragment's
     * viewModel, so it can be invoked for the left pane from the right pane's shared top
     * bar without touching the (non-existent) left menu UI.
     */
    fun performMenuAction(itemId: Int): Boolean {
        return when (itemId) {
            android.R.id.home -> {
                // Toggle: open when closed, close when open (this path is hit instead of
                // the Activity's handler because fragments receive menu events first).
                openNavigationDrawer()
                true
            }
            R.id.action_view_list -> {
                viewModel.viewType = FileViewType.LIST
                true
            }
            R.id.action_view_grid -> {
                viewModel.viewType = FileViewType.GRID
                true
            }
            R.id.action_sort_by_name -> {
                viewModel.setSortBy(By.NAME)
                true
            }
            R.id.action_sort_by_type -> {
                viewModel.setSortBy(By.TYPE)
                true
            }
            R.id.action_sort_by_size -> {
                viewModel.setSortBy(By.SIZE)
                true
            }
            R.id.action_sort_by_last_modified -> {
                viewModel.setSortBy(By.LAST_MODIFIED)
                true
            }
            R.id.action_sort_order_ascending -> {
                // Toggle based on the current order, independent of any menu check state
                // (the left pane has no visible menu in two-pane mode).
                viewModel.setSortOrder(
                    if (viewModel.sortOptions.order == Order.DESCENDING) {
                        Order.ASCENDING
                    } else {
                        Order.DESCENDING
                    }
                )
                true
            }
            R.id.action_sort_directories_first -> {
                viewModel.setSortDirectoriesFirst(!viewModel.sortOptions.isDirectoriesFirst)
                true
            }
            R.id.action_view_sort_path_specific -> {
                viewModel.isViewSortPathSpecific = !viewModel.isViewSortPathSpecific
                true
            }
            R.id.action_new_task -> {
                newTask()
                true
            }
            R.id.action_navigate_up -> {
                navigateUp()
                true
            }
            R.id.action_navigate_to -> {
                showNavigateToPathDialog()
                true
            }
            R.id.action_refresh -> {
                refresh()
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            R.id.action_show_hidden_files -> {
                setShowHiddenFiles(!Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat)
                true
            }
            R.id.action_manage_hidden -> {
                showManageHiddenDialog()
                true
            }
            R.id.action_rebuild_index -> {
                rebuildSearchIndex()
                true
            }
            R.id.action_share -> {
                share()
                true
            }
            R.id.action_copy_path -> {
                copyPath()
                true
            }
            R.id.action_open_in_terminal -> {
                openInTerminal()
                true
            }
            R.id.action_show_logcat -> {
                startActivity(LogcatActivity::class.createIntent())
                true
            }
            R.id.action_show_activity_launcher -> {
                startActivity(ActivityLauncherActivity::class.createIntent())
                true
            }
            R.id.action_add_bookmark -> {
                addBookmark()
                true
            }
            R.id.action_create_shortcut -> {
                createShortcut()
                true
            }
            else -> false
        }
    }

    fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        // F5/F6 cross-pane transfer (MT Manager / Total Commander style): the ACTIVE
        // pane's selection goes to the OTHER pane's current directory. F5 copies,
        // F6 moves. Only meaningful in two-pane mode.
        val isTwoPane = isTwoPaneMode
        if (isTwoPane && viewModel.selectedFiles.isNotEmpty()) {
            if (keyCode == KeyEvent.KEYCODE_F5) {
                val files = viewModel.selectedFiles
                val target = otherPaneCurrentPath() ?: return true
                FileJobService.copy(makePathListForJob(files), target, requireContext())
                viewModel.selectFiles(files, false)
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_F6) {
                val files = viewModel.selectedFiles
                val target = otherPaneCurrentPath() ?: return true
                FileJobService.move(makePathListForJob(files), target, requireContext())
                viewModel.selectFiles(files, false)
                return true
            }
        }
        if (bottomActionMode.isActive) {
            val menu = bottomActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        if (overlayActionMode.isActive) {
            val menu = overlayActionMode.menu
            menu.setQwertyMode(
                KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC
            )
            if (menu.performShortcut(keyCode, event, 0)) {
                return true
            }
        }
        return false
    }

    private fun onPersistentDrawerOpenChanged(open: Boolean) {
        binding.persistentDrawerLayout?.let {
            if (open) {
                it.openDrawer(GravityCompat.START)
            } else {
                it.closeDrawer(GravityCompat.START)
            }
        }
        updateSpanCount()
    }

    private fun onCurrentPathChanged(path: Path) {
        // Record the visited directory for the Recent folders feature.
        RecentDirectories.add(path)
        // Keep the two-pane state in sync so the other pane knows where to copy/cut to.
        if (args.secondaryPane) {
            TwoPaneState.secondaryPanePath = path
        } else {
            TwoPaneState.primaryPanePath = path
        }
        updateOverlayToolbar()
        updateBottomToolbar()
        // Navigating to a new directory brings the shared FAB back (it may have been
        // hidden while scrolling the previous directory's list).
        if (isTwoPaneMode) {
            (activity as? FileListActivity)?.showFab()
        }
    }

    private fun onSearchViewExpandedChanged(expanded: Boolean) {
        updateViewSortMenuItems()
    }

    private fun onFileListChanged(stateful: Stateful<List<FileItem>>) {
        val files = stateful.value
        val isSearching = viewModel.searchState.isSearching
        when {
            stateful is Failure -> binding.toolbar.setSubtitle(R.string.error)
            stateful is Loading && !isSearching -> binding.toolbar.setSubtitle(R.string.loading)
            else -> binding.toolbar.subtitle = getSubtitle(files!!)
        }
        val hasFiles = !files.isNullOrEmpty()
        binding.swipeRefreshLayout.isRefreshing = stateful is Loading && (hasFiles || isSearching)
        binding.progress.fadeToVisibilityUnsafe(stateful is Loading && !(hasFiles || isSearching))
        binding.errorText.fadeToVisibilityUnsafe(stateful is Failure && !hasFiles)
        val throwable = (stateful as? Failure)?.throwable
        if (throwable != null) {
            throwable.printStackTrace()
            val error = throwable.toString()
            if (hasFiles) {
                showToast(error)
            } else {
                binding.errorText.text = error
            }
        }
        binding.emptyView.fadeToVisibilityUnsafe(stateful is Success && !hasFiles)
        if (files != null) {
            updateAdapterFileList()
        } else {
            // This resets animation as well.
            adapter.clear()
        }
        if (stateful is Success) {
            val pendingState = viewModel.pendingState
            if (pendingState != null) {
                layoutManager.onRestoreInstanceState(pendingState)
            } else {
                // No saved scroll state for this directory (first visit, refresh, or a
                // sibling-directory switch whose trail state was never recorded): jump
                // to the top instead of keeping a stale offset that would clip the
                // first row.
                layoutManager.scrollToPositionWithOffset(0, 0)
            }
        }
    }

    private fun getSubtitle(files: List<FileItem>): String {
        val directoryCount = files.count { it.attributes.isDirectory }
        val fileCount = files.size - directoryCount
        val directoryCountText = if (directoryCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_directory_count_format, directoryCount, directoryCount
            )
        } else {
            null
        }
        val fileCountText = if (fileCount > 0) {
            getQuantityString(
                R.plurals.file_list_subtitle_file_count_format, fileCount, fileCount
            )
        } else {
            null
        }
        return when {
            !directoryCountText.isNullOrEmpty() && !fileCountText.isNullOrEmpty() ->
                (directoryCountText + getString(R.string.file_list_subtitle_separator)
                    + fileCountText)
            !directoryCountText.isNullOrEmpty() -> directoryCountText
            !fileCountText.isNullOrEmpty() -> fileCountText
            else -> getString(R.string.empty)
        }
    }

    private fun onViewTypeChanged(viewType: FileViewType) {
        updateSpanCount()
        adapter.viewType = viewType
        updateViewSortMenuItems()
    }

    private fun updateSpanCount() {
        layoutManager.spanCount = when (viewModel.viewType) {
            FileViewType.LIST -> 1
            FileViewType.GRID -> {
                var widthDp = resources.configuration.screenWidthDp
                if (isTwoPaneMode) {
                    // Each pane is half the screen: compute the span count from the PANE
                    // width, or a wide screen would cram a 4-column grid into a
                    // half-width pane.
                    widthDp /= 2
                }
                val persistentDrawerLayout = binding.persistentDrawerLayout
                if (persistentDrawerLayout != null &&
                    persistentDrawerLayout.isDrawerOpen(GravityCompat.START)) {
                    widthDp -= getDimensionDp(R.dimen.navigation_max_width).roundToInt()
                }
                (widthDp / 180).coerceAtLeast(if (adapter.denseLayout) 3 else 2 )
            }
        }
    }

    private fun onSortOptionsChanged(sortOptions: FileSortOptions) {
        adapter.sortOptions = sortOptions
        updateViewSortMenuItems()
    }

    private fun onViewSortPathSpecificChanged(pathSpecific: Boolean) {
        updateViewSortMenuItems()
    }

    private fun updateViewSortMenuItems() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val searchViewExpanded = menuViewModel.isSearchViewExpanded
        menuBinding.viewSortItem.isVisible = !searchViewExpanded
        if (searchViewExpanded) {
            return
        }
        val viewType = menuViewModel.viewType
        val checkedViewTypeItem = when (viewType) {
            FileViewType.LIST -> menuBinding.viewListItem
            FileViewType.GRID -> menuBinding.viewGridItem
        }
        checkedViewTypeItem.isChecked = true
        val sortOptions = menuViewModel.sortOptions
        val checkedSortByItem = when (sortOptions.by) {
            By.NAME -> menuBinding.sortByNameItem
            By.TYPE -> menuBinding.sortByTypeItem
            By.SIZE -> menuBinding.sortBySizeItem
            By.LAST_MODIFIED -> menuBinding.sortByLastModifiedItem
        }
        checkedSortByItem.isChecked = true
        menuBinding.sortOrderAscendingItem.isChecked = sortOptions.order == Order.ASCENDING
        menuBinding.sortDirectoriesFirstItem.isChecked = sortOptions.isDirectoriesFirst
        menuBinding.viewSortPathSpecificItem.isChecked = viewModel.isViewSortPathSpecific
    }

    private fun navigateUp() {
        collapseSearchView()
        viewModel.navigateUp()
    }

    /**
     * Handles the back key on behalf of the Activity in two-pane mode (the back key is
     * routed to the pane the user last touched). Returns true when consumed.
     */
    fun performBack(): Boolean {
        // An expanded search bar owns the back key: back collapses the search (hiding
        // the keyboard too) instead of navigating up / leaving the results.
        if (viewModel.isSearchViewExpanded) {
            collapseSearchView()
            return true
        }
        if (viewModel.canNavigateUpBreadcrumb) {
            navigateUp()
            return true
        }
        return false
    }

    private fun showNavigateToPathDialog() {
        NavigateToPathDialogFragment.show(currentPath, this)
    }

    private fun newTask() {
        openInNewTask(currentPath)
    }

    private fun refresh() {
        viewModel.reload()
    }

    private fun setShowHiddenFiles(showHiddenFiles: Boolean) {
        Settings.FILE_LIST_SHOW_HIDDEN_FILES.putValue(showHiddenFiles)
    }

    private fun onShowHiddenFilesChanged(showHiddenFiles: Boolean) {
        updateAdapterFileList()
        updateShowHiddenFilesMenuItem()
    }

    private fun updateAdapterFileList() {
        var files = viewModel.fileListStateful.value ?: return
        if (!Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat) {
            files = files.filterNot { it.isHidden }
        }
        adapter.replaceListAndIsSearching(files, viewModel.searchState.isSearching)
    }

    private fun updateShowHiddenFilesMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val showHiddenFiles = Settings.FILE_LIST_SHOW_HIDDEN_FILES.valueCompat
        menuBinding.showHiddenFilesItem.isChecked = showHiddenFiles
    }

    private fun share() {
        shareFile(currentPath, MimeType.DIRECTORY)
    }

    private fun copyPath() {
        copyPath(currentPath)
    }

    private fun openInTerminal() {
        val path = currentPath
        if (path.isLinuxPath) {
            startActivity(
                TerminalActivity::class.createIntent()
                    .putArgs(TerminalArgs(path.toFile().path, Shell.isAppGrantedRoot() == true))
            )
        } else {
            showToast(R.string.terminal_not_supported_for_path)
        }
    }

    override fun navigateTo(path: Path) {
        collapseSearchView()
        val state = layoutManager.onSaveInstanceState()
        viewModel.navigateTo(state!!, path)
    }

    override fun navigateToPath() {
        showNavigateToPathDialog()
    }

    override fun copyPath(path: Path) {
        clipboardManager.copyText(path.toUserFriendlyString(), requireContext())
    }

    override fun movePathsTo(paths: List<Path>, directory: Path) {
        // Archive sources are read-only: dragging OUT of an archive is always a copy.
        if (paths.first().isArchivePath) {
            FileJobService.copy(paths, directory, requireContext())
        } else {
            FileJobService.move(paths, directory, requireContext())
        }
    }

    override fun openInNewTask(path: Path) {
        val intent = FileListActivity.createViewIntent(path)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        startActivitySafe(intent)
    }

    private fun onPickOptionsChanged(pickOptions: PickOptions?) {
        val title = if (pickOptions == null) {
            getString(R.string.file_list_title)
        } else {
            val count = if (pickOptions.allowMultiple) Int.MAX_VALUE else 1
            when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE ->
                    getQuantityString(R.plurals.file_list_title_open_file, count)
                PickOptions.Mode.CREATE_FILE -> getString(R.string.file_list_title_create_file)
                PickOptions.Mode.OPEN_DIRECTORY ->
                    getQuantityString(R.plurals.file_list_title_open_directory, count)
            }
        }
        requireActivity().title = title
        updateSelectAllMenuItem()
        updateOverlayToolbar()
        updateBottomToolbar()
        adapter.pickOptions = pickOptions
    }

    private fun updateSelectAllMenuItem() {
        if (!this::menuBinding.isInitialized) {
            return
        }
        val pickOptions = menuViewModel.pickOptions
        menuBinding.selectAllItem.isVisible = pickOptions == null || pickOptions.allowMultiple
    }

    private fun pickFiles(files: FileItemSet) {
        pickPaths(files.mapTo(linkedSetOf()) { it.path })
    }

    private fun pickPaths(paths: LinkedHashSet<Path>) {
        val intent = Intent().apply {
            val pickOptions = viewModel.pickOptions!!
            if (paths.size == 1) {
                val path = paths.single()
                data = path.fileProviderUri
                extraPath = path
            } else {
                val mimeTypes = pickOptions.mimeTypes.map { it.value }
                val items = paths.map { ClipData.Item(it.fileProviderUri) }
                clipData = ClipData::class.create(null, mimeTypes, items)
                extraPathList = paths.toList()
            }
            var flags =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (!pickOptions.readOnly) {
                flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            if (pickOptions.mode == PickOptions.Mode.OPEN_DIRECTORY) {
                flags = flags or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            }
            addFlags(flags)
        }
        requireActivity().run {
            setResult(Activity.RESULT_OK, intent)
            finish()
        }
    }

    private fun onSelectedFilesChanged(files: FileItemSet) {
        // Both panes share ONE multi-select action bar over the shared top bar, so only
        // one pane may hold a selection at a time: selecting files clears the other
        // pane's selection (clearing must NOT touch the active pane).
        if (isTwoPaneMode &&
            files.isNotEmpty()
        ) {
            TwoPaneState.setActivePaneSecondary(isSecondaryPane)
            val activity = requireActivity() as FileListActivity
            val otherFragment = activity.findFileListFragment(secondaryPane = !isSecondaryPane)
            if (otherFragment != null && otherFragment.viewModel.selectedFiles.isNotEmpty()) {
                otherFragment.viewModel.clearSelectedFiles()
            }
        }
        updateOverlayToolbar()
        adapter.replaceSelectedFiles(files)
        // Hide the shared FAB while a multi-select action mode is active (it would
        // otherwise overlap the action bar area and distract from delete/copy).
        if (isTwoPaneMode) {
            (activity as? FileListActivity)?.setFabHiddenBySelection(files.isNotEmpty())
        }
    }

    /** Hides the toolbar overflow (three-dot) menu by clearing its icon. */
    private fun androidx.appcompat.widget.Toolbar.hideOverflowMenu() {
        overflowIcon = null
    }

    /** Switches list item icons to the small size so filenames get more room. */
    private fun applyTwoPaneSmallIcons() {
        adapter.useSmallIcons = true
    }

    /**
     * Left-pane fast scrollbar on the LEFT edge (two-pane left pane only, mirroring the
     * classic dual-pane layout): AndroidFastScroll positions the bar by layout direction,
     * so the RecyclerView is re-parented into an RTL wrapper (the bar draws at the
     * wrapper's left edge) while the list itself stays LTR. Scrolling and touches still
     * go through the RecyclerView via the custom view helper.
     */
    private fun createLeftPaneFastScroller(): FastScroller {
        val recyclerView = binding.recyclerView
        val parent = recyclerView.parent as? ViewGroup
            ?: return ThemedFastScroller.create(recyclerView)
        val index = parent.indexOfChild(recyclerView)
        val layoutParams = recyclerView.layoutParams
        parent.removeView(recyclerView)
        val rtlWrapper = FrameLayout(requireContext()).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        rtlWrapper.addView(
            recyclerView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // The wrapper is RTL ONLY for the scrollbar positioning; layout direction is
        // inherited, so force the list back to LTR or its icons/text would mirror.
        recyclerView.layoutDirection = View.LAYOUT_DIRECTION_LTR
        parent.addView(rtlWrapper, index, layoutParams)
        val viewHelper = RecyclerViewFastScrollerViewHelper(
            recyclerView, adapter as? PopupTextProvider
        )
        return FastScrollerBuilder(rtlWrapper)
            .useMd2Style()
            .setThumbDrawable(
                requireContext().getDrawableCompat(R.drawable.fast_scroll_thumb_m3)
            )
            .setViewHelper(viewHelper)
            .build()
    }

    /**
     * Applies the dense-layout setting. In two-pane mode the dedicated two-pane dense
     * switch takes precedence over the global one, and also controls the small-font and
     * small-icon modes (so the setting and the rendered list can never disagree).
     */
    private fun updateDenseLayout() {
        val twoPane = isTwoPaneMode
        val dense = if (twoPane) {
            me.zhanghai.android.files.settings.Settings.FILE_LIST_TWO_PANE_DENSE.valueCompat
        } else {
            me.zhanghai.android.files.settings.Settings.FILE_LIST_DENSE_LAYOUT.valueCompat
        }
        onDenseLayoutChanged(dense, twoPane)
    }

    private fun onDenseLayoutChanged(denseLayout: Boolean, twoPaneMode: Boolean) {
        adapter.denseLayout = denseLayout
        if (twoPaneMode) {
            adapter.useSmallFont = denseLayout
            adapter.useSmallIcons = denseLayout
        }
        updateSpanCount()
        // re-set adapter to prevent RecyclerView from recycling views and reusing old padding
        // values on refresh. Neither notifyDataSetChanged() / notifyItemRangeChanged
        // nor adapter.refresh() does work here.
        binding.recyclerView.adapter = adapter
    }

    private fun updateOverlayToolbar() {
        val files = viewModel.selectedFiles
        if (files.isEmpty()) {
            overlayActionMode.finishIfOwned(overlayCallback)
            return
        }
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_pick)
            val menu = overlayActionMode.menu
            val isOpen = when (pickOptions.mode) {
                PickOptions.Mode.OPEN_FILE, PickOptions.Mode.OPEN_DIRECTORY -> true
                PickOptions.Mode.CREATE_FILE -> false
            }
            menu.findItem(R.id.action_open).isVisible = isOpen
            menu.findItem(R.id.action_create).isVisible = !isOpen
            menu.findItem(R.id.action_select_all).isVisible = pickOptions.allowMultiple
        } else {
            overlayActionMode.title = getString(R.string.file_list_select_title_format, files.size)
            overlayActionMode.setMenuResource(R.menu.file_list_select)
            val menu = overlayActionMode.menu
            val isAnyFileReadOnly = files.any { it.path.fileSystem.isReadOnly }
            menu.findItem(R.id.action_cut).isVisible = !isAnyFileReadOnly
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            menu.findItem(R.id.action_copy)
                .setIcon(
                    if (areAllFilesArchivePaths) {
                        R.drawable.extract_icon_control_normal_24dp
                    } else {
                        R.drawable.copy_icon_control_normal_24dp
                    }
                )
                .setTitle(
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_select_action_extract
                    } else {
                        R.string.copy
                    }
                )
            menu.findItem(R.id.action_delete).isVisible = !isAnyFileReadOnly
            val areAllFilesArchiveFiles = files.all { it.isArchiveFile }
            menu.findItem(R.id.action_extract).isVisible = areAllFilesArchiveFiles
            menu.findItem(R.id.action_extract_here).isVisible = areAllFilesArchiveFiles
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            menu.findItem(R.id.action_archive).isVisible = !isCurrentPathReadOnly
            menu.findItem(R.id.action_batch_rename).isVisible = !isCurrentPathReadOnly
            if (isTwoPaneMode) {
                // Mirror the single-file three-dot menu exactly: an item is visible when it
                // would be visible for ANY selected file (a single selection behaves
                // identically to the per-item menu; a multi selection keeps every option
                // that applies to at least one file).
                menu.findItem(R.id.action_rename).isVisible = !isAnyFileReadOnly
                menu.findItem(R.id.action_set_timestamp).isVisible = !isAnyFileReadOnly
                menu.findItem(R.id.action_copy_path).isVisible = true
                menu.findItem(R.id.action_add_bookmark).isVisible =
                    files.any { it.attributes.isDirectory }
                menu.findItem(R.id.action_create_shortcut).isVisible =
                    files.any { !it.attributes.isDirectory }
                menu.findItem(R.id.action_properties).isVisible = true
                menu.findItem(R.id.action_hide).isVisible = !isAnyFileReadOnly
                // Open-with and share only for files (never directories).
                menu.findItem(R.id.action_open_with).isVisible =
                    files.any { !it.attributes.isDirectory }
                menu.findItem(R.id.action_share).isVisible =
                    files.any { !it.attributes.isDirectory }
                // Analyzer/action items mirror the single-file menu (visible when they
                // apply to any selected file).
                menu.findItem(R.id.action_dex_analyze).isVisible =
                    files.any { it.name.endsWith(".dex", ignoreCase = true) }
                menu.findItem(R.id.action_elf_analyze).isVisible =
                    files.any { it.name.endsWith(".so", ignoreCase = true) ||
                        it.name.endsWith(".elf", ignoreCase = true) }
                menu.findItem(R.id.action_hex_view).isVisible =
                    files.any { !it.attributes.isDirectory }
                menu.findItem(R.id.action_install).isVisible = files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_apk_string_search).isVisible =
                    files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_compare_apk).isVisible = files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_view_manifest).isVisible =
                    files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_auto_sign_apk).isVisible =
                    files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_sign_apk).isVisible = files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_kill_signature).isVisible =
                    files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_edit_arsc).isVisible = files.any {
                    it.mimeType.isApk || it.name.endsWith(".arsc", ignoreCase = true)
                }
                menu.findItem(R.id.action_rename_apk).isVisible =
                    files.any { it.mimeType.isApk }
                menu.findItem(R.id.action_convert_encoding).isVisible =
                    files.any { !it.attributes.isDirectory && isTextFile(it) }
            }
            // The regular clipboard cut/copy keep their original icons and stay visible
            // (copy/cut on one pane, then paste on the other pane).
            menu.findItem(R.id.action_cut).isVisible = !isAnyFileReadOnly
            menu.findItem(R.id.action_copy).isVisible = true
            // Single-pane mode keeps the ORIGINAL menu: every item added for two-pane
            // browsing (open-with, per-file ops, analyzers, ...) is hidden so the single
            // pane behaves exactly as before.
            if (!isTwoPaneMode) {
                menu.findItem(R.id.action_open_with).isVisible = false
                menu.findItem(R.id.action_hide).isVisible = false
                menu.findItem(R.id.action_rename).isVisible = false
                menu.findItem(R.id.action_set_timestamp).isVisible = false
                menu.findItem(R.id.action_copy_path).isVisible = false
                menu.findItem(R.id.action_add_bookmark).isVisible = false
                menu.findItem(R.id.action_create_shortcut).isVisible = false
                menu.findItem(R.id.action_properties).isVisible = false
                menu.findItem(R.id.action_dex_analyze).isVisible = false
                menu.findItem(R.id.action_elf_analyze).isVisible = false
                menu.findItem(R.id.action_hex_view).isVisible = false
                menu.findItem(R.id.action_install).isVisible = false
                menu.findItem(R.id.action_apk_string_search).isVisible = false
                menu.findItem(R.id.action_compare_apk).isVisible = false
                menu.findItem(R.id.action_view_manifest).isVisible = false
                menu.findItem(R.id.action_auto_sign_apk).isVisible = false
                menu.findItem(R.id.action_sign_apk).isVisible = false
                menu.findItem(R.id.action_kill_signature).isVisible = false
                menu.findItem(R.id.action_edit_arsc).isVisible = false
                menu.findItem(R.id.action_rename_apk).isVisible = false
                menu.findItem(R.id.action_convert_encoding).isVisible = false
            }
        }
        if (!overlayActionMode.isActive || overlayActionMode.currentCallback() != overlayCallback) {
            // In single-pane mode expand the scrolling app bar so the overlay toolbar
            // (inside it) is visible; in two-pane mode the shared top bar is fixed, so no
            // expansion is needed (and expanding the pane's own app bar is meaningless).
            if (!isTwoPaneMode) {
                binding.appBarLayout.setExpanded(true)
                binding.appBarLayout.addOnOffsetChangedListener(
                    AppBarLayoutExpandHackListener(binding.recyclerView)
                )
            }
            overlayActionMode.start(overlayCallback)
        }
    }

    private val overlayCallback = object : ToolbarActionMode.Callback {
        override fun onToolbarActionModeMenuItemClicked(
            toolbarActionMode: ToolbarActionMode,
            item: MenuItem
        ): Boolean = onOverlayActionModeMenuItemClicked(item)

        override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
            onOverlayActionModeFinished()
        }
    }

    private fun onOverlayActionModeMenuItemClicked(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_open -> {
                pickFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_create -> {
                confirmReplaceFile(viewModel.selectedFiles.single())
                true
            }
            R.id.action_cut -> {
                cutFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_copy -> {
                copyFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_delete -> {
                confirmDeleteFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_extract -> {
                extractFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_extract_here -> {
                extractFilesHere(viewModel.selectedFiles)
                true
            }
            R.id.action_archive -> {
                showCreateArchiveDialog(viewModel.selectedFiles)
                true
            }
            R.id.action_share -> {
                shareFiles(viewModel.selectedFiles)
                true
            }
            R.id.action_select_all -> {
                selectAllFiles()
                true
            }
            R.id.action_select_range -> {
                rangeSelectFiles()
                true
            }
            R.id.action_batch_rename -> {
                showBatchRenameDialog(viewModel.selectedFiles)
                true
            }
            R.id.action_open_with -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                openFileWith(file)
                true
            }
            R.id.action_hide -> {
                val files = viewModel.selectedFiles
                if (files.size == 1) {
                    hideFile(files.single())
                } else {
                    val hiddenPaths = HiddenPaths.getAll().toMutableSet()
                    files.forEach { hiddenPaths += it.path.toString() }
                    HiddenPaths.set(hiddenPaths)
                    viewModel.reload()
                }
                true
            }
            R.id.action_rename -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showRenameFileDialog(file)
                true
            }
            R.id.action_set_timestamp -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showSetTimestampDialog(file)
                true
            }
            R.id.action_copy_path -> {
                viewModel.selectedFiles.forEach { copyPath(it) }
                true
            }
            R.id.action_add_bookmark -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                addBookmark(file)
                true
            }
            R.id.action_create_shortcut -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                createShortcut(file)
                true
            }
            R.id.action_properties -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showPropertiesDialog(file)
                true
            }
            R.id.action_dex_analyze -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showDexAnalyzer(file)
                true
            }
            R.id.action_elf_analyze -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showElfAnalyzer(file)
                true
            }
            R.id.action_hex_view -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showHexViewer(file)
                true
            }
            R.id.action_install -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                installFile(file)
                true
            }
            R.id.action_apk_string_search -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showApkStringSearch(file)
                true
            }
            R.id.action_compare_apk -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                compareApk(file)
                true
            }
            R.id.action_view_manifest -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showManifest(file)
                true
            }
            R.id.action_auto_sign_apk -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                autoSignApk(file)
                true
            }
            R.id.action_sign_apk -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showSignApkDialog(file)
                true
            }
            R.id.action_kill_signature -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                killSignature(file)
                true
            }
            R.id.action_edit_arsc -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showArscEditor(file)
                true
            }
            R.id.action_rename_apk -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                renameApkWithVersion(file)
                true
            }
            R.id.action_convert_encoding -> {
                val file = viewModel.selectedFiles.singleOrNull() ?: return false
                showEncodingConversionDialog(file)
                true
            }
            else -> false
        }

    private fun onOverlayActionModeFinished() {
        viewModel.clearSelectedFiles()
    }

    private fun confirmReplaceFile(file: FileItem, setFileName: Boolean = true) {
        if (setFileName) {
            val fileName = file.name
            binding.bottomCreateFileNameEdit.setText(fileName)
            binding.bottomCreateFileNameEdit.setSelection(
                0, fileName.asFileName().baseName.length
            )
        }
        ConfirmReplaceFileDialogFragment.show(file, this)
    }

    override fun replaceFile(file: FileItem) {
        pickFiles(fileItemSetOf(file))
    }

    private fun cutFiles(files: FileItemSet) {
        viewModel.addToPasteState(false, files)
        viewModel.selectFiles(files, false)
    }

    private fun copyFiles(files: FileItemSet) {
        viewModel.addToPasteState(true, files)
        viewModel.selectFiles(files, false)
    }

    fun confirmDeleteFiles(files: FileItemSet) {
        ConfirmDeleteFilesDialogFragment.show(files, this)
    }

    override fun deleteFiles(files: FileItemSet) {
        FileJobService.trashDelete(makePathListForJob(files), requireContext())
        viewModel.selectFiles(files, false)
    }

    private fun extractFiles(files: FileItemSet) {
        // Copying an archive's root extracts it; the bottom paste bar shows the
        // editable destination (defaulting to the current directory) and the job
        // appends each archive's base name so archives never merge into one folder.
        copyFiles(files.mapTo(fileItemSetOf()) { it.createDummyArchiveRoot() })
        viewModel.selectFiles(files, false)
    }

    /** Extracts immediately into the current directory (no destination dialog). When
     *  the current directory is inside an opened archive, lands next to the archive
     *  file itself (the archive-internal tree is read-only). */
    private fun extractFilesHere(files: FileItemSet) {
        extractTo(files, extractionBaseDirectory(files))
    }

    override fun extractFilesHere(file: FileItem) {
        extractFilesHere(fileItemSetOf(file))
    }

    /**
     * The base directory for extraction destinations: the archive file's own directory
     * when extracting out of an opened archive, otherwise the current directory.
     */
    private fun extractionBaseDirectory(files: FileItemSet): Path {
        val firstSource = files.first().path
        return if (firstSource.isArchivePath) {
            firstSource.archiveFile.parent ?: viewModel.currentPath
        } else {
            viewModel.currentPath
        }
    }

    private fun extractTo(files: FileItemSet, targetDirectory: Path) {
        // An entry selected INSIDE an opened archive is copied as itself (just that
        // entry); an archive FILE is copied as its root (the job appends the archive's
        // base name so multiple archives never merge into one folder).
        FileJobService.copy(
            files.mapTo(mutableListOf()) { file ->
                if (file.path.isArchivePath) {
                    file.path
                } else {
                    file.createDummyArchiveRoot().path
                }
            },
            targetDirectory,
            requireContext()
        )
        viewModel.selectFiles(files, false)
    }

    private fun showCreateArchiveDialog(files: FileItemSet) {
        CreateArchiveDialogFragment.show(files, this)
    }

    override fun archive(
        files: FileItemSet,
        name: String,
        format: Int,
        filter: Int,
        password: String?
    ) {
        val archiveFile = viewModel.currentPath.resolve(name)
        FileJobService.archive(
            makePathListForJob(files), archiveFile, format, filter, password, requireContext()
        )
        viewModel.selectFiles(files, false)
    }

    private fun shareFiles(files: FileItemSet) {
        shareFiles(files.map { it.path }, files.map { it.mimeType })
        viewModel.selectFiles(files, false)
    }

    private fun selectAllFiles() {
        adapter.selectAllFiles()
    }

    private fun rangeSelectFiles() {
        adapter.rangeSelectFiles()
    }

    private fun onPasteStateChanged(pasteState: PasteState) {
        updateBottomToolbar()
    }

    private fun updateBottomToolbar() {
        if (isTwoPaneMode) {
            // Two-pane mode hosts the paste bar at the Activity level (it spans both
            // panes); its paste action targets the ACTIVE pane's current directory.
            (requireActivity() as FileListActivity).updateTwoPaneBottomToolbar()
            return
        }
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            bottomActionMode.setMenuResource(R.menu.file_list_pick_bottom)
            val menu = bottomActionMode.menu
            when (pickOptions.mode) {
                PickOptions.Mode.CREATE_FILE -> {
                    bottomActionMode.title = null
                    binding.bottomCreateFileNameEdit.isVisible = true
                    val createMenuItem = menu.findItem(R.id.action_create)
                    binding.bottomCreateFileNameEdit.setOnEditorConfirmActionListener {
                        onBottomActionModeMenuItemClicked(createMenuItem)
                    }
                    if (!viewModel.isCreateFileNameEditInitialized) {
                        val fileName = pickOptions.fileName!!
                        binding.bottomCreateFileNameEdit.setText(fileName)
                        binding.bottomCreateFileNameEdit.setSelection(
                            0, fileName.asFileName().baseName.length
                        )
                        binding.bottomCreateFileNameEdit.requestFocus()
                        viewModel.isCreateFileNameEditInitialized = true
                    }
                    menu.findItem(R.id.action_open).isVisible = false
                    createMenuItem.isVisible = true
                }
                PickOptions.Mode.OPEN_DIRECTORY -> {
                    val path = viewModel.currentPath
                    val navigationRoot = NavigationRootMapLiveData.valueCompat[path]
                    val name = navigationRoot?.getName(requireContext()) ?: path.name
                    bottomActionMode.title =
                        getString(R.string.file_list_open_current_directory_format, name)
                    binding.bottomCreateFileNameEdit.isVisible = false
                    menu.findItem(R.id.action_open).isVisible = true
                    menu.findItem(R.id.action_create).isVisible = false
                }
                else -> {
                    if (bottomActionMode.isActive) {
                        bottomActionMode.finish()
                    }
                    return
                }
            }
        } else {
            val pasteState = viewModel.pasteState
            val files = pasteState.files
            if (files.isEmpty()) {
                if (bottomActionMode.isActive) {
                    bottomActionMode.finish()
                }
                return
            }
            val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
            bottomActionMode.title = getString(
                if (pasteState.copy) {
                    if (areAllFilesArchivePaths) {
                        R.string.file_list_paste_extract_title_format
                    } else {
                        R.string.file_list_paste_copy_title_format
                    }
                } else {
                    R.string.file_list_paste_move_title_format
                }, files.size
            )
            binding.bottomCreateFileNameEdit.isVisible = false
            // Extract mode (clipboard holds archive roots): show the editable extraction
            // destination, prefilled with the current directory.
            binding.extractDestinationEdit.isVisible = areAllFilesArchivePaths
            bottomActionMode.setMenuResource(R.menu.file_list_paste)
            val isCurrentPathReadOnly = viewModel.currentPath.fileSystem.isReadOnly
            bottomActionMode.menu.findItem(R.id.action_paste)
                .setTitle(
                    if (areAllFilesArchivePaths) R.string.file_list_paste_action_extract_here else R.string.paste
                )
                .isEnabled = !isCurrentPathReadOnly
        }
        if (!bottomActionMode.isActive) {
            if (binding.extractDestinationEdit.isVisible) {
                // Archive sources: default to the archive file's own directory (the
                // archive-internal path string must never leak into the destination).
                val firstSource = viewModel.pasteState.files.first().path
                val defaultDirectory = if (firstSource.isArchivePath) {
                    firstSource.archiveFile.parent?.toString()
                } else {
                    viewModel.currentPath.toString()
                }
                binding.extractDestinationEdit.setText(
                    defaultDirectory ?: viewModel.currentPath.toString()
                )
            }
            bottomActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarNavigationIconClicked(toolbarActionMode: ToolbarActionMode) {
                    onBottomToolbarNavigationIconClicked()
                }

                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: MenuItem
                ): Boolean = onBottomActionModeMenuItemClicked(item)

                override fun onToolbarActionModeFinished(toolbarActionMode: ToolbarActionMode) {
                    onBottomActionModeFinished()
                }
            })
        }
    }

    private fun onBottomToolbarNavigationIconClicked() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            requireActivity().finish()
        } else {
            bottomActionMode.finish()
        }
    }

    private fun onBottomActionModeMenuItemClicked(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.action_open -> {
                pickPaths(linkedSetOf(viewModel.currentPath))
                true
            }
            R.id.action_create -> {
                val fileName = binding.bottomCreateFileNameEdit.text.toString()
                if (fileName.isEmpty()) {
                    showToast(R.string.file_list_create_file_name_error_empty)
                } else if (fileName.asFileNameOrNull() == null) {
                    showToast(R.string.file_list_create_file_name_error_invalid)
                } else {
                    val file = getFileWithName(fileName)
                    if (file != null) {
                        confirmReplaceFile(file, false)
                    } else {
                        val path = viewModel.currentPath.resolve(fileName)
                        pickPaths(linkedSetOf(path))
                    }
                }
                true
            }
            R.id.action_paste -> {
                // A bare destination name resolves against the base directory (the
                // process CWD is "/", which would hit the read-only root).
                val baseDirectory = extractionBaseDirectory(viewModel.pasteState.files)
                val text = binding.extractDestinationEdit.text.toString().trim()
                val targetDirectory = when {
                    text.isEmpty() -> baseDirectory
                    text.startsWith("/") -> Paths.get(text)
                    else -> baseDirectory.resolve(text)
                }
                pasteFiles(targetDirectory)
                true
            }
            else -> false
        }

    private fun onBottomActionModeFinished() {
        val pickOptions = viewModel.pickOptions
        if (pickOptions == null) {
            viewModel.clearPasteState()
        }
    }

    private fun pasteFiles(targetDirectory: Path) {
        val pasteState = viewModel.pasteState
        // Archive sources are read-only: a "move" out of an opened archive could never
        // delete the sources, so it degrades to a copy.
        val sourcesInArchive = pasteState.files.all { it.path.isArchivePath }
        if (pasteState.copy || sourcesInArchive) {
            FileJobService.copy(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        } else {
            FileJobService.move(
                makePathListForJob(pasteState.files), targetDirectory, requireContext()
            )
        }
        viewModel.clearPasteState()
    }

    /** Public wrapper for the FAB's "paste to this pane" action (MT Manager style).
     *  An optional [targetDirectory] overrides the pane's current directory (used by the
     *  two-pane paste bar's editable extraction destination). */
    fun pasteFilesToCurrentPane(targetDirectory: Path? = null) {
        if (viewModel.pasteState.files.isEmpty()) {
            return
        }
        pasteFiles(targetDirectory ?: viewModel.currentPath)
    }

    /** Whether this pane has pending clipboard content that can be pasted here. */
    fun hasPasteContent(): Boolean = viewModel.pasteState.files.isNotEmpty()

    private fun makePathListForJob(files: FileItemSet): List<Path> =
        files.map { it.path }.sortedBy { it.toUri() }

    private fun onFileNameEllipsizeChanged(fileNameEllipsize: TextUtils.TruncateAt) {
        adapter.nameEllipsize = fileNameEllipsize
    }

    private fun onWrapLongFileNamesChanged(wrapLongFileNames: Boolean) {
        adapter.wrapLongFileNames = wrapLongFileNames
        // Re-set the adapter so RecyclerView re-measures the (now content-sized) item
        // heights; notifyDataSetChanged() alone reuses the old measured heights.
        binding.recyclerView.adapter = adapter
    }

    override fun clearSelectedFiles() {
        viewModel.clearSelectedFiles()
    }

    override fun selectFile(file: FileItem, selected: Boolean) {
        viewModel.selectFile(file, selected)
    }

    override fun selectFiles(files: FileItemSet, selected: Boolean) {
        viewModel.selectFiles(files, selected)
    }

    override fun openFile(file: FileItem) {
        val pickOptions = viewModel.pickOptions
        if (pickOptions != null) {
            if (file.attributes.isDirectory) {
                navigateTo(file.path)
            } else {
                when (pickOptions.mode) {
                    PickOptions.Mode.OPEN_FILE -> pickFiles(fileItemSetOf(file))
                    PickOptions.Mode.CREATE_FILE -> confirmReplaceFile(file)
                    PickOptions.Mode.OPEN_DIRECTORY -> {}
                }
            }
            return
        }
        if (file.mimeType.isApk) {
            openApk(file)
            return
        }
        if (file.isListable) {
            navigateTo(file.listablePath)
            return
        }
        // Built-in default openers (MT Manager style): categories with a matching built-in
        // opener are opened with it directly, without consulting the system default.
        if (openWithBuiltInViewer(file)) {
            return
        }
        if (file.mimeType == MimeType.GENERIC) {
            // No extension or an unrecognized one: sniff the file header and route by
            // content (ELF/DEX analyzers, images, text) instead of always asking. When
            // the content is an unknown binary, fall through with the sniffed MIME so
            // the system intent still reaches handlers that filter by type.
            viewLifecycleOwner.lifecycleScope.launch {
                val contentIntent: Intent?
                val sniffedMime: MimeType?
                withContext(Dispatchers.IO) {
                    contentIntent = BuiltInFileOpeners.createOpenIntentForContent(file.path)
                    sniffedMime = BuiltInFileOpeners.sniffMimeType(file.path)?.asMimeType()
                }
                if (contentIntent != null) {
                    startActivitySafe(contentIntent)
                } else {
                    openFileWithIntent(file, false, sniffedMime)
                }
            }
            return
        }
        openFileWithIntent(file, false)
    }

    private fun openApk(file: FileItem) {
        if (!file.isListable) {
            installApk(file)
            return
        }
        when (Settings.OPEN_APK_DEFAULT_ACTION.valueCompat) {
            OpenApkDefaultAction.INSTALL -> installApk(file)
            OpenApkDefaultAction.VIEW -> viewApk(file)
            OpenApkDefaultAction.ASK -> OpenApkDialogFragment.show(file, this)
        }
    }

    override fun installApk(file: FileItem) {
        val path = file.path
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!path.isArchivePath) path.fileProviderUri else null
        } else {
            // PackageInstaller only supports file URI before N.
            if (path.isLinuxPath) Uri.fromFile(path.toFile()) else null
        }
        if (uri != null) {
            startActivitySafe(uri.createInstallPackageIntent())
        } else {
            FileJobService.installApk(path, requireContext())
        }
    }

    override fun viewApk(file: FileItem) {
        navigateTo(file.listablePath)
    }

    override fun openFileWith(file: FileItem) {
        if (file.mimeType == MimeType.GENERIC) {
            // No extension / unrecognized extension: sniff the content so the system
            // chooser is filtered by the real type instead of the generic octet-stream.
            viewLifecycleOwner.lifecycleScope.launch {
                val sniffedMime: MimeType? = withContext(Dispatchers.IO) {
                    BuiltInFileOpeners.sniffMimeType(file.path)?.asMimeType()
                }
                openFileWithIntent(file, true, sniffedMime)
            }
            return
        }
        openFileWithIntent(file, true)
    }

    /**
     * Opens the file with the built-in opener for its category (MT Manager style): images
     * in the image viewer, text/code in the editor, DEX in the DEX analyzer and ELF in the
     * ELF analyzer, without consulting the system's default open method. Returns false when
     * there is no built-in opener for the file, so the caller falls back to ACTION_VIEW.
     */
    private fun openWithBuiltInViewer(file: FileItem): Boolean {
        val path = file.path
        val mimeType = file.mimeType
        val intent = BuiltInFileOpeners.createOpenIntent(path, mimeType) ?: return false
        if (mimeType.isImage) {
            // Add the neighbouring images of this directory for swipe navigation, replacing
            // the single-image extras.
            maybeAddImageViewerActivityExtras(intent, path, mimeType)
        }
        startActivitySafe(intent)
        return true
    }

    private fun openFileWithIntent(
        file: FileItem,
        withChooser: Boolean,
        mimeTypeOverride: MimeType? = null
    ) {
        val path = file.path
        val mimeType = mimeTypeOverride ?: file.mimeType
        if (path.isArchivePath) {
            FileJobService.open(path, mimeType, withChooser, requireContext())
        } else {
            val intent = path.fileProviderUri.createViewIntent(mimeType)
                .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .apply {
                    extraPath = path
                    maybeAddImageViewerActivityExtras(this, path, mimeType)
                }
                .let {
                    if (withChooser) {
                        it.withChooser(
                            EditFileActivity::class.createIntent()
                                .putArgs(EditFileActivity.Args(path, mimeType)),
                            OpenFileAsDialogActivity::class.createIntent()
                                .putArgs(OpenFileAsDialogFragment.Args(path))
                        )
                    } else {
                        it
                    }
                }
            startActivitySafe(intent)
        }
    }

    private fun maybeAddImageViewerActivityExtras(intent: Intent, path: Path, mimeType: MimeType) {
        if (!mimeType.isImage) {
            return
        }
        var paths = mutableListOf<Path>()
        // We need the ordered list from our adapter instead of the list from FileListLiveData.
        for (index in 0..<adapter.itemCount) {
            val file = adapter.getItem(index)
            val filePath = file.path
            if (file.mimeType.isImage || filePath == path) {
                paths.add(filePath)
            }
        }
        var position = paths.indexOf(path)
        if (position == -1) {
            return
        }
        // HACK: Don't send too many paths to avoid TransactionTooLargeException.
        if (paths.size > IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX) {
            val start = (position - IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX / 2)
                .coerceIn(0, paths.size - IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX)
            paths = paths.subList(start, start + IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX)
            position -= start
        }
        ImageViewerActivity.putExtras(intent, paths, position)
    }

    override fun cutFile(file: FileItem) {
        cutFiles(fileItemSetOf(file))
    }

    override fun copyFile(file: FileItem) {
        copyFiles(fileItemSetOf(file))
    }

    /** The current directory of the other pane in two-pane mode, or null (F5/F6 shortcut). */
    private fun otherPaneCurrentPath(): Path? =
        if (args.secondaryPane) {
            TwoPaneState.primaryPanePath
        } else {
            TwoPaneState.secondaryPanePath
        }

    override fun confirmDeleteFile(file: FileItem) {
        confirmDeleteFiles(fileItemSetOf(file))
    }

    override fun showRenameFileDialog(file: FileItem) {
        RenameFileDialogFragment.show(file, this)
    }

    override fun hideFile(file: FileItem) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.file_item_action_hide)
            .setMessage(getString(R.string.file_item_action_hide_confirm_format, file.name))
            .setPositiveButton(R.string.file_item_action_hide) { _, _ ->
                val hiddenPaths = HiddenPaths.getAll().toMutableSet()
                hiddenPaths += file.path.toString()
                HiddenPaths.set(hiddenPaths)
                viewModel.reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showManageHiddenDialog() {
        val hiddenPaths = HiddenPaths.getAll().toList()
        if (hiddenPaths.isEmpty()) {
            // A toast is too easy to miss; use a dialog so the user actually learns how to
            // reach the Hide entry (the 鈰?menu on a file, not long-press which is multi-select).
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.file_list_action_manage_hidden)
                .setMessage(R.string.file_list_action_manage_hidden_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val checked = BooleanArray(hiddenPaths.size) { true }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.file_list_action_manage_hidden)
            .setMultiChoiceItems(hiddenPaths.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.unhide) { _, _ ->
                val remaining = hiddenPaths.filterIndexed { index, _ -> !checked[index] }.toSet()
                HiddenPaths.set(remaining)
                viewModel.reload()
            }
            .setNegativeButton(R.string.unhide_all) { _, _ ->
                HiddenPaths.set(emptySet())
                viewModel.reload()
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Rebuilds the SQLite file name index (storage volumes, plus /data trees when root/
     * Shizuku is available) in the background, e.g. after installing new files.
     */
    private fun rebuildSearchIndex() {
        if (FileIndexer.isIndexing) {
            showToast(R.string.file_list_action_rebuild_index_already)
            return
        }
        val roots = FileIndexer.getIndexRoots()
        if (roots.isEmpty()) {
            showToast(R.string.file_list_action_rebuild_index_error)
            return
        }
        showToast(R.string.file_list_action_rebuild_index_started)
        FileIndexer.startIndex(roots, onProgress = {}, onDone = { throwable ->
            // showToast() posts to the main thread when called off it.
            if (throwable == null) {
                showToast(R.string.file_list_action_rebuild_index_done)
            } else {
                showToast(throwable.toString())
            }
        })
    }

    override fun hasFileWithName(name: String): Boolean = getFileWithName(name) != null

    private fun getFileWithName(name: String): FileItem? {
        val fileListData = viewModel.fileListStateful
        if (fileListData !is Success) {
            return null
        }
        return fileListData.value.find { it.name == name }
    }

    override fun renameFile(file: FileItem, newName: String) {
        FileJobService.rename(file.path, newName, requireContext())
        viewModel.selectFile(file, false)
    }

    override fun extractFile(file: FileItem) {
        copyFile(file.createDummyArchiveRoot())
    }

    override fun showCreateArchiveDialog(file: FileItem) {
        showCreateArchiveDialog(fileItemSetOf(file))
    }

    override fun shareFile(file: FileItem) {
        shareFile(file.path, file.mimeType)
    }

    private fun shareFile(path: Path, mimeType: MimeType) {
        shareFiles(listOf(path), listOf(mimeType))
    }

    private fun shareFiles(paths: List<Path>, mimeTypes: List<MimeType>) {
        val uris = paths.map { it.fileProviderUri }
        val intent = uris.createSendStreamIntent(mimeTypes)
            .withChooser()
        startActivitySafe(intent)
    }

    override fun copyPath(file: FileItem) {
        copyPath(file.path)
    }

    override fun addBookmark(file: FileItem) {
        addBookmark(file.path)
    }

    private fun addBookmark() {
        addBookmark(currentPath)
    }

    private fun addBookmark(path: Path) {
        BookmarkDirectories.add(BookmarkDirectory(null, path))
        showToast(R.string.file_add_bookmark_success)
    }

    override fun createShortcut(file: FileItem) {
        createShortcut(file.path, file.mimeType)
    }

    private fun createShortcut() {
        createShortcut(currentPath, MimeType.DIRECTORY)
    }

    private fun createShortcut(path: Path, mimeType: MimeType) {
        val context = requireContext()
        val isDirectory = mimeType == MimeType.DIRECTORY
        val shortcutInfo = ShortcutInfoCompat.Builder(context, path.toString())
            .setShortLabel(path.name)
            .setIntent(
                if (isDirectory) {
                    FileListActivity.createViewIntent(path)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                } else {
                    OpenFileActivity.createIntent(path, mimeType)
                }
            )
            .setIcon(
                IconCompat.createWithResource(
                    context, if (isDirectory) {
                        R.mipmap.directory_shortcut_icon
                    } else {
                        R.mipmap.file_shortcut_icon
                    }
                )
            )
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, shortcutInfo, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            showToast(R.string.shortcut_created)
        }
    }

    override fun showPropertiesDialog(file: FileItem) {
        FilePropertiesDialogFragment.show(file, this)
    }

    override fun showDexAnalyzer(file: FileItem) {
        startActivity(
            DexAnalyzerActivity::class.createIntent().apply {
                extraPath = file.path
            }
        )
    }

    override fun installFile(file: FileItem) {
        val path = file.path
        if (path.isArchivePath) {
            FileJobService.installApk(path, requireContext())
        } else {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(path.fileProviderUri, file.mimeType.value)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    override fun showApkStringSearch(file: FileItem) {
        startActivity(
            ApkStringSearchActivity::class.createIntent().apply {
                extraPath = file.path
            }
        )
    }

    override fun showElfAnalyzer(file: FileItem) {
        startActivity(
            ElfAnalyzerActivity::class.createIntent().apply {
                extraPath = file.path
            }
        )
    }

    override fun showHexViewer(file: FileItem) {
        startActivity(
            HexViewerActivity::class.createIntent().apply {
                extraPath = file.path
            }
        )
    }

    override fun compareApk(file: FileItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val message = try {
                compareApkWithInstalled(file)
            } catch (e: Throwable) {
                e.javaClass.simpleName + ": " + (e.localizedMessage ?: "")
            }
            withContext(Dispatchers.Main) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.compare_apk_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun compareApkWithInstalled(file: FileItem): String {
        @Suppress("DEPRECATION")
        var packageInfoFlags = (PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfoFlags = packageInfoFlags or PackageManager.GET_SIGNING_CERTIFICATES
        }
        val (apkPackageInfo, closeable) =
            packageManager.getPackageArchiveInfoCompat(file.path, packageInfoFlags)
        val apkPackageInfoValue = apkPackageInfo ?: throw IOException("ApplicationInfo is null")
        val apkSigningCertificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            apkPackageInfoValue.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            apkPackageInfoValue.signatures
        }
        val apkSigningDigests = (apkSigningCertificates ?: emptyArray())
            .map { it.toByteArray().sha1Digest().toHexString() }
        closeable?.close()
        val builder = StringBuilder()
        builder.append(getString(R.string.compare_apk_apk_format, formatApkVersion(apkPackageInfoValue)))
            .append('\n')
        val installedPackageInfo = try {
            packageManager.getPackageInfo(
                apkPackageInfoValue.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        if (installedPackageInfo == null) {
            builder.append(getString(R.string.compare_apk_not_installed))
        } else {
            builder.append(
                getString(
                    R.string.compare_apk_installed_format,
                    formatApkVersion(installedPackageInfo)
                )
            ).append('\n')
            val installedSigningCertificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installedPackageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                installedPackageInfo.signatures
            }
            val installedSigningDigests = (installedSigningCertificates ?: emptyArray())
                .map { it.toByteArray().sha1Digest().toHexString() }
            builder.append(
                if (apkSigningDigests.isNotEmpty() &&
                    apkSigningDigests == installedSigningDigests
                ) {
                    getString(R.string.compare_apk_signature_match)
                } else if (apkSigningDigests.isEmpty() || installedSigningDigests.isEmpty()) {
                    getString(R.string.compare_apk_signature_unknown)
                } else {
                    getString(R.string.compare_apk_signature_mismatch)
                }
            )
        }
        return builder.toString()
    }

    override fun showManifest(file: FileItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val path = try {
                decodeAndCacheManifest(file)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast(e.localizedMessage ?: getString(R.string.manifest_decode_error))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                startActivity(
                    TextEditorActivity::class.createIntent().apply {
                        extraPath = path
                    }
                )
            }
        }
    }

    private fun decodeAndCacheManifest(file: FileItem): Path {
        val cacheDirectory = File(requireContext().cacheDir, "manifest-cache")
        cacheDirectory.mkdirs()
        // Unique cache name per input path: two concurrent manifest views of different APKs
        // must not overwrite each other's cache file.
        val cacheKey = file.path.toString().hashCode()
        val cacheFile = File(cacheDirectory, "AndroidManifest-$cacheKey.xml")
        Files.newInputStream(file.path).use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        val manifestBytes = java.util.zip.ZipFile(cacheFile).use { zipFile ->
            val entry = zipFile.getEntry("AndroidManifest.xml")
                ?: throw IOException("AndroidManifest.xml not found")
            zipFile.getInputStream(entry).use { it.readBytes() }
        }
        val decoded = AndroidManifestDecoder.decode(manifestBytes)
        val outputFile = File(cacheDirectory, "AndroidManifest-decoded-$cacheKey.xml")
        Files.write(Paths.get(outputFile.absolutePath), decoded.toByteArray())
        return Paths.get(outputFile.absolutePath)
    }

    private fun formatApkVersion(packageInfo: PackageInfo): String = getString(
        R.string.compare_apk_version_format, packageInfo.versionName,
        packageInfo.longVersionCodeCompat
    )

    override fun showSetTimestampDialog(file: FileItem) {
        val now = System.currentTimeMillis()
        var lastModified = now
        var lastAccessed = now
        var creation = now
        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.set_timestamp_title)
            .setNegativeButton(android.R.string.cancel, null)
        val view = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun addTimeRow(labelRes: Int, initial: Long, onSet: (Long) -> Unit) {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(requireContext()).apply {
                text = getString(labelRes)
            }
            val value = TextView(requireContext()).apply {
                text = formatTimestamp(initial)
                setTextColor(
                    androidx.core.content.ContextCompat.getColor(
                        requireContext(), R.color.color_primary
                    )
                )
                val margin = (8 * resources.displayMetrics.density).toInt()
                setPadding(margin, 0, 0, 0)
            }
            row.addView(label)
            row.addView(value)
            row.setOnClickListener {
                pickTimestamp(initial) { time ->
                    onSet(time)
                    value.text = formatTimestamp(time)
                }
            }
            view.addView(row)
        }
        addTimeRow(R.string.set_timestamp_last_modified, lastModified) {
            lastModified = it
        }
        addTimeRow(R.string.set_timestamp_last_accessed, lastAccessed) {
            lastAccessed = it
        }
        addTimeRow(R.string.set_timestamp_creation, creation) {
            creation = it
        }
        dialogBuilder.setView(view)
            .setPositiveButton(R.string.set_timestamp_apply) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        Files.setLastModifiedTime(file.path, FileTime.fromMillis(lastModified))
                        try {
                            Files.setAttribute(file.path, "lastAccessTime", FileTime.fromMillis(lastAccessed))
                        } catch (e: UnsupportedOperationException) {
                        }
                        try {
                            Files.setAttribute(file.path, "creationTime", FileTime.fromMillis(creation))
                        } catch (e: UnsupportedOperationException) {
                        }
                        viewModel.reload()
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            val message = if (e.localizedMessage?.contains("MFMT") == true) {
                                getString(R.string.set_timestamp_ftp_unsupported)
                            } else {
                                e.localizedMessage ?: getString(R.string.set_timestamp_failed)
                            }
                            showToast(message)
                        }
                    }
                }
            }
        dialogBuilder.show()
    }

    private fun showBatchRenameDialog(files: FileItemSet) {
        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.batch_rename_title)
            .setNegativeButton(android.R.string.cancel, null)
        val view = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun addField(labelRes: Int, hint: String? = null): EditText {
            val label = TextView(requireContext()).apply {
                text = getString(labelRes)
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
            }
            val field = EditText(requireContext()).apply {
                this.hint = hint
                isSingleLine = true
            }
            view.addView(label)
            view.addView(field)
            return field
        }
        val prefixField = addField(R.string.batch_rename_prefix)
        val suffixField = addField(R.string.batch_rename_suffix)
        val findField = addField(R.string.batch_rename_find)
        val replaceField = addField(R.string.batch_rename_replace)
        val numberingCheckBox = CheckBox(requireContext()).apply {
            text = getString(R.string.batch_rename_numbering)
        }
        view.addView(numberingCheckBox)
        val numberingRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val startField = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("1")
        }
        val digitsField = EditText(requireContext()).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("2")
        }
        numberingRow.addView(
            TextView(requireContext()).apply { text = getString(R.string.batch_rename_start) }
        )
        numberingRow.addView(startField)
        numberingRow.addView(
            TextView(requireContext()).apply { text = getString(R.string.batch_rename_digits) }
        )
        numberingRow.addView(digitsField)
        view.addView(numberingRow)
        dialogBuilder.setView(view)
            .setPositiveButton(R.string.batch_rename_apply) { _, _ ->
                val prefix = prefixField.text?.toString().orEmpty()
                val suffix = suffixField.text?.toString().orEmpty()
                val find = findField.text?.toString()
                val replace = replaceField.text?.toString().orEmpty()
                val numbering = numberingCheckBox.isChecked
                val start = startField.text?.toString()?.toIntOrNull() ?: 1
                val digits = digitsField.text?.toString()?.toIntOrNull() ?: 2
                applyBatchRename(
                    files, prefix, suffix, find, replace, numbering, start, digits
                )
            }
        dialogBuilder.show()
    }

    private fun applyBatchRename(
        files: FileItemSet,
        prefix: String,
        suffix: String,
        find: String?,
        replace: String,
        numbering: Boolean,
        start: Int,
        digits: Int
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val sortedFiles = files.sortedBy { it.name }
            var renamed = 0
            var number = start
            for (file in sortedFiles) {
                try {
                    val base = file.name.substringBeforeLast('.', file.name)
                    val extension = if (file.name.contains('.')) {
                        file.name.substringAfterLast('.')
                    } else {
                        ""
                    }
                    var newBase = base
                    if (!find.isNullOrEmpty()) {
                        newBase = newBase.replace(find, replace)
                    }
                    val numberText = if (numbering) {
                        number.toString().padStart(digits, '0')
                    } else {
                        ""
                    }
                    number++
                    val newName = prefix + newBase + suffix + numberText +
                        (if (extension.isEmpty()) "" else ".$extension")
                    val target = file.path.parent.resolve(newName)
                    if (Files.exists(target)) {
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.batch_rename_conflict))
                        }
                        return@launch
                    }
                    Files.move(file.path, target)
                    renamed++
                } catch (e: Throwable) {
                    withContext(Dispatchers.Main) {
                        showToast(
                            getString(R.string.batch_rename_failed_format, e.localizedMessage ?: "")
                        )
                    }
                    return@launch
                }
            }
            withContext(Dispatchers.Main) {
                showToast(getString(R.string.batch_rename_done_format, renamed))
                viewModel.reload()
            }
        }
    }

    override fun renameApkWithVersion(file: FileItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val versionName = getApkVersionName(file)
                val base = file.name.substringBeforeLast('.', file.name)
                var newName = "${base}_$versionName.apk"
                var target = file.path.parent.resolve(newName)
                var index = 1
                while (Files.exists(target)) {
                    newName = "${base}_$versionName($index).apk"
                    target = file.path.parent.resolve(newName)
                    index++
                }
                Files.move(file.path, target)
                withContext(Dispatchers.Main) {
                    showToast(getString(R.string.apk_rename_done_format, newName))
                    viewModel.reload()
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast(e.localizedMessage ?: getString(R.string.apk_rename_error))
                }
            }
        }
    }

    private fun getApkVersionName(file: FileItem): String {
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES
        val (packageInfo, closeable) =
            packageManager.getPackageArchiveInfoCompat(file.path, flags)
        closeable?.close()
        return packageInfo?.versionName ?: "unknown"
    }

    override fun showSignApkDialog(file: FileItem) {
        var keystorePath: Path? = null
        val dialogBuilder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.apk_sign_title)
            .setNegativeButton(android.R.string.cancel, null)
        val view = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val keystoreRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val keystoreText = TextView(requireContext()).apply {
            text = getString(R.string.apk_sign_no_keystore)
        }
        val chooseButton = MaterialButton(requireContext()).apply {
            text = getString(R.string.apk_sign_choose_keystore)
        }
        chooseButton.setOnClickListener {
            keystorePickerCallback.callback = { path ->
                if (path != null) {
                    keystorePath = path
                    keystoreText.text = path.fileName.toString()
                }
            }
            keystorePickerLauncher.launch(listOf(MimeType.ANY))
        }
        keystoreRow.addView(keystoreText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        keystoreRow.addView(chooseButton)
        view.addView(keystoreRow)
        fun addPasswordField(labelRes: Int): EditText {
            val label = TextView(requireContext()).apply {
                text = getString(labelRes)
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
            }
            val field = EditText(requireContext()).apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
            }
            view.addView(label)
            view.addView(field)
            return field
        }
        val storePasswordField = addPasswordField(R.string.apk_sign_keystore_password)
        val aliasField = addPasswordField(R.string.apk_sign_key_alias)
        val keyPasswordField = addPasswordField(R.string.apk_sign_key_password)
        view.addView(
            TextView(requireContext()).apply {
                text = getString(R.string.apk_sign_v1_note)
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
            }
        )
        dialogBuilder.setView(view)
            .setPositiveButton(R.string.file_item_action_sign_apk) { _, _ ->
                val keystore = keystorePath ?: return@setPositiveButton
                val storePassword = storePasswordField.text?.toString().orEmpty()
                val alias = aliasField.text?.toString().orEmpty()
                val keyPassword = keyPasswordField.text?.toString().orEmpty()
                signApk(file, keystore, storePassword, alias, keyPassword)
            }
        dialogBuilder.show()
    }

    override fun autoSignApk(file: FileItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val cacheDirectory = File(requireContext().cacheDir, "apk-sign")
                cacheDirectory.mkdirs()
                val inputFile = File(cacheDirectory, "input-auto.apk")
                Files.newInputStream(file.path).use { input ->
                    inputFile.outputStream().use { output -> input.copyTo(output) }
                }
                val (privateKey, certificate) = AutoSigner.getOrCreateKey(requireContext())
                val outputFile = File(
                    cacheDirectory,
                    file.name.substringBeforeLast('.', file.name) + "-signed.apk"
                )
                ApkSigner.sign(inputFile, outputFile, privateKey, certificate)
                outputFile
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast(e.localizedMessage ?: getString(R.string.apk_sign_error))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val outputPath = file.path.parent.resolve(result.name)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        Files.newInputStream(
                            java8.nio.file.Paths.get(result.absolutePath)
                        ).use { input ->
                            Files.newOutputStream(outputPath).use { output ->
                                input.copyTo(output)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.apk_sign_done_format, result.name))
                            viewModel.reload()
                        }
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            showToast(e.localizedMessage ?: getString(R.string.apk_sign_error))
                        }
                    }
                }
            }
        }
    }

    override fun killSignature(file: FileItem) {
        val outputName = file.name.substringBeforeLast('.', file.name) + "-nosign.apk"
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.apk_kill_title)
            .setMessage(getString(R.string.apk_kill_confirm_format, outputName))
            .setPositiveButton(R.string.file_item_action_kill_signature) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val result = try {
                        val cacheDirectory = File(requireContext().cacheDir, "apk-kill-output")
                        cacheDirectory.mkdirs()
                        val outputFile = File(cacheDirectory, "output.apk")
                        ApkSignatureKiller.kill(requireContext(), file.path, outputFile)
                        val outputPath = file.path.parent.resolve(outputName)
                        Files.newInputStream(Paths.get(outputFile.absolutePath)).use { input ->
                            Files.newOutputStream(outputPath).use { output ->
                                input.copyTo(output)
                            }
                        }
                        outputFile.delete()
                        outputName
                    } catch (e: Throwable) {
                        Log.e("ApkSignatureKiller", "kill failed", e)
                        withContext(Dispatchers.Main) {
                            showToast(e.localizedMessage ?: getString(R.string.apk_kill_error))
                        }
                        return@launch
                    }
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.apk_kill_done_format, result))
                        viewModel.reload()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun showArscEditor(file: FileItem) {
        val intent = ArscEditorActivity::class.createIntent()
        intent.extraPath = file.path
        startActivitySafe(intent)
    }

    override fun showEncodingConversionDialog(file: FileItem) {
        val encodings = listOf(
            "UTF-8", "GB18030", "GBK", "UTF-16LE", "UTF-16BE", "UTF-16",
            "ISO-8859-1", "US-ASCII", "BIG5", "Shift_JIS"
        )
        val fromSpinner = Spinner(requireContext()).apply {
            adapter = android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, encodings
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(0)
        }
        val toSpinner = Spinner(requireContext()).apply {
            adapter = android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_item, encodings
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(0)
        }
        fun addRow(labelRes: Int, spinner: Spinner): LinearLayout {
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val label = TextView(requireContext()).apply {
                text = getString(labelRes)
                setTextAppearance(android.R.style.TextAppearance_Material_Small)
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(spinner)
            return row
        }
        val view = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(addRow(R.string.encoding_convert_source, fromSpinner))
            addView(addRow(R.string.encoding_convert_target, toSpinner))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.file_item_action_convert_encoding)
            .setMessage(file.name)
            .setView(view)
            .setPositiveButton(R.string.encoding_convert_convert) { _, _ ->
                val fromEncoding = encodings[fromSpinner.selectedItemPosition]
                val toEncoding = encodings[toSpinner.selectedItemPosition]
                convertFileEncoding(file, fromEncoding, toEncoding)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun convertFileEncoding(file: FileItem, fromEncoding: String, toEncoding: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val bytes = Files.newInputStream(file.path).use { it.readBytes() }
                val text = try {
                    String(bytes, Charset.forName(fromEncoding))
                } catch (e: Exception) {
                    throw java.io.IOException(getString(R.string.encoding_convert_decode_error))
                }
                val converted = try {
                    text.toByteArray(Charset.forName(toEncoding))
                } catch (e: Exception) {
                    throw java.io.IOException(getString(R.string.encoding_convert_encode_error))
                }
                Files.newOutputStream(file.path).use { it.write(converted) }
                getString(R.string.encoding_convert_done_format, fromEncoding, toEncoding)
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast(e.localizedMessage ?: getString(R.string.encoding_convert_error))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                showToast(result)
                viewModel.reload()
            }
        }
    }

    /**
     * Loads a signing keystore, trying the common formats in order. The platform default
     * (BKS on Android) cannot open the PKCS12/JKS files produced by keytool/Android Studio,
     * which is why the type must be probed explicitly.
     */
    private fun loadKeystore(
        path: java8.nio.file.Path,
        password: CharArray
    ): java.security.KeyStore {
        val bytes = path.fileSystem.provider().newInputStream(path).use { it.readBytes() }
        var lastError: Exception? = null
        for (type in listOf("PKCS12", "JKS", "BKS")) {
            try {
                val keyStore = java.security.KeyStore.getInstance(type)
                bytes.inputStream().use { keyStore.load(it, password) }
                return keyStore
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("Unable to load keystore")
    }

    private fun signApk(
        file: FileItem,
        keystorePath: Path,
        storePassword: String,
        alias: String,
        keyPassword: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                val cacheDirectory = File(requireContext().cacheDir, "apk-sign")
                cacheDirectory.mkdirs()
                val inputFile = File(cacheDirectory, "input.apk")
                Files.newInputStream(file.path).use { input ->
                    inputFile.outputStream().use { output -> input.copyTo(output) }
                }
                val keyStore = loadKeystore(keystorePath, storePassword.toCharArray())
                val privateKey = keyStore.getKey(
                    alias, keyPassword.toCharArray()
                ) as java.security.PrivateKey
                val certificate = keyStore.getCertificate(alias) as java.security.cert.X509Certificate
                val outputFile = File(
                    cacheDirectory,
                    file.name.substringBeforeLast('.', file.name) + "-signed.apk"
                )
                ApkSigner.sign(inputFile, outputFile, privateKey, certificate)
                outputFile
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    showToast(e.localizedMessage ?: getString(R.string.apk_sign_error))
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val outputPath = file.path.parent.resolve(result.name)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        Files.newInputStream(
                            java8.nio.file.Paths.get(result.absolutePath)
                        ).use { input ->
                            Files.newOutputStream(outputPath).use { output ->
                                input.copyTo(output)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            showToast(getString(R.string.apk_sign_done_format, result.name))
                            viewModel.reload()
                        }
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) {
                            showToast(e.localizedMessage ?: getString(R.string.apk_sign_error))
                        }
                    }
                }
            }
        }
    }

    private val keystorePickerCallback = object {
        var callback: ((Path?) -> Unit)? = null
    }
    private val keystorePickerLauncher = registerForActivityResult(
        FileListActivity.OpenFileContract()
    ) { path ->
        keystorePickerCallback.callback?.invoke(path)
        keystorePickerCallback.callback = null
    }

    private fun pickTimestamp(initial: Long, onSet: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply { timeInMillis = initial }
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                TimePickerDialog(
                    requireContext(),
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        onSet(calendar.timeInMillis)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    true
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun formatTimestamp(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))

    fun showCreateFileDialog() {
        CreateFileDialogFragment.show(this)
    }

    override fun createFile(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, false, requireContext())
    }

    fun showCreateDirectoryDialog() {
        CreateDirectoryDialogFragment.show(this)
    }

    override fun createDirectory(name: String) {
        val path = currentPath.resolve(name)
        FileJobService.create(path, true, requireContext())
    }

    override val currentPath: Path
        get() = viewModel.currentPath

    override fun navigateToRoot(path: Path) {
        collapseSearchView()
        viewModel.resetTo(path)
    }

    override fun navigateToDefaultRoot() {
        navigateToRoot(Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat)
    }

    override fun observeCurrentPath(owner: LifecycleOwner, observer: (Path) -> Unit) {
        viewModel.currentPathLiveData.observe(owner, observer)
    }

    override fun closeNavigationDrawer() {
        if (isTwoPaneMode) {
            (requireActivity() as FileListActivity).closeNavigationDrawer()
            return
        }
        binding.drawerLayout?.closeDrawer(GravityCompat.START)
    }

    private fun ensureStorageAccess() {
        if (viewModel.isStorageAccessRequested) {
            return
        }
        if (Environment::class.supportsExternalStorageManager()) {
            if (!Environment.isExternalStorageManager()) {
                ShowRequestAllFilesAccessRationaleDialogFragment.show(this)
                viewModel.isStorageAccessRequested = true
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                if (shouldShowRequestPermissionRationale(
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )) {
                    ShowRequestStoragePermissionRationaleDialogFragment.show(this)
                } else {
                    requestStoragePermission()
                }
                viewModel.isStorageAccessRequested = true
            }
        }
    }

    override fun onShowRequestAllFilesAccessRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestAllFilesAccess()
        } else {
            viewModel.isStorageAccessRequested = false
            // This isn't an onActivityResult() callback so it's not delivered before calling
            // onResume(), and we need to do this manually.
            ensureNotificationPermission()
        }
    }

    private fun requestAllFilesAccess() {
        requestAllFilesAccessLauncher.launch(Unit)
    }

    private fun onRequestAllFilesAccessResult(isGranted: Boolean) {
        viewModel.isStorageAccessRequested = false
        if (isGranted) {
            refresh()
        }
    }

    override fun onShowRequestStoragePermissionRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestStoragePermission()
        } else {
            viewModel.isStorageAccessRequested = false
        }
    }

    private fun requestStoragePermission() {
        requestStoragePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun onRequestStoragePermissionResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isStorageAccessRequested = false
            refresh()
        } else if (shouldShowRequestPermissionRationale(
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        )) {
            ShowRequestStoragePermissionRationaleDialogFragment.show(this)
        } else {
            ShowRequestStoragePermissionInSettingsRationaleDialogFragment.show(this)
        }
    }

    override fun onShowRequestStoragePermissionInSettingsRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestStoragePermissionInSettings()
        } else {
            viewModel.isStorageAccessRequested = false
        }
    }

    private fun requestStoragePermissionInSettings() {
        requestStoragePermissionInSettingsLauncher.launch(Unit)
    }

    private fun onRequestStoragePermissionInSettingsResult(isGranted: Boolean) {
        viewModel.isStorageAccessRequested = false
        if (isGranted) {
            refresh()
        }
    }

    private fun ensureNotificationPermission() {
        if (viewModel.isNotificationPermissionRequested) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )) {
                    ShowRequestNotificationPermissionRationaleDialogFragment.show(this)
                } else {
                    requestNotificationPermission()
                }
                viewModel.isNotificationPermissionRequested = true
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionRationaleResult(shouldRequest: Boolean) {
        if (shouldRequest) {
            requestNotificationPermission()
        } else {
            viewModel.isNotificationPermissionRequested = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNotificationPermission() {
        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onRequestNotificationPermissionResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isNotificationPermissionRequested = false
        } else if (shouldShowRequestPermissionRationale(
            android.Manifest.permission.POST_NOTIFICATIONS
        )) {
            ShowRequestNotificationPermissionRationaleDialogFragment.show(this)
        } else {
            ShowRequestNotificationPermissionInSettingsRationaleDialogFragment.show(this)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onShowRequestNotificationPermissionInSettingsRationaleResult(
        shouldRequest: Boolean
    ) {
        if (shouldRequest) {
            requestNotificationPermissionInSettings()
        } else {
            viewModel.isNotificationPermissionRequested = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun requestNotificationPermissionInSettings() {
        requestNotificationPermissionInSettingsLauncher.launch(Unit)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun onRequestNotificationPermissionInSettingsResult(isGranted: Boolean) {
        if (isGranted) {
            viewModel.isNotificationPermissionRequested = false
        }
    }

    companion object {
        private const val TAG = "SoraEditor"

        private const val ACTION_VIEW_DOWNLOADS =
            "me.zhanghai.android.files.intent.action.VIEW_DOWNLOADS"

        private const val IMAGE_VIEWER_ACTIVITY_PATH_LIST_SIZE_MAX = 1000
    }

    private class RequestAllFilesAccessContract : ActivityResultContract<Unit, Boolean>() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun createIntent(context: Context, input: Unit): Intent =
            Environment::class.createManageAppAllFilesAccessPermissionIntent(context.packageName)

        @RequiresApi(Build.VERSION_CODES.R)
        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            Environment.isExternalStorageManager()
    }

    private class RequestPermissionInSettingsContract(private val permissionName: String)
        : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            application.checkSelfPermissionCompat(permissionName) ==
                PackageManager.PERMISSION_GRANTED
    }

    @Parcelize
    class Args(val intent: Intent, val secondaryPane: Boolean) : ParcelableArgs {
        // @Parcelize does not allow default parameter values, so provide a secondary
        // constructor for the common single-pane case.
        constructor(intent: Intent) : this(intent, false)
    }

private class Binding private constructor(
        val root: FrameLayout,
        val drawerLayout: DrawerLayout?,
        val persistentDrawerLayout: PersistentDrawerLayout?,
        val persistentBarLayout: PersistentBarLayout,
        val appBarLayout: CoordinatorAppBarLayout,
        val toolbar: Toolbar,
        val overlayToolbar: OverlayToolbar,
        val breadcrumbLayout: BreadcrumbLayout,
        val contentLayout: ViewGroup,
        val progress: ProgressBar,
        val errorText: TextView,
        val emptyView: View,
        val swipeRefreshLayout: SwipeRefreshLayout,
        val recyclerView: RecyclerView,
        val bottomBarLayout: ViewGroup,
        val bottomToolbar: Toolbar,
        val bottomCreateFileNameEdit: EditText,
        val extractDestinationEdit: EditText,
        val speedDialView: SpeedDialView
    ) {
        companion object {
            fun inflate(
                inflater: LayoutInflater,
                root: ViewGroup?,
                attachToRoot: Boolean
            ): Binding {
                val binding = FileListFragmentBinding.inflate(inflater, root, attachToRoot)
                val bindingRoot = binding.root
                val includeBinding = FileListFragmentIncludeBinding.bind(bindingRoot)
                val appBarBinding = FileListFragmentAppBarIncludeBinding.bind(bindingRoot)
                val contentBinding = FileListFragmentContentIncludeBinding.bind(bindingRoot)
                val bottomBarBinding = FileListFragmentBottomBarIncludeBinding.bind(bindingRoot)
                val speedDialBinding = FileListFragmentSpeedDialIncludeBinding.bind(bindingRoot)
                return Binding(
                    bindingRoot, includeBinding.drawerLayout, includeBinding.persistentDrawerLayout,
                    includeBinding.persistentBarLayout, appBarBinding.appBarLayout,
                    appBarBinding.toolbar, appBarBinding.overlayToolbar,
                    appBarBinding.breadcrumbLayout, contentBinding.contentLayout,
                    contentBinding.progress, contentBinding.errorText, contentBinding.emptyView,
                    contentBinding.swipeRefreshLayout, contentBinding.recyclerView,
                    bottomBarBinding.bottomBarLayout, bottomBarBinding.bottomToolbar,
                    bottomBarBinding.bottomCreateFileNameEdit,
                    bottomBarBinding.extractDestinationEdit, speedDialBinding.speedDialView
                )
            }
        }
    }

    private class MenuBinding private constructor(
        val menu: Menu,
        val searchItem: MenuItem,
        val viewSortItem: MenuItem,
        val viewListItem: MenuItem,
        val viewGridItem: MenuItem,
        val sortByNameItem: MenuItem,
        val sortByTypeItem: MenuItem,
        val sortBySizeItem: MenuItem,
        val sortByLastModifiedItem: MenuItem,
        val sortOrderAscendingItem: MenuItem,
        val sortDirectoriesFirstItem: MenuItem,
        val viewSortPathSpecificItem: MenuItem,
        val selectAllItem: MenuItem,
        val showHiddenFilesItem: MenuItem
    ) {
        companion object {
            fun inflate(menu: Menu, inflater: MenuInflater): MenuBinding {
                inflater.inflate(R.menu.file_list, menu)
                return MenuBinding(
                    menu, menu.findItem(R.id.action_search), menu.findItem(R.id.action_view_sort),
                    menu.findItem(R.id.action_view_list), menu.findItem(R.id.action_view_grid),
                    menu.findItem(R.id.action_sort_by_name),
                    menu.findItem(R.id.action_sort_by_type),
                    menu.findItem(R.id.action_sort_by_size),
                    menu.findItem(R.id.action_sort_by_last_modified),
                    menu.findItem(R.id.action_sort_order_ascending),
                    menu.findItem(R.id.action_sort_directories_first),
                    menu.findItem(R.id.action_view_sort_path_specific),
                    menu.findItem(R.id.action_select_all),
                    menu.findItem(R.id.action_show_hidden_files)
                )
            }
        }
    }
}








/**
 * Shared current directories of the two panes in two-pane browsing, so cross-pane
 * copy/cut can target the other pane's location.
 */
object TwoPaneState {
    @Volatile
    var primaryPanePath: java8.nio.file.Path? = null

    @Volatile
    var secondaryPanePath: java8.nio.file.Path? = null

    /**
     * The primary (left) pane's share of the available width, draggable via the divider
     * like a classic dual-pane file manager. In-memory only: it survives Activity
     * recreation but resets to an even split on process death.
     */
    @Volatile
    var paneWidthRatio: Float = 0.5f

    const val PANE_WIDTH_MIN_RATIO = 0.25f
    const val PANE_WIDTH_MAX_RATIO = 0.75f

    private val _activePaneSecondary = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The pane the user last touched; the back key navigates it. */
    val activePaneSecondary: Boolean
        get() = _activePaneSecondary.get()

    /** Called whenever the active pane changes, so the Activity can restyle the panes. */
    @Volatile
    var activePaneSecondaryListener: (() -> Unit)? = null

    fun setActivePaneSecondary(secondary: Boolean) {
        if (_activePaneSecondary.getAndSet(secondary) != secondary) {
            activePaneSecondaryListener?.invoke()
        }
    }
}
