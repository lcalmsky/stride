package stride.app.search

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stride.app.ui.FileTypeIcon
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.SearchIcon
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.backdropGlass
import stride.app.ui.theme.glassBorder
import stride.app.ui.theme.neonBorder
import stride.app.ui.theme.neonGlow
import stride.app.ui.theme.neonGlowLayered
import stride.app.ui.theme.neonSelectionBar
import stride.app.ui.theme.rememberNeonAlpha
import stride.core.vfs.VFile

@Composable
fun SearchEverywherePopup(
    search: SearchEverywhere,
    onChoose: (SearchResultEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(emptyList<SearchResultEntry>()) }
    var selectedIndex by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)

    LaunchedEffect(query) {
        if (query.isEmpty()) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        val fetched = withContext(Dispatchers.Default) { search.fetch(query) }
        results = fetched
        selectedIndex = 0
        loading = false
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    Box(
        modifier
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
                .neonBorder(DesignTokens.accent, shape, radius = DesignTokens.glowRadiusLarge)
                .clip(shape)
                .backdropGlass(shape)
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
                                if (results.isNotEmpty()) selectedIndex = (selectedIndex + 1).coerceAtMost(results.lastIndex)
                                true
                            }
                            Key.DirectionUp -> {
                                if (results.isNotEmpty()) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> {
                                results.getOrNull(selectedIndex)?.let(onChoose)
                                true
                            }
                            else -> false
                        }
                    }
                },
        ) {
            PopupHeader(search.displayName, query, focusRequester, onQueryChange = { query = it })
            if (query.isNotEmpty()) {
                when {
                    loading && results.isEmpty() -> Unit
                    results.isEmpty() -> Text(
                        "No matching files",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space12),
                    )
                    else -> ResultList(results, selectedIndex, onChoose)
                }
            }
            Footer()
        }
    }
}

@Composable
private fun PopupHeader(
    contributorName: String,
    query: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(Modifier.size(16.dp), color = DesignTokens.accent)
        Spacer(Modifier.width(DesignTokens.space12))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search files by name",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = 15.sp,
                    fontFamily = DesignTokens.uiFont,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                singleLine = true,
                textStyle = TextStyle(
                    color = DesignTokens.text,
                    fontSize = 15.sp,
                    fontFamily = DesignTokens.uiFont,
                ),
                cursorBrush = SolidColor(DesignTokens.accent),
            )
        }
        Spacer(Modifier.width(DesignTokens.space12))
        ContributorChip(contributorName)
    }
}

@Composable
private fun ContributorChip(name: String) {
    val chipShape = RoundedCornerShape(DesignTokens.radiusSmall)
    Box(
        Modifier
            .background(DesignTokens.glassFill, chipShape)
            .glassBorder(chipShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            name,
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

@Composable
private fun ResultList(
    results: List<SearchResultEntry>,
    selectedIndex: Int,
    onChoose: (SearchResultEntry) -> Unit,
) {
    val listState = rememberLazyListState()
    val visibleRows = results.size.coerceAtMost(DesignTokens.popupMaxRows)
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight * visibleRows),
    ) {
        LazyColumn(state = listState) {
            itemsIndexed(results) { index, entry ->
                ResultRow(entry, index == selectedIndex) { onChoose(entry) }
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
private fun ResultRow(entry: SearchResultEntry, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val neon = rememberNeonAlpha(selected)
    val background = when {
        selected -> DesignTokens.selectionGlowBackground
        hovered -> DesignTokens.glassFill
        else -> DesignTokens.transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .neonGlowLayered(DesignTokens.accent2, alpha = neon)
            .neonGlow(DesignTokens.accent2Glow, radius = DesignTokens.glowRadius, alpha = neon * 0.6f)
            .then(if (selected) Modifier.background(DesignTokens.raised) else Modifier)
            .background(background)
            .neonSelectionBar(alpha = neon)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val file = entry.item as? VFile
        if (file != null) {
            FileTypeIcon(file.name, Modifier.size(13.dp))
            Spacer(Modifier.width(DesignTokens.space8))
        }
        Text(
            entry.presentationText,
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val directory = entry.secondaryText.substringBeforeLast('/', "")
        if (directory.isNotEmpty()) {
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                "$directory/",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun Footer() {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupFooterHeight)
            .drawBehind {
                drawLine(DesignTokens.border, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
            }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FooterHint("↑↓ Navigate")
        Spacer(Modifier.width(20.dp))
        FooterHint("↵ Open")
        Spacer(Modifier.width(20.dp))
        FooterHint("Esc Close")
    }
}

@Composable
private fun FooterHint(text: String) {
    Text(
        text,
        color = DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.labelTextSize,
        fontFamily = DesignTokens.uiFont,
    )
}

/** Popup open fade from 0 to 1; the final state on the first frame when animations are disabled. */
@Composable
internal fun rememberPopupFadeAlpha(): Float {
    if (!LocalAnimationsEnabled.current) return 1f
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(
            1f,
            tween(DesignTokens.motionPopupMillis, easing = DesignTokens.standardEasing),
        )
    }
    return alpha.value
}

/**
 * An alpha layer for the popup contents only. The glass node itself and its ancestors stay
 * layer-free so `backdropGlass` keeps blurring the real canvas.
 */
@Composable
internal fun popupContentFade(alpha: Float): Modifier =
    if (LocalAnimationsEnabled.current) Modifier.graphicsLayer { this.alpha = alpha } else Modifier
