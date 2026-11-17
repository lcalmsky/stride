package stride.app.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.ui.theme.DesignTokens

/** The hit's line with the matched text struck through, followed by its replacement. */
fun replacePreviewLine(hit: FindInPathHit, replacement: String, replacementColor: Color): AnnotatedString =
    buildAnnotatedString {
        append(hit.lineText)
        val from = (hit.column - 1).coerceIn(0, hit.lineText.length)
        val to = (from + (hit.end - hit.start)).coerceIn(from, hit.lineText.length)
        if (to > from) {
            addStyle(SpanStyle(color = DesignTokens.neonAmber, textDecoration = TextDecoration.LineThrough), from, to)
        }
        append(" → ")
        pushStyle(SpanStyle(color = replacementColor))
        append(replacement)
        pop()
    }

/**
 * The inline Replace All confirmation: Enter applies, Esc cancels the confirmation only. While it
 * is shown it takes the keyboard and swallows every other key.
 */
@Composable
fun ReplaceConfirmationRow(
    text: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    showButtons: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onConfirm()
                            true
                        }
                        Key.Escape -> {
                            onCancel()
                            true
                        }
                        else -> true
                    }
                }
            }
            .focusable(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = DesignTokens.text,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = DesignTokens.space16),
        )
        if (showButtons) {
            ReplaceTextButton("Replace", onConfirm)
            ReplaceTextButton("Cancel", onCancel)
        }
        Spacer(Modifier.width(DesignTokens.space16))
    }
}

/** A header text button in the same clickable-label style the popups use for their toggles. */
@Composable
fun ReplaceTextButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Text(
        label,
        color = if (enabled) DesignTokens.accent2 else DesignTokens.muted,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DesignTokens.space8, vertical = 2.dp),
    )
}
