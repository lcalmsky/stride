package stride.app.vcs

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.actions.ActionGroups
import stride.app.actions.ComponentAction
import stride.app.actions.contextMenuRequest
import stride.app.actions.openContextMenu
import stride.app.glass.nativeGlass
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.selectionRow
import stride.app.ui.toolwindow.ToolWindowEmptyState
import stride.core.vfs.VFile

private enum class CommitFocusPart { LIST, MESSAGE }

internal const val COMMIT_LIST_TAG: String = "commit-list"
internal const val COMMIT_MESSAGE_TAG: String = "commit-message"
internal const val COMMIT_CAPSULE_TAG: String = "commit-capsule"
internal const val COMMIT_AND_PUSH_CAPSULE_TAG: String = "commit-and-push-capsule"
internal const val COMMIT_ERROR_TAG: String = "commit-error"
internal const val COMMIT_AMEND_TAG: String = "commit-amend"

/**
 * The Commit window: the grouped file list on the left, the message and the Commit capsule on
 * the right. Space toggles, ↑/↓ move, ↩/F4 open, Tab switches columns, ⌘↩ commits and Esc hands
 * the keyboard back to the editor.
 */
@Composable
fun CommitToolWindowContent(state: AppState, modifier: Modifier) {
    val session = state.session
    if (session == null) {
        ToolWindowEmptyState(
            "No changes",
            modifier,
            focusRequests = state.toolWindows.focusRequests,
            onEscape = { state.focusSelectedEditor() },
        )
        return
    }
    val git = session.git
    val snapshot = git.snapshot
    val cold = snapshot.repository == null && snapshot.error == null
    var settled by remember(session) { mutableStateOf(!cold) }
    if (cold && !settled) {
        LaunchedEffect(session) {
            withContext(Dispatchers.IO) { git.refreshNow() }
            Snapshot.withMutableSnapshot { settled = true }
        }
        return
    }
    val empty = snapshot.error ?: if (snapshot.repository == null) notRepositoryMessage(session) else null
    if (empty != null) {
        ToolWindowEmptyState(
            empty,
            modifier,
            focusRequests = state.toolWindows.focusRequests,
            onEscape = { state.focusSelectedEditor() },
        )
        return
    }

    val window = session.commit
    val head = snapshot.branch?.head
    val root = realProjectRoot(session.project.root)
    val rows = commitRows(session, window)
    LaunchedEffect(rows) { window.syncRows(rows) }
    val modifiedEditors = modifiedEditorPaths(session)
    LaunchedEffect(head, modifiedEditors, snapshot.changes) {
        withContext(Dispatchers.IO) { resolveTracked(session, window) }
    }

    val listFocus = remember { FocusRequester() }
    val messageFocus = remember { FocusRequester() }
    var listFocused by remember { mutableStateOf(false) }
    var lastPart by remember { mutableStateOf(CommitFocusPart.MESSAGE) }

    LaunchedEffect(state.toolWindows.focusRequests) {
        if (state.toolWindows.focusRequests > 0) {
            withFrameNanos { }
            runCatching { messageFocus.requestFocus() }
        }
    }

    // A diff, push, update, rollback, branches, VCS or context-menu popup that closed onto this
    // window gives the keyboard back to the part that had it; one that closed onto the editor left
    // `state.focus` there and is left alone.
    val popupOpen = state.diffRequest != null || state.pushRequest != null ||
        state.updateRequest != null || state.vcsPopup != null || state.rollbackRequest != null ||
        state.branchesPopup != null || state.contextMenu != null
    LaunchedEffect(popupOpen) {
        if (popupOpen || state.focus != KeyContext.COMMIT_WINDOW) return@LaunchedEffect
        withFrameNanos { }
        val target = if (lastPart == CommitFocusPart.LIST) listFocus else messageFocus
        runCatching { target.requestFocus() }
    }

    val ordered = CommitModel.ordered(rows)
    val index = window.selectedIndex()
    val selectedPath = ordered.getOrNull(index)?.path
    val checked = window.checkedCount()
    val amending = window.amend != null
    val enabled = session.git.busy == null && window.message.isNotBlank() && (checked > 0 || amending)

    fun move(step: Int) {
        if (ordered.isEmpty()) return
        window.selectedPath = ordered[(index + step).coerceIn(0, ordered.lastIndex)].path
    }

    // A4/A5: a right press on a file row selects it and keeps its check box; a group row leaves
    // the selection as it was. Jump to Source is A7's openCommitRow, which F4 also runs.
    fun openMenu(row: CommitRow?, position: Offset) {
        if (row != null) window.selectedPath = row.path
        openContextMenu(
            state,
            ActionGroups.CHANGES_VIEW_POPUP_MENU,
            KeyContext.COMMIT_WINDOW,
            position,
            mapOf(
                ActionId.EDIT_SOURCE to ComponentAction {
                    window.selectedRow()?.let { openCommitRow(state, session, it) }
                },
            ),
        )
    }

    Column(
        modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else {
                when {
                    event.key == Key.Escape -> {
                        state.focusSelectedEditor()
                        true
                    }
                    event.key == Key.Tab -> {
                        runCatching { (if (listFocused) messageFocus else listFocus).requestFocus() }
                        true
                    }
                    event.isMetaPressed && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                        commitFromWindow(state)
                        true
                    }
                    listFocused && event.key == Key.Spacebar -> {
                        ordered.getOrNull(index)?.let(window.inclusion::toggle)
                        true
                    }
                    listFocused && event.key == Key.DirectionDown -> {
                        move(1)
                        true
                    }
                    listFocused && event.key == Key.DirectionUp -> {
                        move(-1)
                        true
                    }
                    listFocused && (event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.F4) -> {
                        ordered.getOrNull(index)?.let { openCommitRow(state, session, it) }
                        true
                    }
                    else -> false
                }
            }
        },
    ) {
        Row(Modifier.fillMaxSize()) {
            FileList(
                window = window,
                groups = CommitModel.groups(rows),
                selectedIndex = index,
                selectedPath = selectedPath,
                root = root,
                listFocus = listFocus,
                onListFocus = { focused ->
                    listFocused = focused
                    if (focused) lastPart = CommitFocusPart.LIST
                },
                onSelect = { window.selectedPath = it.path },
                onToggle = { window.inclusion.toggle(it) },
                onOpen = { openCommitRow(state, session, it) },
                onRowContextMenu = { row, position -> openMenu(row, position) },
                onGroupContextMenu = { position -> openMenu(null, position) },
                modifier = Modifier.weight(0.55f).fillMaxHeight(),
            )
            Column(
                Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .padding(
                        start = DesignTokens.space12,
                        top = DesignTokens.space12,
                        end = DesignTokens.space12,
                        bottom = DesignTokens.space12,
                    ),
            ) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (window.message.isEmpty()) {
                        Text(
                            "Commit message",
                            color = DesignTokens.muted,
                            fontSize = 15.sp,
                            fontFamily = DesignTokens.uiFont,
                            modifier = Modifier.padding(DesignTokens.space4),
                        )
                    }
                    BasicTextField(
                        value = window.message,
                        onValueChange = { window.message = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(DesignTokens.space4)
                            .testTag(COMMIT_MESSAGE_TAG)
                            .focusRequester(messageFocus)
                            .onFocusChanged { focus -> if (focus.isFocused) lastPart = CommitFocusPart.MESSAGE },
                        textStyle = DesignTokens.chromeTextStyle.merge(
                            TextStyle(
                                color = DesignTokens.text,
                                fontSize = 15.sp,
                                fontFamily = DesignTokens.uiFont,
                            ),
                        ),
                        cursorBrush = SolidColor(DesignTokens.accent),
                    )
                }
                AmendCheckbox(checked = amending, onToggle = { toggleAmend(state) })
                window.error?.let { error ->
                    Text(
                        error,
                        color = DesignTokens.neonPinkOnGlass,
                        fontSize = DesignTokens.smallTextSize,
                        fontFamily = DesignTokens.uiFont,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = DesignTokens.space4)
                            .testTag(COMMIT_ERROR_TAG),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = DesignTokens.space8),
                    horizontalArrangement = Arrangement.End,
                ) {
                    CommitCapsule(
                        enabled = enabled,
                        checked = checked,
                        total = rows.size,
                        onClick = { commitFromWindow(state) },
                        label = if (amending) "Amend Commit" else "Commit",
                    )
                    Spacer(Modifier.width(DesignTokens.space8))
                    CommitCapsule(
                        enabled = enabled,
                        checked = checked,
                        total = rows.size,
                        onClick = { commitFromWindow(state, push = true) },
                        label = if (amending) "Amend Commit and Push…" else "Commit and Push…",
                        tag = COMMIT_AND_PUSH_CAPSULE_TAG,
                        showCount = false,
                    )
                }
            }
        }
    }
}

