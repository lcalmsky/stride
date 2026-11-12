package stride.app.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.LocalTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.CompletionPopup
import stride.app.editor.FileEditor
import stride.app.editor.FileEditorManager
import stride.app.editor.LocalEditorScrollState
import stride.app.editor.RenamePopup
import stride.app.editor.TextFileEditor
import stride.app.editor.applyRename
import stride.app.editor.find.FindBar
import stride.app.editor.find.FindBarState
import stride.app.editor.find.findBarHeight
import stride.app.editor.insertCompletion
import stride.app.glass.GlassHoles
import stride.app.glass.GlassRegistry
import stride.app.glass.GlassRuntime
import stride.app.glass.LocalNativeGlass
import stride.app.glass.chromeLayout
import stride.app.glass.nativeGlass
import stride.app.glass.windowDragRegion
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
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
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.CapsuleEdge
import stride.app.ui.theme.EditorInsets
import stride.app.ui.theme.LocalEditorInsets
import stride.app.ui.theme.LocalSidebarChrome
import stride.app.ui.theme.MeshBackground
import stride.app.ui.theme.SidebarChrome
import stride.app.ui.theme.capsuleGlass
import stride.app.ui.theme.contentClip
import stride.app.ui.theme.focusHalo
import stride.app.ui.theme.glassCapsule
import stride.app.ui.theme.glassLensCapsule
import stride.app.ui.theme.rememberNeonAlpha
import stride.app.ui.theme.sidebarGlass
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
    val appearance = AppearanceState.appearance
    LaunchedEffect(appearance) {
        // Match highlights carry palette colours, so a live theme switch repaints them too.
        if (state.findBar.visible) {
            state.findBar.refresh(session?.editors?.selected as? TextFileEditor)
        }
    }
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
    val nativeGlass = GlassRuntime.active.value
    CompositionLocalProvider(LocalNativeGlass provides nativeGlass) {
        LaunchedEffect(nativeGlass) {
            if (!nativeGlass) return@LaunchedEffect
            // One native sync per changed frame; the elements themselves only report bounds.
            snapshotFlow { GlassRegistry.shared.revision }.collect {
                withFrameNanos { }
                GlassRegistry.shared.flush()
            }
        }
        MeshBackground(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val maxToolWindowHeight = (maxHeight * 0.8f).coerceAtLeast(ToolWindowManager.MIN_HEIGHT)
            // On the native path the merged title row replaces the top chrome inset, and the
            // sidebar starts below that row instead of reaching the window edge.
            val chrome = chromeLayout(nativeGlass)
            val topInset = chrome.topInset
            val topRowHeight = chrome.topRowHeight
            val sidebarTop = chrome.sidebarTop
            // The floating sidebar spans the window with chromeInset above and below it.
            val sidebarHeight = (maxHeight - sidebarTop - DesignTokens.chromeInset).coerceAtLeast(0.dp)
            val treeVisible = session != null && state.projectTreeVisible
            // Content runs edge to edge; the text column starts right of the floating sidebar.
            val contentLeft = DesignTokens.chromeInset +
                (if (treeVisible) treeWidth + DesignTokens.contentGap else 0.dp)
            val topBand = chrome.topBand
            val bottomBand = DesignTokens.chromeInset + DesignTokens.statusCapsuleHeight + DesignTokens.chromeGap
            if (session == null) {
                WelcomeScreen(onOpenFolder)
            } else {
                CompositionLocalProvider(
                    LocalEditorScrollState provides (session.editors.selected as? TextFileEditor)?.verticalScroll,
                    LocalSidebarChrome provides if (treeVisible) {
                        SidebarChrome(
                            left = DesignTokens.chromeInset,
                            width = treeWidth,
                            top = sidebarTop,
                            bottom = DesignTokens.chromeInset,
                        )
                    } else {
                        null
                    },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
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
                                    focusRequests = state.editorFocusRequests,
                                    leftInset = contentLeft,
                                    topBand = topBand,
                                    bottomInset = if (state.toolWindows.visible) DesignTokens.chromeGap else bottomBand,
                                )
                                TestGutterMarkers(session, state.toolWindows) { message -> state.statusMessage = message }
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
                                    contentInset = contentLeft,
                                    contentBottomInset = bottomBand,
                                )
                            }
                        }
                        if (!nativeGlass) ContentEdgeFade(topBand = topBand, bottomBand = bottomBand)
                        if (treeVisible) {
                            ProjectTreePanel(
                                session,
                                Modifier
                                    .offset(x = DesignTokens.chromeInset, y = sidebarTop)
                                    .width(treeWidth)
                                    // The panel floats chromeInset in from the bottom edge too, so
                                    // its rounded bottom corners stay inside the window.
                                    .height(sidebarHeight)
                                    .onFocusChanged { treeFocused = it.hasFocus },
                                focused = treeFocused,
                                focusRequests = state.treeFocusRequests,
                                revealRequests = state.revealRequests,
                            )
                            Splitter(
                                onResize = { delta ->
                                    treeWidth = (treeWidth + delta)
                                        .coerceIn(DesignTokens.treeMinWidth, DesignTokens.treeMaxWidth)
                                },
                                modifier = Modifier
                                    .offset(
                                        x = DesignTokens.chromeInset + treeWidth - DesignTokens.space4,
                                        y = sidebarTop,
                                    )
                                    .width(DesignTokens.chromeGap)
                                    .height(sidebarHeight),
                            )
                        }
                        TopChromeRow(
                            projectName = session.displayName(),
                            manager = session.editors,
                            onSearch = { state.openSearch(SearchTab.ALL) },
                            leftInset = if (nativeGlass) {
                                maxOf(contentLeft, DesignTokens.nativeTitleLightsInset)
                            } else {
                                contentLeft
                            },
                            topInset = topInset,
                            rowHeight = topRowHeight,
                            dragInset = if (nativeGlass) DesignTokens.nativeTitleLightsInset else 0.dp,
                        )
                        BottomChromeRow(
                            session = session,
                            focusLabel = focusLabel(state.focus),
                            message = state.statusMessage,
                            leftInset = contentLeft,
                            modifier = Modifier.align(Alignment.BottomStart),
                        )
                    }
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

