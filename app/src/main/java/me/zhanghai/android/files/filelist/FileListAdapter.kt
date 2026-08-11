/*
 * Copyright (c) 2018 Hai Zhang <dreaming.in.code.zh@gmail.com>
 * All Rights Reserved.
 */

package me.zhanghai.android.files.filelist

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import android.util.Log
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import java8.nio.file.Path
import me.zhanghai.android.fastscroll.PopupTextProvider
import me.zhanghai.android.files.R
import me.zhanghai.android.files.coil.AppIconPackageName
import me.zhanghai.android.files.compat.foregroundCompat
import me.zhanghai.android.files.compat.getDrawableCompat
import me.zhanghai.android.files.compat.isSingleLineCompat
import me.zhanghai.android.files.databinding.FileItemGridBinding
import me.zhanghai.android.files.databinding.FileItemListBinding
import me.zhanghai.android.files.file.FileItem
import me.zhanghai.android.files.file.fileSize
import me.zhanghai.android.files.file.formatShort
import me.zhanghai.android.files.file.iconRes
import me.zhanghai.android.files.file.isApk
import me.zhanghai.android.files.provider.archive.isArchivePath
import me.zhanghai.android.files.provider.common.isEncrypted
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.ui.AnimatedListAdapter
import me.zhanghai.android.files.ui.CheckableForegroundLinearLayout
import me.zhanghai.android.files.ui.CheckableItemBackground
import me.zhanghai.android.files.util.activity
import me.zhanghai.android.files.util.isMaterial3Theme
import me.zhanghai.android.files.util.layoutInflater
import me.zhanghai.android.files.util.valueCompat
import java.io.File
import java.util.BitSet
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt


/** Text file extensions recognized by the encoding-conversion feature (shared by the
 *  per-item menu and the two-pane multi-select menu). */
internal val TEXT_FILE_EXTENSIONS = setOf(
    "txt", "log", "xml", "json", "html", "htm", "css", "js", "ts", "java", "kt",
    "kts", "c", "h", "cpp", "hpp", "py", "go", "rs", "sh", "bat", "cmd", "ps1",
    "md", "csv", "tsv", "ini", "cfg", "conf", "properties", "gradle", "smali",
    "yml", "yaml", "toml", "sql", "svg", "xsd", "xsl", "pro", "rc", "mk",
    "gitignore", "editorconfig"
)

internal fun isTextFile(file: FileItem): Boolean {
    if (file.mimeType.type == "text") {
        return true
    }
    val extension = file.name.substringAfterLast('.', "").lowercase()
    return extension in TEXT_FILE_EXTENSIONS
}

