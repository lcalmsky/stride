package stride.app.vcs

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class UpdateUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(UpdateUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `command T fast-forwards and the open editor reads the new disk text`() = runComposeUiTest {
        val repo = upstream()
        val state = AppState()
        try {
            open(repo, state)
            val editor = state.session!!.editors.openFile(VFile(repo.root.resolve("src/A.kt"))) as TextFileEditor
            state.focusSelectedEditor()
            val clone = cloneOf(repo)
            commitIn(repo, clone, "src/A.kt", "new A\n", "Remote A")
            setContent { KeyHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            pressCommand(Key.T)

            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
            onNodeWithText("main ← origin/main").assertExists()
            onNodeWithText("Fast-forward").assertExists()
            onNodeWithText("Remote A").assertExists()
            onNodeWithText("↵ Update").assertExists()

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) {
                state.statusMessage == "Updated main: 1 commits from origin/main (fast-forward)"
            }
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "new A\n" }
            assertFalse(editor.isModified)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an unsaved editor is saved before the update`() = runComposeUiTest {
        val repo = upstream()
        val state = AppState()
        try {
            open(repo, state)
            val file = repo.root.resolve("src/Main.kt")
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            editor.setText("mine\n")
            state.focusSelectedEditor()
            val clone = cloneOf(repo)
            commitIn(repo, clone, "src/Remote.kt", "remote\n", "Remote one")
            setContent { KeyHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            pressCommand(Key.T)

            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
            onNodeWithText("Saves 1 unsaved editors first").assertExists()

            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) {
                state.statusMessage == "Updated main: 1 commits from origin/main (fast-forward)"
            }
            waitUntil(timeoutMillis = 5_000) { Files.readString(file) == "mine\n" }
            assertFalse(editor.isModified)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Tab switches a diverged plan to rebase and the history stays linear`() = runComposeUiTest {
        val repo = diverged()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.T)

            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
            assertEquals(UpdateStrategy.MERGE, state.updateRequest!!.plan.defaultStrategy)
            onNodeWithText("Merge").assertExists()
            onNodeWithText("Rebase").assertExists()
            onNodeWithText("1 local commits").assertExists()

            onRoot().performKeyInput { pressKey(Key.Tab) }
            onRoot().performKeyInput { pressKey(Key.Enter) }

            waitUntil(timeoutMillis = 15_000) {
                state.statusMessage == "Updated main: 1 commits from origin/main (rebase)"
            }
            val head = repo.git("rev-list", "--parents", "-n", "1", "HEAD").trim().split(' ')
            assertEquals(2, head.size)
            assertEquals("Local commit", repo.git("log", "-1", "--format=%s").trim())
            assertEquals("Remote commit", repo.git("log", "-2", "--format=%s", "HEAD").lines()[1].trim())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `nothing incoming opens no popup`() = runComposeUiTest {
        val repo = upstream()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }

            pressCommand(Key.T)

            waitUntil(timeoutMillis = 10_000) { state.statusMessage == "Already up to date" }
            assertNull(state.updateRequest)
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape closes the popup, leaves HEAD alone and hands the keyboard to the editor`() = runComposeUiTest {
        val repo = upstream()
        val state = AppState()
        try {
            open(repo, state)
            state.session!!.editors.openFile(VFile(repo.root.resolve("src/A.kt")))
            state.focusSelectedEditor()
            val clone = cloneOf(repo)
            commitIn(repo, clone, "src/A.kt", "new A\n", "Remote A")
            val head = repo.git("rev-parse", "HEAD").trim()
            setContent { KeyHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            pressCommand(Key.T)

            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
            onRoot().performKeyInput { pressKey(Key.Escape) }

            waitUntil(timeoutMillis = 5_000) { state.updateRequest == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
            assertEquals(head, repo.git("rev-parse", "HEAD").trim())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a merge plan refuses when an editor was edited while the popup was open`() = runComposeUiTest {
        val repo = diverged()
        val state = AppState()
        try {
            open(repo, state)
            val file = repo.root.resolve("src/Main.kt")
            val editor = state.session!!.editors.openFile(VFile(file)) as TextFileEditor
            state.focusSelectedEditor()
            val head = repo.git("rev-parse", "HEAD").trim()
            setContent { KeyHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            pressCommand(Key.T)

            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
            editor.setText("dirty\n")

            onRoot().performKeyInput { pressKey(Key.Enter) }

            val refusal = "Update needs a clean working tree to merge or rebase: commit your 1 changed files first"
            waitUntil(timeoutMillis = 10_000) { state.statusMessage == refusal }
            assertEquals(head, repo.git("rev-parse", "HEAD").trim())
            assertEquals("base\n", Files.readString(file))
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the vcs popup digits seven and eight run update and fetch`() = runComposeUiTest {
        val repo = upstream()
        val state = AppState()
        try {
            open(repo, state)
            state.session!!.editors.openFile(VFile(repo.root.resolve("src/Main.kt")))
            state.focusSelectedEditor()
            val clone = cloneOf(repo)
            commitIn(repo, clone, "src/Remote.kt", "remote\n", "Remote one")
            setContent { KeyHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
            onRoot().performKeyInput { pressKey(Key.Eight) }
            waitUntil(timeoutMillis = 10_000) {
                state.statusMessage == "Fetched from origin: 1 new commits on origin/main"
            }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
            onRoot().performKeyInput { pressKey(Key.NumPad7) }
            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            waitUntil(timeoutMillis = 5_000) { state.updateRequest == null }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
            val fetchesBefore = fetchCount(state)
            onRoot().performKeyInput { pressKey(Key.NumPad8) }
            waitUntil(timeoutMillis = 10_000) { fetchCount(state) == fetchesBefore + 1 }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
            onRoot().performKeyInput { pressKey(Key.Seven) }
            waitUntil(timeoutMillis = 10_000) { state.updateRequest != null }
        } finally {
            state.dispose()
        }
    }

    private fun fetchCount(state: AppState): Int =
        state.session?.git?.console?.count { it.command.startsWith("git fetch") } ?: 0

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the status bar shows Fetching while the fetch runs`() = runComposeUiTest {
        val repo = upstream()
        val state = AppState()
        val gate = CountDownLatch(1)
        try {
            open(repo, state)
            val git = state.session!!.git
            git.executor = GatedFetchExecutor(CliGitExecutor(TEST_GIT, repo.environment), gate)
            setContent { KeyHarness(state) }

            pressControl(Key.V)
            waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
            onRoot().performKeyInput { pressKey(Key.Eight) }

            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("Fetching…").fetchSemanticsNodes().isNotEmpty()
            }
            gate.countDown()
            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Fetched from origin" }
        } finally {
            gate.countDown()
            state.dispose()
        }
    }

    private fun upstream(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "base\n")
        repo.write("src/A.kt", "old\n")
        repo.commitAll("Initial commit")
        repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        return repo
    }

    /** The local branch and the upstream both gained a commit on a different file. */
    private fun diverged(): TestGitRepo {
        val repo = upstream()
        val clone = cloneOf(repo)
        commitIn(repo, clone, "src/Remote.kt", "remote\n", "Remote commit")
        repo.write("src/Local.kt", "local\n")
        repo.commitAll("Local commit")
        return repo
    }

    private fun cloneOf(repo: TestGitRepo): Path {
        val bare = repo.root.resolveSibling("${repo.root.fileName}-origin.git")
        val clone = Files.createTempDirectory(temp, "clone")
        val result = TestGitRepo.run(listOf("git", "clone", bare.toString(), clone.toString()), temp, repo.environment)
        assertEquals(0, result.exitCode, result.stderr)
        return clone
    }

    private fun gitAt(repo: TestGitRepo, dir: Path, vararg args: String): String {
        val result = TestGitRepo.run(listOf("git", "-C", dir.toString()) + args, dir, repo.environment)
        assertEquals(0, result.exitCode, result.stderr)
        return result.stdout
    }

    private fun commitIn(repo: TestGitRepo, clone: Path, file: String, text: String, message: String) {
        val target = clone.resolve(file)
        target.parent.createDirectories()
        target.writeText(text)
        gitAt(repo, clone, "add", "-A")
        gitAt(repo, clone, "commit", "-q", "-m", message)
        gitAt(repo, clone, "push")
    }

    private fun open(repo: TestGitRepo, state: AppState) {
        state.openFolder(repo.root)
        val git = requireNotNull(state.session).git
        git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { git.refreshNow() }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressCommand(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.pressControl(key: Key) {
        onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
    }
}

/** Holds the first `git fetch` until [gate] opens, so the busy message can be observed. */
private class GatedFetchExecutor(private val inner: GitExecutor, private val gate: CountDownLatch) : GitExecutor {
    override fun run(command: GitCommand): GitResult {
        if (command.args.firstOrNull() == "fetch") gate.await(10, TimeUnit.SECONDS)
        return inner.run(command)
    }
}

@Composable
private fun KeyHarness(state: AppState) {
    val router = remember { KeyRouter(isMac = true) }
    val focusRequester = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                handleWindowKeyEvent(event, router, state) { null }
            }
            .focusable(),
    ) {
        CompositionLocalProvider(
            LocalAnimationsEnabled provides false,
            LocalWordmarkCaretBlink provides false,
        ) {
            App(state, onOpenFolder = {})
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}
