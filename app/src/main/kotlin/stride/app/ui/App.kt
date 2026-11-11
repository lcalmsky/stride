package stride.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.CompletionPopup
import stride.app.editor.FileEditor
import stride.app.editor.FileEditorManager
import stride.app.editor.RenamePopup
import stride.app.editor.TextFileEditor
import stride.app.editor.applyRename
import stride.app.editor.find.FindBar
import stride.app.editor.insertCompletion
import stride.app.editor.find.FindBarState
import stride.app.keys.KeyContext
import stride.app.navigation.BookmarksPopup
import stride.app.navigation.ChooserRow
import stride.app.navigation.FileStructure
import stride.app.navigation.FileStructureEntry
import stride.app.navigation.FileStructurePopup
import stride.app.navigation.TargetChooserPopup
import stride.app.navigation.goToDeclaration
import stride.app.navigation.navigationLocation
import stride.app.navigation.openBookmark
import stride.app.navigation.openDeclaration
import stride.app.navigation.openFileAtLine
import stride.app.run.ContextActionsPopup
import stride.app.run.RunConfigurationPopup
import stride.app.run.RunManager
import stride.app.run.TestGutterMarkers
import stride.app.run.runContextAction
import stride.app.search.FindInPathPopup
import stride.app.search.RecentFilesPopup
import stride.app.search.SearchEverywhere
import stride.app.search.SearchEverywherePopup
import stride.app.tree.TreeRow
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.MeshBackground
import stride.app.ui.theme.NeonEdge
import stride.app.ui.theme.glassPanel
import stride.app.ui.theme.focusHalo
import stride.app.ui.theme.neonGlow
import stride.app.ui.theme.neonSelectionBar
import stride.app.ui.theme.neonUnderline
import stride.app.ui.theme.rememberNeonAlpha
import stride.app.ui.toolwindow.ToolWindowManager
import stride.app.ui.toolwindow.ToolWindowPanel
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.FileTypeRegistry
import stride.search.Declaration
import stride.search.FileNameIndex
import stride.search.SearchTab
import java.awt.Cursor
import java.nio.file.Path

private val resizeCursor: PointerIcon by lazy {
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
}

private val horizontalResizeCursor: PointerIcon by lazy {
    PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
}

