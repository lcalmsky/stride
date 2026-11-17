package stride.app.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import stride.app.glass.nativeGlass
import stride.app.ui.CloseIcon
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.rememberSelectionAlpha
import stride.app.ui.toolwindow.ListToolWindow
import stride.app.ui.toolwindow.ListWindowRow
import stride.app.ui.toolwindow.ToolWindowEmptyState

/**
 * The Find tool window: a tab per search, its result count and the flat list of hits. A REPLACE
 * tab previews each match's replacement and adds Replace Selected and Replace All; [replace]
 * plans and applies those edits.
 */
@Composable
fun FindToolWindow(
    state: FindToolWindowState,
    focusRequests: Int,
    onOpen: (FindInPathHit) -> Unit,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
    replace: FindReplaceController? = null,
) {
    val tab = state.selectedTab
    val scope = rememberCoroutineScope()
    var selectedRow by remember(tab?.id) { mutableStateOf(0) }
    val replaceState = remember(tab) { tab?.let(state::replaceState) }
    var focusedEmptyStates by remember(tab?.id) { mutableStateOf(0) }
    var listFocusTicks by remember(tab?.id) { mutableStateOf(0) }
    val effectiveFocusRequests = focusRequests + focusedEmptyStates

    fun applyPending(current: FindTab, controller: FindReplaceController, tabState: ReplaceTabState) {
        val plan = tabState.pendingPlan ?: return
        tabState.pendingPlan = null
        tabState.confirmation = null
        state.finishReplace(current, controller.apply(plan).message)
        focusedEmptyStates++
    }

    fun replaceSelected(current: FindTab, controller: FindReplaceController, tabState: ReplaceTabState) {
        if (tabState.busy) return
        val hit = current.hits.getOrNull(selectedRow) ?: return
        tabState.busy = true
        scope.launch {
            try {
                val hitPlan = withContext(Dispatchers.Default) { controller.planHit(current, hit) }
                val error = hitPlan.error
                if (error != null) {
                    state.showMessage(current, error)
                    return@launch
                }
                val planned = hitPlan.plan
                val outcome = if (planned == null) {
                    ReplaceOutcome(skippedChanged = 1)
                } else {
                    controller.apply(ReplacePlan(listOf(planned)))
                }
                val fresh = withContext(Dispatchers.Default) { controller.scanFile(current, hit) }
                state.finishReplaceSelected(current, hit.file, outcome, fresh)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                state.showMessage(current, "Replace failed: ${t.message ?: t::class.simpleName}")
            } finally {
                tabState.busy = false
            }
        }
    }

    Column(modifier) {
        if (state.tabs.isNotEmpty()) {
            FindTabs(state)
        }
        if (tab == null) {
            ToolWindowEmptyState(
                "No results · ⇧⌘F Find in Path, ⌥F7 Find Usages",
                Modifier.fillMaxWidth(),
                focusRequests = effectiveFocusRequests,
                onEscape = onEscape,
            )
            return@Column
        }
        Text(
            tab.header,
            color = DesignTokens.muted,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier.padding(horizontal = DesignTokens.space12, vertical = 4.dp),
        )
        if (tab.kind == FindTabKind.REPLACE && replace != null) {
            ReplaceTabHeader(
                enabled = replaceState != null && !replaceState.busy && replaceState.confirmation == null,
                onReplaceSelected = {
                    if (replaceState != null) replaceSelected(tab, replace, replaceState)
                },
                onReplaceAll = { state.startReplaceAll(tab, replace) },
            )
        }
        if (replaceState?.busy == true) {
            TabMessage("counting…")
        } else if (tab.hits.isNotEmpty()) {
            tab.message?.let { message -> TabMessage(message) }
        }
        replaceState?.confirmation?.let { text ->
            ReplaceConfirmationRow(
                text = text,
                onConfirm = {
                    replace?.let { controller ->
                        replaceState?.let { applyPending(tab, controller, it) }
                    }
                },
                onCancel = {
                    replaceState?.let {
                        it.pendingPlan = null
                        it.confirmation = null
                    }
                    listFocusTicks++
                },
                modifier = Modifier.padding(horizontal = DesignTokens.space12),
                showButtons = true,
            )
        }
        when {
            tab.searching && tab.hits.isEmpty() -> ToolWindowEmptyState(
                "searching…",
                Modifier.fillMaxWidth(),
                focusRequests = effectiveFocusRequests,
                onEscape = onEscape,
            )
            tab.hits.isEmpty() -> ToolWindowEmptyState(
                tab.message ?: "No results · ⇧⌘F Find in Path, ⌥F7 Find Usages",
                Modifier.fillMaxWidth(),
                focusRequests = effectiveFocusRequests,
                onEscape = onEscape,
            )
            else -> key(tab.id) {
                val rows = remember(tab.hits, tab.replacement) {
                    tab.hits.map { hit ->
                        ListWindowRow(
                            primary = AnnotatedString("${hit.relativePath}:${hit.line}"),
                            secondary = if (tab.kind == FindTabKind.REPLACE) {
                                replacePreviewLine(hit, tab.replacement.orEmpty(), DesignTokens.accent2)
                            } else {
                                hitLine(hit)
                            },
                            iconName = hit.file.name,
                        )
                    }
                }
                ListToolWindow(
                    rows = rows,
                    focusRequests = focusRequests + listFocusTicks,
                    onOpen = { index -> tab.hits.getOrNull(index)?.let(onOpen) },
                    onEscape = onEscape,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onSelectionChange = { selectedRow = it },
                )
            }
        }
    }
}

