package stride.app.navigation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.glass.nativeGlass
import stride.app.ui.FileTypeIcon
import stride.app.ui.FolderIcon
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.glassLensCapsule
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

/** One segment of the navigation bar: the project root, a folder or the file. */
data class NavigationBarSegment(val name: String, val path: Path, val isDirectory: Boolean)

/** The children of [folder] the bar shows above itself, with the selected row. */
data class NavigationBarList(val folder: Path, val children: List<VFile>, val selected: Int = 0)

/**
 * Segments and keyboard state of the navigation bar. It is a pure value so ←/→, the child list
 * and the Esc levels can be tested without a window; the composable copies it on every change.
 */
data class NavigationBarState(
    val segments: List<NavigationBarSegment>,
    val selected: Int,
    val list: NavigationBarList? = null,
) {
    init {
        require(segments.isNotEmpty()) { "the navigation bar needs at least the root segment" }
    }

    val segment: NavigationBarSegment get() = segments[selected.coerceIn(0, segments.lastIndex)]

    val selectedChild: VFile? get() = list?.let { it.children.getOrNull(it.selected) }

    /** ← at the left end stays on the root segment. */
    fun previous(): NavigationBarState = copy(selected = (selected - 1).coerceAtLeast(0))

    /** → at the right end stays on the file segment. */
    fun next(): NavigationBarState = copy(selected = (selected + 1).coerceAtMost(segments.lastIndex))

    /** Opens the selected segment's children; the file segment has none. */
    fun openList(children: (Path) -> List<VFile>): NavigationBarState {
        val current = segment
        if (!current.isDirectory) return this
        return copy(list = NavigationBarList(current.path, children(current.path)))
    }

    fun moveInList(step: Int): NavigationBarState {
        val current = list ?: return this
        if (current.children.isEmpty()) return this
        val target = (current.selected + step).coerceIn(0, current.children.lastIndex)
        return copy(list = current.copy(selected = target))
    }

    fun closeList(): NavigationBarState = copy(list = null)

    /** Makes [folder] the segment after the selected one, dropping the deeper segments. */
    fun descend(folder: VFile, children: (Path) -> List<VFile>): NavigationBarState {
        val path = folder.path.toAbsolutePath().normalize()
        val segments = segments.take(selected + 1) + NavigationBarSegment(folder.name, path, true)
        return NavigationBarState(segments, segments.lastIndex, NavigationBarList(path, children(path)))
    }

    companion object {
        /** The bar of the open [file], or of the project root alone when no file is open. */
        fun forFile(root: Path, file: Path?): NavigationBarState {
            val rootPath = root.toAbsolutePath().normalize()
            val rootName = rootPath.fileName?.toString() ?: rootPath.toString()
            val segments = ArrayList<NavigationBarSegment>()
            segments.add(NavigationBarSegment(rootName, rootPath, true))
            val target = file?.toAbsolutePath()?.normalize()
            if (target != null && target != rootPath) {
                if (target.startsWith(rootPath)) {
                    val relative = rootPath.relativize(target)
                    var path = rootPath
                    for (index in 0 until relative.nameCount) {
                        path = path.resolve(relative.getName(index))
                        segments.add(
                            NavigationBarSegment(
                                relative.getName(index).toString(),
                                path,
                                index < relative.nameCount - 1,
                            ),
                        )
                    }
                } else {
                    segments.add(NavigationBarSegment(target.fileName?.toString() ?: target.toString(), target, false))
                }
            }
            return NavigationBarState(segments, segments.lastIndex)
        }
    }
}

/**
 * Opens [file] from the bar. A file that is already open is only selected, so its caret stays
 * where it was; a closed one opens at its start.
 */
fun openNavigationBarFile(state: AppState, session: ProjectSession, file: VFile): Boolean {
    val path = file.path.toAbsolutePath().normalize()
    if (!Files.exists(path)) {
        state.statusMessage = "File not found: ${relativePath(session, file)}"
        state.focusSelectedEditor()
        return false
    }
    val open = session.editors.openFiles.any { it.file.path.toAbsolutePath().normalize() == path }
    if (open) {
        session.editors.openFile(file)
        state.focusSelectedEditor()
    } else {
        openFileAtOffset(state, session, path, 0)
    }
    return true
}

/**
 * The bottom capsule while the navigation bar is active: the project root, each folder and the
 * file as segments. It owns the keyboard (the modal [stride.app.keys.KeyContext.NAV_BAR]) and
 * reports the selected segment's left edge so the child list can sit above it.
 */
