package stride.app.run

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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow

private val contextPopupWidth = 360.dp

/**
 * The ⌥↩ menu. It opens at the top of the editor rather than under the caret because the
 * caret's window coordinates are not available outside the text field's layout.
 */
@Composable
fun ContextActionsPopup(
    actions: List<ContextAction>,
    onChoose: (ContextAction) -> Unit,
    onClose: () -> Unit,
) {
    ChoicePopup(
        labels = actions.map { it.label },
        width = contextPopupWidth,
        groups = actions.map { it.group.title },
        confirmHint = if (actions.any { it.group != ContextActionGroup.RUN }) "↵ Apply" else "↵ Run",
        onChoose = { index -> actions.getOrNull(index)?.let(onChoose) },
        onClose = onClose,
    )
}

@Composable
internal fun ChoicePopup(
    labels: List<String>,
    width: Dp = contextPopupWidth,
    title: String? = null,
    secondaryLabels: List<String> = emptyList(),
    groups: List<String> = emptyList(),
    confirmHint: String = "↵ Run",
    onChoose: (Int) -> Unit,
    onClose: () -> Unit,
) {
    if (labels.isEmpty()) return
    var selectedIndex by remember(labels) { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(labels) {
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
                .width(width)
                .popupPanel("popup-context-actions", shape)
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
                                selectedIndex = (selectedIndex + 1).coerceAtMost(labels.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                onChoose(selectedIndex)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            title?.let { text ->
                Text(
                    text,
                    color = DesignTokens.textOnBusyGlass,
                    fontSize = DesignTokens.labelTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
                )
            }
            val grouped = groups.distinct().size >= 2
            for (index in labels.indices) {
                if (grouped) {
                    val group = groups.getOrNull(index)
                    if (group != null && (index == 0 || groups.getOrNull(index - 1) != group)) {
                        ChoiceGroupHeader(group)
                    }
                }
                ChoiceRow(
                    label = labels[index],
                    secondary = secondaryLabels.getOrNull(index),
                    selected = index == selectedIndex,
                    onClick = { onChoose(index) },
                )
            }
            PopupHintBar("↑↓ Navigate", confirmHint, "Esc Close")
            }
        }
    }
}

@Composable
private fun ChoiceGroupHeader(text: String) {
    Text(
        text,
        color = DesignTokens.textOnBusyGlass,
        fontSize = DesignTokens.labelTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier
            .padding(horizontal = DesignTokens.space16)
            .padding(top = DesignTokens.space8, bottom = DesignTokens.space4),
    )
}

@Composable
private fun ChoiceRow(label: String, secondary: String?, selected: Boolean, onClick: () -> Unit) {
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
        Text(
            label,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (secondary != null) {
            Text(
                secondary,
                color = DesignTokens.textOnBusyGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
