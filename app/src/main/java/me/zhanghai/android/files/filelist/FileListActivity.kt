/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
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
import me.zhanghai.android.files.util.createIntent
import me.zhanghai.android.files.util.extraPath
import me.zhanghai.android.files.util.putArgs
import me.zhanghai.android.files.util.valueCompat

class FileListActivity : AppActivity() {
    private lateinit var fragment: FileListFragment
    private lateinit var navigationFragment: NavigationFragment

    /** The single shared multi-select action bar rendered over the shared top bar. */
    private lateinit var sharedOverlayActionMode: OverlayToolbarActionMode

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
            val actionBarSize = TypedValue().let { typedValue ->
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
                    topBarFrame.layoutParams.height = actionBarSize + top
                }
                insets
            }
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
            // Back key closes the navigation drawer first.
            onBackPressedDispatcher.addCallback(
                this,
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
            )
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
            onBackPressedDispatcher.addCallback(
                this,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        Log.i(
                            "TwoPaneDebug",
                            "back pressed: activePaneSecondary=${TwoPaneState.activePaneSecondary}"
                        )
                        val activeFragment = if (TwoPaneState.activePaneSecondary) {
                            supportFragmentManager
                                .findFragmentById(R.id.rightPaneContent) as? FileListFragment
                        } else {
                            supportFragmentManager
                                .findFragmentById(R.id.leftPaneContent) as? FileListFragment
                        }
                        if (activeFragment == null || !activeFragment.performBack()) {
                            // The touched pane cannot navigate up (e.g. it is at its root):
                            // try the other pane before falling through to the default exit.
                            val otherFragment = if (TwoPaneState.activePaneSecondary) {
                                supportFragmentManager
                                    .findFragmentById(R.id.leftPaneContent) as? FileListFragment
                            } else {
                                supportFragmentManager
                                    .findFragmentById(R.id.rightPaneContent) as? FileListFragment
                            }
                            if (otherFragment == null || !otherFragment.performBack()) {
                                isEnabled = false
                                onBackPressedDispatcher.onBackPressed()
                            }
                        }
                    }
                }
            )
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
        // Apply two-pane setting changes when returning from the settings screen: the
        // Activity is stopped while the setting screen is on top. Recreate() would restore
        // the old fragment hierarchy into containers that no longer match the new pane
        // layout (crash), so finish and start a fresh instance instead. Picker activities
        // always run single-pane regardless of the setting, so they must NOT be restarted
        // here (that would close the picker and relaunch a plain file list).
        if (!isPickMode(intent) && Settings.FILE_LIST_TWO_PANE.valueCompat != twoPaneAtCreation) {
            finish()
            startActivity(Intent(this, FileListActivity::class.java))
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
                if (!fabTouched && !topBarTouched) {
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
        TwoPaneState.activePaneSecondaryListener = { updateResponsivePanes() }
        updateResponsivePanes()
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
        // Both panes side by side, each exactly half the width (minus the divider).
        val paneWidth = (screenWidth - 1) / 2
        leftPane.layoutParams.width = paneWidth
        rightPane.layoutParams.width = screenWidth - paneWidth - 1
        leftPane.isVisible = true
        rightPane.isVisible = true
        divider.isVisible = true
        // Active-pane marker: a highlight border around the active pane container plus a
        // dimmed (alpha 0.6) inactive pane, MT Manager style. The border color resolves
        // from the theme so it matches whatever theme (MD2/MD3) is active.
        fun resolveColor(attrRes: Int, fallbackRes: Int): Int {
            val typedValue = android.util.TypedValue()
            return if (theme.resolveAttribute(attrRes, typedValue, true)) {
                typedValue.data
            } else {
                getColor(fallbackRes)
            }
        }
        val borderColor = resolveColor(
            android.R.attr.colorAccent,
            android.R.color.holo_blue_light
        )
        val paneStroke = android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP, 2f, resources.displayMetrics
        ).toInt()
        fun applyPaneHighlight(pane: View, active: Boolean) {
            val border = android.graphics.drawable.GradientDrawable()
            if (active) {
                border.setStroke(paneStroke, borderColor)
            }
            pane.background = border
        }
        applyPaneHighlight(leftPane, !TwoPaneState.activePaneSecondary)
        applyPaneHighlight(rightPane, TwoPaneState.activePaneSecondary)
        // Dim the inactive pane's content.
        val leftContent = findViewById<View>(R.id.leftPaneContent)
        val rightContent = findViewById<View>(R.id.rightPaneContent)
        leftContent.alpha = if (TwoPaneState.activePaneSecondary) 0.6f else 1f
        rightContent.alpha = if (TwoPaneState.activePaneSecondary) 1f else 0.6f
    }

    private fun currentFragment(): FileListFragment? =
        if (twoPaneAtCreation) {
            supportFragmentManager.findFragmentById(R.id.leftPaneContent) as? FileListFragment
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