/**
 * The scroll edge effect's fade half: the content under the floating control rows darkens toward
 * the window edge. The editor adds the blurred half over its own content.
 */
@Composable
private fun BoxScope.ContentEdgeFade(topBand: Dp, bottomBand: Dp) {
    Canvas(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topBand)) {
        drawRect(
            Brush.verticalGradient(
                0f to DesignTokens.scrim,
                1f to Color.Transparent,
            ),
        )
    }
    Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(bottomBand)) {
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent,
                1f to DesignTokens.scrim,
            ),
        )
    }
}

/** The floating top row: project capsule, tab group, search capsule; the content shows between. */
@Composable
private fun TopChromeRow(
    projectName: String,
    manager: FileEditorManager?,
    onSearch: () -> Unit,
    leftInset: Dp,
    topInset: Dp = DesignTokens.chromeInset,
    rowHeight: Dp = DesignTokens.capsuleHeight,
    dragInset: Dp = 0.dp,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = topInset, end = DesignTokens.chromeInset)
            .height(rowHeight),
    ) {
        // The empty strip is the window drag handle on the native merged title row. It starts
        // right of the traffic lights so a press on them still reaches the system buttons.
        Box(Modifier.fillMaxSize().padding(start = dragInset).windowDragRegion())
        Row(
            Modifier.fillMaxSize().padding(start = leftInset),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.chromeGap),
        ) {
            ProjectCapsule(projectName, rowHeight)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (manager != null && manager.openFiles.isNotEmpty()) {
                    TabGroup(manager, rowHeight, Modifier.align(Alignment.CenterStart))
                }
            }
            SearchCapsule(onSearch, rowHeight)
        }
    }
}

/** Keeps a press on a floating capsule from reaching the editor under it; nothing is consumed. */
private fun Modifier.editorPressBlocker(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent()
    }
}

