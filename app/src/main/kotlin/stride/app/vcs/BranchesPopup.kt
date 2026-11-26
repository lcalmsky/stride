package stride.app.vcs

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.ui.PopupHintBar
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.popupPanel
import stride.app.ui.theme.selectionRow

internal const val BRANCHES_POPUP_KEY: String = "popup-branches"
internal const val BRANCHES_POPUP_TAG: String = "branches-popup"
internal const val BRANCHES_SEARCH_TAG: String = "branches-search"
internal const val BRANCHES_NAME_FIELD_TAG: String = "branches-name-field"
internal const val BRANCHES_NAME_PROBLEM_TAG: String = "branches-name-problem"
internal const val BRANCHES_DELETE_CAPSULE_TAG: String = "branches-delete-capsule"

internal const val BRANCH_ROW_TAG_PREFIX: String = "branch-row-"

private val branchesWidth = 440.dp
private val branchesListMaxHeight = 320.dp

enum class BranchesPage { LIST, MENU, NAME, DELETE }

/** The branch a menu page belongs to: [display] is its name, or `<remote>/<name>` for a remote. */
data class BranchMenuTarget(
    val target: BranchTarget,
    val display: String,
    val current: Boolean,
)

sealed interface BranchesNamePage {
    data class New(val start: BranchTarget?, val startLabel: String) : BranchesNamePage

    data class Rename(val old: String) : BranchesNamePage
}

/**
 * The branches popup's whole state, one page at a time. [list] is `null` while `branches()` runs;
 * [token] tells a late read whether this popup is still the one it was asked for.
 */
data class BranchesRequest(
    val token: Long,
    val page: BranchesPage = BranchesPage.LIST,
    val list: BranchList? = null,
    val query: String = "",
    val selected: Int = 0,
    /** The list row to select when a menu page goes back to the list. */
    val listSelected: Int = 0,
    val menu: BranchMenuTarget? = null,
    val namePage: BranchesNamePage? = null,
    val name: TextFieldValue = TextFieldValue(),
    val checkout: Boolean = true,
    val openedDirectly: Boolean = false,
    val delete: DeletePlan.Unmerged? = null,
)

internal sealed interface BranchesListRow {
    val selectable: Boolean

    data class NewBranch(val enabled: Boolean) : BranchesListRow {
        override val selectable: Boolean get() = true
    }

    data class Header(val text: String) : BranchesListRow {
        override val selectable: Boolean get() = false
    }

    data class Local(val branch: LocalBranch) : BranchesListRow {
        override val selectable: Boolean get() = true
    }

    data class Remote(val branch: RemoteBranch) : BranchesListRow {
        override val selectable: Boolean get() = true
    }

    data class Message(val text: String) : BranchesListRow {
        override val selectable: Boolean get() = false
    }
}

private sealed interface BranchMenuRow {
    data object Checkout : BranchMenuRow

    data class NewFrom(val label: String) : BranchMenuRow

    data object Rename : BranchMenuRow

    data object Delete : BranchMenuRow
}

/** The rows the list page draws for [list] and [query]; headers hide when their group empties. */
private fun branchRows(list: BranchList?, query: String): List<BranchesListRow> {
    when (list) {
        null -> return listOf(BranchesListRow.Message("Loading…"))
        is BranchList.Refused -> return listOf(BranchesListRow.Message(list.message))
        is BranchList.Ready -> Unit
    }
    val ready = list as BranchList.Ready
    val matches = { text: String -> query.isEmpty() || text.contains(query, ignoreCase = true) }
    val local = ready.local.filter { matches(it.name) }
    val remote = ready.remote.filter { matches(it.display) }
    val rows = ArrayList<BranchesListRow>()
    rows += BranchesListRow.NewBranch(enabled = ready.head != null)
    if (local.isNotEmpty()) {
        rows += BranchesListRow.Header("Local (${local.size})")
        local.mapTo(rows) { BranchesListRow.Local(it) }
    }
    if (remote.isNotEmpty()) {
        rows += BranchesListRow.Header("Remote (${remote.size})")
        remote.mapTo(rows) { BranchesListRow.Remote(it) }
    }
    return rows
}