private fun openCommitRow(state: AppState, session: ProjectSession, row: CommitRow) {
    session.editors.openFile(VFile(row.path))
    state.focusSelectedEditor()
}

@Composable
private fun FileList(
    window: CommitWindowState,
    groups: List<CommitGroup>,
    selectedIndex: Int,
    selectedPath: Path?,
    root: Path,
    listFocus: FocusRequester,
    onListFocus: (Boolean) -> Unit,
    onSelect: (CommitRow) -> Unit,
    onToggle: (CommitRow) -> Unit,
    onOpen: (CommitRow) -> Unit,
    onRowContextMenu: (CommitRow, Offset) -> Unit,
    onGroupContextMenu: (Offset) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedPath, groups) {
        val target = selectedPath ?: return@LaunchedEffect
        val item = itemIndexOf(groups, target)
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == item }) listState.scrollToItem(item)
    }
    Box(modifier) {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .focusRequester(listFocus)
                .onFocusChanged { focus -> onListFocus(focus.isFocused) }
                .focusable()
                .testTag(COMMIT_LIST_TAG),
            state = listState,
        ) {
            var offset = 0
            for (group in groups) {
                item(key = "group-${group.title}") {
                    GroupRow("${group.title} (${group.rows.size})") { position ->
                        onGroupContextMenu(position)
                    }
                }
                val groupOffset = offset
                itemsIndexed(group.rows, key = { _, row -> row.path.toString() }) { position, row ->
                    FileRow(
                        row = row,
                        selected = selectedIndex == groupOffset + position,
                        checked = window.inclusion.isChecked(row),
                        root = root,
                        onSelect = {
                            onSelect(row)
                            runCatching { listFocus.requestFocus() }
                        },
                        onToggle = {
                            onSelect(row)
                            onToggle(row)
                            runCatching { listFocus.requestFocus() }
                        },
                        onOpen = { onOpen(row) },
                        onRightClick = { pointer ->
                            onSelect(row)
                            runCatching { listFocus.requestFocus() }
                            onRowContextMenu(row, pointer)
                        },
                    )
                }
                offset += group.rows.size
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = DesignTokens.scrollbarStyle,
        )
    }
}