@Composable
private fun ReplaceTabHeader(
    enabled: Boolean,
    onReplaceSelected: () -> Unit,
    onReplaceAll: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReplaceTextButton("Replace Selected", onReplaceSelected, enabled)
        Spacer(Modifier.width(DesignTokens.space12))
        ReplaceTextButton("Replace All", onReplaceAll, enabled)
    }
}

@Composable
private fun TabMessage(message: String) {
    Text(
        message,
        color = DesignTokens.muted,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = 4.dp),
    )
}

@Composable
private fun FindTabs(state: FindToolWindowState) {
    val shape = RoundedCornerShape(percent = 50)
    val scrollState = rememberScrollState()
    var tabBounds by remember { mutableStateOf<Map<Long, Rect>>(emptyMap()) }
    LaunchedEffect(state.selectedTabId, tabBounds, scrollState.viewportSize) {
        val selected = state.selectedTabId ?: return@LaunchedEffect
        val bounds = tabBounds[selected] ?: return@LaunchedEffect
        val viewport = scrollState.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val current = scrollState.value
        val target = when {
            bounds.left < current -> bounds.left
            bounds.right > current + viewport -> bounds.right - viewport
            else -> return@LaunchedEffect
        }
        scrollState.scrollTo(target.toInt().coerceIn(0, scrollState.maxValue))
    }
    Row(
        Modifier
            .height(DesignTokens.capsuleHeight)
            .capsuleGlass(shape)
            .nativeGlass("find-tabs", shape, tint = DesignTokens.nativeGlassTint)
            .padding(horizontal = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            Row(
                Modifier.fillMaxHeight().horizontalScroll(scrollState),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (tab in state.tabs) {
                    val active = tab.id == state.selectedTabId
                    val pill = rememberSelectionAlpha(active)
                    Row(
                        Modifier
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInParent()
                                if (tabBounds[tab.id] != bounds) tabBounds = tabBounds + (tab.id to bounds)
                            }
                            .fillMaxHeight()
                            .glassCapsule(
                                shape = RoundedCornerShape(percent = 50),
                                insetX = 2.dp,
                                insetY = 3.dp,
                                selectionAlpha = pill,
                            )
                            .clickable { state.select(tab) }
                            .padding(start = DesignTokens.space8, end = DesignTokens.space4),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            tab.title,
                            color = if (active) DesignTokens.selectedText else DesignTokens.textSecondaryOnGlass,
                            fontSize = DesignTokens.smallTextSize,
                            fontFamily = DesignTokens.uiFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.width(DesignTokens.space4))
                        Box(
                            Modifier
                                .size(16.dp)
                                .clickable { state.close(tab) },
                            contentAlignment = Alignment.Center,
                        ) {
                            CloseIcon(Modifier.size(9.dp))
                        }
                    }
                }
            }
        }
    }
}
