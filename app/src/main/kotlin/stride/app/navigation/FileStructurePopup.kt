package stride.app.navigation

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.glass.nativeGlass
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.scrimSurface
import stride.app.ui.theme.selectionRow
import stride.app.ui.theme.popupShadow
import stride.search.DeclarationKind

const val FILE_STRUCTURE_FIELD_TAG: String = "file-structure-query"

/** File Structure: the declarations of one file, filtered by name, Enter jumps to the line. */
@Composable
fun FileStructurePopup(
    entries: List<FileStructureEntry>,
    onChoose: (FileStructureEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedIndex by remember { mutableStateOf(0) }
    val items = remember(query, entries) { FileStructure.matching(entries, query) }
    val index = selectedIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
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
                .width(DesignTokens.popupWidth)
                .popupShadow(shape)
                .clip(shape)
                .backdropGlass(shape)
                .nativeGlass("popup-file-structure", shape, tint = DesignTokens.nativeGlassTint, hole = true, shadow = true)
                .then(contentFade)
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
                                if (items.isNotEmpty()) selectedIndex = (index + 1).coerceAtMost(items.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                if (items.isNotEmpty()) selectedIndex = (index - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                items.getOrNull(index)?.let(onChoose)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = DesignTokens.space16),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "File Structure",
                    color = DesignTokens.text,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
                Spacer(Modifier.width(DesignTokens.space16))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            "Filter declarations",
                            color = DesignTokens.textOnBusyGlass,
                            fontSize = DesignTokens.uiTextSize,
                            fontFamily = DesignTokens.uiFont,
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            selectedIndex = 0
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .testTag(FILE_STRUCTURE_FIELD_TAG),
                        singleLine = true,
                        textStyle = DesignTokens.chromeTextStyle.merge(
                            TextStyle(
                                color = DesignTokens.text,
                                fontSize = DesignTokens.uiTextSize,
                                fontFamily = DesignTokens.uiFont,
                            ),
                        ),
                        cursorBrush = SolidColor(DesignTokens.accent),
                    )
                }
            }
            if (items.isEmpty()) {
                Text(
                    "No matching declarations",
                    color = DesignTokens.textOnBusyGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(DesignTokens.popupRowHeight * items.size.coerceAtMost(DesignTokens.popupMaxRows)),
                ) {
                    LazyColumn(state = listState) {
                        itemsIndexed(items) { rowIndex, entry ->
                            StructureRow(entry, rowIndex == index) { onChoose(entry) }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        style = DesignTokens.scrollbarStyle,
                    )
                }
            }
            PopupHintBar("↑↓ Navigate", "↵ Jump", "Esc Close")
            }
        }
    }
}

@Composable
private fun StructureRow(entry: FileStructureEntry, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = DesignTokens.space16 + 12.dp * entry.depth, end = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.name,
            color = when {
                selected -> DesignTokens.selectedText
                entry.kind == DeclarationKind.CLASS -> DesignTokens.accent2
                else -> DesignTokens.text
            },
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            (entry.line + 1).toString(),
            color = DesignTokens.textOnBusyGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}
