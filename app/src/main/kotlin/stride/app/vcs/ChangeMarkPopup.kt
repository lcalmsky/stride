package stride.app.vcs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel

/** The gutter mark whose popup is open, with the hunk's own base lines. */
class ChangeMarkPopupRequest(
    val editor: TextFileEditor,
    val hunk: LineHunk,
    val baseLines: List<String>,
)

const val CHANGE_MARK_POPUP_TAG: String = "change-mark-popup"

private val changeMarkPopupWidth = 420.dp
private val changeMarkPopupMaxHeight = 360.dp
private const val MAX_BASE_LINES = 20

/**
 * The gutter mark's popup: the hunk's changed line span, its base lines and the two actions.
 * Esc closes it, ⌥⌘Z rolls the hunk back.
 */
@Composable
fun ChangeMarkPopup(request: ChangeMarkPopupRequest, state: AppState, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
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
                .width(changeMarkPopupWidth)
                .popupPanel("popup-change-mark", shape)
                .focusRequester(focusRequester)
                .focusable()
                .testTag(CHANGE_MARK_POPUP_TAG)
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when {
                            event.key == Key.Escape -> {
                                onClose()
                                true
                            }
                            event.key == Key.Z && event.isAltPressed && event.isMetaPressed &&
                                !event.isShiftPressed && !event.isCtrlPressed -> {
                                rollbackHunk(state, request.editor, request.hunk)
                                onClose()
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                    verticalArrangement = Arrangement.spacedBy(DesignTokens.space4),
                ) {
                    Text(
                        headerLine(request.hunk),
                        color = DesignTokens.textOnBusyGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                    if (request.hunk.newCount > 0) {
                        CountLine("${request.hunk.newCount} lines added", DesignTokens.vcsAdded)
                    }
                    if (request.hunk.oldCount > 0) {
                        CountLine("${request.hunk.oldCount} lines deleted", DesignTokens.vcsDeleted)
                    }
                    if (request.baseLines.isNotEmpty()) {
                        BaseLines(request.baseLines)
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
                    horizontalArrangement = Arrangement.spacedBy(DesignTokens.space8),
                ) {
                    PopupAction("Rollback ⌥⌘Z") {
                        rollbackHunk(state, request.editor, request.hunk)
                        onClose()
                    }
                    PopupAction("Show Diff") {
                        openWorkingTreeDiff(state, request.editor.file.path, request.hunk.newStart)
                        onClose()
                    }
                }
                PopupHintBar("Esc Close")
            }
        }
    }
}

private fun headerLine(hunk: LineHunk): String {
    val first = hunk.newStart + 1
    val last = hunk.newStart + hunk.newCount.coerceAtLeast(1)
    return "Lines $first–$last changed"
}

@Composable
private fun CountLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        color = color,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
    )
}

@Composable
private fun BaseLines(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = changeMarkPopupMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        val shown = lines.take(MAX_BASE_LINES)
        for (line in shown) {
            Text(
                line,
                color = DesignTokens.text,
                fontSize = DesignTokens.codeTextSize,
                fontFamily = DesignTokens.codeFont,
            )
        }
        if (lines.size > MAX_BASE_LINES) {
            Text(
                "…",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.codeTextSize,
                fontFamily = DesignTokens.codeFont,
            )
        }
    }
}

@Composable
private fun PopupAction(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(DesignTokens.radiusSmall)
    Text(
        label,
        color = DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier
            .background(DesignTokens.glassFill, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.space8, vertical = DesignTokens.space4),
    )
}
