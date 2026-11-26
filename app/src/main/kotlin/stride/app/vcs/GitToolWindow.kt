package stride.app.vcs

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import stride.app.AppState
import stride.app.actions.ActionGroups
import stride.app.actions.ComponentAction
import stride.app.actions.contextMenuRequest
import stride.app.actions.openContextMenu
import stride.app.glass.nativeGlass
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.rememberSelectionAlpha
import stride.app.ui.theme.selectionRow
import stride.app.ui.toolwindow.ToolWindowEmptyState
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration as TimeDuration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Test tag of the focusable Log list. */
internal const val GIT_LOG_TAG: String = "git-log"

internal const val MAX_DIFF_FILES: Int = 50

internal const val MAX_PARALLEL_FILE_LOADS: Int = 8

private enum class GitTab(val title: String) { LOG("Log"), CONSOLE("Console") }

/**
 * The Git tool window: the last commits of HEAD and, in the Console tab, every write and every
 * failed read. The log loads off the UI thread when the window appears and again when HEAD moves.
 */
@Composable
fun GitToolWindowContent(state: AppState, modifier: Modifier) {
    val session = state.session
    val focusRequests = state.toolWindows.focusRequests
    fun handBackFocus() {
        state.focusSelectedEditor()
    }
    if (session == null) {
        ToolWindowEmptyState("Not a Git repository", modifier, focusRequests, ::handBackFocus)
        return
    }
    val git = session.git
    var undecided by remember(git) {
        mutableStateOf(git.snapshot.repository == null && git.snapshot.error == null)
    }
    LaunchedEffect(git) {
        if (undecided) {
            withContext(Dispatchers.IO) { git.refreshNow() }
            undecided = false
        }
    }
    if (undecided) {
        Box(modifier)
        return
    }
    val snapshot = git.snapshot
    if (snapshot.repository == null) {
        val root = session.project.root.toAbsolutePath().normalize()
        val message = snapshot.error ?: "Not a Git repository: $root"
        ToolWindowEmptyState(message, modifier, focusRequests, ::handBackFocus)
        return
    }
    var tab by remember { mutableStateOf(GitTab.LOG) }
    Column(modifier) {
        GitTabs(tab) { tab = it }
        when (tab) {
            GitTab.LOG -> GitLog(git, state, Modifier.weight(1f).fillMaxWidth(), focusRequests, ::handBackFocus)
            GitTab.CONSOLE -> GitConsole(
                entries = git.console,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                focusRequests = focusRequests,
                onEscape = ::handBackFocus,
            )
        }
    }
}

@Composable
private fun GitTabs(selected: GitTab, onSelect: (GitTab) -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(DesignTokens.capsuleHeight)
            .capsuleGlass(shape)
            .nativeGlass("git-tabs", shape, tint = DesignTokens.nativeGlassTint),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            GitTab.entries.forEach { tab ->
                GitTabButton(tab.title, tab == selected) { onSelect(tab) }
            }
        }
    }
}

@Composable
private fun GitTabButton(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .fillMaxHeight()
            .glassCapsule(
                shape = shape,
                insetX = 2.dp,
                insetY = 3.dp,
                selectionAlpha = rememberSelectionAlpha(active),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (active) DesignTokens.selectedText else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
        )
    }
}

@Composable
private fun GitLog(
    git: GitService,
    state: AppState,
    modifier: Modifier,
    focusRequests: Int,
    onEscape: () -> Unit,
) {
    var entries by remember { mutableStateOf<List<LogEntry>?>(null) }
    val head = git.snapshot.branch?.head
    LaunchedEffect(head, git.refsVersion) {
        entries = git.log()
    }
    val loaded = entries
    if (loaded == null) {
        Box(modifier)
        return
    }
    if (loaded.isEmpty()) {
        ToolWindowEmptyState("No commits yet", modifier, focusRequests, onEscape)
        return
    }
    val root = state.session?.project?.root
    GitLogList(
        entries = loaded,
        root = root,
        modifier = modifier,
        focusRequests = focusRequests,
        onEscape = onEscape,
        onOpen = { entry ->
            git.launch { openCommitDiff(git, state, entry, root) }
        },
        // A4/A7: Show Diff is the component action openCommitDiff, which ↩ and a double click run.
        onContextMenu = { entry, position ->
            openContextMenu(
                state,
                ActionGroups.VCS_LOG_CONTEXT_MENU,
                KeyContext.LIST_WINDOW,
                position,
                mapOf(
                    ActionId.SHOW_DIFF to ComponentAction {
                        git.launch { openCommitDiff(git, state, entry, root) }
                    },
                ),
            )
        },
    )
}

