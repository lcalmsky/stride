package stride.app.generate

import androidx.compose.foundation.VerticalScrollbar
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.glass.GlassHoles
import stride.app.glass.LocalNativeGlass
import stride.app.search.popupContentFade
import stride.app.search.rememberPopupFadeAlpha
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.scrimSurface
import stride.app.ui.theme.selectionRow
import stride.lang.MemberOption

/** Test tags the UI tests find the member chooser by. */
const val MEMBER_CHOOSER_POPUP_TAG: String = "member-chooser-popup"
const val MEMBER_CHOOSER_ERROR_TAG: String = "member-chooser-error"

private sealed interface MemberChooserRow {
    class Heading(val text: String) : MemberChooserRow

    class Member(val index: Int) : MemberChooserRow
}

/**
 * A `ChooseMembers` step: group headings, one checkbox row per member and the last rejected
 * choice's [error]. ↑↓ move, Space toggles, ⌘A toggles every enabled row, Enter submits the
 * checked rows or the row under the cursor, Escape closes.
 */
@Composable
fun MemberChooserPopup(
    title: String,
    members: List<MemberOption>,
    error: String?,
    onSubmit: (List<MemberOption>) -> Unit,
    onClose: () -> Unit,
) {
    var checked by remember(members) { mutableStateOf(members.map { it.checked }) }
    var cursor by remember(members) { mutableStateOf(0) }
    val rows = remember(members) { chooserRows(members) }
    val index = cursor.coerceIn(0, (members.size - 1).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val fade = rememberPopupFadeAlpha()
    val contentFade = popupContentFade(fade)
    val holes = if (LocalNativeGlass.current) GlassHoles.snapshot() else emptyList()

    fun setChecked(at: Int, value: Boolean) {
        checked = checked.toMutableList().also { it[at] = value }
    }

    fun toggleEnabledRows() {
        val enabled = members.indices.filter { members[it].disabledReason == null }
        if (enabled.isEmpty()) return
        val next = if (enabled.all { checked[it] }) false else true
        checked = checked.toMutableList().also { list -> enabled.forEach { list[it] = next } }
    }

    fun submit() {
        val chosen = members.indices.filter { checked[it] && members[it].disabledReason == null }
        val current = members.getOrNull(index)
        when {
            chosen.isNotEmpty() -> onSubmit(chosen.map { members[it] })
            current != null && current.disabledReason == null -> onSubmit(listOf(current))
        }
    }

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
                .popupPanel("popup-member-chooser", shape)
                .then(contentFade)
                .focusRequester(focusRequester)
                .focusable()
                .testTag(MEMBER_CHOOSER_POPUP_TAG)
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
                                cursor = (index + 1).coerceAtMost((members.size - 1).coerceAtLeast(0))
                                true
                            }
                            event.key == Key.DirectionUp -> {
                                cursor = (index - 1).coerceAtLeast(0)
                                true
                            }
                            event.key == Key.Spacebar -> {
                                if (members.getOrNull(index)?.disabledReason == null) {
                                    setChecked(index, !checked[index])
                                }
                                true
                            }
                            event.key == Key.A && event.isMetaPressed -> {
                                toggleEnabledRows()
                                true
                            }
                            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                submit()
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
                        title,
                        color = DesignTokens.text,
                        fontSize = DesignTokens.uiTextSize,
                        fontFamily = DesignTokens.uiFont,
                    )
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(DesignTokens.popupRowHeight * rows.size.coerceAtMost(DesignTokens.popupMaxRows)),
                ) {
                    LazyColumn(state = listState) {
                        items(rows) { row ->
                            when (row) {
                                is MemberChooserRow.Heading -> HeadingRow(row.text)
                                is MemberChooserRow.Member -> {
                                    val member = members[row.index]
                                    MemberRow(
                                        member = member,
                                        checked = checked[row.index],
                                        selected = row.index == index,
                                        onToggle = {
                                            cursor = row.index
                                            if (member.disabledReason == null) {
                                                setChecked(row.index, !checked[row.index])
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        style = DesignTokens.scrollbarStyle,
                    )
                }
                if (error != null) {
                    Text(
                        error,
                        color = DesignTokens.errorOnPopupPlate,
                        fontSize = DesignTokens.labelTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier
                            .padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8)
                            .testTag(MEMBER_CHOOSER_ERROR_TAG),
                    )
                }
                PopupHintBar("↑↓ Navigate", "Space Toggle", "⌘A All", "↵ Apply", "Esc Close")
            }
        }
    }
}

private fun chooserRows(members: List<MemberOption>): List<MemberChooserRow> = buildList {
    var group: String? = null
    members.forEachIndexed { index, member ->
        val name = member.group
        if (name != null && name != group) add(MemberChooserRow.Heading(name))
        if (name != null) group = name
        add(MemberChooserRow.Member(index))
    }
}

@Composable
private fun HeadingRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MemberRow(
    member: MemberOption,
    checked: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val disabled = member.disabledReason != null
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onToggle)
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (checked) "✓" else "",
            color = DesignTokens.accent2,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            member.text,
            color = when {
                disabled -> DesignTokens.textSecondaryOnGlass
                selected -> DesignTokens.selectedText
                else -> DesignTokens.text
            },
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        val reason = member.disabledReason
        if (reason != null) {
            Spacer(Modifier.width(DesignTokens.space12))
            Text(
                reason,
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