private fun menuRows(target: BranchMenuTarget?): List<BranchMenuRow> {
    if (target == null) return emptyList()
    val rows = ArrayList<BranchMenuRow>()
    if (!target.current) rows += BranchMenuRow.Checkout
    rows += BranchMenuRow.NewFrom(target.display)
    if (target.target is BranchTarget.Local) {
        rows += BranchMenuRow.Rename
        if (!target.current) rows += BranchMenuRow.Delete
    }
    return rows
}

private fun nearestSelectable(rows: List<BranchesListRow>, requested: Int): Int {
    if (rows.isEmpty()) return -1
    val clamped = requested.coerceIn(0, rows.lastIndex)
    if (rows[clamped].selectable) return clamped
    return (clamped until rows.size).firstOrNull { rows[it].selectable }
        ?: (clamped downTo 0).firstOrNull { rows[it].selectable }
        ?: -1
}

private fun existingNames(list: BranchList?): List<String> =
    (list as? BranchList.Ready)?.local?.map { it.name } ?: emptyList()

/** The live name line; a rename may keep the old name, so it is not one of the existing ones. */
private fun nameProblem(namePage: BranchesNamePage?, text: String, existing: List<String>): String? {
    val names = if (namePage is BranchesNamePage.Rename) existing.filter { it != namePage.old } else existing
    return BranchNames.problem(text, names)
}

private fun localDetail(branch: LocalBranch): String? = buildString {
    val upstream = branch.upstream
    if (upstream != null) {
        append(upstream)
        if (branch.upstreamGone) {
            append(" gone")
        } else {
            append(" ↑").append(branch.ahead).append(" ↓").append(branch.behind)
        }
    }
    if (branch.worktree != null) {
        if (isNotEmpty()) append("  ")
        append("worktree")
    }
}.ifEmpty { null }

private fun currentLabel(request: BranchesRequest, session: ProjectSession?): String? {
    val ready = request.list as? BranchList.Ready
    if (ready != null) return ready.current ?: ready.head?.let { "HEAD ${it.take(7)}" }
    val branch = session?.git?.snapshot?.branch ?: return null
    return branch.branch ?: branch.head?.let { "HEAD ${it.take(7)}" }
}

/**
 * The branches popup: the list with its search field, the selected branch's menu, the name page
 * and the unmerged branch's delete page. `⌥⌘N` opens the name page from the list, `→` opens the
 * selected row's menu, `←` goes back from the menu, `Tab` toggles `Checkout branch`.
 */