@Composable
private fun ProjectCapsule(name: String, height: Dp = DesignTokens.capsuleHeight) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(height)
            .capsuleGlass(shape)
            .nativeGlass("top-project", shape, tint = DesignTokens.nativeGlassTint)
            .editorPressBlocker()
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogoMark(Modifier.size(16.dp))
        Spacer(Modifier.width(DesignTokens.space8))
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            Text(
                name,
                color = DesignTokens.text,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
private fun SearchCapsule(onClick: () -> Unit, height: Dp = DesignTokens.capsuleHeight) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(height)
            .capsuleGlass(shape)
            .nativeGlass("top-search", shape, tint = DesignTokens.nativeGlassTint)
            .then(
                if (hovered) {
                    Modifier.border(1.dp, DesignTokens.accent2.copy(alpha = 0.6f), shape)
                } else {
                    Modifier
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
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
                KeyRouter.SEARCH_EVERYWHERE_GESTURE,
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.labelTextSize,
                fontFamily = DesignTokens.uiFont,
            )
        }
    }
}

/** The invisible drag handle on the floating sidebar's right edge. */
@Composable
private fun Splitter(onResize: (Dp) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .pointerHoverIcon(resizeCursor)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onResize(dragAmount.x.toDp())
                }
            },
    )
}

@Composable
private fun VerticalSplitter(onResize: (Dp) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.chromeGap)
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
    val shape = RoundedCornerShape(DesignTokens.sidebarRadius)
    CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
        Column(
            modifier
                .sidebarGlass(shape)
                .nativeGlass("sidebar", shape, tint = DesignTokens.nativeGlassTint)
                .editorPressBlocker()
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
                    "Project",
                    color = DesignTokens.textSecondaryOnGlass,
                    fontSize = DesignTokens.uiTextSize,
                    fontFamily = DesignTokens.uiFont,
                    fontWeight = FontWeight.Medium,
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
}