class FileListAdapter(
    private val listener: Listener
) : AnimatedListAdapter<FileItem, FileListAdapter.ViewHolder>(CALLBACK), PopupTextProvider {
    private var isSearching = false
    /** True when this list is the secondary (right) pane of two-pane browsing. */
    var isSecondaryPane: Boolean = false

    /** True when the two-pane small-icon layout is active (shows more filename text). */
    var useSmallIcons: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** True to hide folder icons in the list (two-pane mode, shows more text). */
    var hideFolderIcons: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    /** True to hide the per-item "three dots" menu button (two-pane lists). */
    var hideMenuButtons: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    private lateinit var _viewType: FileViewType
    var viewType: FileViewType
        get() = _viewType
        set(value) {
            _viewType = value
            if (!isSearching) {
                super.replace(list, true)
            }
        }

    private lateinit var _sortOptions: FileSortOptions
    var sortOptions: FileSortOptions
        get() = _sortOptions
        set(value) {
            _sortOptions = value
            if (!isSearching) {
                val sortedList = list.sortedWith(value.createComparator())
                super.replace(sortedList, true)
                rebuildFilePositionMap()
            }
        }

    var pickOptions: PickOptions? = null
        set(value) {
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }

    private val selectedFiles = fileItemSetOf()

    private val _touchData: TouchData = TouchData()

    private val filePositionMap = mutableMapOf<Path, Int>()

    private lateinit var _nameEllipsize: TextUtils.TruncateAt
    var nameEllipsize: TextUtils.TruncateAt
        get() = _nameEllipsize
        set(value) {
            _nameEllipsize = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_STATE_CHANGED)
        }

    private var _denseLayout: Boolean = false
    var denseLayout: Boolean
        get() = _denseLayout
        set(value) {
            _denseLayout = value
        }

    fun replaceSelectedFiles(files: FileItemSet) {
        val changedFiles = fileItemSetOf()
        val iterator = selectedFiles.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (file !in files) {
                iterator.remove()
                changedFiles.add(file)
            }
        }
        for (file in files) {
            if (file !in selectedFiles) {
                selectedFiles.add(file)
                changedFiles.add(file)
            }
        }
        for (file in changedFiles) {
            val position = filePositionMap[file.path]
            position?.let { notifyItemChanged(it, PAYLOAD_STATE_CHANGED) }
        }
    }

    private fun selectFile(file: FileItem) {
        if (!isFileSelectable(file)) {
            return
        }
        val selected = file in selectedFiles
        val pickOptions = pickOptions
        if (!selected && pickOptions != null && !pickOptions.allowMultiple) {
            listener.clearSelectedFiles()
        }
        listener.selectFile(file, !selected)
    }

    fun selectAllFiles() {
        val files = fileItemSetOf()
        for (index in 0..<itemCount) {
            val file = getItem(index)
            if (isFileSelectable(file)) {
                files.add(file)
            }
        }
        listener.selectFiles(files, true)
    }

    fun rangeSelectFiles() {
        var firstSelectItem = -1
        var lastSelectItem = -1
        for (index in 0..<itemCount) {
            val file = getItem(index)
            if (file in selectedFiles) {
                firstSelectItem = index
                break
            }
        }
        for (index in itemCount - 1 downTo firstSelectItem) {
            val file = getItem(index)
            if (file in selectedFiles) {
                lastSelectItem = index
                break
            }
        }
        val files = fileItemSetOf()
        if (firstSelectItem >= 0
            && lastSelectItem >= 0
            && lastSelectItem < itemCount
            && firstSelectItem < lastSelectItem
        ) {
            for (index in firstSelectItem..lastSelectItem) {
                val file = getItem(index)
                if (isFileSelectable(file)) {
                    files.add(file)
                }
            }
        }
        listener.selectFiles(files, true)
    }

    private fun isFileSelectable(file: FileItem): Boolean {
        val pickOptions = pickOptions ?: return true
        return when (pickOptions.mode) {
            PickOptions.Mode.OPEN_FILE, PickOptions.Mode.CREATE_FILE ->
                !file.attributes.isDirectory &&
                    pickOptions.mimeTypes.any { it.match(file.mimeType) }
            PickOptions.Mode.OPEN_DIRECTORY -> file.attributes.isDirectory
        }
    }

    override fun clear() {
        super.clear()

        rebuildFilePositionMap()
    }

    @Deprecated("", ReplaceWith("replaceListAndSearching(list, searching)"))
    override fun replace(list: List<FileItem>, clear: Boolean) {
        throw UnsupportedOperationException()
    }

    fun replaceListAndIsSearching(list: List<FileItem>, isSearching: Boolean) {
        val clear = this.isSearching != isSearching
        this.isSearching = isSearching
        val sortedList = if (!isSearching) list.sortedWith(sortOptions.createComparator()) else list
        super.replace(sortedList, clear)
        rebuildFilePositionMap()
    }

    private fun rebuildFilePositionMap() {
        filePositionMap.clear()
        for (index in 0..<itemCount) {
            val file = getItem(index)
            filePositionMap[file.path] = index
        }
    }

    override fun getItemViewType(position: Int): Int = viewType.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val viewType = FileViewType.entries[viewType]
        val inflater = parent.context.layoutInflater
        val holder = when (viewType) {
            FileViewType.LIST -> ViewHolder(FileItemListBinding.inflate(inflater, parent, false))
            FileViewType.GRID -> ViewHolder(FileItemGridBinding.inflate(inflater, parent, false))
        }
        return holder.apply {
            itemLayout.apply {
                val context = context

                val isMaterial3Theme = context.isMaterial3Theme
                if (viewType == FileViewType.GRID && isMaterial3Theme) {
                    foregroundCompat =
                        context.getDrawableCompat(R.drawable.file_item_grid_foreground_material3)
                }
                background = if (viewType == FileViewType.GRID && isMaterial3Theme) {
                    CheckableItemBackground.create(4f, 12f, context)
                } else {
                    CheckableItemBackground.create(0f, 0f, context)
                }
                if (viewType == FileViewType.LIST && denseLayout) {
                    layoutParams = layoutParams.apply {
                        height = context.resources.getDimensionPixelSize(R.dimen.dense_two_line_list_item_height)
                    }
                }
            }
            thumbnailOutlineView?.apply {
                val context = context
                if (context.isMaterial3Theme) {
                    background = context.getDrawableCompat(
                        R.drawable.file_item_grid_thumbnail_outline_material3
                    )
                }
            }
            popupMenu = PopupMenu(menuButton.context, menuButton)
                .apply { inflate(R.menu.file_item) }
            menuButton.setOnClickListener { popupMenu.show() }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        throw UnsupportedOperationException()
    }

    private fun onTouchListener(context: Context, holder: ViewHolder, view: View, event: MotionEvent, file: FileItem) {
        if (_viewType == FileViewType.GRID) return
        val maxWaitMillisSelection = 400L
        val localPosition = holder.absoluteAdapterPosition
        val horizontalError = 7.5f
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                view.parent.requestDisallowInterceptTouchEvent(true)
                _touchData.reset()
                _touchData.setup(
                    pStartTouchPosX = event.x,
                    pStartTouchPosY = event.y,
                    pIsDuringClick = true,
                    pIsGestureHorizontal = false,
                    pIsMultipleSelectionStarted = false,
                    pClickedLineAnchorY = view.y,
                    pLastPosSelected = localPosition,
                    pActionIdentifier = event.eventTime,
                )
                _touchData.threadedWaiter = Thread {
                    val id = _touchData.actionIdentifier
                    SystemClock.sleep(maxWaitMillisSelection)
                    if (!_touchData.isDuringClick || _touchData.isMultipleSelectionStarted || _touchData.isGestureHorizontal || id != _touchData.actionIdentifier) return@Thread
                    _touchData.isMultipleSelectionStarted = true
                    selectFile(file)
                }
                _touchData.threadedWaiter.start()
            }
            MotionEvent.ACTION_MOVE -> {
                // event.y is local to the icon view while clickedLineAnchorY is based on
                // the row position. Mixing those coordinate spaces shifts selection by
                // neighbouring rows, especially with the 48dp dense-row layout. Resolve
                // the current adapter position from the RecyclerView child instead.
                val newPosition = positionAtEvent(view, event, localPosition)
                if (newPosition < 0) return
                val deltaPos = newPosition - localPosition
                if (newPosition != _touchData.lastPosSelected && !_touchData.isGestureHorizontal) {
                    if (!_touchData.isMultipleSelectionStarted) {
                        _touchData.isMultipleSelectionStarted = true
                        selectFile(file)
                    }
                    if (newPosition < 0) return
                    if (!_touchData.isDeltaPosSet) {
                        _touchData.isDeltaPosSet = true
                        _touchData.prevDeltaPos = 0
                        _touchData.isDeltaPosGrowing = deltaPos > 0
                    }
                    if ((deltaPos > _touchData.prevDeltaPos) != _touchData.isDeltaPosGrowing) {
                        _touchData.isDeltaPosGrowing = !_touchData.isDeltaPosGrowing
                        selectFile(getItem(_touchData.lastPosSelected))
                    }
                    // Handle fast user input (touch capture may be too slow -- abs(deltaPos) > 1)
                    if (_touchData.isDeltaPosGrowing) {
                        for (p in _touchData.lastPosSelected+1..newPosition)
                            selectFile(getItem(p))
                    } else {
                        for (p in newPosition..<_touchData.lastPosSelected) {
                            selectFile(getItem(p))
                        }
                    }
                    _touchData.lastPosSelected = newPosition
                } else {
                    if (_touchData.isMultipleSelectionStarted) return
                    if (abs(event.x - _touchData.startTouchPosX) > horizontalError) {
                        _touchData.isGestureHorizontal = true
                        view.parent.requestDisallowInterceptTouchEvent(false)
                    }
                }
                _touchData.prevDeltaPos = deltaPos
            }
            MotionEvent.ACTION_UP -> {
                if (!_touchData.isMultipleSelectionStarted && TouchData.isClickAction(context, _touchData.startTouchPosX, _touchData.startTouchPosY, event.x, event.y)) {
                    _touchData.isDuringClick = false
                    view.performClick()
                }
                view.parent.requestDisallowInterceptTouchEvent(false)
                _touchData.reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                // A cancelled gesture (e.g. the list started scrolling) must not leave
                // stale long-press/drag state behind, otherwise the next touch on another
                // row would mis-select files.
                _touchData.reset()
                view.parent.requestDisallowInterceptTouchEvent(false)
            }
        }
    }

    private fun positionAtEvent(view: View, event: MotionEvent, fallback: Int): Int {
        val recyclerView = view.parent?.parent as? RecyclerView ?: return fallback
        val location = IntArray(2)
        recyclerView.getLocationOnScreen(location)
        val child = recyclerView.findChildViewUnder(
            event.rawX - location[0],
            event.rawY - location[1]
        ) ?: return fallback
        return recyclerView.getChildViewHolder(child).bindingAdapterPosition
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        val file = getItem(position)
        val isDirectory = file.attributes.isDirectory
        val isEnabled = isFileSelectable(file) || isDirectory
        holder.itemLayout.isEnabled = isEnabled
        holder.menuButton.isEnabled = isEnabled
        holder.menuButton.isVisible = !hideMenuButtons
        val menu = holder.popupMenu.menu
        val path = file.path
        val hasPickOptions = pickOptions != null
        val isReadOnly = path.fileSystem.isReadOnly
        val hideItem = menu.findItem(R.id.action_hide)
        Log.i(
            "SoraEditor",
            "FileListAdapter menu for ${path.fileName}: isReadOnly=$isReadOnly, " +
                "hideItemExists=${hideItem != null}, hideVisible=${hideItem?.isVisible}"
        )
        menu.findItem(R.id.action_cut).isVisible = !hasPickOptions && !isReadOnly
        menu.findItem(R.id.action_copy).isVisible = !hasPickOptions
        val checked = file in selectedFiles
        holder.itemLayout.isChecked = checked
        holder.nameText.apply {
            if (isSingleLineCompat) {
                val nameEllipsize = nameEllipsize
                ellipsize = nameEllipsize
                isSelected = nameEllipsize == TextUtils.TruncateAt.MARQUEE
            }
        }
        if (payloads.isNotEmpty()) {
            return
        }
        bindViewHolderAnimation(holder)

        holder.itemLayout.apply {
            setOnClickListener {
                if (selectedFiles.isEmpty()) {
                    listener.openFile(file)
                } else {
                    selectFile(file)
                }
            }
            setOnLongClickListener {
                if (selectedFiles.isEmpty()) {
                    selectFile(file)
                } else {
                    listener.openFile(file)
                }
                true
            }
        }
        holder.iconLayout.apply {
            // Two-pane folders: collapse the icon area to zero width so the name spans
            // the full row (the drag-to-select gesture is handled on the whole item row).
            val rowContext = holder.itemLayout.context
            val iconLayoutParams = layoutParams
            if (hideFolderIcons && isDirectory) {
                iconLayoutParams.width = 0
                (iconLayoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
            } else {
                iconLayoutParams.width = rowContext.resources
                    .getDimensionPixelSize(R.dimen.touch_target_size)
                (iconLayoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd =
                    rowContext.resources.getDimensionPixelSize(
                        R.dimen.content_start_from_screen_edge_margin_minus_44dp
                    )
            }
            layoutParams = iconLayoutParams
            setOnClickListener { selectFile(file) }
        }
        holder.itemLayout.setOnTouchListener { view, event ->
            onTouchListener(holder.itemLayout.context, holder, view, event, file)
            true
        }
        val iconRes = file.mimeType.iconRes
        holder.iconImage.apply {
            isVisible = !(hideFolderIcons && isDirectory)
            setImageResource(iconRes)
            if (useSmallIcons) {
                val size = holder.itemLayout.context.resources
                    .getDimensionPixelSize(R.dimen.small_icon_size)
                layoutParams = layoutParams.apply { width = size; height = size }
            }
        }
        holder.directoryThumbnailImage?.isVisible = isDirectory
        holder.thumbnailOutlineView?.isVisible = !isDirectory
        val supportsThumbnail = file.supportsThumbnail
        val shouldLoadThumbnailIcon = supportsThumbnail && holder.thumbnailIconImage != null &&
            file.mimeType.isApk
        val attributes = file.attributes
        holder.thumbnailIconImage?.apply {
            dispose()
            isVisible = !isDirectory
            setImageResource(iconRes)
            if (shouldLoadThumbnailIcon) {
                load(path to attributes)
            }
        }
        holder.thumbnailImage.apply {
            dispose()
            setImageDrawable(null)
            val shouldLoadThumbnail = supportsThumbnail && !shouldLoadThumbnailIcon
            isVisible = shouldLoadThumbnail
            if (shouldLoadThumbnail) {
                load(path to attributes) {
                    listener { _, _ ->
                        val iconImage = holder.thumbnailIconImage ?: holder.iconImage
                        iconImage.isVisible = false
                    }
                }
            }
        }
        holder.appIconBadgeImage.apply {
            dispose()
            setImageDrawable(null)
            val appDirectoryPackageName = file.appDirectoryPackageName
            val hasAppIconBadge = appDirectoryPackageName != null
            isVisible = hasAppIconBadge
            if (hasAppIconBadge) {
                load(AppIconPackageName(appDirectoryPackageName!!))
            }
        }
        holder.badgeImage.apply {
            val badgeIconRes = if (file.attributesNoFollowLinks.isSymbolicLink) {
                if (file.isSymbolicLinkBroken) {
                    R.drawable.error_badge_icon_18dp
                } else {
                    R.drawable.symbolic_link_badge_icon_18dp
                }
            } else if (file.attributesNoFollowLinks.isEncrypted()) {
                R.drawable.encrypted_badge_icon_18dp
            } else {
                null
            }
            val hasBadge = badgeIconRes != null
            isVisible = hasBadge
            if (hasBadge) {
                setImageResource(badgeIconRes!!)
            } else {
                setImageDrawable(null)
            }
        }
        holder.nameText.text = file.name
        holder.descriptionText?.text = if (isDirectory) {
            null
        } else {
            val context = holder.descriptionText!!.context
            val lastModificationTime = attributes.lastModifiedTime().toInstant()
                .formatShort(context)
            val size = attributes.fileSize.formatHumanReadable(context)
            val descriptionSeparator = context.getString(R.string.file_item_description_separator)
            listOf(lastModificationTime, size).joinToString(descriptionSeparator)
        }
        val isArchivePath = path.isArchivePath
        menu.findItem(R.id.action_copy)
            .setTitle(if (isArchivePath) R.string.file_item_action_extract else R.string.copy)
        menu.findItem(R.id.action_delete).isVisible = !isReadOnly
        menu.findItem(R.id.action_rename).isVisible = !isReadOnly
        menu.findItem(R.id.action_hide).isVisible = !isReadOnly
        menu.findItem(R.id.action_extract).isVisible = file.isArchiveFile
        menu.findItem(R.id.action_dex_analyze).isVisible =
            file.name.endsWith(".dex", ignoreCase = true) || file.mimeType.isApk
        menu.findItem(R.id.action_install).isVisible = file.mimeType.isApk
        menu.findItem(R.id.action_apk_string_search).isVisible = file.mimeType.isApk
        menu.findItem(R.id.action_elf_analyze).isVisible =
            file.name.endsWith(".so", ignoreCase = true) ||
                file.name.endsWith(".elf", ignoreCase = true)
        menu.findItem(R.id.action_hex_view).isVisible = !isDirectory
        menu.findItem(R.id.action_compare_apk).isVisible = file.mimeType.isApk
        menu.findItem(R.id.action_view_manifest).isVisible = file.mimeType.isApk
        menu.findItem(R.id.action_set_timestamp).isVisible = !isReadOnly
                menu.findItem(R.id.action_auto_sign_apk).isVisible = file.mimeType.isApk
                menu.findItem(R.id.action_sign_apk).isVisible = file.mimeType.isApk
                menu.findItem(R.id.action_kill_signature).isVisible = file.mimeType.isApk
                menu.findItem(R.id.action_edit_arsc).isVisible =
                    file.mimeType.isApk || file.name.endsWith(".arsc", ignoreCase = true)
        menu.findItem(R.id.action_rename_apk).isVisible = file.mimeType.isApk
        menu.findItem(R.id.action_convert_encoding).isVisible = !isDirectory && isTextFile(file)
        menu.findItem(R.id.action_archive).isVisible = !isArchivePath
        menu.findItem(R.id.action_add_bookmark).isVisible = isDirectory
        // The cross-pane items exist only in the single-file menu for single-pane mode
        // (original behavior). In two-pane mode the multi-select action bar in the shared
        // top bar handles cross-pane transfers, so hide them here.
        val isTwoPane = me.zhanghai.android.files.settings.Settings.FILE_LIST_TWO_PANE.valueCompat
        menu.findItem(R.id.action_copy_to_other_pane).isVisible = !isTwoPane
        menu.findItem(R.id.action_cut_to_other_pane).isVisible = !isTwoPane
        holder.popupMenu.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_open_with -> {
                    listener.openFileWith(file)
                    true
                }
                R.id.action_cut -> {
                    listener.cutFile(file)
                    true
                }
                R.id.action_copy -> {
                    listener.copyFile(file)
                    true
                }
                R.id.action_copy_to_other_pane -> {
                    listener.copyToOtherPane(file)
                    true
                }
                R.id.action_cut_to_other_pane -> {
                    listener.cutToOtherPane(file)
                    true
                }
                R.id.action_delete -> {
                    listener.confirmDeleteFile(file)
                    true
                }
                R.id.action_rename -> {
                    listener.showRenameFileDialog(file)
                    true
                }
                R.id.action_hide -> {
                    listener.hideFile(file)
                    true
                }
                R.id.action_extract -> {
                    listener.extractFile(file)
                    true
                }
                R.id.action_dex_analyze -> {
                    listener.showDexAnalyzer(file)
                    true
                }
                R.id.action_install -> {
                    listener.installFile(file)
                    true
                }
                R.id.action_apk_string_search -> {
                    listener.showApkStringSearch(file)
                    true
                }
                R.id.action_elf_analyze -> {
                    listener.showElfAnalyzer(file)
                    true
                }
                R.id.action_hex_view -> {
                    listener.showHexViewer(file)
                    true
                }
                R.id.action_compare_apk -> {
                    listener.compareApk(file)
                    true
                }
                R.id.action_view_manifest -> {
                    listener.showManifest(file)
                    true
                }
                R.id.action_set_timestamp -> {
                    listener.showSetTimestampDialog(file)
                    true
                }
                R.id.action_auto_sign_apk -> {
                    listener.autoSignApk(file)
                    true
                }
                R.id.action_sign_apk -> {
                    listener.showSignApkDialog(file)
                    true
                }
                R.id.action_kill_signature -> {
                    listener.killSignature(file)
                    true
                }
                R.id.action_edit_arsc -> {
                    listener.showArscEditor(file)
                    true
                }
                R.id.action_rename_apk -> {
                    listener.renameApkWithVersion(file)
                    true
                }
                R.id.action_convert_encoding -> {
                    listener.showEncodingConversionDialog(file)
                    true
                }
                R.id.action_archive -> {
                    listener.showCreateArchiveDialog(file)
                    true
                }
                R.id.action_share -> {
                    listener.shareFile(file)
                    true
                }
                R.id.action_copy_path -> {
                    listener.copyPath(file)
                    true
                }
                R.id.action_add_bookmark -> {
                    listener.addBookmark(file)
                    true
                }
                R.id.action_create_shortcut -> {
                    listener.createShortcut(file)
                    true
                }
                R.id.action_properties -> {
                    listener.showPropertiesDialog(file)
                    true
                }
                else -> false
            }
        }
    }

    override fun getPopupText(view: View, position: Int): CharSequence {
        val file = getItem(position)
        return when (sortOptions.by) {
            FileSortOptions.By.NAME -> file.name.take(1).uppercase(Locale.getDefault())
            FileSortOptions.By.TYPE -> file.extension.uppercase(Locale.getDefault())
            FileSortOptions.By.SIZE -> file.attributes.fileSize.formatHumanReadable(view.context)
            FileSortOptions.By.LAST_MODIFIED ->
                file.attributes.lastModifiedTime().toInstant().formatShort(view.context)
        }
    }

    override val isAnimationEnabled: Boolean
        get() = Settings.FILE_LIST_ANIMATION.valueCompat

    companion object {
        private val PAYLOAD_STATE_CHANGED = Any()

        private val CALLBACK = object : DiffUtil.ItemCallback<FileItem>() {
            override fun areItemsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
                oldItem.path == newItem.path

            override fun areContentsTheSame(oldItem: FileItem, newItem: FileItem): Boolean =
                oldItem == newItem
        }
    }

    class ViewHolder private constructor(
        root: View,
        val itemLayout: CheckableForegroundLinearLayout,
        val iconLayout: View,
        val iconImage: ImageView,
        val directoryThumbnailImage: ImageView?,
        val thumbnailOutlineView: View?,
        val thumbnailIconImage: ImageView?,
        val thumbnailImage: ImageView,
        val appIconBadgeImage: ImageView,
        val badgeImage: ImageView,
        val nameText: TextView,
        val descriptionText: TextView?,
        val menuButton: ImageButton
    ) : RecyclerView.ViewHolder(root) {
        constructor(binding: FileItemListBinding) : this(
            binding.root,
            binding.itemLayout,
            binding.iconLayout,
            binding.iconImage,
            null,
            null,
            null,
            binding.thumbnailImage,
            binding.appIconBadgeImage,
            binding.badgeImage,
            binding.nameText,
            binding.descriptionText,
            binding.menuButton
        )

        constructor(binding: FileItemGridBinding) : this(
            binding.root,
            binding.itemLayout,
            binding.iconLayout,
            binding.iconImage,
            binding.directoryThumbnailImage,
            binding.thumbnailOutlineView,
            binding.thumbnailIconImage,
            binding.thumbnailImage,
            binding.appIconBadgeImage,
            binding.badgeImage,
            binding.nameText,
            null,
            binding.menuButton
        )

        lateinit var popupMenu: PopupMenu
    }

    interface Listener {
        fun clearSelectedFiles()
        fun selectFile(file: FileItem, selected: Boolean)
        fun selectFiles(files: FileItemSet, selected: Boolean)
        fun openFile(file: FileItem)
        fun openFileWith(file: FileItem)
        fun cutFile(file: FileItem)
        fun copyFile(file: FileItem)
        /** Copy/cut the file to the other pane's current directory (two-pane mode). */
        fun copyToOtherPane(file: FileItem)
        fun cutToOtherPane(file: FileItem)
        fun confirmDeleteFile(file: FileItem)
        fun showRenameFileDialog(file: FileItem)
        fun hideFile(file: FileItem)
        fun extractFile(file: FileItem)
        fun showDexAnalyzer(file: FileItem)
        fun installFile(file: FileItem)
        fun showApkStringSearch(file: FileItem)
        fun showElfAnalyzer(file: FileItem)
        fun showHexViewer(file: FileItem)
        fun compareApk(file: FileItem)
        fun showManifest(file: FileItem)
        fun showSetTimestampDialog(file: FileItem)
        fun autoSignApk(file: FileItem)
        fun showSignApkDialog(file: FileItem)
        fun killSignature(file: FileItem)
        fun showArscEditor(file: FileItem)
        fun renameApkWithVersion(file: FileItem)
        fun showEncodingConversionDialog(file: FileItem)
        fun showCreateArchiveDialog(file: FileItem)
        fun shareFile(file: FileItem)
        fun copyPath(file: FileItem)
        fun addBookmark(file: FileItem)
        fun createShortcut(file: FileItem)
        fun showPropertiesDialog(file: FileItem)
    }
}