@Composable
fun BranchesPopup(request: BranchesRequest, state: AppState, onClose: () -> Unit) {
    val session = state.session
    val shape = RoundedCornerShape(DesignTokens.radiusPopup)
    val panelFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var fired by remember { mutableStateOf(false) }

    val current = state.branchesPopup ?: request
    val page = current.page
    val listRows = remember(current.list, current.query) { branchRows(current.list, current.query) }
    val listIndex = nearestSelectable(listRows, current.selected)
    val popupMenuRows = remember(current.menu) { menuRows(current.menu) }
    val menuIndex = if (popupMenuRows.isEmpty()) -1 else current.selected.coerceIn(0, popupMenuRows.lastIndex)
    val unsaved = session?.let { saveableEditors(it).size } ?: 0

    fun update(block: (BranchesRequest) -> BranchesRequest) {
        val now = state.branchesPopup ?: return
        if (now.token != current.token) return
        state.branchesPopup = block(now)
    }

    fun moveList(step: Int) {
        val now = state.branchesPopup ?: return
        val rows = branchRows(now.list, now.query)
        val from = nearestSelectable(rows, now.selected)
        if (from < 0) return
        var next = from + step
        while (next in rows.indices && !rows[next].selectable) next += step
        if (next !in rows.indices) return
        update { it.copy(selected = next) }
    }

    fun moveMenu(step: Int) {
        val now = state.branchesPopup ?: return
        val rows = menuRows(now.menu)
        if (rows.isEmpty()) return
        val from = now.selected.coerceIn(0, rows.lastIndex)
        update { it.copy(selected = (from + step).coerceIn(0, rows.lastIndex)) }
    }


    fun openNameTyped(row: Int?) {
        val now = state.branchesPopup ?: return
        val ready = now.list as? BranchList.Ready ?: return
        if (ready.head == null) return
        val typed = now.query
        update {
            val rows = branchRows(it.list, it.query)
            val from = row ?: nearestSelectable(rows, it.selected)
            it.copy(
                page = BranchesPage.NAME,
                namePage = BranchesNamePage.New(null, "HEAD"),
                name = TextFieldValue(typed, TextRange(typed.length)),
                checkout = true,
                openedDirectly = false,
                selected = 0,
                listSelected = if (from < 0) it.listSelected else from,
            )
        }
    }

    fun openMenu(target: BranchMenuTarget, row: Int) {
        update { it.copy(page = BranchesPage.MENU, menu = target, selected = 0, listSelected = row) }
    }

    fun openRow(index: Int, row: BranchesListRow?) {
        when (row) {
            is BranchesListRow.NewBranch -> if (row.enabled) openNameTyped(index)
            is BranchesListRow.Local -> openMenu(
                BranchMenuTarget(BranchTarget.Local(row.branch.name), row.branch.name, row.branch.current),
                index,
            )
            is BranchesListRow.Remote -> openMenu(
                BranchMenuTarget(BranchTarget.Remote(row.branch.remote, row.branch.name), row.branch.display, false),
                index,
            )
            else -> Unit
        }
    }

    fun backToList() {
        update { it.copy(page = BranchesPage.LIST, selected = it.listSelected, menu = null) }
    }

    fun runMenuRow(row: BranchMenuRow?) {
        val now = state.branchesPopup ?: return
        val target = now.menu ?: return
        when (row) {
            BranchMenuRow.Checkout -> {
                if (fired) return
                fired = true
                onClose()
                runCheckout(state, target.target)
            }
            is BranchMenuRow.NewFrom -> update {
                it.copy(
                    page = BranchesPage.NAME,
                    namePage = BranchesNamePage.New(target.target, row.label),
                    name = TextFieldValue(),
                    checkout = true,
                    openedDirectly = false,
                    selected = 0,
                )
            }
            BranchMenuRow.Rename -> update {
                it.copy(
                    page = BranchesPage.NAME,
                    namePage = BranchesNamePage.Rename(target.display),
                    name = TextFieldValue(target.display, TextRange(0, target.display.length)),
                    openedDirectly = false,
                    selected = 0,
                )
            }
            BranchMenuRow.Delete -> runDeleteBranch(state, target.display, onClose)
            null -> Unit
        }
    }

    fun runSelectedMenuRow() {
        val now = state.branchesPopup ?: return
        val rows = menuRows(now.menu)
        if (rows.isEmpty()) return
        runMenuRow(rows.getOrNull(now.selected.coerceIn(0, rows.lastIndex)))
    }

    fun openSelectedRow() {
        val now = state.branchesPopup ?: return
        val rows = branchRows(now.list, now.query)
        val index = nearestSelectable(rows, now.selected)
        openRow(index, rows.getOrNull(index))
    }

    fun backFromName() {
        val now = state.branchesPopup ?: return
        if (now.openedDirectly) {
            onClose()
        } else {
            update { it.copy(page = if (it.menu != null) BranchesPage.MENU else BranchesPage.LIST, selected = 0) }
        }
    }

    fun submitName() {
        val now = state.branchesPopup ?: return
        val namePage = now.namePage ?: return
        val text = now.name.text
        if (nameProblem(namePage, text, existingNames(now.list)) != null) return
        if (namePage is BranchesNamePage.Rename && text == namePage.old) return
        if (fired) return
        fired = true
        onClose()
        when (namePage) {
            is BranchesNamePage.New -> runCreateBranch(state, text, namePage.start, now.checkout)
            is BranchesNamePage.Rename -> runRenameBranch(state, namePage.old, text)
        }
    }

    fun submitDelete() {
        val now = state.branchesPopup ?: return
        val plan = now.delete ?: return
        if (fired) return
        fired = true
        onClose()
        runForceDeleteBranch(state, plan.name, plan.id)
    }

    LaunchedEffect(page) {
        fired = false
        withFrameNanos { }
        val target = if (page == BranchesPage.LIST || page == BranchesPage.NAME) fieldFocus else panelFocus
        runCatching { target.requestFocus() }
    }
    LaunchedEffect(page, listIndex, listRows.size) {
        if (page != BranchesPage.LIST || listIndex < 0) return@LaunchedEffect
        runCatching { listState.animateScrollToItem(listIndex) }
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
                .width(branchesWidth)
                .popupPanel(BRANCHES_POPUP_KEY, shape)
                .focusRequester(panelFocus)
                .focusable()
                .testTag(BRANCHES_POPUP_TAG)
                .pointerInput(Unit) { detectTapGestures { } }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (page) {
                            BranchesPage.LIST -> when (event.key) {
                                Key.Escape -> {
                                    onClose()
                                    true
                                }
                                Key.DirectionDown -> {
                                    moveList(1)
                                    true
                                }
                                Key.DirectionUp -> {
                                    moveList(-1)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter, Key.DirectionRight -> {
                                    openSelectedRow()
                                    true
                                }
                                Key.N -> if (event.isMetaPressed && event.isAltPressed) {
                                    openNameTyped(null)
                                    true
                                } else {
                                    false
                                }
                                else -> false
                            }
                            BranchesPage.MENU -> when (event.key) {
                                Key.Escape, Key.DirectionLeft -> {
                                    backToList()
                                    true
                                }
                                Key.DirectionDown -> {
                                    moveMenu(1)
                                    true
                                }
                                Key.DirectionUp -> {
                                    moveMenu(-1)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    runSelectedMenuRow()
                                    true
                                }
                                else -> false
                            }
                            BranchesPage.NAME -> when {
                                event.key == Key.Escape -> {
                                    backFromName()
                                    true
                                }
                                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                    submitName()
                                    true
                                }
                                event.key == Key.Tab && current.namePage is BranchesNamePage.New -> {
                                    update { it.copy(checkout = !it.checkout) }
                                    true
                                }
                                else -> false
                            }
                            BranchesPage.DELETE -> when (event.key) {
                                Key.Escape -> {
                                    update { it.copy(page = BranchesPage.MENU, selected = 0) }
                                    true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    submitDelete()
                                    true
                                }
                                else -> false
                            }
                        }
                    }
                },
        ) {
            CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
                BranchesHeader(currentLabel(current, session))
                when (page) {
                    BranchesPage.LIST -> BranchesListPage(
                        query = current.query,
                        rows = listRows,
                        index = listIndex,
                        listState = listState,
                        fieldFocus = fieldFocus,
                        onQueryChange = { text -> update { it.copy(query = text, selected = 0) } },
                        onOpen = ::openRow,
                    )
                    BranchesPage.MENU -> BranchesMenuPage(
                        target = current.menu,
                        rows = popupMenuRows,
                        index = menuIndex,
                        unsaved = unsaved,
                        onRun = ::runMenuRow,
                    )
                    BranchesPage.NAME -> BranchesNamePageView(
                        namePage = current.namePage,
                        name = current.name,
                        problem = nameProblem(current.namePage, current.name.text, existingNames(current.list)),
                        checkout = current.checkout,
                        unsaved = unsaved,
                        fieldFocus = fieldFocus,
                        onNameChange = { value -> update { it.copy(name = value) } },
                        onToggleCheckout = { update { it.copy(checkout = !it.checkout) } },
                    )
                    BranchesPage.DELETE -> BranchesDeletePage(
                        plan = current.delete,
                        onDelete = ::submitDelete,
                    )
                }
                PopupHintBar(*hints(page, current).toTypedArray())
            }
        }
    }
}

