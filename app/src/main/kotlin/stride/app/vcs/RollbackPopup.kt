package stride.app.vcs

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.nio.file.Path
import stride.app.AppState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow

/**
 * The rollback confirmation's contents: V13's rows, its own checked set and the row ↑/↓ walk
 * from. The checked set starts with the row the Commit window or the editor had selected.
 */
data class RollbackRequest(
    val rows: List<CommitRow>,
    val checked: Set<Path> = emptySet(),
    val selected: Int = 0,
) {
    fun isChecked(row: CommitRow): Boolean = row.path in checked
}

internal const val ROLLBACK_POPUP_KEY: String = "popup-rollback"
internal const val ROLLBACK_POPUP_TAG: String = "rollback-popup"
internal const val ROLLBACK_CAPSULE_TAG: String = "rollback-capsule"

private val rollbackWidth = 560.dp
private val rollbackListMaxHeight = 320.dp

/**
 * The rollback confirmation: V13's rows with a checkbox, the `Rollback` capsule and the warning
 * that only open editors can undo it. ↑/↓ move, Space toggles, `↩` rolls back the checked rows,
 * Esc cancels; a click outside cancels too.
 */
@Composable
fun RollbackPopup(request: RollbackRequest, state: AppState, onClose: () -> Unit) {
    val session = state.session
    val root = session?.let { realProjectRoot(it.project.root) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    var fired by remember { mutableStateOf(false) }
    val checked = request.checked.size
    val total = request.rows.size
    val enabled = session?.git?.busy == null && checked > 0
    val index = request.selected.coerceIn(0, request.rows.lastIndex.coerceAtLeast(0))

    fun move(step: Int) {
        val current = state.rollbackRequest ?: return
        if (current.rows.isEmpty()) return
        state.rollbackRequest = current.copy(
            selected = (index + step).coerceIn(0, current.rows.lastIndex),
        )
    }

    fun toggle(rowIndex: Int) {
        val current = state.rollbackRequest ?: return
        val row = current.rows.getOrNull(rowIndex) ?: return
        // Path is Iterable<Path>, so + and - take the set as an operand to keep the element whole.
        val next = if (row.path in current.checked) {
            current.checked - setOf(row.path)
        } else {
            current.checked + setOf(row.path)
        }
        state.rollbackRequest = current.copy(checked = next, selected = rowIndex)
    }

    fun confirm() {
        if (fired) return
        val current = state.rollbackRequest ?: return
        if (current.checked.isEmpty()) return
        fired = true
        onClose()
        runRollback(state, current)
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
                .width(rollbackWidth)
                .popupPanel(ROLLBACK_POPUP_KEY, shape)
                .focusRequester(focusRequester)
                .focusable()
                .testTag(ROLLBACK_POPUP_TAG)
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
                                move(1)
                                true
                            }
                            Key.DirectionUp -> {
                                move(-1)
                                true
                            }
                            Key.Spacebar -> {
                                toggle(index)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                confirm()
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).padding(horizontal = DesignTokens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Rollback Changes",
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
                if (request.rows.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = rollbackListMaxHeight)
                            .padding(vertical = DesignTokens.space4),
                    ) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                            request.rows.forEachIndexed { rowIndex, row ->
                                RollbackRow(
                                    row = row,
                                    checked = request.isChecked(row),
                                    selected = rowIndex == index,
                                    root = root,
                                    onSelect = {
                                        state.rollbackRequest = state.rollbackRequest?.copy(selected = rowIndex)
                                    },
                                    onToggle = { toggle(rowIndex) },
                                )
                            }
                        }
                    }
                }
                Text(
                    "Open editors can undo this with ⌘Z; files that are not open cannot be brought back",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    CommitCapsule(
                        enabled = enabled,
                        checked = checked,
                        total = total,
                        onClick = ::confirm,
                        label = "Rollback",
                        tag = ROLLBACK_CAPSULE_TAG,
                    )
                }
            }
        }
    }
}

@Composable
private fun RollbackRow(
    row: CommitRow,
    checked: Boolean,
    selected: Boolean,
    root: Path?,
    onSelect: () -> Unit,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .pointerInput(row) { detectTapGestures(onPress = { onSelect() }) }
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
        Spacer(Modifier.width(DesignTokens.space8))
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
        val folder = root?.let { folderOf(row.path, it) }
        if (folder != null) {
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                folder,
                color = DesignTokens.textSecondaryOnGlass,
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
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
    }
}
