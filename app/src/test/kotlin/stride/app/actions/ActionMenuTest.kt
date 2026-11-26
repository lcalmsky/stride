package stride.app.actions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextClipboard
import stride.app.files.FileOperationPage
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.MacKeymap
import stride.app.keys.dispatchAction
import stride.app.vcs.CliGitExecutor
import stride.app.vcs.TEST_GIT
import stride.app.vcs.TestGitRepo
import stride.core.services.Application
import stride.core.vfs.VFile

class ActionMenuTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var state: AppState

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
        val session = openProject("src/main/kotlin/api/OrderService.kt")
        val file = session.tree.root.path.resolve("src/main/kotlin/api/OrderService.kt")
        session.tree.select(VFile(file))

        val actions = resolve().flatMap { it.leaves() }.filterIsInstance<MenuRow.Action>()
            .filter { it.enabled }
            .map { it.action }
        assertTrue(actions.isNotEmpty())
        // Finder, the shell, Gradle, git's remote commands and the Commit window are not run.
        val skipped = setOf(
            ActionId.REVEAL_IN_FINDER,
            ActionId.OPEN_IN_TERMINAL,
            ActionId.BUILD_PROJECT,
            ActionId.PUSH,
            ActionId.FETCH,
            ActionId.UPDATE_PROJECT,
            ActionId.COMMIT,
        )

        for (action in actions) {
            if (action in skipped) continue
            state.fileOperation = null
            state.contextMenu = null
            state.findInPathVisible = false
            state.focus = KeyContext.PROJECT_TREE
            assertTrue(dispatchAction(action, state) { null }, "$action was handed back")
        }
    }

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