@Composable
private fun TreeRowItem(session: ProjectSession, row: TreeRow) {
    val interaction = remember(row.file.path) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val selectedFile = session.editors.selected?.file
    val selected = selectedFile != null && samePath(selectedFile, row.file)
    val neon = rememberNeonAlpha(selected)
    val capsuleShape = RoundedCornerShape(DesignTokens.radiusSmall)
    val capsule = if (selected) {
        Modifier.glassLensCapsule(
            shape = capsuleShape,
            insetX = 6.dp,
            insetY = 2.dp,
            lineAlpha = neon,
        )
    } else {
        Modifier.glassCapsule(
            shape = capsuleShape,
            insetX = 6.dp,
            insetY = 2.dp,
            fillAlpha = if (hovered) 1f else 0f,
            lineAlpha = 0f,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(DesignTokens.treeRowHeight)
            .then(capsule)
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
            FileTypeIcon(
                row.file.name,
                Modifier.size(14.dp),
                color = if (selected) DesignTokens.accent else DesignTokens.muted,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            row.file.name,
            color = if (selected) DesignTokens.selectedText else DesignTokens.textSecondaryOnGlass,
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
    focusRequests: Int = 0,
    leftInset: Dp = 0.dp,
    topBand: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
) {
    LaunchedEffect(focusRequests) {
        if (focusRequests == 0) return@LaunchedEffect
        withFrameNanos { }
        (session.editors.selected as? TextFileEditor)?.let { runCatching { it.focusRequester.requestFocus() } }
    }
    // The editor fills the area and draws under the chrome; the floating controls only reserve
    // insets. The editor has no focus halo: its area reaches the window edge, where the line
    // would read as a bar across the whole title row.
    Box(modifier) {
        val nativeGlass = LocalNativeGlass.current
        val barHeight = if (findBar.visible) findBarHeight(findBar) else 0.dp
        val topInset = if (findBar.visible) topBand + DesignTokens.chromeGap + barHeight else topBand
        // The native surface starts below the find bar so the bar itself floats on glass.
        val surfaceTop = topBand + if (findBar.visible) barHeight + DesignTokens.chromeGap else 0.dp
        val surfaceShape = RoundedCornerShape(DesignTokens.contentSurfaceRadius)
        val holes = if (nativeGlass) GlassHoles.snapshot() else emptyList()
        CompositionLocalProvider(
            LocalEditorInsets provides if (nativeGlass) {
                EditorInsets()
            } else {
                EditorInsets(top = topInset, bottom = bottomInset, left = leftInset)
            },
        ) {
            val selected = session.editors.selected
            if (selected == null) {
                EmptyEditor(leftInset)
            } else {
                if (selected is TextFileEditor) {
                    selected.onFocusContext = onFocusContext
                    selected.onGoToDeclarationAt = { offset -> onGoToDeclaration(selected, offset) }
                    selected.onJump = { onJump(selected) }
                }
                if (nativeGlass) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(
                                top = surfaceTop,
                                start = leftInset,
                                end = DesignTokens.chromeInset,
                                bottom = bottomInset,
                            )
                            .nativeGlass("editor-surface", surfaceShape, tint = DesignTokens.editorGlassTint)
                            .contentClip(surfaceShape, holes),
                    ) {
                        selected.Content(Modifier.fillMaxSize())
                    }
                } else {
                    selected.Content(Modifier.fillMaxSize())
                }
            }
        }
        if (findBar.visible) {
            val findShape = RoundedCornerShape(DesignTokens.capsuleHeight / 2)
            FindBar(
                findBar,
                session.editors.selected as? TextFileEditor,
                onFocusContext,
                Modifier
                    .align(Alignment.TopStart)
                    .padding(top = topBand, start = leftInset, end = DesignTokens.chromeInset)
                    .fillMaxWidth(),
                shape = findShape,
            )
        }
    }
}

/**
 * The open tabs as one glass capsule group. The selected tab is a glass pill that slides and
 * stretches to the newly selected tab; the pill's cyan line keeps the selection instant.
 */
@Composable
private fun TabGroup(
    manager: FileEditorManager,
    height: Dp = DesignTokens.capsuleHeight,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val groupShape = RoundedCornerShape(percent = 50)
    val pillShape = RoundedCornerShape(percent = 50)
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
    val density = LocalDensity.current
    val selectedBounds = selectedKey?.let { tabBounds[it] }
    val targetLeft = selectedBounds?.let {
        with(density) { (it.left - scrollState.value).toDp() }
    } ?: 0.dp
    val targetWidth = selectedBounds?.let { with(density) { it.width.toDp() } } ?: 0.dp
    val morph: AnimationSpec<Dp> = if (LocalAnimationsEnabled.current) {
        tween(DesignTokens.motionMediumMillis, easing = DesignTokens.standardEasing)
    } else {
        snap()
    }
    val pillLeft by animateDpAsState(targetLeft, morph, label = "tab-pill-left")
    val pillWidth by animateDpAsState(targetWidth, morph, label = "tab-pill-width")
    Box(
        modifier
            .height(height)
            .capsuleGlass(groupShape)
            .nativeGlass("top-tabs", groupShape, tint = DesignTokens.nativeGlassTint)
            .editorPressBlocker()
            .padding(horizontal = DesignTokens.space4),
    ) {
        if (selectedBounds != null && pillWidth > 0.dp) {
            Box(
                Modifier
                    .offset(x = pillLeft, y = 3.dp)
                    .width(pillWidth)
                    .height((height - 6.dp).coerceAtLeast(1.dp))
                    .glassLensCapsule(
                        shape = pillShape,
                        insetX = 1.dp,
                        insetY = 1.dp,
                        lineEdge = CapsuleEdge.BOTTOM,
                        lineAlpha = 1f,
                    ),
            )
        }
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            Row(Modifier.fillMaxHeight().horizontalScroll(scrollState)) {
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
    }
}

@Composable
private fun TabItem(manager: FileEditorManager, editor: FileEditor) {
    val interaction = remember(editor.file.path) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = editor === manager.selected
    val hoverShape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .fillMaxHeight()
            .widthIn(max = DesignTokens.tabMaxWidth)
            .then(
                if (hovered && !active) {
                    Modifier.glassCapsule(
                        shape = hoverShape,
                        insetX = 2.dp,
                        insetY = 4.dp,
                        fillAlpha = 1f,
                        lineAlpha = 0f,
                    )
                } else {
                    Modifier
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                manager.openFile(editor.file)
            }
            .padding(start = DesignTokens.space8, end = DesignTokens.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileTypeIcon(editor.file.name, Modifier.size(13.dp), color = if (active) DesignTokens.accent else DesignTokens.muted)
        Spacer(Modifier.width(8.dp))
        Text(
            editor.file.name,
            color = if (active) DesignTokens.selectedText else DesignTokens.textOnBusyGlass,
            fontSize = DesignTokens.tabTextSize,
            fontFamily = DesignTokens.uiFont,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
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
                    .background(DesignTokens.neonPink, CircleShape),
            )
            active || hovered -> CloseIcon(Modifier.size(9.dp))
        }
    }
}

/** The floating bottom row: the file path capsule at the left, one status capsule group right. */
@Composable
private fun BottomChromeRow(
    session: ProjectSession,
    focusLabel: String? = null,
    message: String? = null,
    leftInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                start = leftInset,
                end = DesignTokens.chromeInset,
                bottom = DesignTokens.chromeInset,
            )
            .height(DesignTokens.statusCapsuleHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.chromeGap),
    ) {
        val editor = session.editors.selected
        val path = relativePath(session, editor?.file)
        if (path.isNotEmpty()) {
            PathCapsule(path, Modifier.weight(1f, fill = false))
        }
        Spacer(Modifier.weight(1f))
        StatusCapsule(session, focusLabel, message)
    }
}