private fun hints(page: BranchesPage, request: BranchesRequest): List<String> = when (page) {
    BranchesPage.LIST -> listOf("↑↓ Navigate", "↵ Menu", "⌥⌘N New Branch", "Esc Close")
    BranchesPage.MENU -> listOf("↑↓ Navigate", "↵ Run", "← Back", "Esc Back")
    BranchesPage.NAME -> buildList {
        add(if (request.namePage is BranchesNamePage.Rename) "↵ Rename" else "↵ Create")
        if (request.namePage is BranchesNamePage.New) add("Tab Checkout")
        add("Esc Back")
    }
    BranchesPage.DELETE -> listOf("↵ Delete", "Esc Back")
}

@Composable
private fun BranchesHeader(current: String?) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Branches",
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
        )
        if (current != null) {
            Text(
                current,
                color = DesignTokens.accentOnPopupPlate,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = DesignTokens.space8),
            )
        }
    }
}

@Composable
private fun BranchesListPage(
    query: String,
    rows: List<BranchesListRow>,
    index: Int,
    listState: LazyListState,
    fieldFocus: FocusRequester,
    onQueryChange: (String) -> Unit,
    onOpen: (Int, BranchesListRow) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "Search branches",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    .testTag(BRANCHES_SEARCH_TAG),
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
    Box(Modifier.fillMaxWidth().heightIn(max = branchesListMaxHeight)) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(rows) { rowIndex, row ->
                when (row) {
                    is BranchesListRow.Header -> BranchesGroupHeader(row.text)
                    is BranchesListRow.Message -> BranchesMessageRow(row.text)
                    is BranchesListRow.NewBranch -> BranchesMenuRowItem(
                        label = "New Branch…",
                        shortcut = "⌥⌘N",
                        enabled = row.enabled,
                        selected = rowIndex == index,
                        onClick = { onOpen(rowIndex, row) },
                    )
                    is BranchesListRow.Local -> LocalBranchRow(
                        branch = row.branch,
                        selected = rowIndex == index,
                        onClick = { onOpen(rowIndex, row) },
                    )
                    is BranchesListRow.Remote -> RemoteBranchRow(
                        branch = row.branch,
                        selected = rowIndex == index,
                        onClick = { onOpen(rowIndex, row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BranchesGroupHeader(text: String) {
    Row(
        Modifier.fillMaxWidth().height(28.dp).padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

@Composable
private fun BranchesMessageRow(text: String) {
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
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BranchesMenuRowItem(
    label: String,
    shortcut: String,
    enabled: Boolean,
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
            .hoverable(interaction)
            .pointerInput(enabled) { detectTapGestures { if (enabled) onClick() } }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = when {
                selected -> DesignTokens.selectedText
                enabled -> DesignTokens.text
                else -> DesignTokens.textSecondaryOnGlass
            },
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (shortcut.isNotEmpty()) {
            Text(
                shortcut,
                color = DesignTokens.textOnBusyGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun LocalBranchRow(branch: LocalBranch, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .testTag(BRANCH_ROW_TAG_PREFIX + branch.name)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .pointerInput(branch) { detectTapGestures { onClick() } }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (branch.current) "✓" else "",
            color = if (selected) DesignTokens.selectedText else DesignTokens.accentOnPopupPlate,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier.width(18.dp),
        )
        Text(
            branch.name,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val detail = localDetail(branch)
        if (detail != null) {
            Spacer(Modifier.width(DesignTokens.space8))
            Text(
                detail,
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RemoteBranchRow(branch: RemoteBranch, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.popupRowHeight)
            .testTag(BRANCH_ROW_TAG_PREFIX + branch.display)
            .selectionRow(selected = selected, hovered = hovered)
            .hoverable(interaction)
            .pointerInput(branch) { detectTapGestures { onClick() } }
            .padding(horizontal = DesignTokens.space16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            branch.display,
            color = if (selected) DesignTokens.selectedText else DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BranchesMenuPage(
    target: BranchMenuTarget?,
    rows: List<BranchMenuRow>,
    index: Int,
    unsaved: Int,
    onRun: (BranchMenuRow) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            target?.display.orEmpty(),
            color = DesignTokens.accentOnPopupPlate,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Column(Modifier.fillMaxWidth().padding(vertical = DesignTokens.space4)) {
        rows.forEachIndexed { rowIndex, row ->
            when (row) {
                BranchMenuRow.Checkout -> {
                    BranchesMenuRowItem(
                        label = "Checkout",
                        shortcut = "",
                        enabled = true,
                        selected = rowIndex == index,
                        onClick = { onRun(row) },
                    )
                    if (unsaved > 0) {
                        Text(
                            "Saves $unsaved unsaved editors first",
                            color = DesignTokens.textSecondaryOnGlass,
                            fontSize = DesignTokens.smallTextSize,
                            fontFamily = DesignTokens.uiFont,
                            modifier = Modifier.padding(
                                start = DesignTokens.space16,
                                bottom = DesignTokens.space4,
                            ),
                        )
                    }
                }
                is BranchMenuRow.NewFrom -> BranchesMenuRowItem(
                    label = "New Branch from '${row.label}'…",
                    shortcut = "",
                    enabled = true,
                    selected = rowIndex == index,
                    onClick = { onRun(row) },
                )
                BranchMenuRow.Rename -> BranchesMenuRowItem(
                    label = "Rename…",
                    shortcut = "",
                    enabled = true,
                    selected = rowIndex == index,
                    onClick = { onRun(row) },
                )
                BranchMenuRow.Delete -> BranchesMenuRowItem(
                    label = "Delete",
                    shortcut = "",
                    enabled = true,
                    selected = rowIndex == index,
                    onClick = { onRun(row) },
                )
            }
        }
    }
}

@Composable
private fun BranchesNamePageView(
    namePage: BranchesNamePage?,
    name: TextFieldValue,
    problem: String?,
    checkout: Boolean,
    unsaved: Int,
    fieldFocus: FocusRequester,
    onNameChange: (TextFieldValue) -> Unit,
    onToggleCheckout: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (namePage) {
            is BranchesNamePage.New -> {
                Text(
                    "New Branch",
                    color = DesignTokens.text,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
                Text(
                    "from ${namePage.startLabel}",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                    modifier = Modifier.padding(start = DesignTokens.space8),
                )
            }
            is BranchesNamePage.Rename -> {
                Text(
                    "Rename",
                    color = DesignTokens.text,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
                Text(
                    namePage.old,
                    color = DesignTokens.accentOnPopupPlate,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = DesignTokens.space8),
                )
            }
            null -> Unit
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = 6.dp)) {
        BasicTextField(
            value = name,
            onValueChange = onNameChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(fieldFocus)
                .testTag(BRANCHES_NAME_FIELD_TAG),
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
    if (problem != null) {
        Text(
            problem,
            color = DesignTokens.errorOnPopupPlate,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
            modifier = Modifier
                .padding(horizontal = DesignTokens.space16)
                .testTag(BRANCHES_NAME_PROBLEM_TAG),
        )
    }
    if (namePage is BranchesNamePage.New) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DesignTokens.popupRowHeight)
                .pointerInput(Unit) { detectTapGestures { onToggleCheckout() } }
                .padding(horizontal = DesignTokens.space16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (checkout) "✓" else "",
                color = DesignTokens.accentOnPopupPlate,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                modifier = Modifier.width(18.dp),
            )
            Text(
                "Checkout branch",
                color = DesignTokens.text,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
        if (checkout && namePage.start != null && unsaved > 0) {
            Text(
                "Saves $unsaved unsaved editors first",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.smallTextSize,
                fontFamily = DesignTokens.uiFont,
                modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
            )
        }
    }
}

@Composable
private fun BranchesDeletePage(plan: DeletePlan.Unmerged?, onDelete: () -> Unit) {
    if (plan == null) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Delete ${plan.name}?",
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val total = plan.commits.size + plan.moreCommits
    Text(
        "$total ${if (total == 1) "commit is" else "commits are"} not merged into ${plan.reference}",
        color = DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.smallTextSize,
        fontFamily = DesignTokens.uiFont,
        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
    )
    if (plan.commits.isNotEmpty()) {
        Box(Modifier.fillMaxWidth().heightIn(max = branchesListMaxHeight)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                for (entry in plan.commits) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(DesignTokens.popupRowHeight)
                            .padding(horizontal = DesignTokens.space16),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.shortId,
                            color = DesignTokens.text,
                            fontSize = DesignTokens.codeTextSize,
                            fontFamily = DesignTokens.codeFont,
                        )
                        Spacer(Modifier.width(DesignTokens.space12))
                        Text(
                            entry.subject,
                            color = DesignTokens.text,
                            fontSize = DesignTokens.uiTextSize,
                            fontFamily = DesignTokens.uiFont,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (plan.moreCommits > 0) {
                    Text(
                        "… ${plan.moreCommits} more",
                        color = DesignTokens.textSecondaryOnGlass,
                        fontSize = DesignTokens.smallTextSize,
                        fontFamily = DesignTokens.uiFont,
                        modifier = Modifier.padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space4),
                    )
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DesignTokens.space16, vertical = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        CommitCapsule(
            enabled = true,
            checked = 0,
            total = 0,
            onClick = onDelete,
            label = "Delete",
            tag = BRANCHES_DELETE_CAPSULE_TAG,
            showCount = false,
        )
    }
}