@Composable
fun App(
    state: AppState,
    onOpenFolder: () -> Unit,
    searchInitialQuery: String = "",
    chooseFolder: () -> Path? = { null },
) {
    val session = state.session
    var treeWidth by remember { mutableStateOf(DesignTokens.treeDefaultWidth) }
    var treeFocused by remember { mutableStateOf(false) }
    val gotoLineEditor = session?.editors?.selected as? TextFileEditor
    val selectedEditor = session?.editors?.selected
    LaunchedEffect(gotoLineEditor, gotoLineEditor?.gotoLineVisible) {
        // The goto-line field removes itself on close without a focus-lost event.
        if (state.focus == KeyContext.EDITOR_FIELD && gotoLineEditor?.gotoLineVisible != true) {
            state.focus = KeyContext.EDITOR
        }
    }
    // The caret is observed outside composition; reading it while composing would make the
    // whole window recompose on every arrow key and click.
    LaunchedEffect(session) {
        val current = session ?: return@LaunchedEffect
        snapshotFlow {
            val editor = current.editors.selected as? TextFileEditor ?: return@snapshotFlow null
            editor.file.path.toAbsolutePath().normalize() to editor.value.selection.start
        }.collect { location ->
            if (location != null) current.history.updateCurrent(location.first, location.second)
        }
    }
    val bookmarkEntries = session?.bookmarks?.entries?.toList()
    LaunchedEffect(session, selectedEditor, bookmarkEntries) {
        val editor = selectedEditor as? TextFileEditor ?: return@LaunchedEffect
        editor.bookmarkLines = session.bookmarks.linesOf(editor.file.path.toAbsolutePath().normalize())
    }
    MeshBackground(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxToolWindowHeight = (maxHeight * 0.8f).coerceAtLeast(ToolWindowManager.MIN_HEIGHT)
            Column(Modifier.fillMaxSize()) {
                TopBar(
                    projectName = session?.displayName() ?: "strIDE",
                    searchEnabled = session != null,
                    onSearch = { state.openSearch(SearchTab.ALL) },
                )
                if (session == null) {
                    NoProjectState(onOpenFolder, Modifier.weight(1f))
                } else {
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        if (state.projectTreeVisible) {
                            ProjectTreePanel(
                                session,
                                Modifier
                                    .width(treeWidth)
                                    .fillMaxHeight()
                                    .onFocusChanged { treeFocused = it.hasFocus },
                                focused = treeFocused,
                                focusRequests = state.treeFocusRequests,
                                revealRequests = state.revealRequests,
                            )
                            Splitter(onResize = { delta ->
                                treeWidth = (treeWidth + delta)
                                    .coerceIn(DesignTokens.treeMinWidth, DesignTokens.treeMaxWidth)
                            })
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .onFocusChanged { focus ->
                                    // The find bar sits inside this box and reports FIND_BAR itself.
                                    if (!state.findBar.visible) {
                                        if (focus.hasFocus) {
                                            if (state.focus != KeyContext.EDITOR_FIELD) {
                                                state.focus = KeyContext.EDITOR
                                            }
                                        } else if (state.focus == KeyContext.EDITOR ||
                                            state.focus == KeyContext.EDITOR_FIELD
                                        ) {
                                            state.focus = KeyContext.GLOBAL
                                        }
                                    }
                                },
                        ) {
                            EditorArea(
                                session,
                                state.findBar,
                                onFocusContext = { state.focus = it },
                                onGoToDeclaration = { _, offset -> goToDeclaration(state, offset) },
                                onJump = { editor -> session.history.record(navigationLocation(editor)) },
                                modifier = Modifier.fillMaxSize(),
                                focused = state.focus == KeyContext.EDITOR,
                                focusRequests = state.editorFocusRequests,
                            )
                            TestGutterMarkers(session, state.toolWindows) { message -> state.statusMessage = message }
                        }
                    }
                    if (state.toolWindows.visible) {
                        VerticalSplitter(onResize = { delta ->
                            state.toolWindows.height = (state.toolWindows.height - delta)
                                .coerceIn(ToolWindowManager.MIN_HEIGHT, maxToolWindowHeight)
                        })
                        ToolWindowPanel(
                            manager = state.toolWindows,
                            onFocusContext = { context ->
                                if (context == KeyContext.GLOBAL) {
                                    if (state.focus == state.toolWindows.active()?.context) {
                                        state.focus = KeyContext.GLOBAL
                                    }
                                } else {
                                    state.focus = context
                                }
                            },
                            modifier = Modifier.height(state.toolWindows.height).fillMaxWidth(),
                        )
                    }
                    StatusBar(session, focusLabel = focusLabel(state.focus), message = state.statusMessage)
                }
            }
        }
        if (state.searchVisible && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.SEARCH_POPUP
                    },
            ) {
                val search = remember(session) { SearchEverywhere(session.project, state, chooseFolder) }
                key(state.searchGeneration) {
                    SearchEverywherePopup(
                        search = search,
                        initialQuery = searchInitialQuery,
                        initialTab = state.searchTab,
                        onChoose = { entry ->
                            // Close first so an action that opens another popup (e.g. Go to Class) is not closed again.
                            state.searchVisible = false
                            if (!search.process(entry)) {
                                when (val item = entry.item) {
                                    is VFile -> session.editors.openFile(item)
                                    is Declaration -> session.editors.openFileAtLine(item.file, item.line)
                                }
                                refocusEditor(state)
                            } else if (!popupOwnsFocus(state) && state.focus == KeyContext.SEARCH_POPUP) {
                                // An action that opens no popup must not leave the modal context behind.
                                refocusEditor(state)
                            }
                        },
                        onClose = {
                            state.searchVisible = false
                            refocusEditor(state)
                        },
                    )
                }
            }
        }
        if (state.findInPathVisible && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.SEARCH_POPUP
                    },
            ) {
                key(state.findInPathGeneration) {
                    FindInPathPopup(
                        index = session.project.service<FileNameIndex>(),
                        initialQuery = state.findInPathQuery,
                        initialWholeWord = state.findInPathWholeWord,
                        onChoose = { hit ->
                            state.findInPathVisible = false
                            session.history.withoutRecording {
                                (session.editors.openFile(hit.file) as? TextFileEditor)?.let { editor ->
                                    editor.updateValue(TextFieldValue(editor.value.text, TextRange(hit.start, hit.end)))
                                    runCatching { editor.focusRequester.requestFocus() }
                                }
                            }
                            (session.editors.selected)?.let { session.history.push(navigationLocation(it)) }
                            refocusEditor(state)
                        },
                        onClose = {
                            state.findInPathVisible = false
                            refocusEditor(state)
                        },
                    )
                }
            }
        }
        if (state.recentFilesVisible && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.SEARCH_POPUP
                    },
            ) {
                RecentFilesPopup(
                    files = session.editors.recentFiles,
                    projectRoot = session.project.root,
                    onChoose = { file ->
                        state.recentFilesVisible = false
                        session.editors.openFile(file)
                        refocusEditor(state)
                    },
                    onClose = {
                        state.recentFilesVisible = false
                        refocusEditor(state)
                    },
                )
            }
        }
        if (state.declarationChooserVisible && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.CONTEXT_POPUP
                    },
            ) {
                TargetChooserPopup(
                    title = "Go to Declaration",
                    rows = state.declarationChoices.map { declaration ->
                        ChooserRow(
                            "${declaration.name} — ${declaration.relativePath}:${declaration.line + 1}",
                            declaration,
                        )
                    },
                    onChoose = { declaration ->
                        state.declarationChooserVisible = false
                        state.declarationChoices = emptyList()
                        openDeclaration(state, session, declaration)
                        refocusEditor(state)
                    },
                    onClose = {
                        state.declarationChooserVisible = false
                        state.declarationChoices = emptyList()
                        refocusEditor(state)
                    },
                )
            }
        }
        if (state.testChooserVisible && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.CONTEXT_POPUP
                    },
            ) {
                TargetChooserPopup(
                    title = "Go to Test",
                    rows = state.testChoices.map { file -> ChooserRow("${file.name} — ${relativePath(session, file)}", file) },
                    onChoose = { file ->
                        state.testChooserVisible = false
                        state.testChoices = emptyList()
                        openFileAtLine(state, session, file, 0)
                        refocusEditor(state)
                    },
                    onClose = {
                        state.testChooserVisible = false
                        state.testChoices = emptyList()
                        refocusEditor(state)
                    },
                )
            }
        }
        if (state.fileStructureVisible && session != null) {
            (session.editors.selected as? TextFileEditor)?.let { editor ->
                FileStructureHost(state, editor)
            }
        }
        if (state.bookmarksVisible && session != null) {
            val rows = session.bookmarks.entries.sortedWith(compareBy({ it.relativePath }, { it.line }))
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.CONTEXT_POPUP
                    },
            ) {
                BookmarksPopup(
                    bookmarks = rows,
                    onChoose = { bookmark ->
                        state.bookmarksVisible = false
                        openBookmark(state, bookmark)
                        refocusEditor(state)
                    },
                    onRemove = { bookmark -> session.bookmarks.remove(bookmark) },
                    onClose = {
                        state.bookmarksVisible = false
                        refocusEditor(state)
                    },
                )
            }
        }
        if (state.contextActionsVisible && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) {
                            state.focus = KeyContext.CONTEXT_POPUP
                        }
                    },
            ) {
                ContextActionsPopup(
                    actions = state.contextActions,
                    onChoose = { action ->
                        state.contextActionsVisible = false
                        refocusEditor(state)
                        runContextAction(state, action.kind)
                    },
                    onClose = {
                        state.contextActionsVisible = false
                        refocusEditor(state)
                    },
                )
            }
        }
        if (state.runConfigurationsVisible && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) {
                            state.focus = KeyContext.CONTEXT_POPUP
                        }
                    },
            ) {
                RunConfigurationPopup(
                    configurations = state.runConfigurationChoices,
                    projectRoot = session.project.root,
                    onChoose = { configuration ->
                        state.runConfigurationsVisible = false
                        refocusEditor(state)
                        session.project.service<RunManager>().run(configuration)
                        state.toolWindows.activate("run")
                    },
                    onClose = {
                        state.runConfigurationsVisible = false
                        refocusEditor(state)
                    },
                )
            }
        }
        val completion = state.completion
        if (completion != null && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.CONTEXT_POPUP
                    },
            ) {
                CompletionPopup(
                    request = completion,
                    onChoose = { item ->
                        state.completion = null
                        insertCompletion(completion, item)
                        refocusEditor(state)
                    },
                    onClose = {
                        state.completion = null
                        refocusEditor(state)
                    },
                )
            }
        }
        val rename = state.rename
        if (rename != null && session != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { focus ->
                        if (focus.hasFocus) state.focus = KeyContext.CONTEXT_POPUP
                    },
            ) {
                RenamePopup(
                    initialName = rename.original,
                    onRename = { name ->
                        state.rename = null
                        applyRename(state, rename, name)
                        refocusEditor(state)
                    },
                    onClose = {
                        state.rename = null
                        refocusEditor(state)
                    },
                )
            }
        }
    }
}

