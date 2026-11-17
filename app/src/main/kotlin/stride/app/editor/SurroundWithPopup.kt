package stride.app.editor

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow

const val SURROUND_POPUP_TAG: String = "surround-popup"
const val SURROUND_SELECTED_ROW_TAG: String = "surround-selected-row"

private val surroundPopupWidth = 320.dp
private const val SHORTCUT_CHARS = "1234567890abcdefghijklmnopqrstuvwxyz"

/** Each template's leading key: `1`…`9`, `0`, then the letters of [SHORTCUT_CHARS]. */
private fun surroundShortcut(index: Int): Char? = SHORTCUT_CHARS.getOrNull(index)

private val shortcutKeys: Map<Key, Int> = buildMap {
    val keys = listOf(
        Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine, Key.Zero,
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    keys.forEachIndexed { index, key -> put(key, index) }
}

/** Surround With: ↑↓ moves, Enter surrounds with the selected template, a shortcut key applies one. */
@Composable
fun SurroundWithPopup(
    request: SurroundRequest,
    onChoose: (SurroundTemplate) -> Unit,
    onClose: () -> Unit,
) {
    var selectedIndex by remember(request) { mutableStateOf(0) }
    val templates = request.templates
    val index = selectedIndex.coerceIn(0, (templates.size - 1).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(surroundPopupWidth)
                .popupPanel("popup-surround", shape)
                .then(contentFade)
                .focusRequester(focusRequester)
                .focusable()
                .testTag(SURROUND_POPUP_TAG)
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
                            event.key == Key.DirectionDown -> {
                                if (templates.isNotEmpty()) {
                                    selectedIndex = (index + 1).coerceAtMost(templates.lastIndex)
                                }
                                true
                            }
                            event.key == Key.DirectionUp -> {
                                if (templates.isNotEmpty()) {
                                    selectedIndex = (index - 1).coerceAtLeast(0)
                                }
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                templates.getOrNull(index)?.let(onChoose)
                                true
                            }
                            else -> {
                                val modified = event.isMetaPressed || event.isCtrlPressed || event.isAltPressed
                                val shortcut = if (modified) null else shortcutKeys[event.key]
                                val template = shortcut?.let { templates.getOrNull(it) }
                                if (template != null) {
                                    onChoose(template)
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Text(
                    "Surround With",
                    color = DesignTokens.textOnBusyGlass,
                    fontSize = DesignTokens.labelTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(
                        horizontal = DesignTokens.space16,
                        vertical = DesignTokens.space8,
                    ),
                )
                for ((row, template) in templates.withIndex()) {
                    SurroundRow(
                        shortcut = surroundShortcut(row),
                        template = template,
                        selected = row == index,
                        onClick = { onChoose(template) },
                    )
                }
                PopupHintBar("↑↓ Navigate", "↵ Surround", "Esc Close")
            }
        }
    }
}

@Composable
private fun SurroundRow(
    shortcut: Char?,
    template: SurroundTemplate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .then(if (selected) Modifier.testTag(SURROUND_SELECTED_ROW_TAG) else Modifier)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            shortcut?.toString().orEmpty(),
            color = if (selected) DesignTokens.selectedText else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            template.label,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}
