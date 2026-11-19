package stride.app.vcs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
import stride.app.keys.shortcutText
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow
import java.nio.file.Path

/**
 * The open VCS Operations popup. [branch] is `null` on a detached HEAD and shown as `HEAD`;
 * [error] (no git) and [outsideRepository] each replace the rows with one dimmed message row,
 * and [editorRowsEnabled] stays false until the working-tree comparison has answered.
 */
class VcsPopupRequest internal constructor() {
    var branch: String? by mutableStateOf(null)
    var error: String? by mutableStateOf(null)
    var outsideRepository: Boolean by mutableStateOf(false)
    var editorRowsEnabled: Boolean by mutableStateOf(false)

    /** Row 9 stays dimmed until Rollback would list the file the popup opened on. */
    var rollbackEnabled: Boolean by mutableStateOf(false)

    internal var file: Path? = null
    internal var line: Int? = null
    internal var rollbackPath: Path? = null
    internal var rollbackFromWindow: Boolean = false
    internal var rollbackNeedsTrackedRead: Boolean = false

    /** Read before the popup takes `state.focus`; the App hands the keyboard back to it on close. */
    internal var openedFrom: KeyContext? = null
}

internal const val VCS_POPUP_KEY: String = "popup-vcs-operations"

private val vcsPopupWidth = 360.dp

/** Opens the VCS Operations popup; without a project it opens with the not-a-repository row. */
fun openVcsPopup(state: AppState): Boolean {
    val request = VcsPopupRequest()
    request.openedFrom = state.focus
    val session = state.session
    if (session == null) {
        request.outsideRepository = true
        state.vcsPopup = request
        return true
    }
    val git = session.git
    val editor = session.editors.selected as? TextFileEditor
    request.file = editor?.file?.path?.toAbsolutePath()?.normalize()
    request.line = editor?.let(::caretLine)
    val snapshot = git.snapshot
    request.branch = snapshot.branch?.branch
    request.error = snapshot.error
    request.outsideRepository = snapshot.repository == null
    val unsaved = editor?.isModified == true
    request.rollbackFromWindow = state.focus == KeyContext.COMMIT_WINDOW
    if (request.rollbackFromWindow) {
        request.rollbackEnabled = rollbackRows(commitRows(session)).isNotEmpty()
    } else {
        val lexicalRoot = session.project.root.toAbsolutePath().normalize()
        val realRoot = realProjectRoot(session.project.root)
        val path = request.file?.let { realPathOf(it, lexicalRoot, realRoot) }
        request.rollbackPath = path
        val inProject = path != null && path.startsWith(realRoot)
        request.rollbackEnabled = inProject && rollbackRows(commitRows(session)).any { it.path == path }
        request.rollbackNeedsTrackedRead = inProject && !request.rollbackEnabled &&
            (unsaved || snapshot.repository == null)
    }
    state.vcsPopup = request
    state.focus = KeyContext.CONTEXT_POPUP
    git.launch {
        if (request.error != null) return@launch
        if (request.outsideRepository) {
            // The snapshot may simply not have run yet; a repository above the project root wins.
            val fresh = git.refreshNow()
            if (fresh.repository == null) return@launch
            Snapshot.withMutableSnapshot {
                request.outsideRepository = false
                request.branch = fresh.branch?.branch
            }
        }
        val changed = editorHasChanges(git, editor)
        Snapshot.withMutableSnapshot { request.editorRowsEnabled = changed }
        val path = request.rollbackPath
        if (request.rollbackNeedsTrackedRead && path != null) {
            val listed = rollbackWouldList(session, path, unsaved)
            Snapshot.withMutableSnapshot { request.rollbackEnabled = listed }
        }
    }
    return true
}

private fun caretLine(editor: TextFileEditor): Int {
    val text = editor.value.text
    val offset = editor.value.selection.start.coerceIn(0, text.length)
    var line = 0
    for (index in 0 until offset) {
        if (text[index] == '\n') line++
    }
    return line
}

private suspend fun editorHasChanges(git: GitService, editor: TextFileEditor?): Boolean {
    val path = editor?.file?.path ?: return false
    val current = LineDiff.split(editor.value.text)
    return when (val base = git.baseText(path.toAbsolutePath().normalize())) {
        is BaseText.Unavailable -> false
        BaseText.Untracked -> current.size > 1 || current.firstOrNull().orEmpty().isNotEmpty()
        is BaseText.Text -> {
            val hunks = LineDiff.diff(LineDiff.split(base.content), current) ?: return true
            hunks.isNotEmpty()
        }
    }
}

private class VcsRow(
    val number: Int,
    val label: String,
    val shortcut: String,
    val enabled: Boolean,
    val run: () -> Unit,
)