private data class TouchData(
    var startTouchPosX: Float = 0f,
    var startTouchPosY: Float = 0f,
    var isDeltaPosSet: Boolean = false,
    var isDeltaPosGrowing: Boolean = false,
    var prevDeltaPos: Int = 0,
    var isDuringClick: Boolean = false,
    var isMultipleSelectionStarted: Boolean = false,
    var isGestureHorizontal: Boolean = false,
    var lastPosSelected: Int = -1,
    var clickedLineAnchorY: Float = 0f,
    var actionIdentifier: Long = 0L
) {
    lateinit var threadedWaiter: Thread

    fun setup(pStartTouchPosX: Float,
              pStartTouchPosY: Float,
              pIsDuringClick: Boolean,
              pIsGestureHorizontal: Boolean,
              pIsMultipleSelectionStarted: Boolean,
              pClickedLineAnchorY: Float,
              pLastPosSelected: Int,
              pActionIdentifier: Long) {
        startTouchPosX = pStartTouchPosX
        startTouchPosY = pStartTouchPosY
        isDuringClick = pIsDuringClick
        isGestureHorizontal = pIsGestureHorizontal
        isMultipleSelectionStarted = pIsMultipleSelectionStarted
        clickedLineAnchorY = pClickedLineAnchorY
        lastPosSelected = pLastPosSelected
        actionIdentifier = pActionIdentifier
    }

    /** Fully resets the gesture state after ACTION_UP / ACTION_CANCEL. */
    fun reset() {
        isDuringClick = false
        isGestureHorizontal = false
        isMultipleSelectionStarted = false
        isDeltaPosSet = false
        isDeltaPosGrowing = false
        prevDeltaPos = 0
        lastPosSelected = -1
        actionIdentifier = 0L
    }

    companion object {
        fun isClickAction(context: Context, startX: Float, startY: Float, endX: Float, endY: Float): Boolean {
            val clickActionThreshold = ViewConfiguration.get(context).scaledTouchSlop
            val differenceX = abs(startX - endX)
            val differenceY = abs(startY - endY)
            return differenceX <= clickActionThreshold && differenceY <= clickActionThreshold
        }
    }
}