/** Parses the selected file off the UI thread and shows the File Structure popup over it. */
@Composable
private fun FileStructureHost(state: AppState, editor: TextFileEditor) {
    var entries by remember(editor) { mutableStateOf(emptyList<FileStructureEntry>()) }
    LaunchedEffect(editor, editor.value.text) {
        val text = editor.value.text
        entries = withContext(Dispatchers.Default) { FileStructure.entries(editor.file.name, text) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { focus ->
                if (focus.hasFocus) state.focus = KeyContext.CONTEXT_POPUP
            },
    ) {
        FileStructurePopup(
            entries = entries,
            onChoose = { entry ->
                state.fileStructureVisible = false
                editor.gotoLine((entry.line + 1).toString())
                refocusEditor(state)
            },
            onClose = {
                state.fileStructureVisible = false
                refocusEditor(state)
            },
        )
    }
}

/** True while a popup or the find bar owns the keyboard, so closing the search must not refocus. */
private fun popupOwnsFocus(state: AppState): Boolean =
    state.searchVisible || state.findInPathVisible || state.recentFilesVisible ||
        state.declarationChooserVisible || state.testChooserVisible || state.fileStructureVisible ||
        state.bookmarksVisible || state.contextActionsVisible || state.runConfigurationsVisible ||
        state.completion != null || state.rename != null || state.findBar.visible

private fun refocusEditor(state: AppState) {
    state.focus = KeyContext.EDITOR
    (state.session?.editors?.selected as? TextFileEditor)?.let { editor ->
        runCatching { editor.focusRequester.requestFocus() }
    }
}

@Composable
private fun TopBar(projectName: String, searchEnabled: Boolean, onSearch: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.topBarHeight)
            .glassPanel()
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(
            Modifier
                .size(18.dp)
                .neonGlow(DesignTokens.accentGlow, radius = 8.dp, shape = CircleShape),
        )
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            projectName,
            color = DesignTokens.text,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (searchEnabled) {
            SearchPill(onSearch)
        }
    }
}