/**
 * The VCS Operations quick list: the branch in the title and the nine numbered commands with their
 * shortcuts. A digit runs its row, ↑/↓ select, ↵ runs, Esc closes; a dimmed row does nothing.
 */
@Composable
fun VcsPopup(request: VcsPopupRequest, state: AppState, onClose: () -> Unit) {
    val keymap = remember { Keymap.forPlatform(KeyRouter.isMacOs()) }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val focusRequester = remember { FocusRequester() }
    var selected by remember(request) { mutableStateOf(0) }

    fun shortcut(action: ActionId): String = shortcutText(keymap, action)

    val rows = if (request.error != null) {
        listOf(VcsRow(0, request.error.orEmpty(), "", false) {})
    } else if (request.outsideRepository) {
        listOf(VcsRow(0, "Not a Git repository", "", false) {})
    } else {
        listOf(
            VcsRow(1, "Commit…", shortcut(ActionId.COMMIT), true) { openCommit(state) },
            VcsRow(2, "Push…", shortcut(ActionId.PUSH), true) { openPush(state) },
            VcsRow(3, "Rollback Lines", shortcut(ActionId.ROLLBACK_LINES), request.editorRowsEnabled) {
                rollbackLines(state)
            },
            VcsRow(4, "Show Diff", shortcut(ActionId.SHOW_DIFF), request.editorRowsEnabled) {
                request.file?.let { openWorkingTreeDiff(state, it, request.line) }
            },
            VcsRow(5, "Commit Window", shortcut(ActionId.ACTIVATE_COMMIT_TOOL_WINDOW), true) {
                state.toggleToolWindow("commit", KeyContext.COMMIT_WINDOW)
            },
            VcsRow(6, "Git Log", shortcut(ActionId.ACTIVATE_GIT_TOOL_WINDOW), true) {
                state.toggleToolWindow("git", KeyContext.LIST_WINDOW)
            },
            VcsRow(7, "Update Project…", shortcut(ActionId.UPDATE_PROJECT), true) { openUpdate(state) },
            VcsRow(8, "Fetch", "", true) { fetchFromVcs(state) },
            VcsRow(9, "Rollback…", shortcut(ActionId.ROLLBACK_CHANGES), request.rollbackEnabled) {
                openRollback(state)
            },
        )
    }
    val index = selected.coerceIn(0, rows.lastIndex.coerceAtLeast(0))

    fun runRow(row: VcsRow?) {
        if (row == null || !row.enabled) return
        onClose()
        row.run()
    }

    fun runDigit(digit: Int) {
        runRow(rows.firstOrNull { it.number == digit })
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(vcsPopupWidth)
                .popupPanel(VCS_POPUP_KEY, shape)
                .then(contentFade)
                .focusRequester(focusRequester)
                .focusable()
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.Escape -> {
                                onClose()
                                true
                            }
                            Key.DirectionDown -> {
                                selected = (index + 1).coerceAtMost(rows.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                selected = (index - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                runRow(rows.getOrNull(index))
                                true
                            }
                            Key.One, Key.NumPad1 -> { runDigit(1); true }
                            Key.Two, Key.NumPad2 -> { runDigit(2); true }
                            Key.Three, Key.NumPad3 -> { runDigit(3); true }
                            Key.Four, Key.NumPad4 -> { runDigit(4); true }
                            Key.Five, Key.NumPad5 -> { runDigit(5); true }
                            Key.Six, Key.NumPad6 -> { runDigit(6); true }
                            Key.Seven, Key.NumPad7 -> { runDigit(7); true }
                            Key.Eight, Key.NumPad8 -> { runDigit(8); true }
                            Key.Nine, Key.NumPad9 -> { runDigit(9); true }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                VcsHeader(request.branch)
                rows.forEachIndexed { rowIndex, row ->
                    VcsPopupRow(
                        row = row,
                        selected = rowIndex == index,
                        onClick = { runRow(row) },
                    )
                }
                PopupHintBar("↑↓ Navigate", "↵ Run", "Esc Close")
            }
        }
    }
}

@Composable
private fun VcsHeader(branch: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Git",
            color = DesignTokens.textOnBusyGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
        Text(
            branch ?: "HEAD",
            color = DesignTokens.accentOnPopupPlate,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = DesignTokens.space8),
        )
    }
}

@Composable
private fun VcsPopupRow(row: VcsRow, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
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
        if (row.number > 0) {
            Text(
                row.number.toString(),
                color = DesignTokens.textOnBusyGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                modifier = Modifier.width(DesignTokens.space16),
            )
        } else {
            Box(Modifier.width(DesignTokens.space16))
        }
        Text(
            row.label,
            color = if (row.enabled) DesignTokens.text else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.shortcut.isNotEmpty()) {
            Text(
                row.shortcut,
                color = DesignTokens.textOnBusyGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
            )
        }
    }
}