@Composable
private fun PathCapsule(path: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier
            .height(DesignTokens.statusCapsuleHeight)
            .capsuleGlass(shape)
            .nativeGlass("bottom-path", shape, tint = DesignTokens.nativeGlassTint)
            .editorPressBlocker()
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            Text(
                path,
                color = DesignTokens.textSecondaryOnGlass,
                fontSize = DesignTokens.statusTextSize,
                fontFamily = DesignTokens.uiFont,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StatusCapsule(
    session: ProjectSession,
    focusLabel: String? = null,
    message: String? = null,
) {
    val editor = session.editors.selected
    val textEditor = editor as? TextFileEditor
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .height(DesignTokens.statusCapsuleHeight)
            .capsuleGlass(shape)
            .nativeGlass("bottom-status", shape, tint = DesignTokens.nativeGlassTint)
            .editorPressBlocker()
            .padding(horizontal = DesignTokens.space12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesignTokens.space16),
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
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
                Text(
                    message,
                    color = DesignTokens.neonAmber,
                    fontSize = DesignTokens.statusTextSize,
                    fontFamily = DesignTokens.uiFont,
                    maxLines = 1,
                    modifier = Modifier.testTag("status-message"),
                )
            }
            if (focusLabel != null) {
                FocusChip(focusLabel)
            }
        }
    }
}

@Composable
private fun FocusChip(label: String) {
    Box(Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
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
    Box(
        Modifier
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
private fun EmptyEditor(leftInset: Dp = 0.dp) {
    Column(
        Modifier.fillMaxSize().padding(start = leftInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(alpha = 0.4f, width = 280.dp)
        Spacer(Modifier.height(28.dp))
        ShortcutList(hasProject = true)
    }
}

/**
 * The no-project screen: the wordmark is the content. There is no Search capsule: with no session
 * the popup would not render, and taking the modal focus would stop the global shortcuts.
 */
@Composable
private fun WelcomeScreen(onOpenFolder: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Wordmark(alpha = 1f, width = 420.dp)
            Spacer(Modifier.height(28.dp))
            OpenFolderButton(onOpenFolder)
            Spacer(Modifier.height(28.dp))
            ShortcutList(hasProject = false)
        }
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
    val shape = RoundedCornerShape(percent = 50)
    Box(
        Modifier
            .height(DesignTokens.capsuleHeight + DesignTokens.space8)
            .capsuleGlass(shape)
            .nativeGlass("welcome-open", shape, tint = DesignTokens.nativeGlassTint)
            .background(
                DesignTokens.accent.copy(alpha = if (hovered) 0.30f else 0.18f),
                shape,
            )
            .border(1.dp, DesignTokens.accent.copy(alpha = 0.55f), shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = DesignTokens.space16 + DesignTokens.space8),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
            Text(
                "Open Folder…",
                color = DesignTokens.selectedText,
                fontSize = DesignTokens.uiTextSize,
                fontFamily = DesignTokens.uiFont,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ShortcutList(hasProject: Boolean) {
    val macOs = remember { KeyRouter.isMacOs() }
    val keymap = remember(macOs) { Keymap.forPlatform(macOs) }
    val shortcuts = remember(keymap, hasProject) { welcomeHints(keymap, hasProject, macOs) }
    // The hint list sits on the window glass when a project has no file open; the opaque
    // shadow plate is what keeps the muted labels readable there.
    CompositionLocalProvider(LocalTextStyle provides DesignTokens.chromeTextStyle) {
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