@Composable
private fun LogoMark(modifier: Modifier) {
    val painter = rememberLogoPainter()
    if (painter != null) {
        Image(painter, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier.background(DesignTokens.accent, RoundedCornerShape(DesignTokens.radiusTiny)))
    }
}

@Composable
private fun SearchPill(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(DesignTokens.radiusSmall)
    Row(
        Modifier
            .height(24.dp)
            .clip(shape)
            .background(if (hovered) DesignTokens.raised else DesignTokens.background)
            .border(1.dp, if (hovered) DesignTokens.accent2 else DesignTokens.border, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchIcon(Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Search",
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.smallTextSize,
            fontFamily = DesignTokens.uiFont,
        )
        Spacer(Modifier.width(DesignTokens.space8))
        Text(
            "⇧⇧",
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

@Composable
private fun Splitter(onResize: (Dp) -> Unit) {
    Box(
        Modifier
            .width(DesignTokens.splitterWidth)
            .fillMaxHeight()
            .pointerHoverIcon(resizeCursor)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResize(dragAmount.x.toDp())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(DesignTokens.glassBorderSide))
    }
}

@Composable
private fun VerticalSplitter(onResize: (Dp) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.splitterWidth)
            .pointerHoverIcon(horizontalResizeCursor)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResize(dragAmount.y.toDp())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DesignTokens.glassBorderSide))
    }
}