@Composable
fun NavigationBar(
    bar: NavigationBarState,
    children: (Path) -> List<VFile>,
    onChange: (NavigationBarState) -> Unit,
    onOpenFile: (VFile) -> Unit,
    onClose: () -> Unit,
    onSegmentPosition: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    fun activateSegment(): Boolean {
        val current = bar.segment
        if (current.isDirectory) onChange(bar.openList(children)) else onOpenFile(VFile(current.path))
        return true
    }

    fun activateChild(): Boolean {
        val child = bar.selectedChild ?: return true
        if (child.isDirectory) onChange(bar.descend(child, children)) else onOpenFile(child)
        return true
    }

    Row(
        modifier
            .height(DesignTokens.statusCapsuleHeight)
            .capsuleGlass(shape)
            .nativeGlass("bottom-path", shape, tint = DesignTokens.nativeGlassTint)
            .pressBlocker()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    val inList = bar.list != null
                    when (event.key) {
                        Key.Escape -> {
                            if (inList) onChange(bar.closeList()) else onClose()
                            true
                        }
                        Key.DirectionLeft -> {
                            if (inList) onChange(bar.closeList()) else onChange(bar.previous())
                            true
                        }
                        Key.DirectionRight -> {
                            if (inList) activateChild() else onChange(bar.next())
                            true
                        }
                        Key.DirectionDown -> {
                            when {
                                event.isMetaPressed && inList -> activateChild()
                                event.isMetaPressed -> activateSegment()
                                inList -> onChange(bar.moveInList(1))
                                else -> activateSegment()
                            }
                            true
                        }
                        Key.DirectionUp -> {
                            if (inList) onChange(bar.moveInList(-1))
                            true
                        }
                        Key.F4 -> {
                            if (inList) activateChild() else activateSegment()
                            true
                        }
                        Key.Enter, Key.NumPadEnter -> {
                            if (inList) activateChild() else activateSegment()
                            true
                        }
                        else -> false
                    }
                }
            }
            .padding(horizontal = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            bar.segments.forEachIndexed { index, segment ->
                NavigationBarSegmentItem(
                    segment = segment,
                    selected = index == bar.selected,
                    onClick = {
                        val next = bar.copy(selected = index, list = null)
                        onChange(if (segment.isDirectory) next.openList(children) else next)
                    },
                    onPosition = { left -> if (index == bar.selected) onSegmentPosition(with(density) { left.toDp() }) },
                )
            }
        }
    }
}

@Composable
private fun NavigationBarSegmentItem(
    segment: NavigationBarSegment,
    selected: Boolean,
    onClick: () -> Unit,
    onPosition: (Float) -> Unit,
) {
    val interaction = remember(segment.path) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pillShape = RoundedCornerShape(percent = 50)
    val pill = when {
        selected -> Modifier.glassLensCapsule(shape = pillShape, insetX = 6.dp, insetY = 2.dp, selectionAlpha = 1f)
        hovered -> Modifier.glassCapsule(shape = pillShape, insetX = 6.dp, insetY = 2.dp, fillAlpha = 1f)
        else -> Modifier
    }
    Row(
        Modifier
            .height(DesignTokens.statusCapsuleHeight)
            .then(pill)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .onGloballyPositioned { coordinates -> onPosition(coordinates.positionInParent().x) }
            .padding(horizontal = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (segment.isDirectory) {
            FolderIcon(Modifier.size(12.dp))
            Spacer(Modifier.width(DesignTokens.space4))
        }
        Text(
            segment.name,
            color = if (selected) DesignTokens.selectedText else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.statusTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The selected folder's children above the bar. The bar keeps the keyboard, so the panel only
 * draws and takes clicks; a click on a row behaves like ↵ on it.
 */
@Composable
fun NavigationBarChildrenPopup(
    children: List<VFile>,
    selected: Int,
    onChoose: (VFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val listState = rememberLazyListState()
    val index = selected.coerceIn(0, (children.size - 1).coerceAtLeast(0))
    LaunchedEffect(index, children.size) {
        if (children.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.none { it.index == index }) listState.scrollToItem(index)
    }
    Column(
        modifier
            .width(NAV_BAR_LIST_WIDTH)
            .popupPanel("popup-nav-bar", shape),
    ) {
        if (children.isEmpty()) {
            Text(
                "Empty folder",
                color = DesignTokens.textOnBusyGlass,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(DesignTokens.popupRowHeight * children.size.coerceAtMost(NAV_BAR_MAX_ROWS)),
            ) {
                LazyColumn(Modifier.fillMaxWidth(), state = listState) {
                    itemsIndexed(children) { rowIndex, file ->
                        NavigationBarChildRow(
                            file = file,
                            selected = rowIndex == index,
                            onClick = { onChoose(file) },
                        )
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = DesignTokens.scrollbarStyle,
                )
            }
        }
        PopupHintBar("↑↓ Navigate", "↵ Open", "← Back", "Esc Close")
    }
}

@Composable
private fun NavigationBarChildRow(file: VFile, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DesignTokens.popupRowHeight)
                .selectionRow(selected = selected, hovered = hovered)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = DesignTokens.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (file.isDirectory) {
                FolderIcon(Modifier.size(13.dp))
            } else {
                FileTypeIcon(
                    file.name,
                    Modifier.size(13.dp),
                    color = if (selected) DesignTokens.accent else DesignTokens.muted,
                )
            }
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                file.name,
                color = if (selected) DesignTokens.selectedText else DesignTokens.text,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Keeps a press on the bar from reaching the editor under it; nothing is consumed. */
private fun Modifier.pressBlocker(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

private val NAV_BAR_LIST_WIDTH = 360.dp
private const val NAV_BAR_MAX_ROWS = 8
