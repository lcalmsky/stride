package stride.app.search

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.ui.FileTypeIcon
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.selectionRow
import stride.core.vfs.VFile
import java.nio.file.Path

@Composable
fun RecentFilesPopup(
    files: List<VFile>,
    projectRoot: Path,
    onChoose: (VFile) -> Unit,
    onClose: () -> Unit,
) {
    val initialIndex = if (files.size > 1) 1 else 0
    var selectedIndex by remember(files) { mutableStateOf(initialIndex) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(DesignTokens.scrim.copy(alpha = DesignTokens.scrim.alpha * fade))
            .pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = DesignTokens.popupTop)
                .width(DesignTokens.popupWidth)
                .shadow(24.dp, shape, clip = false)
                .clip(shape)
                .backdropGlass(shape)
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
                                if (files.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(files.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                if (files.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            // The Switcher keeps ctrl held and presses Tab again to step down.
                            Key.Tab -> {
                                if (files.isNotEmpty()) {
                                    selectedIndex = if (event.isShiftPressed) {
                                        (selectedIndex - 1).coerceAtLeast(0)
                                    } else {
                                        (selectedIndex + 1).coerceAtMost(files.lastIndex)
                                    }
                                }
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                files.getOrNull(selectedIndex)?.let(onChoose)
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
                        .height(48.dp)
                        .padding(horizontal = DesignTokens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Recent Files",
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
                if (files.isEmpty()) {
                    Text(
                        "No recent files",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                    )
                } else {
                    ResultList(files, projectRoot, selectedIndex, onChoose)
                }
                PopupHintBar("↑↓ Navigate", "↵ Open", "Esc Close")
            }
        }
    }
}

@Composable
private fun ResultList(
    files: List<VFile>,
    projectRoot: Path,
    selectedIndex: Int,
    onChoose: (VFile) -> Unit,
) {
    val listState = rememberLazyListState()
    val visibleRows = files.size.coerceAtMost(DesignTokens.popupMaxRows)
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight * visibleRows),
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(files) { index, file ->
                RecentRow(file, projectRoot, index == selectedIndex) { onChoose(file) }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = DesignTokens.scrollbarStyle,
        )
    }
}

@Composable
private fun RecentRow(file: VFile, projectRoot: Path, selected: Boolean, onClick: () -> Unit) {
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
        FileTypeIcon(file.name, Modifier.size(13.dp), color = if (selected) DesignTokens.accent else DesignTokens.muted)
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            file.name,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DesignTokens.space12))
        Text(
            relativePath(projectRoot, file),
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun relativePath(projectRoot: Path, file: VFile): String {
    val path = file.path.toAbsolutePath().normalize()
    return runCatching { projectRoot.toAbsolutePath().normalize().relativize(path).toString().replace('\\', '/') }
        .getOrDefault(path.toString())
}