@Composable
private fun ProjectTreePanel(
    session: ProjectSession,
    modifier: Modifier,
    focused: Boolean = false,
    focusRequests: Int = 0,
    revealRequests: Int = 0,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequests) {
        if (focusRequests == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
    Column(
        modifier
            .glassPanel()
            .focusHalo(focused)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(DesignTokens.treeHeaderHeight)
                .padding(horizontal = DesignTokens.space12),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PROJECT",
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(revealRequests) {
            withFrameNanos { }
            val key = session.editors.selected?.let { pathKey(it.file) } ?: return@LaunchedEffect
            val index = session.tree.rows().indexOfFirst { pathKey(it.file) == key }
            if (index >= 0) listState.scrollToItem(index)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(session.tree.rows(), key = { it.file.path.toString() }) { row ->
                    TreeRowItem(session, row)
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style = DesignTokens.scrollbarStyle,
            )
        }
    }
}

@Composable
private fun TreeRowItem(session: ProjectSession, row: TreeRow) {
    val interaction = remember(row.file.path) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selectedFile = session.editors.selected?.file
    val selected = selectedFile != null && samePath(selectedFile, row.file)
    val neon = rememberNeonAlpha(selected)
    val background = when {
        selected -> DesignTokens.selectionGlowBackground
        hovered -> DesignTokens.raised.copy(alpha = 0.6f)
        else -> DesignTokens.transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.treeRowHeight)
            .background(background)
            .neonGlow(
                DesignTokens.accent2Glow,
                radius = DesignTokens.glowRadius,
                alpha = neon * 0.6f,
            )
            .neonSelectionBar(alpha = neon)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                if (row.file.isDirectory) session.tree.toggle(row.file) else session.editors.openFile(row.file)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(8.dp + DesignTokens.treeIndent * row.depth).fillMaxHeight()) {
            IndentGuides(row.depth)
        }
        if (row.file.isDirectory) {
            ChevronIcon(row.isExpanded, Modifier.size(12.dp))
            Spacer(Modifier.width(DesignTokens.space4))
            FolderIcon(Modifier.size(14.dp))
        } else {
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.width(DesignTokens.space4))
            FileTypeIcon(row.file.name, Modifier.size(14.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            row.file.name,
            color = if (selected) DesignTokens.text else DesignTokens.text.copy(alpha = 0.9f),
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = DesignTokens.space8),
        )
    }
}

@Composable
private fun IndentGuides(depth: Int) {
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 1.dp.toPx()
        for (level in 0 until depth) {
            val x = (8.dp + DesignTokens.treeIndent * level + 6.dp).toPx()
            drawLine(DesignTokens.glassBorderSide, Offset(x, 0f), Offset(x, size.height), stroke)
        }
    }
}