/** Loads a commit's files off the UI thread and opens them in the diff popup. */
private suspend fun openCommitDiff(git: GitService, state: AppState, entry: LogEntry, root: Path?) {
    val changes = git.changedFiles(entry.id)
    if (changes.isEmpty()) {
        Snapshot.withMutableSnapshot { state.statusMessage = "No changes in ${entry.shortId}" }
        return
    }
    val shown = changes.take(MAX_DIFF_FILES)
    val gate = Semaphore(MAX_PARALLEL_FILE_LOADS)
    val files = coroutineScope {
        shown.map { change ->
            async {
                gate.withPermit {
                    DiffFile(
                        path = change.path,
                        displayPath = displayPathOf(root, change),
                        old = git.fileAt("${entry.id}^", change.originalPath ?: change.path),
                        new = git.fileAt(entry.id, change.path),
                        openable = false,
                    )
                }
            }
        }.awaitAll()
    }
    val request = DiffRequest(
        title = "${entry.shortId}  ${entry.subject}",
        files = files,
        moreFiles = changes.size - files.size,
    )
    Snapshot.withMutableSnapshot { state.diffRequest = request }
}

/** A rename reads `<old> → <new>`, because the file row is the only place the pair shows. */
private fun displayPathOf(root: Path?, change: FileChange): String {
    val new = displayPathOf(root, change.path)
    val original = change.originalPath ?: return new
    return "${displayPathOf(root, original)} → $new"
}

private fun displayPathOf(root: Path?, path: Path): String {
    if (root == null) return path.fileName?.toString().orEmpty()
    val normalized = path.toAbsolutePath().normalize()
    return if (normalized.startsWith(root)) {
        root.relativize(normalized).toString().replace('\\', '/')
    } else {
        path.fileName?.toString().orEmpty()
    }
}

