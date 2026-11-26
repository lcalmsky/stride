package stride.app.actions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextClipboard
import stride.app.editor.ClipboardHistory
import stride.app.editor.TextFileEditor
import stride.app.files.FileOperationPage
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyEventType
import stride.app.keys.KeyRouter
import stride.app.keys.MacKeymap
import stride.app.keys.dispatchAction
import stride.app.run.FakeProcess
import stride.app.run.ProcessStarter
import stride.app.run.RunConfiguration
import stride.app.run.RunKind
import stride.app.run.RunManager
import stride.app.run.StartedProcess
import stride.app.run.contextRun
import stride.app.vcs.CliGitExecutor
import stride.app.vcs.TEST_GIT
import stride.app.vcs.TestGitRepo
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile

class ActionMenuTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var state: AppState

    /** Finder, the shell, Gradle, git's remote side and the window-opening rows: no test runs them. */
    private val sideEffectActions = setOf(
        ActionId.REVEAL_IN_FINDER,
        ActionId.OPEN_IN_TERMINAL,
        ActionId.BUILD_PROJECT,
        ActionId.PUSH,
        ActionId.FETCH,
        ActionId.UPDATE_PROJECT,
        ActionId.COMMIT,
        ActionId.RUN_CONTEXT,
    )

    @BeforeEach
    fun setUp() {
        state = AppState()
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
    }

    @Test
    fun `the tree menu follows A8 and trims its separators`() {
        openProject("src/main/kotlin/api/OrderService.kt", "README.md")

        val rows = resolve()

        assertEquals("New", (rows.first() as MenuRow.Submenu).text)
        assertEquals(
            listOf("Kotlin Class/File", "File", "Directory"),
            (rows.first() as MenuRow.Submenu).rows.map { (it as MenuRow.Action).text },
        )
        assertEquals(
            listOf("Cut", "Copy", "Paste", "Edit Source"),
            rows.mapNotNull { (it as? MenuRow.Action)?.text }.filter { it in setOf("Cut", "Copy", "Paste", "Edit Source") },
        )
        assertEquals(listOf("Move…", "Copy…"), rows.submenu("Refactor").rows.map { (it as MenuRow.Action).text })
        assertEquals(listOf("Finder", "Terminal"), rows.submenu("Open In").rows.map { (it as MenuRow.Action).text })
        assertEquals(ActionId.RENAME_FILE, rows.action(ActionId.RENAME_FILE).action)
        assertEquals("Rename…", rows.action(ActionId.RENAME_FILE).text)
        assertEquals("⌫", rows.action(ActionId.DELETE_ELEMENT).shortcut)
        // No leading, trailing or repeated separators after the hidden rows are dropped.
        assertFalse(rows.first() is MenuRow.Separator)
        assertFalse(rows.last() is MenuRow.Separator)
        for (index in 1 until rows.size) {
            assertFalse(rows[index] is MenuRow.Separator && rows[index - 1] is MenuRow.Separator)
        }
    }

    @Test
    fun `rows with nothing to show drop out with their separators`() {
        val group = ActionGroup(
            "Test.Group",
            listOf(
                GroupSeparator,
                GroupSubmenu("Empty", listOf(GroupSeparator)),
                GroupSeparator,
                GroupAction(ActionId.COPY_PATHS),
                GroupSeparator,
                GroupSeparator,
                GroupAction(ActionId.COPY_REFERENCE),
                GroupSeparator,
            ),
        )
        openProject("a.kt")
        state.focus = KeyContext.PROJECT_TREE

        val rows = resolveMenuRows(MacKeymap.keymap, state, group)

        // One separator between the two actions survives; only the empty edges and the repeats go.
        assertEquals(MenuRow.Separator, rows[1])
        assertEquals(
            listOf(ActionId.COPY_PATHS, ActionId.COPY_REFERENCE),
            rows.filterIsInstance<MenuRow.Action>().map { it.action },
        )
    }

    @Test
    fun `the git submenu is hidden outside a repository and shown inside one`() {
        openProject("a.kt")
        assertTrue(resolve().none { it is MenuRow.Submenu && it.text == "Git" })

        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }

        val git = resolve().submenu("Git")
        assertEquals(
            listOf(
                ActionId.COMMIT,
                ActionId.ROLLBACK_CHANGES,
                ActionId.SHOW_DIFF,
                ActionId.PUSH,
                ActionId.UPDATE_PROJECT,
                ActionId.FETCH,
                ActionId.SHOW_BRANCHES,
                ActionId.NEW_BRANCH,
            ),
            git.rows.filterIsInstance<MenuRow.Action>().map { it.action },
        )
    }

    @Test
    fun `paste follows the file clipboard and edit source follows a directory target`() {
        val session = openProject("src/main/kotlin/api/OrderService.kt", "README.md")
        val directory = session.tree.root.path.resolve("src/main/kotlin/api")
        session.tree.select(VFile(directory))

        assertFalse(resolve().action(ActionId.PASTE_FILE).enabled)
        assertFalse(resolve().action(ActionId.EDIT_SOURCE).enabled)

        session.files.copy(directory)
        assertTrue(resolve().action(ActionId.PASTE_FILE).enabled)

        val file = directory.resolve("OrderService.kt")
        session.tree.select(VFile(file))
        assertTrue(resolve().action(ActionId.EDIT_SOURCE).enabled)
    }

    @Test
    fun `a component action supplies its own text and enabled state`() {
        val group = ActionGroup("Test.Component", listOf(GroupAction(ActionId.EDIT_SOURCE)))
        openProject("a.kt")
        val component = ComponentAction(enabled = false, text = "Jump to Source") {}

        val rows = resolveMenuRows(
            MacKeymap.keymap,
            state,
            group,
            mapOf(ActionId.EDIT_SOURCE to component),
        )

        val row = rows.single() as MenuRow.Action
        assertEquals("Jump to Source", row.text)
        assertFalse(row.enabled)
    }

    @Test
    fun `new rows open the New popup at their own name page`() {
        val session = openProject("src/main/kotlin/api/OrderService.kt")
        val file = session.tree.root.path.resolve("src/main/kotlin/api/OrderService.kt")
        session.tree.select(VFile(file))
        state.focus = KeyContext.PROJECT_TREE

        assertTrue(dispatchAction(ActionId.NEW_KOTLIN_FILE, state) { null })
        assertEquals(FileOperationPage.KOTLIN, state.fileOperation?.page)
        assertEquals(file.parent, state.fileOperation?.target)

        state.fileOperation = null
        state.focus = KeyContext.PROJECT_TREE
        assertTrue(dispatchAction(ActionId.NEW_FILE, state) { null })
        assertEquals(FileOperationPage.NEW_NAME, state.fileOperation?.page)
        assertEquals(false, state.fileOperation?.newDirectory)

        state.fileOperation = null
        state.focus = KeyContext.PROJECT_TREE
        assertTrue(dispatchAction(ActionId.NEW_DIRECTORY, state) { null })
        assertEquals(FileOperationPage.NEW_NAME, state.fileOperation?.page)
        assertEquals(true, state.fileOperation?.newDirectory)
        assertEquals(file.parent, state.fileOperation?.target)
    }

    @Test
    fun `copy path in the tree copies the project-relative target`() {
        val clipboard = RecordingClipboard()
        state.clipboard = clipboard
        val session = openProject("src/main/kotlin/api/OrderService.kt")
        val file = session.tree.root.path.resolve("src/main/kotlin/api/OrderService.kt")
        session.tree.select(VFile(file))
        state.focus = KeyContext.PROJECT_TREE

        assertTrue(dispatchAction(ActionId.COPY_PATHS, state) { null })
        assertEquals("src/main/kotlin/api/OrderService.kt", clipboard.text)

        assertTrue(dispatchAction(ActionId.COPY_REFERENCE, state) { null })
        assertEquals("src/main/kotlin/api/OrderService.kt", clipboard.text)
    }

    @Test
    fun `copy path outside the tree keeps the editor's absolute path and line`() {
        val clipboard = RecordingClipboard()
        state.clipboard = clipboard
        val session = openProject("src/main/kotlin/api/OrderService.kt")
        val file = session.tree.root.path.resolve("src/main/kotlin/api/OrderService.kt")
        session.editors.openFile(VFile(file))
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.COPY_PATHS, state) { null })
        assertEquals(file.toAbsolutePath().normalize().toString(), clipboard.text)

        assertTrue(dispatchAction(ActionId.COPY_REFERENCE, state) { null })
        assertEquals("src/main/kotlin/api/OrderService.kt:1", clipboard.text)
    }

    @Test
    fun `rollback from the tree pre-checks the tree's changed row`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.write("src/Other.kt", "other\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        val main = repo.root.resolve("src/Main.kt")
        session.tree.reveal(VFile(main))
        session.tree.select(VFile(main))
        state.focus = KeyContext.PROJECT_TREE

        assertTrue(dispatchAction(ActionId.ROLLBACK_CHANGES, state) { null })

        assertEquals(setOf(main), state.rollbackRequest?.checked)
    }

    @Test
    fun `rollback outside the tree still pre-checks the selected editor's file`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.write("src/Other.kt", "other\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        val main = repo.root.resolve("src/Main.kt")
        session.editors.openFile(VFile(main))
        state.focus = KeyContext.EDITOR

        assertTrue(dispatchAction(ActionId.ROLLBACK_CHANGES, state) { null })

        assertEquals(setOf(main), state.rollbackRequest?.checked)
    }

    @Test
    fun `show diff outside the commit window uses the tree target`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        val main = repo.root.resolve("src/Main.kt")
        session.tree.reveal(VFile(main))
        session.tree.select(VFile(main))
        state.focus = KeyContext.PROJECT_TREE

        assertTrue(dispatchAction(ActionId.SHOW_DIFF, state) { null })
        waitFor { state.diffRequest != null }
        assertEquals(main.toAbsolutePath().normalize(), state.diffRequest?.files?.single()?.path)
    }

    @Test
    fun `show diff in the commit window reads the window's row, not the tree`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.write("src/Other.kt", "other\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        repo.write("src/Other.kt", "other changed\n")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        val main = repo.root.resolve("src/Main.kt")
        val other = repo.root.resolve("src/Other.kt")
        session.tree.reveal(VFile(other))
        session.tree.select(VFile(other))
        session.commit.selectedPath = main

        state.focus = KeyContext.COMMIT_WINDOW
        assertTrue(dispatchAction(ActionId.SHOW_DIFF, state) { null })

        waitFor { state.diffRequest != null }
        assertEquals(main.toAbsolutePath().normalize(), state.diffRequest?.files?.single()?.path)
    }

    @Test
    fun `the menu opens where the pointer is and flips at the window edge`() {
        val window = Size(1000f, 800f)
        val menu = Size(200f, 300f)

        assertEquals(Offset(100f, 100f), contextMenuPosition(Offset(100f, 100f), menu, window))
        assertEquals(Offset(700f, 100f), contextMenuPosition(Offset(900f, 100f), menu, window))
        assertEquals(Offset(100f, 400f), contextMenuPosition(Offset(100f, 700f), menu, window))
        assertEquals(Offset(700f, 400f), contextMenuPosition(Offset(900f, 700f), menu, window))
        // A menu larger than the window starts at its top-left corner.
        assertEquals(
            Offset(0f, 0f),
            contextMenuPosition(Offset(10f, 10f), Size(1200f, 900f), window),
        )
    }

    @Test
    fun `a submenu opens beside its row and flips left at the window edge`() {
        val window = Size(1000f, 800f)
        val submenu = Size(180f, 200f)

        assertEquals(
            Offset(300f, 120f),
            submenuPosition(Offset(100f, 120f), menuWidth = 200f, submenuSize = submenu, window = window),
        )
        assertEquals(
            Offset(20f, 120f),
            submenuPosition(Offset(200f, 120f), menuWidth = 700f, submenuSize = submenu, window = window),
        )
        // Top-aligned with its row, kept inside the window's bottom edge.
        assertEquals(
            Offset(300f, 600f),
            submenuPosition(Offset(100f, 700f), menuWidth = 200f, submenuSize = submenu, window = window),
        )
    }

    @Test
    fun `no enabled tree row hands its key back to the component`() {
        state.clipboard = RecordingClipboard()
        val plain = openProject("src/main/kotlin/api/OrderService.kt", "README.md")
        val plainFile = plain.tree.root.path.resolve("src/main/kotlin/api/OrderService.kt")
        val plainDirectory = plain.tree.root.path.resolve("src/main/kotlin/api")
        // Outside a repository the Git submenu is hidden; every row that is left must consume.
        for (select in listOf<() -> Unit>(
            { plain.tree.select(VFile(plainFile)) },
            { plain.tree.select(VFile(plainDirectory)) },
        )) {
            assertTrue(
                assertEveryEnabledRowIsConsumed(ActionGroups.projectViewPopupMenu, KeyContext.PROJECT_TREE, reset = select) > 0,
            )
        }
        assertTrue(
            assertEveryEnabledRowIsConsumed(ActionGroups.projectViewPopupMenu, KeyContext.PROJECT_TREE) {
                plain.tree.clearSelection()
                plain.editors.closeAll()
            } > 0,
        )

        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.write("src/Other.kt", "other\n")
        repo.write("build.gradle.kts", "")
        repo.write(
            "src/test/kotlin/sample/orders/OrderServiceTest.kt",
            "class OrderServiceTest {\n    @Test\n    fun placesOrder() {\n    }\n}\n",
        )
        repo.commitAll("Initial commit")
        repo.write("src/Other.kt", "other changed\n")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        val unchanged = repo.root.resolve("src/Main.kt")
        val changed = repo.root.resolve("src/Other.kt")
        val directory = repo.root.resolve("src")

        val testFile = repo.root.resolve("src/test/kotlin/sample/orders/OrderServiceTest.kt")
        val states: List<Pair<String, () -> Unit>> = listOf(
            "unchanged file" to { session.tree.select(VFile(unchanged)) },
            "changed file" to { session.tree.select(VFile(changed)) },
            "directory" to { session.tree.select(VFile(directory)) },
            "test file" to { session.tree.select(VFile(testFile)) },
        )
        for ((_, select) in states) {
            select()
            state.focus = KeyContext.PROJECT_TREE
            assertRunContextRowResolves(state, ActionGroups.projectViewPopupMenu)
            assertTrue(
                assertEveryEnabledRowIsConsumed(ActionGroups.projectViewPopupMenu, KeyContext.PROJECT_TREE, reset = select) > 0,
            )
        }
    }

    @Test
    fun `the editor menu follows A8 and trims its separators`() {
        openProject("src/main/kotlin/api/OrderService.kt")
        state.focus = KeyContext.EDITOR

        val rows = resolveEditor()

        assertEquals(
            listOf(
                "Show Context Actions",
                null,
                "Cut",
                "Copy",
                "Paste",
                "Copy / Paste Special…",
                "Column Selection Mode",
                null,
                "Find in Files…",
                "Find Usages",
                "Go To",
                "Folding",
                null,
                "Refactor",
                "Generate…",
                null,
                "Open In",
            ),
            rows.map { row -> if (row is MenuRow.Separator) null else (row as? MenuRow.Action)?.text ?: (row as MenuRow.Submenu).text },
        )
        assertEquals(
            listOf("Declaration or Usages", "Implementation(s)", "Type Declaration", "Super Method", "Test"),
            rows.submenu("Go To").rows.map { (it as MenuRow.Action).text },
        )
        assertEquals(
            listOf("Expand", "Collapse", "Expand All", "Collapse All"),
            rows.submenu("Folding").rows.map { (it as MenuRow.Action).text },
        )
        val refactor = rows.submenu("Refactor").rows
        assertEquals(
            listOf(
                "Refactor This…",
                "Rename…",
                "Rename File…",
                null,
                "Introduce Variable…",
                "Extract Method…",
                "Inline…",
            ),
            refactor.map { (it as? MenuRow.Action)?.text },
        )
        assertEquals(listOf("Finder", "Terminal"), rows.submenu("Open In").rows.map { (it as MenuRow.Action).text })
    }

    @Test
    fun `the editor menu shows the strokes of its Cut, Copy and Paste rows`() {
        openProject("a.kt")
        state.focus = KeyContext.EDITOR

        val rows = resolveEditor()

        assertEquals("⌘X", rows.action(ActionId.CUT_LINE).shortcut)
        assertEquals("⌘C", rows.action(ActionId.COPY_LINE).shortcut)
        assertEquals("⌘V", rows.action(ActionId.PASTE).shortcut)
    }

    @Test
    fun `the gutter and tab menus follow A8`() {
        val session = openProject("a.kt", "b.kt")
        session.editors.openFile(VFile(session.tree.root.path.resolve("a.kt")))
        state.focus = KeyContext.EDITOR

        val gutter = resolveMenuRows(MacKeymap.keymap, state, ActionGroups.editorGutterPopupMenu)
        assertEquals(
            listOf(ActionId.TOGGLE_BOOKMARK, ActionId.SHOW_BOOKMARKS),
            gutter.filterIsInstance<MenuRow.Action>().map { it.action },
        )

        val tabs = resolveMenuRows(MacKeymap.keymap, state, ActionGroups.editorTabPopupMenu)
        assertEquals(
            listOf(
                "Close",
                "Close Other Tabs",
                "Close All Tabs",
                "Close Tabs to the Left",
                "Close Tabs to the Right",
                null,
                "Copy Path",
                "Copy Reference",
                null,
                "Reopen Closed Tab",
            ),
            tabs.map { row ->
                if (row is MenuRow.Separator) null else (row as MenuRow.Action).text
            },
        )
        assertEquals("⌘W", tabs.action(ActionId.CLOSE_TAB).shortcut)
    }

    @Test
    fun `close tabs to the left keeps the selected tab and fills the reopen order`() {
        val session = openProject("a.kt", "b.kt", "c.kt", "d.kt")
        val root = session.tree.root.path
        val editors = session.editors
        editors.openFile(VFile(root.resolve("a.kt")))
        editors.openFile(VFile(root.resolve("b.kt")))
        editors.openFile(VFile(root.resolve("c.kt")))
        editors.openFile(VFile(root.resolve("d.kt")))
        val c = editors.openFile(VFile(root.resolve("c.kt")))!!

        editors.closeTabsToTheLeft()

        assertEquals(listOf("c.kt", "d.kt"), editors.openFiles.map { it.file.name })
        assertSame(c, editors.selected)
        // The left tabs closed in tab order, so the rightmost reopens first.
        assertEquals("b.kt", editors.reopenClosed()?.file?.name)
        assertEquals("a.kt", editors.reopenClosed()?.file?.name)
    }

    @Test
    fun `close tabs to the right keeps the selected tab and fills the reopen order`() {
        val session = openProject("a.kt", "b.kt", "c.kt", "d.kt")
        val root = session.tree.root.path
        val editors = session.editors
        editors.openFile(VFile(root.resolve("a.kt")))
        editors.openFile(VFile(root.resolve("b.kt")))
        editors.openFile(VFile(root.resolve("c.kt")))
        editors.openFile(VFile(root.resolve("d.kt")))
        val b = editors.openFile(VFile(root.resolve("b.kt")))!!

        editors.closeTabsToTheRight()

        assertEquals(listOf("a.kt", "b.kt"), editors.openFiles.map { it.file.name })
        assertSame(b, editors.selected)
        assertEquals("d.kt", editors.reopenClosed()?.file?.name)
        assertEquals("c.kt", editors.reopenClosed()?.file?.name)
    }

    @Test
    fun `close all leaves no tab and fills the reopen order in tab order`() {
        val session = openProject("a.kt", "b.kt", "c.kt")
        val root = session.tree.root.path
        val editors = session.editors
        editors.openFile(VFile(root.resolve("a.kt")))
        editors.openFile(VFile(root.resolve("b.kt")))
        editors.openFile(VFile(root.resolve("c.kt")))

        editors.closeAll()

        assertTrue(editors.openFiles.isEmpty())
        assertNull(editors.selected)
        assertEquals("c.kt", editors.reopenClosed()?.file?.name)
        assertEquals("b.kt", editors.reopenClosed()?.file?.name)
        assertEquals("a.kt", editors.reopenClosed()?.file?.name)
        assertNull(editors.reopenClosed())
    }

    @Test
    fun `the tab rows are enabled exactly when their action does something`() {
        val session = openProject("a.kt", "b.kt", "c.kt")
        val root = session.tree.root.path
        val editors = session.editors
        editors.openFile(VFile(root.resolve("a.kt")))
        editors.openFile(VFile(root.resolve("b.kt")))
        val c = editors.openFile(VFile(root.resolve("c.kt")))!!
        state.focus = KeyContext.EDITOR

        assertTrue(tabRow(ActionId.CLOSE_TABS_TO_THE_LEFT).enabled)
        assertFalse(tabRow(ActionId.CLOSE_TABS_TO_THE_RIGHT).enabled)
        assertTrue(tabRow(ActionId.CLOSE_ALL_TABS).enabled)
        assertTrue(tabRow(ActionId.CLOSE_OTHER_TABS).enabled)
        assertFalse(tabRow(ActionId.REOPEN_CLOSED_TAB).enabled)

        // A disabled row's dispatch changes nothing; an enabled one does.
        assertTrue(dispatchAction(ActionId.CLOSE_TABS_TO_THE_RIGHT, state) { null })
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), editors.openFiles.map { it.file.name })
        assertFalse(tabRow(ActionId.REOPEN_CLOSED_TAB).enabled)

        assertTrue(dispatchAction(ActionId.CLOSE_TABS_TO_THE_LEFT, state) { null })
        assertEquals(listOf("c.kt"), editors.openFiles.map { it.file.name })
        assertSame(c, editors.selected)
        assertFalse(tabRow(ActionId.CLOSE_OTHER_TABS).enabled)
        assertTrue(tabRow(ActionId.REOPEN_CLOSED_TAB).enabled)

        assertTrue(dispatchAction(ActionId.CLOSE_ALL_TABS, state) { null })
        assertTrue(editors.openFiles.isEmpty())
        assertNull(editors.selected)
        assertFalse(tabRow(ActionId.CLOSE_ALL_TABS).enabled)
        assertTrue(dispatchAction(ActionId.CLOSE_ALL_TABS, state) { null })
        assertTrue(editors.openFiles.isEmpty())
    }

    @Test
    fun `the editor menu disables cut and paste where the key would do nothing`() {
        val root = temp.resolve("component-project")
        Files.createDirectories(root)
        val file = root.resolve("a.kt")
        Files.writeString(file, "one")
        val clipboard = RecordingClipboard().apply { text = "clip" }
        val history = ClipboardHistory()
        val editor = TextFileEditor(VFile(file), clipboard = clipboard)

        val actions = editor.menuComponentActions(history)
        assertTrue(actions.getValue(ActionId.CUT_LINE).enabled)
        assertTrue(actions.getValue(ActionId.COPY_LINE).enabled)
        assertTrue(actions.getValue(ActionId.PASTE).enabled)

        clipboard.text = null
        assertFalse(editor.menuComponentActions(history).getValue(ActionId.PASTE).enabled)

        val readOnly = TextFileEditor(VFile(file), readOnly = true, clipboard = clipboard)
        val readOnlyActions = readOnly.menuComponentActions(history)
        assertFalse(readOnlyActions.getValue(ActionId.CUT_LINE).enabled)
        assertFalse(readOnlyActions.getValue(ActionId.PASTE).enabled)
        assertTrue(readOnlyActions.getValue(ActionId.COPY_LINE).enabled)
    }

    @Test
    fun `the copy and paste component actions leave the key path alone`() {
        openProject("a.kt")
        val router = KeyRouter(isMac = true)

        assertEquals(
            ActionId.COPY_LINE,
            router.route(KeyEventType.DOWN, Key.C, meta = true, focus = KeyContext.EDITOR),
        )
        assertEquals(
            ActionId.COPY,
            router.route(KeyEventType.DOWN, Key.C, meta = true, focus = KeyContext.TERMINAL),
        )
        assertFalse(dispatchAction(ActionId.COPY, state) { null })
        assertEquals(
            ActionId.PASTE,
            router.route(KeyEventType.DOWN, Key.V, meta = true, focus = KeyContext.EDITOR),
        )
        assertFalse(dispatchAction(ActionId.PASTE, state) { null })
        assertEquals(
            ActionId.PASTE,
            router.route(KeyEventType.DOWN, Key.V, meta = true, focus = KeyContext.TERMINAL),
        )
        assertFalse(dispatchAction(ActionId.PASTE, state) { null })
    }

    @Test
    fun `no enabled editor row hands its key back to the component`() {
        state.clipboard = RecordingClipboard().apply { text = "clip" }
        val repo = TestGitRepo.create(temp, "editor-menu")
        repo.write("src/Main.kt", "class OrderService\n")
        repo.write("data.json", "{\n  \"name\": \"order\"\n}\n")
        repo.write("notes.txt", "plain text\n")
        repo.write("locked.txt", "class Locked\n")
        repo.write("script.kts", "println(\"hello\")\n")
        repo.commitAll("Initial commit")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        val kt = repo.root.resolve("src/Main.kt")
        val json = repo.root.resolve("data.json")
        val text = repo.root.resolve("notes.txt")
        val locked = repo.root.resolve("locked.txt")
        val lockedPermissions = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            fun show(file: Path, content: String, selection: TextRange) {
                val editor = session.editors.openFile(VFile(file)) as TextFileEditor
                editor.updateValue(TextFieldValue(content, selection))
            }
            val ktText = "class OrderService\n\n"
            val states: List<Pair<String, () -> Unit>> = listOf(
                "Kotlin identifier" to { show(kt, ktText, TextRange(6)) },
                "Kotlin blank line" to { show(kt, ktText, TextRange(19)) },
                "Kotlin end of file" to { show(kt, ktText, TextRange(ktText.length)) },
                "Kotlin selection" to { show(kt, ktText, TextRange(0, 5)) },
                "JSON" to { show(json, "{\n  \"name\": \"order\"\n}\n", TextRange(7)) },
                "plain text" to { show(text, "plain text\n", TextRange(2)) },
                // A file the editor cannot read opens read-only over empty text.
                "read-only" to { session.editors.openFile(VFile(locked)) },
                "Kotlin script" to { session.editors.openFile(VFile(repo.root.resolve("script.kts"))) },
            )
            for ((_, reset) in states) {
                reset()
                state.focus = KeyContext.EDITOR
                assertRunContextRowResolves(state, ActionGroups.editorPopupMenu)
                assertTrue(
                    assertEveryEnabledRowIsConsumed(
                        ActionGroups.editorPopupMenu,
                        KeyContext.EDITOR,
                        components = setOf(ActionId.CUT_LINE, ActionId.COPY_LINE, ActionId.PASTE),
                        reset = reset,
                    ) > 0,
                )
            }
        } finally {
            Files.setPosixFilePermissions(locked, lockedPermissions)
        }
    }

    @Test
    fun `the five surface menus follow A8`() {
        openProject("a.kt")

        val commit = resolveMenu(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW)
        assertEquals(
            listOf(ActionId.ROLLBACK_CHANGES, ActionId.SHOW_DIFF, ActionId.EDIT_SOURCE),
            commit.filterIsInstance<MenuRow.Action>().map { it.action },
        )
        assertEquals(
            listOf("Rollback…", "Show Diff", "Jump to Source"),
            commit.filterIsInstance<MenuRow.Action>().map { it.text },
        )

        assertEquals(
            listOf(ActionId.SHOW_DIFF),
            resolveMenu(ActionGroups.vcsLogContextMenu, KeyContext.LIST_WINDOW)
                .filterIsInstance<MenuRow.Action>().map { it.action },
        )
        assertEquals(
            listOf(ActionId.RUN_CONTEXT, ActionId.RERUN, ActionId.STOP),
            resolveMenu(ActionGroups.testTreePopupMenu, KeyContext.RUN_WINDOW)
                .filterIsInstance<MenuRow.Action>().map { it.action },
        )
        assertEquals(
            listOf(ActionId.RERUN, ActionId.STOP),
            resolveMenu(ActionGroups.consoleViewPopupMenu, KeyContext.RUN_WINDOW)
                .filterIsInstance<MenuRow.Action>().map { it.action },
        )
        for (group in listOf(ActionGroups.usageViewPopupMenu, ActionGroups.structureViewPopupMenu,
            ActionGroups.hierarchyPopupMenu, ActionGroups.bookmarksToolWindowPopupMenu)) {
            assertEquals(
                listOf(ActionId.EDIT_SOURCE to "Jump to Source"),
                resolveMenu(group, KeyContext.LIST_WINDOW).filterIsInstance<MenuRow.Action>()
                    .map { it.action to it.text },
            )
        }
        assertEquals(
            listOf(ActionId.SHOW_INTENTION_ACTIONS to "Show Quick-Fixes", ActionId.EDIT_SOURCE to "Jump to Source"),
            resolveMenu(ActionGroups.problemsViewTreePopup, KeyContext.LIST_WINDOW)
                .filterIsInstance<MenuRow.Action>().map { it.action to it.text },
        )

        val terminal = resolveMenu(ActionGroups.terminalContextMenu, KeyContext.TERMINAL)
            .filterIsInstance<MenuRow.Action>()
        assertEquals(listOf(ActionId.COPY, ActionId.PASTE), terminal.map { it.action })
        assertEquals(listOf("⌘C", "⌘V"), terminal.map { it.shortcut })
    }

    @Test
    fun `the commit window rows are enabled exactly when they open something`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        state.focus = KeyContext.COMMIT_WINDOW

        // A clean tree lists no file row: both rows are disabled and their dispatch opens nothing.
        assertNull(session.commit.selectedRow())
        assertFalse(resolveMenu(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW).action(ActionId.ROLLBACK_CHANGES).enabled)
        assertFalse(resolveMenu(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW).action(ActionId.SHOW_DIFF).enabled)
        assertFalse(resolveMenu(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW).action(ActionId.EDIT_SOURCE).enabled)
        assertFalse(dispatchAction(ActionId.ROLLBACK_CHANGES, state) { null })
        assertNull(state.rollbackRequest)
        assertFalse(dispatchAction(ActionId.SHOW_DIFF, state) { null })
        assertNull(state.diffRequest)

        // One changed file gives the window a row: both rows enable and open their popup.
        repo.write("src/Main.kt", "second\n")
        runBlocking { session.git.refreshNow() }
        assertTrue(resolveMenu(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW).action(ActionId.ROLLBACK_CHANGES).enabled)
        assertTrue(resolveMenu(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW).action(ActionId.SHOW_DIFF).enabled)
        assertTrue(resolveMenu(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW).action(ActionId.EDIT_SOURCE).enabled)
        assertNotNull(session.commit.selectedRow())
        assertTrue(dispatchAction(ActionId.SHOW_DIFF, state) { null })
        waitFor { state.diffRequest != null }
        state.diffRequest = null
        assertTrue(dispatchAction(ActionId.ROLLBACK_CHANGES, state) { null })
        assertNotNull(state.rollbackRequest)
    }

    @Test
    fun `rerun and stop are enabled exactly when their actions run`() {
        val session = openProject("a.kt")
        val manager = session.project.service<RunManager>()
        state.focus = KeyContext.RUN_WINDOW
        assertNull(manager.lastRun)
        assertFalse(resolveMenu(ActionGroups.testTreePopupMenu, KeyContext.RUN_WINDOW).action(ActionId.RERUN).enabled)
        assertFalse(resolveMenu(ActionGroups.testTreePopupMenu, KeyContext.RUN_WINDOW).action(ActionId.STOP).enabled)
        assertTrue(dispatchAction(ActionId.RERUN, state) { null })
        assertEquals("Nothing to rerun", state.statusMessage)
        assertTrue(dispatchAction(ActionId.STOP, state) { null })
        assertTrue(manager.sessions.isEmpty())

        val process = FakeProcess(blocking = true)
        manager.starter = ProcessStarter { StartedProcess(process, "fake") }
        manager.run(
            RunConfiguration(
                name = "build",
                workingDir = session.project.root,
                args = listOf("classes"),
                kind = RunKind.TASK,
            ),
        )
        assertTrue(resolveMenu(ActionGroups.testTreePopupMenu, KeyContext.RUN_WINDOW).action(ActionId.RERUN).enabled)
        assertTrue(resolveMenu(ActionGroups.testTreePopupMenu, KeyContext.RUN_WINDOW).action(ActionId.STOP).enabled)
        assertTrue(dispatchAction(ActionId.STOP, state) { null })
        waitFor { !manager.canStop }
        assertFalse(resolveMenu(ActionGroups.testTreePopupMenu, KeyContext.RUN_WINDOW).action(ActionId.STOP).enabled)
        assertTrue(resolveMenu(ActionGroups.testTreePopupMenu, KeyContext.RUN_WINDOW).action(ActionId.RERUN).enabled)
    }

    @Test
    fun `no enabled surface row hands its key back to the component`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.write("src/Main.kt", "second\n")
        state.openFolder(repo.root)
        val session = requireNotNull(state.session)
        session.git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { session.git.refreshNow() }
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(blocking = true), "fake") }
        manager.run(
            RunConfiguration(
                name = "build",
                workingDir = session.project.root,
                args = listOf("classes"),
                kind = RunKind.TASK,
            ),
        )

        val dispatchable = listOf(
            Triple(ActionGroups.changesViewPopupMenu, KeyContext.COMMIT_WINDOW, setOf(ActionId.EDIT_SOURCE)),
        )
        for ((group, context, components) in dispatchable) {
            assertTrue(
                assertEveryEnabledRowIsConsumed(group, context, components) > 0,
                "${group.id} dispatched no row",
            )
        }
        // These menus hold only their surface's own handlers, so nothing may reach the dispatcher.
        val componentOnly = listOf(
            Triple(ActionGroups.vcsLogContextMenu, KeyContext.LIST_WINDOW, setOf(ActionId.SHOW_DIFF)),
            Triple(ActionGroups.terminalContextMenu, KeyContext.TERMINAL, setOf(ActionId.COPY, ActionId.PASTE)),
            Triple(ActionGroups.usageViewPopupMenu, KeyContext.LIST_WINDOW, setOf(ActionId.EDIT_SOURCE)),
            Triple(ActionGroups.structureViewPopupMenu, KeyContext.LIST_WINDOW, setOf(ActionId.EDIT_SOURCE)),
            Triple(ActionGroups.hierarchyPopupMenu, KeyContext.LIST_WINDOW, setOf(ActionId.EDIT_SOURCE)),
            Triple(ActionGroups.bookmarksToolWindowPopupMenu, KeyContext.LIST_WINDOW, setOf(ActionId.EDIT_SOURCE)),
            Triple(
                ActionGroups.problemsViewTreePopup,
                KeyContext.LIST_WINDOW,
                setOf(ActionId.EDIT_SOURCE, ActionId.SHOW_INTENTION_ACTIONS),
            ),
        )
        for ((group, context, components) in componentOnly) {
            assertEquals(
                0,
                assertEveryEnabledRowIsConsumed(group, context, components),
                "${group.id} dispatched a row that belongs to its surface",
            )
        }

        // A clean Commit window has no file row: its own rows are disabled and the Git submenu is
        // the only place a row stays enabled.
        repo.write("src/Main.kt", "first\n")
        runBlocking { session.git.refreshNow() }
        assertTrue(
            assertEveryEnabledRowIsConsumed(
                ActionGroups.changesViewPopupMenu,
                KeyContext.COMMIT_WINDOW,
                setOf(ActionId.EDIT_SOURCE),
            ) > 0,
            "the clean Commit window dispatched no branch row",
        )
    }

    @Test
    fun `the run window rows follow the three run states`() {
        val session = openProject("a.kt")
        val manager = session.project.service<RunManager>()
        val configuration = RunConfiguration(
            name = "build",
            workingDir = session.project.root,
            args = listOf("classes"),
            kind = RunKind.TASK,
        )
        state.focus = KeyContext.RUN_WINDOW
        val groups = listOf(ActionGroups.testTreePopupMenu, ActionGroups.consoleViewPopupMenu)

        fun start() {
            manager.starter = ProcessStarter { StartedProcess(FakeProcess(blocking = true), "fake") }
            manager.run(configuration)
        }

        fun finish() {
            manager.stop()
            waitFor { !manager.canStop }
        }

        fun assertState(rerun: Boolean, stop: Boolean, dispatched: Int, ensure: () -> Unit = {}) {
            for (group in groups) {
                ensure()
                val rows = resolveMenuRows(MacKeymap.keymap, state, group)
                assertEquals(rerun, rows.action(ActionId.RERUN).enabled, "${group.id}: Rerun")
                assertEquals(stop, rows.action(ActionId.STOP).enabled, "${group.id}: Stop")
                assertEquals(
                    dispatched,
                    assertEveryEnabledRowIsConsumed(group, KeyContext.RUN_WINDOW),
                    "${group.id}: dispatched rows",
                )
            }
        }

        assertNull(manager.lastRun)
        assertState(rerun = false, stop = false, dispatched = 0)

        assertState(rerun = true, stop = true, dispatched = 2) { start() }

        assertState(rerun = true, stop = false, dispatched = 1) { finish() }
    }

    @Test
    fun `no enabled gutter row hands its key back to the component`() {
        state.clipboard = RecordingClipboard()
        val session = openProject("Main.kt")
        val file = session.tree.root.path.resolve("Main.kt")
        Files.writeString(file, "class Main\n")
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor
        val text = "class Main\n"

        val states: List<Pair<String, () -> Unit>> = listOf(
            "line without a bookmark" to {
                editor.updateValue(TextFieldValue(text, TextRange(6)))
                session.bookmarks.clear()
                editor.bookmarkLines = emptySet()
            },
            "line with a bookmark" to {
                editor.updateValue(TextFieldValue(text, TextRange(6)))
                dispatchAction(ActionId.TOGGLE_BOOKMARK, state) { null }
            },
        )
        for ((_, reset) in states) {
            assertTrue(
                assertEveryEnabledRowIsConsumed(
                    ActionGroups.editorGutterPopupMenu,
                    KeyContext.EDITOR,
                    reset = reset,
                ) > 0,
            )
        }
    }

    @Test
    fun `no enabled tab row hands its key back to the component`() {
        state.clipboard = RecordingClipboard()
        val session = openProject("a.kt", "b.kt", "c.kt")
        val root = session.tree.root.path
        val names = listOf("a.kt", "b.kt", "c.kt")

        val states: List<Pair<String, () -> Unit>> = listOf(
            "one tab" to {
                session.editors.closeAll()
                session.editors.openFile(VFile(root.resolve("a.kt")))
            },
            "three tabs" to {
                session.editors.closeAll()
                names.forEach { session.editors.openFile(VFile(root.resolve(it))) }
                session.editors.openFile(VFile(root.resolve("b.kt")))
            },
        )
        for ((_, reset) in states) {
            assertTrue(
                assertEveryEnabledRowIsConsumed(
                    ActionGroups.editorTabPopupMenu,
                    KeyContext.EDITOR,
                    reset = reset,
                ) > 0,
            )
        }
    }

    @Test
    fun `a row whose caret has no identifier reports it and consumes the action`() {
        val session = openProject("Main.kt")
        val file = session.tree.root.path.resolve("Main.kt")
        val text = "class OrderService\n\n"
        Files.writeString(file, text)
        val editor = session.editors.openFile(VFile(file)) as TextFileEditor

        // The newline's own offset still touches the identifier before it, so the blank line and
        // the end of the file are the two positions with no identifier.
        for (action in listOf(ActionId.FIND_USAGES, ActionId.GOTO_DECLARATION)) {
            for (offset in listOf(text.indexOf('\n') + 1, text.length)) {
                editor.updateValue(TextFieldValue(text, TextRange(offset)))
                state.focus = KeyContext.EDITOR
                state.statusMessage = null
                assertTrue(dispatchAction(action, state) { null }, "$action at $offset was handed back")
                assertEquals("No identifier at the caret", state.statusMessage)
            }
        }
    }

    /**
     * A4: resolves [group] on [context] and dispatches every enabled row that runs through the
     * dispatcher; each must report that it consumed its action. Returns how many rows ran.
     */
    private fun assertEveryEnabledRowIsConsumed(
        group: ActionGroup,
        context: KeyContext,
        components: Set<ActionId> = emptySet(),
        reset: () -> Unit = {},
    ): Int {
        fun restore() {
            reset()
            clearPopups()
            state.focus = context
        }
        restore()
        val rows = resolveMenuRows(MacKeymap.keymap, state, group).flatMap { it.leaves() }
            .filterIsInstance<MenuRow.Action>()
            .filter { it.enabled && it.action !in components && it.action !in sideEffectActions }
        for (row in rows) {
            restore()
            assertTrue(dispatchAction(row.action, state) { null }, "${group.id}: ${row.action} was handed back")
        }
        return rows.size
    }

    private fun assertRunContextRowResolves(state: AppState, group: ActionGroup) {
        val row = resolveMenuRows(MacKeymap.keymap, state, group).flatMap { it.leaves() }
            .filterIsInstance<MenuRow.Action>()
            .singleOrNull { it.action == ActionId.RUN_CONTEXT }
        assertEquals(contextRun(state) != null, row != null, "${group.id}: Run row visibility")
        if (row != null) {
            assertTrue(row.enabled, "${group.id}: Run row is disabled")
            assertNotNull(contextRun(state), "${group.id}: Run row has nothing to run")
        }
    }

    private fun clearPopups() {
        state.contextMenu = null
        state.fileOperation = null
        state.findInPathVisible = false
        state.findBar.close()
        state.showUsages = null
        state.declarationChoices = emptyList()
        state.declarationChooserVisible = false
        state.testChoices = emptyList()
        state.testChooserVisible = false
        state.fileStructureVisible = false
        state.contextActions = emptyList()
        state.contextActionsVisible = false
        state.pasteHistory = null
        state.rename = null
        state.generate = null
        state.refactorThis = null
        state.memberChoose = null
        state.refactoringName = null
        state.refactoringChoose = null
        state.rollbackRequest = null
        state.diffRequest = null
        state.branchesPopup = null
        state.bookmarksVisible = false
        state.statusMessage = null
    }

    private fun resolveMenu(group: ActionGroup, context: KeyContext = KeyContext.PROJECT_TREE): List<MenuRow> {
        state.focus = context
        return resolveMenuRows(MacKeymap.keymap, state, group)
    }

    private fun resolveEditor(): List<MenuRow> {
        state.focus = KeyContext.EDITOR
        return resolveMenuRows(MacKeymap.keymap, state, ActionGroups.editorPopupMenu)
    }

    private fun tabRow(action: ActionId): MenuRow.Action =
        resolveMenuRows(MacKeymap.keymap, state, ActionGroups.editorTabPopupMenu).action(action)

    private fun MenuRow.leaves(): List<MenuRow> = when (this) {
        is MenuRow.Submenu -> rows.flatMap { it.leaves() }
        else -> listOf(this)
    }

    private fun resolve(context: KeyContext = KeyContext.PROJECT_TREE): List<MenuRow> {
        state.focus = context
        return resolveMenuRows(MacKeymap.keymap, state, ActionGroups.projectViewPopupMenu)
    }

    private fun List<MenuRow>.action(id: ActionId): MenuRow.Action =
        filterIsInstance<MenuRow.Action>().first { it.action == id }

    private fun List<MenuRow>.submenu(text: String): MenuRow.Submenu =
        filterIsInstance<MenuRow.Submenu>().first { it.text == text }

    private fun openProject(vararg files: String): ProjectSession {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        for (name in files) {
            val file = root.resolve(name)
            Files.createDirectories(file.parent)
            if (!Files.exists(file)) Files.writeString(file, "")
        }
        state.openFolder(root)
        return requireNotNull(state.session)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !condition()) Thread.sleep(10)
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(ActionMenuTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}

private class RecordingClipboard : TextClipboard {
    var text: String? = null

    override fun writeText(text: String): Boolean {
        this.text = text
        return true
    }

    override fun readText(): String? = text
}