@Composable
private fun EditorArea(
    session: ProjectSession,
    findBar: FindBarState,
    onFocusContext: (KeyContext) -> Unit,
    onGoToDeclaration: (TextFileEditor, Int) -> Unit,
    onJump: (TextFileEditor) -> Unit,
    modifier: Modifier,
    focused: Boolean = false,
    focusRequests: Int = 0,
) {
    LaunchedEffect(focusRequests) {
        if (focusRequests == 0) return@LaunchedEffect
        withFrameNanos { }
        (session.editors.selected as? TextFileEditor)?.let { runCatching { it.focusRequester.requestFocus() } }
    }
    Column(modifier.focusHalo(focused)) {
        TabBar(session.editors)
        if (findBar.visible) {
            FindBar(
                findBar,
                session.editors.selected as? TextFileEditor,
                onFocusContext,
                Modifier.fillMaxWidth(),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val selected = session.editors.selected
            if (selected == null) {
                EmptyEditor()
            } else {
                if (selected is TextFileEditor) {
                    selected.onFocusContext = onFocusContext
                    selected.onGoToDeclarationAt = { offset -> onGoToDeclaration(selected, offset) }
                    selected.onJump = { onJump(selected) }
                }
                selected.Content(Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun TabBar(manager: FileEditorManager) {
    val scrollState = rememberScrollState()
    var tabBounds by remember { mutableStateOf<Map<String, Rect>>(emptyMap()) }
    val selectedKey = manager.selected?.let { pathKey(it.file) }
    LaunchedEffect(selectedKey, tabBounds, scrollState.viewportSize) {
        val key = selectedKey ?: return@LaunchedEffect
        val bounds = tabBounds[key] ?: return@LaunchedEffect
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
            .fillMaxWidth()
            .height(DesignTokens.tabStripHeight)
            .glassPanel()
            .horizontalScroll(scrollState),
    ) {
        manager.openFiles.forEach { editor ->
            val key = pathKey(editor.file)
            Box(
                Modifier.onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInParent()
                    if (tabBounds[key] != bounds) tabBounds = tabBounds + (key to bounds)
                },
            ) {
                TabItem(manager, editor)
            }
        }
    }
}

@Composable
private fun TabItem(manager: FileEditorManager, editor: FileEditor) {
    val interaction = remember(editor.file.path) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = editor === manager.selected
    val neon = rememberNeonAlpha(active)
    val background = if (hovered) DesignTokens.raised.copy(alpha = 0.6f) else DesignTokens.transparent
    Row(
        Modifier
            .fillMaxHeight()
            .widthIn(max = DesignTokens.tabMaxWidth)
            .background(background)
            .neonUnderline(DesignTokens.accent2, alpha = neon, edge = NeonEdge.BOTTOM)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                manager.openFile(editor.file)
            }
            .padding(start = DesignTokens.space8, end = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(editor.file.name, Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            editor.file.name,
            color = if (active) DesignTokens.text else DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.tabTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(6.dp))
        TabCloseButton(manager, editor, active, hovered)
    }
}

@Composable
private fun TabCloseButton(
    manager: FileEditorManager,
    editor: FileEditor,
    active: Boolean,
    hovered: Boolean,
) {
    Box(
        Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .clickable { manager.closeFile(editor.file) },
        contentAlignment = Alignment.Center,
    ) {
        when {
            editor.isModified && !hovered -> Box(
                Modifier
                    .size(6.dp)
                    .neonGlow(DesignTokens.neonPinkGlow, radius = 6.dp, shape = CircleShape)
                    .background(DesignTokens.neonPink, CircleShape),
            )
            active || hovered -> CloseIcon(Modifier.size(9.dp))
        }
    }
}

@Composable
private fun StatusBar(session: ProjectSession, focusLabel: String? = null, message: String? = null) {
    val editor = session.editors.selected
    val textEditor = editor as? TextFileEditor
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.statusBarHeight)
            .glassPanel()
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            relativePath(session, editor?.file),
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = DesignTokens.statusTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (editor != null) {
            if (textEditor != null) {
                val (line, column) = lineAndColumn(textEditor.value.text, textEditor.value.selection.start)
                StatusItem("Ln $line, Col $column")
            }
            StatusItem(FileTypeRegistry.getFileType(editor.file.name).name)
            StatusItem("UTF-8")
            if (textEditor != null && textEditor.isReadOnly) {
                ReadOnlyBadge()
            }
        }
        if (message != null) {
            Spacer(Modifier.width(DesignTokens.space16))
            Text(
                message,
                color = DesignTokens.neonAmber,
                fontSize = DesignTokens.statusTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                modifier = Modifier
                    .neonGlow(DesignTokens.neonAmberGlow, radius = 6.dp)
                    .testTag("status-message"),
            )
        }
        if (focusLabel != null) {
            FocusChip(focusLabel)
        }
    }
}

@Composable
private fun FocusChip(label: String) {
    Spacer(Modifier.width(DesignTokens.space16))
    Box(
        Modifier
            .neonGlow(DesignTokens.accent2Glow, radius = 6.dp)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            label,
            color = DesignTokens.accent2,
            fontSize = DesignTokens.statusTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
        )
    }
}

@Composable
private fun StatusItem(text: String) {
    Spacer(Modifier.width(DesignTokens.space16))
    Text(
        text,
        color = DesignTokens.textSecondaryOnGlass,
        fontSize = DesignTokens.statusTextSize,
        fontFamily = DesignTokens.uiFont,
        maxLines = 1,
    )
}

@Composable
private fun ReadOnlyBadge() {
    Spacer(Modifier.width(DesignTokens.space16))
    Box(
        Modifier
            .background(DesignTokens.raised, RoundedCornerShape(DesignTokens.radiusSmall))
            .border(1.dp, DesignTokens.border, RoundedCornerShape(DesignTokens.radiusSmall))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            "Read-only",
            color = DesignTokens.textSecondaryOnGlass,
            fontSize = 10.sp,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

@Composable
private fun EmptyEditor() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(alpha = 0.4f, width = 280.dp)
        Spacer(Modifier.height(28.dp))
        ShortcutList()
    }
}

@Composable
private fun NoProjectState(onOpenFolder: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(alpha = 1f, width = 320.dp)
        Spacer(Modifier.height(24.dp))
        OpenFolderButton(onOpenFolder)
        Spacer(Modifier.height(28.dp))
        ShortcutList()
    }
}

private const val WORDMARK_VIEWBOX_WIDTH = 617f
private const val WORDMARK_ASPECT = 200f / WORDMARK_VIEWBOX_WIDTH
private const val WORDMARK_CARET_X = 556.71f
private const val WORDMARK_CARET_TOP = 50f
private const val WORDMARK_CARET_WIDTH = 5f
private const val WORDMARK_CARET_HEIGHT = 100f

@Composable
private fun Wordmark(alpha: Float, width: Dp) {
    val wordmark = rememberWordmarkPainter()
    if (wordmark != null) {
        val caretAlpha = rememberWordmarkCaretAlpha()
        Box(Modifier.width(width).height(width * WORDMARK_ASPECT).alpha(alpha)) {
            Image(
                wordmark,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            if (caretAlpha > 0f) {
                Canvas(Modifier.fillMaxSize()) {
                    val scale = size.width / WORDMARK_VIEWBOX_WIDTH
                    drawRoundRect(
                        color = DesignTokens.accent2.copy(alpha = caretAlpha),
                        topLeft = Offset(WORDMARK_CARET_X * scale, WORDMARK_CARET_TOP * scale),
                        size = Size(WORDMARK_CARET_WIDTH * scale, WORDMARK_CARET_HEIGHT * scale),
                        cornerRadius = CornerRadius(WORDMARK_CARET_WIDTH / 2f * scale),
                    )
                }
            }
        }
    } else {
        val icon = rememberLogoPainter()
        if (icon != null) {
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.size(width * 0.5f).alpha(alpha),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                "strIDE",
                color = DesignTokens.text.copy(alpha = alpha),
                fontSize = 24.sp,
                fontFamily = DesignTokens.uiFont,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** A text caret blink: fully visible for 0.5 s, then hidden for 0.5 s, stepping rather than fading. */
@Composable
private fun rememberWordmarkCaretAlpha(): Float {
    if (!LocalWordmarkCaretBlink.current) return 1f
    val transition = rememberInfiniteTransition(label = "wordmark-caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 500
                0f at 501
                0f at 1000
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "wordmark-caret-alpha",
    )
    return alpha
}

@Composable
private fun OpenFolderButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .background(
                if (hovered) DesignTokens.accentHover else DesignTokens.accent,
                RoundedCornerShape(DesignTokens.radiusSmall),
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16 + DesignTokens.space4, vertical = DesignTokens.space8),
    ) {
        Text(
            "Open Folder…",
            color = DesignTokens.onAccent,
            fontSize = DesignTokens.uiTextSize,
            fontFamily = DesignTokens.uiFont,
            fontWeight = FontWeight.Medium,
        )
    }
}

private data class Shortcut(val label: String, val keys: String)

private val shortcuts = listOf(
    Shortcut("Search Everywhere", "⇧⇧"),
    Shortcut("Open Folder", "⌘O"),
    Shortcut("Save", "⌘S"),
    Shortcut("Close Tab", "⌘W"),
)

@Composable
private fun ShortcutList() {
    Column(
        Modifier.width(300.dp),
        verticalArrangement = Arrangement.spacedBy(DesignTokens.space8),
    ) {
        for (shortcut in shortcuts) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    shortcut.label,
                    color = DesignTokens.muted,
                    fontSize = DesignTokens.smallTextSize,
                    fontFamily = DesignTokens.uiFont,
                )
                KeyCap(shortcut.keys)
            }
        }
    }
}