private fun itemIndexOf(groups: List<CommitGroup>, path: Path): Int {
    var index = 0
    for (group in groups) {
        index++
        val position = group.rows.indexOfFirst { it.path == path }
        if (position >= 0) return index + position
        index += group.rows.size
    }
    return 0
}

@Composable
private fun GroupRow(title: String, onRightClick: (Offset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupGroupHeight)
            .contextMenuRequest(onRightClick)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = DesignTokens.muted,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FileRow(
    row: CommitRow,
    selected: Boolean,
    checked: Boolean,
    root: Path,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onRightClick: (Offset) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            // A5: the right press is consumed before the tap and check-box handlers see it.
            .contextMenuRequest(onRightClick)
            .pointerInput(row) { detectTapGestures(onPress = { onSelect() }, onDoubleTap = { onOpen() }) }
            .padding(start = DesignTokens.space8, end = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .pointerInput(row) { detectTapGestures { onToggle() } },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (checked) "✓" else "",
                color = if (selected) DesignTokens.selectedText else DesignTokens.accent2,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
        Text(
            row.letter.toString(),
            color = letterColor(row.kind),
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.codeFont,
            modifier = Modifier.width(18.dp),
        )
        Text(
            row.path.fileName?.toString() ?: row.path.toString(),
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val folder = folderOf(row.path, root)
        if (folder != null) {
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                folder,
                color = DesignTokens.muted,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (row.unsaved) {
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                "unsaved",
                color = DesignTokens.muted,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
    }
}

@Composable
private fun AmendCheckbox(checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .clickable(onClick = onToggle)
            .testTag(COMMIT_AMEND_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (checked) "✓" else "",
            color = DesignTokens.accent2,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            "Amend",
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

internal fun folderOf(path: Path, root: Path): String? {
    val parent = path.parent ?: return null
    val normalizedRoot = root.toAbsolutePath().normalize()
    val relative = if (parent.startsWith(normalizedRoot)) {
        normalizedRoot.relativize(parent).toString()
    } else {
        parent.toString()
    }
    return relative.ifEmpty { null }
}

internal fun letterColor(kind: ChangeKind): Color = when (kind) {
    ChangeKind.ADDED, ChangeKind.RENAMED -> DesignTokens.vcsAdded
    ChangeKind.DELETED, ChangeKind.UNTRACKED, ChangeKind.CONFLICTED -> DesignTokens.vcsDeleted
    ChangeKind.MODIFIED -> DesignTokens.vcsModified
}

@Composable
internal fun CommitCapsule(
    enabled: Boolean,
    checked: Int,
    total: Int,
    onClick: () -> Unit,
    label: String = "Commit",
    tag: String = COMMIT_CAPSULE_TAG,
    showCount: Boolean = true,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(DesignTokens.capsuleHeight)
            .capsuleGlass(shape)
            .nativeGlass(tag, shape, tint = DesignTokens.nativeGlassTint)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            Text(
                label,
                color = if (enabled) DesignTokens.accent2 else DesignTokens.muted,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                fontWeight = FontWeight.SemiBold,
            )
            if (showCount) {
                Spacer(Modifier.width(DesignTokens.space8))
                Text(
                    "$checked of $total",
                    color = if (enabled) DesignTokens.text else DesignTokens.muted,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
            }
        }
    }
}