@Composable
private fun GitLogList(
    entries: List<LogEntry>,
    root: Path?,
    modifier: Modifier,
    focusRequests: Int,
    onEscape: () -> Unit,
    onOpen: (LogEntry) -> Unit,
    onContextMenu: (LogEntry, Offset) -> Unit,
) {
    var selected by remember(entries) { mutableStateOf(0) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val now = remember(entries) { Instant.now() }
    val index = selected.coerceIn(0, entries.lastIndex)

    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(index) {
        val target = index.coerceIn(0, entries.lastIndex)
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty() || visible.any { it.index == target }) return@LaunchedEffect
        if (target < visible.first().index) {
            listState.scrollToItem(target)
        } else {
            val shift = visible.last().index - visible.first().index
            listState.scrollToItem((target - shift).coerceAtLeast(0))
        }
    }

    Column(
        modifier
            .focusRequester(focusRequester)
            .testTag(GIT_LOG_TAG)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Escape -> {
                            onEscape()
                            true
                        }
                        Key.DirectionDown -> {
                            selected = (index + 1).coerceAtMost(entries.lastIndex)
                            true
                        }
                        Key.DirectionUp -> {
                            selected = (index - 1).coerceAtLeast(0)
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.F4 -> {
                            entries.getOrNull(index)?.let(onOpen)
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusable(),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(entries) { rowIndex, entry ->
                    LogRow(
                        entry = entry,
                        now = now,
                        selected = rowIndex == index,
                        onSelect = { selected = rowIndex },
                        onOpen = {
                            selected = rowIndex
                            onOpen(entry)
                        },
                        onRightClick = { position ->
                            selected = rowIndex
                            onContextMenu(entry, position)
                        },
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
}

@Composable
private fun LogRow(
    entry: LogEntry,
    now: Instant,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onRightClick: (Offset) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.treeRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            // A5: a right press selects the commit and never opens its diff.
            .contextMenuRequest(onRightClick)
            .pointerInput(entry) {
                detectTapGestures(onTap = { onSelect() }, onDoubleTap = { onOpen() })
            }
            .padding(start = DesignTokens.space12, end = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.shortId,
            color = DesignTokens.muted,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.codeFont,
            maxLines = 1,
        )
        entry.refs.forEach { ref ->
            Spacer(Modifier.width(DesignTokens.space8))
            RefCapsule(ref)
        }
        Spacer(Modifier.width(DesignTokens.space12))
        Text(
            entry.subject,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DesignTokens.space12))
        Text(
            entry.author,
            color = DesignTokens.muted,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DesignTokens.space12))
        Text(
            relativeDate(entry.time, now),
            color = DesignTokens.muted,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
        )
    }
}

@Composable
private fun RefCapsule(ref: String) {
    val head = ref.startsWith("HEAD")
    Row(
        Modifier
            .background(
                if (head) DesignTokens.accent.copy(alpha = 0.18f) else DesignTokens.glassFill,
                RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = DesignTokens.space8, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            ref,
            color = if (head) DesignTokens.accent2 else DesignTokens.muted,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
        )
    }
}

@Composable
private fun GitConsole(
    entries: List<GitConsoleEntry>,
    modifier: Modifier,
    focusRequests: Int,
    onEscape: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(focusRequests) {
        if (focusRequests > 0) {
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Column(
        modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Escape -> {
                            onEscape()
                            true
                        }
                        Key.DirectionDown -> {
                            scope.launch { listState.scrollBy(48f) }
                            true
                        }
                        Key.DirectionUp -> {
                            scope.launch { listState.scrollBy(-48f) }
                            true
                        }
                        Key.PageDown -> {
                            scope.launch { listState.scrollBy(480f) }
                            true
                        }
                        Key.PageUp -> {
                            scope.launch { listState.scrollBy(-480f) }
                            true
                        }
                        else -> false
                    }
                }
            }
            .focusable(),
    ) {
        if (entries.isEmpty()) {
            ToolWindowEmptyState("No Git commands yet", Modifier.weight(1f).fillMaxWidth(), onEscape = onEscape)
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(entries) { _, entry -> ConsoleEntry(entry) }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    style = DesignTokens.scrollbarStyle,
                )
            }
        }
    }
}

@Composable
private fun ConsoleEntry(entry: GitConsoleEntry) {
    val failed = entry.exitCode != 0
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.space12, vertical = DesignTokens.space8),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$ ${entry.command}",
                color = if (failed) DesignTokens.neonPinkOnGlass else DesignTokens.text,
                fontSize = DesignTokens.codeTextSize,
                fontFamily = DesignTokens.codeFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(DesignTokens.space12))
            Text(
                "exit ${entry.exitCode} · ${formatDuration(entry.duration)}",
                color = if (failed) DesignTokens.neonPinkOnGlass else DesignTokens.muted,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
            )
        }
        if (entry.output.isNotBlank()) {
            Text(
                entry.output.trimEnd(),
                color = DesignTokens.muted,
                fontSize = DesignTokens.codeTextSize,
                fontFamily = DesignTokens.codeFont,
            )
        }
    }
}

/** `just now`, `5 minutes ago`, `3 days ago`, and the date once it is older than 30 days. */
internal fun relativeDate(time: Instant, now: Instant): String {
    val seconds = Duration.between(time, now).seconds
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> ago(seconds / 60, "minute")
        seconds < 86_400 -> ago(seconds / 3_600, "hour")
        seconds < 30L * 86_400 -> ago(seconds / 86_400, "day")
        else -> DateTimeFormatter.ISO_LOCAL_DATE.format(time.atZone(ZoneId.systemDefault()).toLocalDate())
    }
}

private fun ago(count: Long, unit: String): String =
    if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"

internal fun formatDuration(duration: TimeDuration): String =
    if (duration.inWholeSeconds < 1) {
        "${duration.inWholeMilliseconds} ms"
    } else {
        String.format(Locale.ROOT, "%.1f s", duration.inWholeMilliseconds / 1000.0)
    }