@Composable
private fun KeyCap(keys: String) {
    val shape = RoundedCornerShape(DesignTokens.radiusSmall)
    Box(
        Modifier
            .background(DesignTokens.raised, shape)
            .border(1.dp, DesignTokens.border, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            keys,
            color = DesignTokens.muted,
            fontSize = DesignTokens.labelTextSize,
            fontFamily = DesignTokens.uiFont,
        )
    }
}

private fun samePath(a: VFile, b: VFile): Boolean = pathKey(a) == pathKey(b)

private fun pathKey(file: VFile): String = file.path.toAbsolutePath().normalize().toString()

/** The status bar's context chip; `null` keeps the chip hidden while nothing specific has focus. */
private fun focusLabel(context: KeyContext): String? = when (context) {
    KeyContext.GLOBAL -> null
    KeyContext.EDITOR, KeyContext.EDITOR_FIELD -> "Editor"
    KeyContext.FIND_BAR -> "Find"
    KeyContext.SEARCH_POPUP -> "Search"
    KeyContext.RUN_WINDOW -> "Run"
    KeyContext.TERMINAL -> "Terminal"
    KeyContext.CONTEXT_POPUP -> null
}

private fun relativePath(session: ProjectSession, file: VFile?): String {
    if (file == null) return ""
    val path = file.path.toAbsolutePath().normalize()
    return runCatching { session.project.root.relativize(path).toString().replace('\\', '/') }
        .getOrDefault(path.toString())
}

private fun lineAndColumn(text: String, offset: Int): Pair<Int, Int> {
    val clamped = offset.coerceIn(0, text.length)
    var line = 1
    var lastBreak = -1
    for (i in 0 until clamped) {
        if (text[i] == '\n') {
            line++
            lastBreak = i
        }
    }
    return line to (clamped - lastBreak)
}
