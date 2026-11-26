package stride.app.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import stride.app.AppState
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow
import java.nio.file.Path
import kotlin.math.roundToInt

/** Test tag of an action row; the action's name keeps it unique within one menu. */
const val CONTEXT_MENU_ROW_TAG_PREFIX: String = "context-menu-row-"

/** Test tag of a submenu row, which no action identifies. */
const val CONTEXT_MENU_SUBMENU_TAG_PREFIX: String = "context-menu-submenu-"

/** The glass key of one menu level; the root menu is level 0 (A6). */
fun contextMenuKey(level: Int): String = "context-menu-$level"

private val menuMinWidth = 200.dp

/**
 * The context menu (A6): a column of rows at the pointer, each submenu beside its row. The one
 * focusable panel owns the whole menu's keys, so ↑ ↓ → ← ↩ keep working while a submenu is open;
 * hover, click and a click outside work on the rows themselves.
 */
@Composable
fun ContextMenuPopup(
    request: ContextMenuRequest,
    state: AppState,
    chooseFolder: () -> Path?,
    onClose: () -> Unit,
) {
    var openSubmenus by remember(request) { mutableStateOf(request.initialSubmenus) }
    var selected by remember(request) {
        mutableStateOf(firstChoosable(menuRowsAt(request.rows, request.initialSubmenus)))
    }
    val sizes = remember(request) { mutableStateMapOf<Int, Size>() }
    val anchors = remember(request) { mutableStateMapOf<Int, Offset>() }
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val contentFade = popupContentFade(rememberPopupFadeAlpha())
    val focusRequester = remember { FocusRequester() }

    fun rowsAt(level: Int): List<MenuRow> = menuRowsAt(request.rows, openSubmenus.take(level))

    fun move(step: Int) {
        val rows = rowsAt(openSubmenus.size)
        selected = nextChoosable(rows, selected, step) ?: return
    }

    fun openAt(index: Int) {
        val row = rowsAt(openSubmenus.size).getOrNull(index) as? MenuRow.Submenu ?: return
        openSubmenus = openSubmenus + index
        selected = firstChoosable(row.rows)
    }

    fun closeTop() {
        if (openSubmenus.isEmpty()) return
        val parentIndex = openSubmenus.last()
        openSubmenus = openSubmenus.dropLast(1)
        selected = parentIndex
    }

    fun activate(index: Int) {
        val row = rowsAt(openSubmenus.size).getOrNull(index) ?: return
        when (row) {
            is MenuRow.Submenu -> openAt(index)
            is MenuRow.Action -> if (row.enabled) chooseMenuRow(state, request, row, chooseFolder)
            is MenuRow.Command -> if (row.enabled) row.run()
            MenuRow.Separator -> Unit
        }
    }

    fun hover(level: Int, index: Int) {
        if (level != openSubmenus.size) return
        val row = rowsAt(level).getOrNull(index) ?: return
        when {
            row is MenuRow.Submenu -> {
                selected = index
                openAt(index)
            }
            row is MenuRow.Action && row.enabled -> selected = index
            row is MenuRow.Command && row.enabled -> selected = index
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val window = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { onClose() } },
        )
        for (level in 0..openSubmenus.size) {
            key(level) {
                val topLeft = if (level == 0) {
                    contextMenuPosition(request.position, sizes[level] ?: Size.Zero, window)
                } else {
                    submenuPosition(
                        rowTopLeft = anchors[level - 1] ?: request.position,
                        menuWidth = sizes[level - 1]?.width ?: 0f,
                        submenuSize = sizes[level] ?: Size.Zero,
                        window = window,
                    )
                }
                MenuPanel(
                    level = level,
                    rows = rowsAt(level),
                    topLeft = topLeft,
                    maxHeight = maxHeight,
                    selected = selected,
                    openIndex = openSubmenus.getOrNull(level),
                    shape = shape,
                    contentFade = contentFade,
                    panelModifier = if (level == 0) {
                        Modifier
                            .focusRequester(focusRequester)
                            .focusable()
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
                                        Key.DirectionRight -> {
                                            openAt(selected)
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            closeTop()
                                            true
                                        }
                                        Key.Enter, Key.NumPadEnter -> {
                                            activate(selected)
                                            true
                                        }
                                        else -> false
                                    }
                                }
                            }
                    } else {
                        Modifier
                    },
                    onHover = { index -> hover(level, index) },
                    onRun = ::activate,
                    // Writes only on change: an unconditional write here would invalidate the
                    // composition that read the map, and the layout would loop.
                    onMeasured = { if (sizes[level] != it) sizes[level] = it },
                    onAnchor = { if (anchors[level] != it) anchors[level] = it },
                )
            }
        }
    }
}

