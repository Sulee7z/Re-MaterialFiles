/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.commit
import androidx.lifecycle.LifecycleOwner
import java8.nio.file.Path
import me.zhanghai.android.files.R
import me.zhanghai.android.files.app.AppActivity
import me.zhanghai.android.files.compat.themeResIdCompat
import me.zhanghai.android.files.file.MimeType
import me.zhanghai.android.files.navigation.NavigationFragment
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.OverlayToolbarActionMode
import me.zhanghai.android.files.ui.ToolbarActionMode
import me.zhanghai.android.files.provider.archive.archiveFile
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat

class FileListActivity : AppActivity() {
    private lateinit var fragment: FileListFragment
    private lateinit var navigationFragment: NavigationFragment

    /** The single shared multi-select action bar rendered over the shared top bar. */
    private lateinit var sharedOverlayActionMode: OverlayToolbarActionMode

    /** The shared breadcrumb bar (MT Manager style): fixed under the top bar, it always
     *  shows the path of the active pane and follows the active-pane switches. */
    private lateinit var sharedBreadcrumbLayout: BreadcrumbLayout

    /** The two-pane paste bar (bottom): its paste action targets the active pane. */
    private lateinit var twoPaneBottomToolbar: androidx.appcompat.widget.Toolbar

    private lateinit var twoPaneBottomActionMode: ToolbarActionMode

    private var actionBarSizePx: Int = 0

    // Two-pane back-chain callbacks: they disable themselves while falling through (e.g.
    // back at a pane's root), and must be re-enabled or every later back would exit.
    private lateinit var twoPaneDrawerCloseCallback: androidx.activity.OnBackPressedCallback
    private lateinit var twoPanePaneBackCallback: androidx.activity.OnBackPressedCallback

    /** The two-pane value this Activity was created with; used to rebuild only on change.
     *  Computed in onCreate() because it reads intent, which is only set after attach(). */
    private var twoPaneAtCreation: Boolean = false

    /** True while the FAB speed-dial menu is open; touches then must not flip the active pane. */
    private var fabIsOpen: Boolean = false

    /** The effective two-pane mode; false for picker intents even when the setting is on. */
    val isTwoPaneMode: Boolean
        get() = twoPaneAtCreation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Two-pane browsing must not apply to picker intents (open file/directory/create):
        // in two-pane mode the per-pane toolbar carrying the picker confirm action is hidden,
        // so the picker would show a plain list without any way to confirm the selection.
        twoPaneAtCreation = Settings.FILE_LIST_TWO_PANE.valueCompat && !isPickMode(intent)

        // Restore the active-pane state across recreation/process death (the previous
        // process-global object lost it; it is now per-Activity-instance state).
        if (twoPaneAtCreation) {
            TwoPaneState.setActivePaneSecondary(
                savedInstanceState?.getBoolean(STATE_ACTIVE_PANE_SECONDARY)
                    ?: TwoPaneState.activePaneSecondary
            )
        }

        if (twoPaneAtCreation) {
            // Two-pane browsing uses the Material Design 2 theme for a denser layout
            // (Material 3 has larger paddings/heights that waste the narrow panes).
            // CustomThemeHelper already applied the theme in super.onCreate(); re-apply
            // the MD2 variant (keeping any custom theme color/Black suffix) before
            // setContentView. baseThemeName looks like
            // "me.zhanghai.android.files:style/Theme.MaterialFiles.Material3[.Black]";
            // strip the ".Material3" segment to get the MD2 theme.
            val baseThemeName = resources.getResourceName(themeResIdCompat)
            val md2ThemeName = baseThemeName.replace(".Material3", "")
            val md2ThemeRes = resources.getIdentifier(md2ThemeName, null, null)
            if (md2ThemeRes != 0) {
                setTheme(md2ThemeRes)
            }
        }

