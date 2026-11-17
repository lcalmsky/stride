package stride.app.navigation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
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
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.scrimSurface

/** Test tags the peek UI tests find the popups by. */
const val QUICK_DOC_POPUP_TAG: String = "quick-doc-popup"
const val QUICK_DEFINITION_POPUP_TAG: String = "quick-definition-popup"
const val PARAMETER_INFO_PANEL_TAG: String = "parameter-info-panel"

private val peekPopupWidth = 520.dp
private val peekBodyMaxHeight = 420.dp
private val quickDefinitionBodyMaxHeight = 360.dp

/** Quick Documentation: the signature, file position and doc comment of up to three candidates. */
@Composable
fun QuickDocPopup(
    candidates: List<PeekCandidate>,
    onGoTo: (PeekCandidate) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val holes = if (LocalNativeGlass.current) GlassHoles.snapshot() else emptyList()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .scrimSurface(DesignTokens.scrim.alpha * fade, holes)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(peekPopupWidth)
                .popupPanel("popup-quick-doc", shape)
                .then(contentFade)
                .focusRequester(focusRequester)
                .focusable()
                .testTag(QUICK_DOC_POPUP_TAG)
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
                            event.key == Key.F1 -> {
                                onClose()
                                true
                            }
                            event.key == Key.J && event.isCtrlPressed -> {
                                onClose()
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                candidates.firstOrNull()?.let(onGoTo)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Box(Modifier.fillMaxWidth().heightIn(max = peekBodyMaxHeight)) {
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                        candidates.forEachIndexed { index, candidate ->
                            if (index > 0) PeekDivider()
                            QuickDocEntry(candidate)
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        style = DesignTokens.scrollbarStyle,
                    )
                }
                PopupHintBar("↵ Go to", "Esc Close")
            }
        }
    }
}

@Composable
private fun QuickDocEntry(candidate: PeekCandidate) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.space4),
    ) {
        Text(
            candidate.signature,
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.codeFont,
        )
        Text(
            "${candidate.declaration.relativePath}:${candidate.declaration.line + 1}",
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
        val doc = candidate.doc
        if (doc == null) {
            Text(
                "No documentation",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        } else {
            Text(
                doc,
                color = DesignTokens.text,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
    }
}

/** Quick Definition: the definition text of the current candidate, ←→ switch between candidates. */
@Composable
fun QuickDefinitionPopup(
    candidates: List<PeekCandidate>,
    onGoTo: (PeekCandidate) -> Unit,
    onClose: () -> Unit,
) {
    var selectedIndex by remember(candidates) { mutableStateOf(0) }
    val index = selectedIndex.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))
    val candidate = candidates.getOrNull(index)
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val holes = if (LocalNativeGlass.current) GlassHoles.snapshot() else emptyList()

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .scrimSurface(DesignTokens.scrim.alpha * fade, holes)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(peekPopupWidth)
                .popupPanel("popup-quick-definition", shape)
                .then(contentFade)
                .focusRequester(focusRequester)
                .focusable()
                .testTag(QUICK_DEFINITION_POPUP_TAG)
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
                            event.key == Key.DirectionLeft -> {
                                if (candidates.size > 1) {
                                    selectedIndex = (index - 1 + candidates.size) % candidates.size
                                    true
                                } else {
                                    false
                                }
                            }
                            event.key == Key.DirectionRight -> {
                                if (candidates.size > 1) {
                                    selectedIndex = (index + 1) % candidates.size
                                    true
                                } else {
                                    false
                                }
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                candidate?.let(onGoTo)
                                true
                            }
                            event.key == Key.DirectionDown && event.isMetaPressed -> {
                                candidate?.let(onGoTo)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                Row(
                    Modifier.fillMaxWidth().padding(
                        start = DesignTokens.space16,
                        end = DesignTokens.space16,
                        top = DesignTokens.space8,
                        bottom = DesignTokens.space4,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (candidate != null) {
                        Text(
                            "${candidate.declaration.relativePath}:${candidate.declaration.line + 1}",
                            color = DesignTokens.textSecondaryOnGlass,
                            fontSize = DesignTokens.labelTextSize,
                            fontFamily = DesignTokens.uiFont,
                        )
                    }
                    if (candidates.size > 1) {
                        Spacer(Modifier.width(DesignTokens.space8))
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${index + 1}/${candidates.size}",
                            color = DesignTokens.textSecondaryOnGlass,
                            fontSize = DesignTokens.labelTextSize,
                            fontFamily = DesignTokens.uiFont,
                        )
                    }
                }
                if (candidate != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = quickDefinitionBodyMaxHeight)
                            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
                    ) {
                        val scroll = rememberScrollState()
                        Column(Modifier.fillMaxWidth().verticalScroll(scroll)) {
                            Text(
                                candidate.definition,
                                color = DesignTokens.text,
                                fontSize = DesignTokens.uiTextSize,
                                fontFamily = DesignTokens.codeFont,
                            )
                            if (candidate.definitionTruncatedLines > 0) {
                                Text(
                                    "… ${candidate.definitionTruncatedLines} more lines",
                                    color = DesignTokens.textSecondaryOnGlass,
                                    fontSize = DesignTokens.labelTextSize,
                                    fontFamily = DesignTokens.uiFont,
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(scroll),
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            style = DesignTokens.scrollbarStyle,
                        )
                    }
                }
                if (candidates.size > 1) {
                    PopupHintBar("←→ Switch", "↵ Go to", "Esc Close")
                } else {
                    PopupHintBar("↵ Go to", "Esc Close")
                }
            }
        }
    }
}

/**
 * Parameter Info: one line per overload, the parameter the caret is in drawn in the accent. The
 * panel never takes focus, so the editor keeps receiving input while it is open.
 */
@Composable
fun ParameterInfoPanel(request: ParameterInfoRequest, modifier: Modifier = Modifier) {
    val value = request.editor.value
    val active = CallText.activeIndex(value.text, request.openOffset, value.selection.start)
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    Column(
        modifier
            .widthIn(min = 120.dp, max = peekPopupWidth)
            .popupPanel("popup-parameter-info", shape)
            .then(popupContentFade(fade))
            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8)
            .testTag(PARAMETER_INFO_PANEL_TAG),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.space4),
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            for (signature in request.signatures) {
                ParameterInfoRow(CallText.parameters(signature).orEmpty(), active)
            }
        }
    }
}

@Composable
private fun ParameterInfoRow(parameters: List<String>, active: Int?) {
    val dimmed = parameters.isNotEmpty() && active != null && active >= parameters.size
    val baseColor = if (dimmed) DesignTokens.textSecondaryOnGlass else DesignTokens.text
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (parameters.isEmpty()) {
            Text(
                "<no parameters>",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.codeFont,
            )
        } else {
            parameters.forEachIndexed { index, parameter ->
                if (index > 0) {
                    Text(
                        ", ",
                        color = baseColor,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.codeFont,
                    )
                }
                val current = index == active
                Text(
                    parameter,
                    color = if (current) DesignTokens.accentOnPopupPlate else baseColor,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.codeFont,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun PeekDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.space16)
            .height(1.dp)
            .background(DesignTokens.glassBorderSide),
    )
}