@Composable
private fun MenuPanel(
    level: Int,
    rows: List<MenuRow>,
    topLeft: Offset,
    maxHeight: Dp,
    selected: Int,
    openIndex: Int?,
    shape: Shape,
    contentFade: Modifier,
    panelModifier: Modifier,
    onHover: (Int) -> Unit,
    onRun: (Int) -> Unit,
    onMeasured: (Size) -> Unit,
    onAnchor: (Offset) -> Unit,
) {
    Column(
        Modifier
            .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
            .onGloballyPositioned { onMeasured(it.size.toSize()) }
            // As wide as the widest row, so a menu is not as wide as the window, and never
            // narrower than a readable minimum.
            .width(IntrinsicSize.Max)
            .widthIn(min = menuMinWidth)
            .popupPanel(contextMenuKey(level), shape)
            .then(contentFade)
            .pointerInput(Unit) { detectTapGestures { } }
            .then(panelModifier)
            .padding(vertical = DesignTokens.space4)
            // A menu taller than the window scrolls instead of running past its edge.
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            rows.forEachIndexed { index, row ->
                when (row) {
                    MenuRow.Separator -> {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = DesignTokens.space12, vertical = DesignTokens.space4)
                                .height(1.dp)
                                .background(DesignTokens.glassBorderSide),
                        )
                    }
                    is MenuRow.Action -> MenuActionRow(
                        text = row.text,
                        shortcut = row.shortcut,
                        enabled = row.enabled,
                        selected = index == selected,
                        tag = CONTEXT_MENU_ROW_TAG_PREFIX + row.action.name,
                        onHover = { onHover(index) },
                        onClick = { onRun(index) },
                    )
                    is MenuRow.Command -> MenuActionRow(
                        text = row.text,
                        shortcut = row.shortcut,
                        enabled = row.enabled,
                        selected = index == selected,
                        tag = CONTEXT_MENU_ROW_TAG_PREFIX + "COMMAND-" + level + "-" + index,
                        onHover = { onHover(index) },
                        onClick = { onRun(index) },
                    )
                    is MenuRow.Submenu -> MenuSubmenuRow(
                        row = row,
                        selected = index == selected,
                        open = index == openIndex,
                        tag = CONTEXT_MENU_SUBMENU_TAG_PREFIX + level + "-" + index,
                        onHover = { onHover(index) },
                        onClick = { onRun(index) },
                        onPositioned = { if (index == openIndex) onAnchor(it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuActionRow(
    text: String,
    shortcut: String,
    enabled: Boolean,
    selected: Boolean,
    tag: String,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) onHover()
    }
    MenuRowSurface(
        selected = selected,
        hovered = hovered,
        enabled = enabled,
        interaction = interaction,
        tag = tag,
        onPositioned = {},
        onClick = onClick,
    ) {
        Text(
            text,
            color = labelColor(selected, enabled),
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (shortcut.isNotEmpty()) {
            Spacer(Modifier.width(DesignTokens.space12))
            Text(
                shortcut,
                color = hintColor(selected),
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MenuSubmenuRow(
    row: MenuRow.Submenu,
    selected: Boolean,
    open: Boolean,
    tag: String,
    onHover: () -> Unit,
    onClick: () -> Unit,
    onPositioned: (Offset) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) {
        if (hovered) onHover()
    }
    MenuRowSurface(
        selected = selected,
        hovered = hovered,
        enabled = true,
        interaction = interaction,
        tag = tag,
        onPositioned = onPositioned,
        onClick = onClick,
    ) {
        Text(
            row.text,
            color = labelColor(selected, enabled = true),
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DesignTokens.space12))
        Text(
            "▸",
            color = hintColor(selected || open),
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

@Composable
private fun MenuRowSurface(
    selected: Boolean,
    hovered: Boolean,
    enabled: Boolean,
    interaction: MutableInteractionSource,
    tag: String,
    onPositioned: (Offset) -> Unit,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .testTag(tag)
            .onGloballyPositioned { onPositioned(it.boundsInRoot().topLeft) }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

private fun labelColor(selected: Boolean, enabled: Boolean) = when {
    !enabled -> DesignTokens.textSecondaryOnGlass
    selected -> DesignTokens.selectedText
    else -> DesignTokens.text
}

private fun hintColor(selected: Boolean) =
    if (selected) DesignTokens.selectedText else DesignTokens.textSecondaryOnGlass

private fun menuRowsAt(rows: List<MenuRow>, path: List<Int>): List<MenuRow> {
    var current = rows
    for (index in path) {
        current = (current.getOrNull(index) as? MenuRow.Submenu)?.rows ?: return emptyList()
    }
    return current
}

private fun MenuRow.choosable(): Boolean = when (this) {
    is MenuRow.Action -> enabled
    is MenuRow.Command -> enabled
    is MenuRow.Submenu -> true
    MenuRow.Separator -> false
}

private fun firstChoosable(rows: List<MenuRow>): Int = rows.indexOfFirst { it.choosable() }.coerceAtLeast(0)

private fun nextChoosable(rows: List<MenuRow>, from: Int, step: Int): Int? {
    val choosable = rows.indices.filter { rows[it].choosable() }
    if (choosable.isEmpty()) return null
    val position = choosable.indexOf(from)
    if (position < 0) return if (step > 0) choosable.first() else choosable.last()
    return choosable[(position + step).mod(choosable.size)]
}