        // Calls ensureSubDecor().
        findViewById<View>(android.R.id.content)
        if (twoPaneAtCreation) {
            showTwoPane(intent, savedInstanceState)
        } else if (savedInstanceState == null) {
            fragment = FileListFragment().putArgs(FileListFragment.Args(intent))
            supportFragmentManager.commit { add(android.R.id.content, fragment) }
        } else {
            fragment = supportFragmentManager.findFragmentById(android.R.id.content)
                as FileListFragment
        }
    }

    private fun showTwoPane(intent: Intent, savedInstanceState: Bundle?) {
        if (twoPaneAtCreation) {
            // MT Manager style two-pane browsing: two independent file lists side by side.
            // Copy/cut on one pane and paste on the other to move files across panes (the
            // paste state is shared). The right pane starts at the default directory.
            // The layout uses fixed container ids so FragmentManager can restore the two
            // fragments across Activity recreation.
            setContentView(R.layout.file_list_activity_two_pane)
            // The window draws a transparent status bar (per the theme), so the shared
            // top bar must extend its background under the status bar and pad its content
            // by the status-bar height — the same behavior the single-pane AppBarLayout
            // gets from its fitsSystemWindows chain. The two-pane content sits inside a
            // FrameLayout without fitsSystemWindows, and DrawerLayout only forwards
            // window insets to children that opt in via fitsSystemWindows — so the
            // insets never reach the top bar through the normal dispatch chain. Listen
            // on the activity content view instead, which always receives the insets,
            // and grow the top bar by the status-bar height so its background extends
            // under the status bar while its content keeps the full action-bar size.
            val topBarFrame = findViewById<View>(R.id.sharedTopBarFrame)
            actionBarSizePx = TypedValue().let { typedValue ->
                if (theme.resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
                    resources.getDimensionPixelSize(typedValue.resourceId)
                } else {
                    topBarFrame.layoutParams.height
                }
            }
            ViewCompat.setOnApplyWindowInsetsListener(
                findViewById<View>(android.R.id.content)
            ) { _, insets ->
                val top = insets.systemWindowInsetTop
                if (topBarFrame.paddingTop != top) {
                    topBarFrame.updatePaddingRelative(top = top)
                    topBarFrame.layoutParams.height = actionBarSizePx + top
                }
                insets
            }
            // The two-pane paste bar (bottom): its paste action targets the active pane.
            twoPaneBottomToolbar = findViewById(R.id.bottomToolbar)
            twoPaneBottomActionMode = TwoPaneBottomToolbarActionMode(
                twoPaneBottomToolbar, twoPaneBottomToolbar
            )
            // The shared top bar spans both panes and hosts the activity action bar
            // (search/sort/three dots); the per-pane toolbars stay hidden. The multi-select
            // action bar renders in the SAME top bar layer (sharedOverlayToolbar), so
            // selecting files in either pane turns the top bar into the action bar.
            setSupportActionBar(findViewById(R.id.sharedToolbar))
            sharedOverlayActionMode = OverlayToolbarActionMode(
                findViewById(R.id.sharedOverlayToolbar)
            )
            onBackPressedDispatcher.addCallback(
                this, sharedOverlayActionMode.onBackPressedCallback
            )
            // The shared breadcrumb bar always reflects the active pane: taps are routed
            // to the active pane's FileListFragment, and its path is shown here.
            sharedBreadcrumbLayout = findViewById(R.id.sharedBreadcrumbLayout)
            sharedBreadcrumbLayout.setListener(object : BreadcrumbLayout.Listener {
                override fun navigateTo(path: Path) {
                    activeFileListFragmentOrNull()?.navigateTo(path)
                }
                override fun copyPath(path: Path) {
                    activeFileListFragmentOrNull()?.copyPath(path)
                }
                override fun movePathsTo(paths: List<Path>, directory: Path) {
                    // Archive sources are read-only: dragging OUT of an archive is
                    // always a copy (a move could never delete the sources).
                    if (paths.first().isArchivePath) {
                        me.zhanghai.android.files.filejob.FileJobService.copy(
                            paths, directory, applicationContext
                        )
                    } else {
                        me.zhanghai.android.files.filejob.FileJobService.move(
                            paths, directory, applicationContext
                        )
                    }
                }
                override fun openInNewTask(path: Path) {
                    activeFileListFragmentOrNull()?.openInNewTask(path)
                }
                override fun navigateToPath() {
                    activeFileListFragmentOrNull()?.navigateToPath()
                }
            })
            // The FAB is fixed at the bottom-right; its create actions run in the ACTIVE
            // pane (the pane the user last touched), like Amaze's FAB acting on
            // getCurrentMainFragment() / Ghost Commander's current panel. A "paste here"
            // action is always present (disabled until clipboard content exists), MT
            // Manager style.
            // NOTE: activeFileListFragment() must NOT be called here (during onCreate):
            // the pane fragments are added asynchronously below, so no two-pane fragment
            // exists yet — that would throw IllegalStateException and crash startup.
            findViewById<com.leinardi.android.speeddial.SpeedDialView>(R.id.floatingActionButton).apply {
                inflate(R.menu.file_list_speed_dial)
                // While the FAB menu is open, touches must NOT flip the active pane (the
                // menu items sit over the panes and are UI, not "this pane is active").
                setOnChangeListener(object : com.leinardi.android.speeddial.SpeedDialView.OnChangeListener {
                    override fun onMainActionSelected(): Boolean {
                        fabIsOpen = true
                        return false
                    }

                    override fun onToggleChanged(isOpen: Boolean) {
                        fabIsOpen = isOpen
                    }
                })
                setOnActionSelectedListener {
                    fabIsOpen = false
                    val target = activeFileListFragment()
                    when (it.id) {
                        R.id.action_create_file -> target.showCreateFileDialog()
                        R.id.action_create_directory -> target.showCreateDirectoryDialog()
                        R.id.action_paste -> target.pasteFilesToCurrentPane()
                    }
                    close()
                    true
                }
                // "Paste to this pane": present but enabled only when the active pane has
                // clipboard content (checked lazily at click time, so no fragment lookup
                // happens during onCreate).
                addActionItem(
                    com.leinardi.android.speeddial.SpeedDialActionItem.Builder(
                        R.id.action_paste, R.drawable.paste_icon_control_normal_24dp
                    )
                        .setLabel(getString(R.string.paste))
                        .create()
                )
                // Show/hide the add button per the setting.
                Settings.SHOW_ADD_BUTTON.observe(this@FileListActivity) { isVisible = it }
            }
            // The navigation drawer is hosted by this Activity's full-screen DrawerLayout
            // (like single-pane mode), so it draws over the divider and the right pane.
            // The drawer panel is 65% of the screen width; its background covers the rest.
            val drawerPanelWidth = (resources.displayMetrics.widthPixels * 0.65f).toInt()
            findViewById<View>(R.id.activityNavigationFragment).layoutParams.width =
                drawerPanelWidth
            // Lock the drawer's edge-swipe gesture in two-pane mode: the left edge opens
            // the LEFT pane's own back swipe instead (the drawer still opens via the
            // hamburger button).
            findViewById<DrawerLayout>(R.id.activityDrawerLayout).setDrawerLockMode(
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED, GravityCompat.START
            )
            // Back key closes the navigation drawer first.
            twoPaneDrawerCloseCallback =
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val drawerLayout =
                            findViewById<DrawerLayout>(R.id.activityDrawerLayout)
                        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            drawerLayout.closeDrawer(GravityCompat.START)
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            onBackPressedDispatcher.addCallback(this, twoPaneDrawerCloseCallback)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            supportActionBar?.setHomeAsUpIndicator(R.drawable.menu_icon_control_normal_24dp)
            supportActionBar?.setDisplayShowTitleEnabled(false)
            // Fixed widths instead of layout weights: DrawerLayout (the FileListFragment
            // root) crashes when measured with AT_MOST by a weighted LinearLayout.
            // Wide screens (landscape or >= 600dp) show both panes; narrow screens show
            // only the active pane (Ghost Commander style) 鈥?widths are updated in
            // updateResponsivePanes(). setupResponsivePanes() is called AFTER the panes
            // are added below, because updateResponsivePanes() reads the pane fragments.
            // ZenFile-style active-pane tracking: the ACTIVE pane is determined by the list
            // (RecyclerView) the user last touched inside each pane, NOT by the pane
            // container 鈥?otherwise tapping the FAB (which lives inside the right pane)
            // would wrongly mark the right pane as active and break "create in active pane".
            // The RecyclerView listeners are set up in FileListFragment; the back key and
            // the FAB both read TwoPaneState.activePaneSecondary.
            twoPanePaneBackCallback =
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        val activeFragment = if (TwoPaneState.activePaneSecondary) {
                            supportFragmentManager
                                .findFragmentById(R.id.rightPaneContent) as? FileListFragment
                        } else {
                            supportFragmentManager
                                .findFragmentById(R.id.leftPaneContent) as? FileListFragment
                        }
                        if (activeFragment == null || !activeFragment.performBack()) {
                            // The active pane is at its root: exit (standard back
                            // behavior). Silently navigating the OTHER pane from the back
                            // key would destroy its navigation state behind the user's
                            // back.
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            onBackPressedDispatcher.addCallback(this, twoPanePaneBackCallback)
            if (savedInstanceState == null) {
                val leftFragment = FileListFragment().putArgs(FileListFragment.Args(intent))
                supportFragmentManager.commit { add(R.id.leftPaneContent, leftFragment) }
                val rightIntent = FileListActivity.createViewIntent(
                    Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
                )
                val rightFragment = FileListFragment()
                    .putArgs(FileListFragment.Args(rightIntent, secondaryPane = true))
                supportFragmentManager.commit { add(R.id.rightPaneContent, rightFragment) }
            }
            // The pane fragments are (or will be) available now; apply the responsive
            // layout and wire the switch bar / highlight.
            setupResponsivePanes()
            // Create the drawer AFTER the panes so its listener can always find a pane
            // fragment (NavigationFragment.onActivityCreated immediately observes the path).
            if (savedInstanceState == null) {
                navigationFragment = NavigationFragment()
                supportFragmentManager.commit {
                    add(R.id.activityNavigationFragment, navigationFragment)
                }
            } else {
                navigationFragment =
                    supportFragmentManager.findFragmentById(R.id.activityNavigationFragment)
                        as NavigationFragment
            }
            navigationFragment.listener = object : NavigationFragment.Listener {
                override val currentPath: Path
                    get() = activeFileListFragmentOrNull()?.currentPath
                        ?: Settings.FILE_LIST_DEFAULT_DIRECTORY.valueCompat
                override fun navigateTo(path: Path) {
                    closeNavigationDrawer()
                    activeFileListFragmentOrNull()?.navigateToRoot(path)
                }
                override fun navigateToRoot(path: Path) {
                    closeNavigationDrawer()
                    activeFileListFragmentOrNull()?.navigateToRoot(path)
                }
                override fun navigateToDefaultRoot() {
                    closeNavigationDrawer()
                    activeFileListFragmentOrNull()?.navigateToDefaultRoot()
                }
                override fun observeCurrentPath(
                    owner: LifecycleOwner, observer: (Path) -> Unit
                ) {
                    activeFileListFragmentOrNull()?.viewModel
                        ?.currentPathLiveData?.observe(owner, observer)
                }
                override fun closeNavigationDrawer() {
                    this@FileListActivity.closeNavigationDrawer()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Keep the active-pane state per Activity instance (two-pane mode only).
        if (twoPaneAtCreation) {
            outState.putBoolean(
                STATE_ACTIVE_PANE_SECONDARY, TwoPaneState.activePaneSecondary
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-arm the two-pane back chain: its callbacks disable themselves while falling
        // through (back at a pane's root), and without this a home -> return round trip
        // would leave every later back gesture exiting to the desktop.
        if (twoPaneAtCreation && ::twoPanePaneBackCallback.isInitialized) {
            twoPanePaneBackCallback.isEnabled = true
            twoPaneDrawerCloseCallback.isEnabled = true
        }
        // Apply two-pane setting changes when returning from the settings screen: the
        // Activity is stopped while the setting screen is on top. Recreate() would restore
        // the old fragment hierarchy into containers that no longer match the new pane
        // layout (crash), so finish and start a fresh instance instead. Picker activities
        // always run single-pane regardless of the setting, so they must NOT be restarted
        // here (that would close the picker and relaunch a plain file list).
        if (!isPickMode(intent) && Settings.FILE_LIST_TWO_PANE.valueCompat != twoPaneAtCreation) {
            // Restart with a copy of the ORIGINAL intent, so the action/path the activity
            // was launched with (e.g. a VIEW intent for a specific directory) survives the
            // single-pane <-> two-pane switch instead of resetting to the default.
            finish()
            startActivity(Intent(intent))
        }
    }

    /**
     * Active-pane tracking, centralized here (MT Manager style): every touch in two-pane
     * mode marks the pane under the finger as active. We use the touch X coordinate
     * against the divider, instead of view-level listeners — per-view listeners miss
     * touches consumed by item children (clicks/ripples swallow ACTION_DOWN before the
     * parent's onTouchListener sees it), while a coordinate check can never miss: items,
     * breadcrumbs, empty states, toolbars and scrollbars all count as "operating this
     * pane". This is the single source of truth for the FAB, the back key, the shared
     * top-bar menu routing (search/sort/three dots) and cross-pane actions.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (twoPaneAtCreation && !fabIsOpen && ev.actionMasked == MotionEvent.ACTION_DOWN) {
            // Both panes are always shown side by side (portrait included, MT Manager
            // style), so the touch X coordinate decides the pane. findViewById may be null
            // early in the lifecycle (before setContentView completes), so guard.
            // The following touches must NOT flip the active pane (they are global UI, not
            // "this pane is active"): the FAB itself, and the shared top bar (search/sort/
            // three dots) which spans both panes.
            val divider = findViewById<View>(R.id.divider)
            if (divider != null && divider.isVisible) {
                val fab = findViewById<com.leinardi.android.speeddial.SpeedDialView>(
                    R.id.floatingActionButton
                )
                val fabBounds = fab?.let {
                    if (it.isShown) {
                        IntArray(2).also { loc -> it.getLocationOnScreen(loc) }
                    } else {
                        null
                    }
                }
                val fabTouched = fabBounds != null &&
                    ev.rawX >= fabBounds[0] && ev.rawX <= fabBounds[0] + fab!!.width &&
                    ev.rawY >= fabBounds[1] && ev.rawY <= fabBounds[1] + fab.height
                val topBar = findViewById<View>(R.id.sharedToolbar)
                val topBarTouched = topBar != null && topBar.isShown &&
                    ev.rawY >= topBar.top && ev.rawY <= topBar.bottom
                // The shared breadcrumb bar is global UI too (it reflects the active pane,
                // it does not switch it), so touches on it must not flip the active pane.
                val breadcrumb = findViewById<View>(R.id.sharedBreadcrumbLayout)
                val breadcrumbTouched = breadcrumb != null && breadcrumb.isShown &&
                    ev.rawY >= breadcrumb.top && ev.rawY <= breadcrumb.bottom
                // Navigation drawer touches must not flip the active pane either: the drawer
                // sits over the left pane, so tapping a storage/bookmark in it would flip the
                // active pane to the left and drawer navigations would always land there
                // instead of following the pane that was active before it opened.
                val drawerLayout = findViewById<androidx.drawerlayout.widget.DrawerLayout>(
                    R.id.activityDrawerLayout
                )
                val navigationView = findViewById<View>(R.id.activityNavigationFragment)
                val drawerTouched = drawerLayout != null && navigationView != null &&
                    drawerLayout.isDrawerOpen(navigationView)
                // The divider is the pane-resize drag handle: grabbing it must not flip
                // the active pane either (a resize is not "operating" either pane).
                val dividerTouched = ev.rawX >= divider.x &&
                    ev.rawX <= divider.x + divider.width
                // Touches in the system back-gesture zones (screen edges) must not flip
                // the active pane or evaluate the FAB: a horizontal swipe there is the
                // system back gesture and has to pass through untouched.
                val gestureZonePx = SYSTEM_GESTURE_ZONE_DP * resources.displayMetrics.density
                val inSystemGestureZone = ev.rawX <= gestureZonePx ||
                    ev.rawX >= resources.displayMetrics.widthPixels - gestureZonePx
                if (!inSystemGestureZone && !fabTouched && !topBarTouched &&
                    !breadcrumbTouched && !drawerTouched && !dividerTouched
                ) {
                    TwoPaneState.setActivePaneSecondary(ev.rawX > divider.x)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }


    /**
     * MT Manager style: the two panes are ALWAYS shown side by side, each exactly half
     * the screen, in both portrait and landscape. The active pane is marked by a
     * highlight border on the pane container plus the dimmed inactive pane (the per-pane
     * path display is the fragment's own breadcrumb bar, unified with the theme). Touches
     * switch the active pane via dispatchTouchEvent().
     */
    private fun setupResponsivePanes() {
        // Listen for active-pane changes to keep the highlight in sync.
        TwoPaneState.activePaneSecondaryListener = {
            updateResponsivePanes()
            // Refresh the shared top-bar menu so its check states (sort, view type,
            // show-hidden) follow the newly active pane. onCreateOptionsMenu re-expands
            // the search view with the active pane's query, so an open search survives.
            invalidateOptionsMenu()
            // The paste bar (and its extraction destination) follows the active pane.
            updateTwoPaneBottomToolbar(refreshDestination = true)
        }
        setupDividerDrag()
        setupTwoPaneDragController()
        updateResponsivePanes()
    }

    /**
     * Draggable divider: dragging it resizes the two panes live (the ratio is kept in
     * [TwoPaneState.paneWidthRatio], so it survives pane switches and Activity
     * recreation). The divider view is 10dp wide, which is the drag touch target. While
     * the drag is in progress the center line grows and takes the accent color, so the
     * user can see the handle is grabbed.
     */
    private fun setupDividerDrag() {
        val divider = findViewById<View>(R.id.divider)
        divider.isClickable = true
        divider.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.setBackgroundResource(R.drawable.two_pane_divider_active)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val ratio = (event.rawX / resources.displayMetrics.widthPixels)
                        .coerceIn(
                            TwoPaneState.PANE_WIDTH_MIN_RATIO,
                            TwoPaneState.PANE_WIDTH_MAX_RATIO
                        )
                    if (kotlin.math.abs(ratio - TwoPaneState.paneWidthRatio) >= 0.001f) {
                        TwoPaneState.paneWidthRatio = ratio
                        updateResponsivePanes()
                    }
                    true
                }
                else -> {
                    // ACTION_UP / ACTION_CANCEL: the drag ended, restore the idle line.
                    view.setBackgroundResource(R.drawable.two_pane_divider)
                    false
                }
            }
        }
    }

    /** Applies the active-pane state to the pane containers (always side by side). */
    private fun updateResponsivePanes() {
        if (!twoPaneAtCreation) {
            return
        }
        val leftPane = findViewById<View>(R.id.leftPane)
        val rightPane = findViewById<View>(R.id.rightPane)
        val divider = findViewById<View>(R.id.divider)
        val screenWidth = resources.displayMetrics.widthPixels
        // Both panes side by side; the divider is draggable, so the split follows the
        // user's ratio. Reassigning layoutParams (instead of mutating the field) triggers
        // requestLayout, which the live divider drag relies on.
        val dividerWidth = maxOf(divider.layoutParams.width, 1)
        val paneWidth = ((screenWidth - dividerWidth) * TwoPaneState.paneWidthRatio)
            .toInt()
            .coerceIn(1, screenWidth - dividerWidth - 1)
        leftPane.layoutParams = leftPane.layoutParams.apply { width = paneWidth }
        rightPane.layoutParams = rightPane.layoutParams.apply {
            width = screenWidth - paneWidth - dividerWidth
        }
        leftPane.isVisible = true
        rightPane.isVisible = true
        divider.isVisible = true
        // Active-pane marker, MT Manager style: the ACTIVE pane casts a soft shadow
        // gradient onto the INACTIVE pane's divider-facing edge, fading out horizontally
        // toward the pane's outer edge (no blue border / no box elevation).
        val leftActive = !TwoPaneState.activePaneSecondary
        val leftShadow = findViewById<View>(R.id.leftPaneShadow)
        val rightShadow = findViewById<View>(R.id.rightPaneShadow)
        // Shadow falls on the inactive pane's divider side: when the right pane is
        // active it shadows the left pane's right edge, and vice versa.
        leftShadow.isVisible = !leftActive
        rightShadow.isVisible = leftActive
        // Dim the inactive pane's content.
        val leftContent = findViewById<View>(R.id.leftPaneContent)
        val rightContent = findViewById<View>(R.id.rightPaneContent)
        leftContent.alpha = if (TwoPaneState.activePaneSecondary) 0.6f else 1f
        rightContent.alpha = if (TwoPaneState.activePaneSecondary) 1f else 0.6f
        // The shared breadcrumb bar follows the active pane.
        refreshSharedBreadcrumb()
    }

    /**
     * Refreshes the shared breadcrumb bar to show the active pane's current path.
     * Called when the active pane switches and when a pane's path changes.
     */
    fun refreshSharedBreadcrumb() {
        if (!twoPaneAtCreation || !::sharedBreadcrumbLayout.isInitialized) {
            return
        }
        val activeFragment = activeFileListFragmentOrNull() ?: return
        val data = activeFragment.viewModel.breadcrumbLiveData.valueCompat ?: return
        sharedBreadcrumbLayout.setData(data)
    }

    /**
     * Updates the two-pane paste bar from the shared clipboard state. Called by both
     * pane fragments (the clipboard is shared) and on active-pane switches, because the
     * paste action targets the ACTIVE pane's current directory. With
     * [refreshDestination], the editable extraction destination follows the newly
     * active pane (like the FAB).
     */
    fun updateTwoPaneBottomToolbar(refreshDestination: Boolean = false) {
        if (!twoPaneAtCreation || !::twoPaneBottomActionMode.isInitialized) {
            return
        }
        val fragment = activeFileListFragmentOrNull() ?: return
        val pasteState = fragment.viewModel.pasteState
        val files = pasteState.files
        if (files.isEmpty()) {
            if (twoPaneBottomActionMode.isActive) {
                twoPaneBottomActionMode.finish()
            }
            applyTwoPaneBottomBarLayout(visible = false)
            return
        }
        val areAllFilesArchivePaths = files.all { it.path.isArchivePath }
        twoPaneBottomActionMode.title = getString(
            if (pasteState.copy) {
                if (areAllFilesArchivePaths) {
                    R.string.file_list_paste_extract_title_format
                } else {
                    R.string.file_list_paste_copy_title_format
                }
            } else {
                R.string.file_list_paste_move_title_format
            },
            files.size
        )
        val extractDestinationEdit =
            twoPaneBottomToolbar.findViewById<android.widget.EditText>(
                R.id.extractDestinationEdit
            )
        // Extract mode (clipboard holds archive roots): show the editable extraction
        // destination, prefilled with the active pane's current directory.
        extractDestinationEdit.isVisible = areAllFilesArchivePaths
        twoPaneBottomActionMode.setMenuResource(R.menu.file_list_paste)
        val isCurrentPathReadOnly = fragment.viewModel.currentPath.fileSystem.isReadOnly
        twoPaneBottomActionMode.menu.findItem(R.id.action_paste)
            .setTitle(
                if (areAllFilesArchivePaths) {
                    R.string.file_list_paste_action_extract_here
                } else {
                    R.string.paste
                }
            )
            .isEnabled = !isCurrentPathReadOnly
        if (!twoPaneBottomActionMode.isActive) {
            if (areAllFilesArchivePaths) {
                // Archive sources: default to the archive file's own directory (the
                // archive-internal path string must never leak into the destination).
                val firstSource = files.first().path
                val defaultDirectory = if (firstSource.isArchivePath) {
                    firstSource.archiveFile.parent?.toString()
                } else {
                    fragment.viewModel.currentPath.toString()
                }
                extractDestinationEdit.setText(
                    defaultDirectory ?: fragment.viewModel.currentPath.toString()
                )
            }
            twoPaneBottomActionMode.start(object : ToolbarActionMode.Callback {
                override fun onToolbarNavigationIconClicked(
                    toolbarActionMode: ToolbarActionMode
                ) {
                    toolbarActionMode.finish()
                }

                override fun onToolbarActionModeMenuItemClicked(
                    toolbarActionMode: ToolbarActionMode,
                    item: android.view.MenuItem
                ): Boolean {
                    if (item.itemId == R.id.action_paste) {
                        // Resolve the ACTIVE pane FRESH at click time: the callback
                        // object outlives active-pane switches, and a captured fragment
                        // would paste into a stale pane's directory. A bare name
                        // resolves against the extraction base directory (the archive
                        // file's own directory when the sources are archive entries;
                        // the process CWD is "/", which would hit the read-only root).
                        val activeFragment = activeFileListFragmentOrNull()
                            ?: return false
                        val firstSource = activeFragment.viewModel.pasteState.files
                            .first().path
                        val baseDirectory = if (firstSource.isArchivePath) {
                            firstSource.archiveFile.parent
                                ?: activeFragment.viewModel.currentPath
                        } else {
                            activeFragment.viewModel.currentPath
                        }
                        val text = extractDestinationEdit.text.toString().trim()
                        val targetDirectory = when {
                            text.isEmpty() -> baseDirectory
                            text.startsWith("/") -> java8.nio.file.Paths.get(text)
                            else -> baseDirectory.resolve(text)
                        }
                        activeFragment.pasteFilesToCurrentPane(targetDirectory)
                        return true
                    }
                    return false
                }

                override fun onToolbarActionModeFinished(
                    toolbarActionMode: ToolbarActionMode
                ) {
                    // Mirrors the single-pane paste bar: finishing clears the clipboard.
                    activeFileListFragmentOrNull()?.viewModel?.clearPasteState()
                    applyTwoPaneBottomBarLayout(visible = false)
                }
            })
        } else if (refreshDestination && areAllFilesArchivePaths &&
            extractDestinationEdit.isVisible
        ) {
            // The active pane switched while the bar is open: the extraction destination
            // follows the newly active pane (like the FAB).
            extractDestinationEdit.setText(fragment.viewModel.currentPath.toString())
        }
        applyTwoPaneBottomBarLayout(visible = true)
    }

    /**
     * While the paste bar is visible the pane row gives up its height (so list content
     * is not covered) and the FAB sits above the bar via its bottom margin (layout-based
     * so it cannot conflict with the SpeedDial library's own translations). The offset is
     * taken from the bar's OWN resolved layout height (same source as its rendering), so
     * the two can never drift apart.
     */
    private fun applyTwoPaneBottomBarLayout(visible: Boolean) {
        val barHeight = if (visible) twoPaneBottomToolbar.layoutParams.height else 0
        findViewById<com.leinardi.android.speeddial.SpeedDialView>(
            R.id.floatingActionButton
        ).updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
            bottomMargin = barHeight
        }
        findViewById<View>(R.id.paneRow).updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
            bottomMargin = barHeight
        }
    }

    /**
     * Per-view drop targets for the two-pane cross-pane drag-and-drop. Each drop target
     * has its own OnDragListener with clear boundaries:
     * - Pane containers: move/copy to that pane's current directory.
     * - Breadcrumb segments: move to the segment's directory.
     * - Trash bar: delete the dragged files.
     */
    private fun setupTwoPaneDragController() {
        installPaneDropTarget(findViewById(R.id.leftPaneContent), isSecondaryPane = false)
        installPaneDropTarget(findViewById(R.id.rightPaneContent), isSecondaryPane = true)
        installBreadcrumbDropTarget()
        installTrashDropTarget()
    }

    /** Drop target for the two-pane cross-pane drag-and-drop: dropping files over the
     *  other pane's container moves them into that pane's current directory. Same-pane
     *  drops are rejected (the drag simply cancels). The pane is outlined with an accent
     *  stroke while a foreign drag hovers over it.
     */
    private fun installPaneDropTarget(paneContent: View, isSecondaryPane: Boolean) {
        paneContent.setOnDragListener { view, event ->
            val payload = event.localState as? CrossPaneDragPayload
            fun setHighlight(highlighted: Boolean) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    (view as? ViewGroup)?.foreground =
                        if (highlighted) {
                            getDrawable(R.drawable.two_pane_drop_target)
                        } else {
                            null
                        }
                }
            }
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> {
                    // A cross-pane drag started: reveal the delete trash target.
                    setDragDeleteTargetVisible(true)
                    payload != null && payload.sourceIsSecondaryPane != isSecondaryPane
                }

                android.view.DragEvent.ACTION_DRAG_ENTERED,
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    setHighlight(true)
                    true
                }

                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    setHighlight(false)
                    true
                }

                android.view.DragEvent.ACTION_DROP -> {
                    setHighlight(false)
                    val targetFragment = findFileListFragment(isSecondaryPane)
                    if (payload == null || targetFragment == null ||
                        payload.sourceIsSecondaryPane == isSecondaryPane
                    ) {
                        return@setOnDragListener false
                    }
                    // A pane browsing INSIDE an opened archive is read-only: redirect
                    // the move next to the archive file instead.
                    val currentDirectory = targetFragment.viewModel.currentPath
                    val targetDirectory = if (currentDirectory.isArchivePath) {
                        currentDirectory.archiveFile.parent ?: currentDirectory
                    } else {
                        currentDirectory
                    }
                    // Archive sources are read-only: dragging OUT of an archive can
                    // never delete the sources, so it is always a copy.
                    if (payload.paths.first().isArchivePath) {
                        me.zhanghai.android.files.filejob.FileJobService.copy(
                            payload.paths, targetDirectory, applicationContext
                        )
                    } else {
                        me.zhanghai.android.files.filejob.FileJobService.move(
                            payload.paths, targetDirectory, applicationContext
                        )
                    }
                    findFileListFragment(payload.sourceIsSecondaryPane)
                        ?.viewModel?.clearSelectedFiles()
                    true
                }

                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    setHighlight(false)
                    setDragDeleteTargetVisible(false)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * Per-view drop targets for the two-pane cross-pane drag-and-drop. Each drop target
     * has its own OnDragListener with clear boundaries:
     * - Pane containers: move/copy to that pane's current directory.
     * - Breadcrumb segments: move to the segment's directory.
     * - Trash bar: delete the dragged files.
     */

    /** Drop target for breadcrumb segments: dropping files on a segment moves them to
     *  the segment's directory. */
    private fun installBreadcrumbDropTarget() {
        sharedBreadcrumbLayout.setDropTargetListener { paths, directory ->
            // Archive sources are read-only: dragging OUT of an archive is always a
            // copy (a move could never delete the sources).
            if (paths.first().isArchivePath) {
                me.zhanghai.android.files.filejob.FileJobService.copy(
                    paths, directory, applicationContext
                )
            } else {
                me.zhanghai.android.files.filejob.FileJobService.move(
                    paths, directory, applicationContext
                )
            }
        }
    }

    /** Drop target for the trash bar: dropping files on it deletes them. */
    private fun installTrashDropTarget() {
        findViewById<View>(R.id.dragDeleteView).setOnDragListener { view, event ->
            val payload = event.localState as? CrossPaneDragPayload
                ?: return@setOnDragListener false
            fun setHighlight(highlighted: Boolean) {
                view.animate().scaleX(if (highlighted) 1.1f else 1f)
                    .scaleY(if (highlighted) 1.1f else 1f)
                    .setDuration(80)
                    .start()
            }
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> true

                android.view.DragEvent.ACTION_DRAG_ENTERED,
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    setHighlight(true)
                    true
                }

                android.view.DragEvent.ACTION_DRAG_EXITED -> {
                    setHighlight(false)
                    true
                }

                android.view.DragEvent.ACTION_DROP -> {
                    setHighlight(false)
                    view.isVisible = false
                    // Same confirmation flow as normal delete: ask before destroying.
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(this@FileListActivity)
                        .setTitle(getString(R.string.delete))
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            me.zhanghai.android.files.filejob.FileJobService.delete(
                                payload.paths, applicationContext
                            )
                            findFileListFragment(payload.sourceIsSecondaryPane)
                                ?.viewModel?.clearSelectedFiles()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }

                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    setHighlight(false)
                    view.isVisible = false
                    true
                }

                else -> false
            }
        }
    }

    private class TwoPaneBottomToolbarActionMode(
        bar: ViewGroup,
        toolbar: androidx.appcompat.widget.Toolbar
    ) : ToolbarActionMode(bar, toolbar) {
        override fun show(bar: ViewGroup, animate: Boolean) {
            bar.isVisible = true
        }

        override fun hide(bar: ViewGroup, animate: Boolean) {
            bar.isVisible = false
        }
    }

    /**
     * The drag-to-delete trash target at the top: shown while a cross-pane drag is in
     * flight; dropping the dragged files on it deletes them. Highlighted (scaled up)
     * while the drag hovers over it.
     */

    /** Shows or hides the drag-to-delete trash target during a cross-pane drag. */
    fun setDragDeleteTargetVisible(visible: Boolean) {
        findViewById<View>(R.id.dragDeleteView).isVisible = visible
    }
    /**
     * Drop target for the two-pane cross-pane drag-and-drop: dropping files over the
     * other pane's container moves them into that pane's current directory. Same-pane
     * drops are rejected (the drag simply cancels). The pane is outlined with an accent
     * stroke while a foreign drag hovers over it.
     */
    /**
     * Called by a pane's FileListFragment when its breadcrumb data changes, so the
     * shared bar updates when the ACTIVE pane navigates. Also marks the (new) active
     * pane if this fragment is the active one.
     */
    fun onPaneBreadcrumbChanged(fragment: FileListFragment, data: BreadcrumbData) {
        if (!twoPaneAtCreation || !::sharedBreadcrumbLayout.isInitialized) {
            return
        }
        // Re-arm the back chain: navigating INTO a directory must make back work for the
        // panes again (the callbacks self-disable when a pane at its root falls through).
        if (::twoPanePaneBackCallback.isInitialized) {
            twoPanePaneBackCallback.isEnabled = true
            twoPaneDrawerCloseCallback.isEnabled = true
        }
        // Only the active pane's path is shown in the shared bar.
        if (fragment === activeFileListFragmentOrNull()) {
            sharedBreadcrumbLayout.setData(data)
        }
    }

    private fun currentFragment(): FileListFragment? =
        if (twoPaneAtCreation) {
            // Keyboard shortcuts act on the pane the user last touched, like every other
            // shared action (FAB, search, back key).
            activeFileListFragmentOrNull()
        } else {
            fragment
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home && twoPaneAtCreation) {
            // The shared top bar's three-line button toggles the Activity-level drawer.
            openNavigationDrawer()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Returns the FileListFragment of the requested pane in two-pane mode. */
    fun findFileListFragment(secondaryPane: Boolean): FileListFragment? =
        supportFragmentManager.findFragmentById(
            if (secondaryPane) R.id.rightPaneContent else R.id.leftPaneContent
        ) as? FileListFragment

    /**
     * The fragment of the pane the user last touched (left when unknown), or null when the
     * pane fragments are not attached yet (during onCreate, before the async fragment
     * commits run). Callers must handle null — NEVER throw here, because this is reached
     * from lifecycle callbacks (NavigationFragment observing, dispatchTouchEvent) that run
     * before the fragments exist.
     */
    private fun activeFileListFragmentOrNull(): FileListFragment? =
        if (twoPaneAtCreation) {
            findFileListFragment(secondaryPane = TwoPaneState.activePaneSecondary)
                ?: findFileListFragment(secondaryPane = false)
                ?: findFileListFragment(secondaryPane = true)
        } else {
            fragment
        }

    /** Non-null variant for user-action call sites (FAB clicks etc.), where fragments exist. */
    private fun activeFileListFragment(): FileListFragment =
        activeFileListFragmentOrNull()
            ?: throw IllegalStateException("No two-pane fragment available")

    fun openNavigationDrawer() {
        val drawerLayout = findViewById<DrawerLayout>(R.id.activityDrawerLayout)
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    fun closeNavigationDrawer() {
        findViewById<DrawerLayout>(R.id.activityDrawerLayout).closeDrawer(GravityCompat.START)
    }

    /** The shared multi-select action bar rendered over the shared top bar (two-pane mode). */
    fun getSharedOverlayActionMode(): OverlayToolbarActionMode = sharedOverlayActionMode

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        val currentFragment = currentFragment()
        if (currentFragment != null && currentFragment.onKeyShortcut(keyCode, event)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    companion object {
        const val STATE_ACTIVE_PANE_SECONDARY = "state_active_pane_secondary"

        /** The system back-gesture zone width at each screen edge (approximate). */
        private const val SYSTEM_GESTURE_ZONE_DP = 24

        /** True for picker intents (open file / directory / create), which must run in
         *  single-pane mode: two-pane mode hides the per-pane toolbar that carries the
         *  picker's confirm (check/save) action, so the picker would be unusable. */
        fun isPickMode(intent: Intent): Boolean = when (intent.action) {
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT,
            Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT_TREE -> true
            else -> false
        }

        fun createViewIntent(path: Path): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_VIEW)
                .apply { extraPath = path }
    }

    class OpenFileContract : ActivityResultContract<List<MimeType>, Path?>() {
        override fun createIntent(context: Context, input: List<MimeType>): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT)
                .setType(MimeType.ANY.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_MIME_TYPES, input.map { it.value }.toTypedArray())

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class CreateFileContract : ActivityResultContract<Triple<MimeType, String?, Path?>, Path?>() {
        override fun createIntent(
            context: Context,
            input: Triple<MimeType, String?, Path?>
        ): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_CREATE_DOCUMENT)
                .setType(input.first.value)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .apply {
                    input.second?.let { putExtra(Intent.EXTRA_TITLE, it) }
                    input.third?.let { extraPath = it }
                }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }

    class OpenDirectoryContract : ActivityResultContract<Path?, Path?>() {
        override fun createIntent(context: Context, input: Path?): Intent =
            FileListActivity::class.createIntent()
                .setAction(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .apply { input?.let { extraPath = it } }

        override fun parseResult(resultCode: Int, intent: Intent?): Path? =
            if (resultCode == RESULT_OK) intent?.extraPath else null
    }
}
