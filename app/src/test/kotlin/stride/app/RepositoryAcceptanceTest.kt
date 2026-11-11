package stride.app

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.search.SearchEverywhere
import stride.app.ui.App
import stride.app.ui.SnapshotRenderer
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.lang.ColorScheme
import stride.lang.TextAttributesKey
import stride.search.FileNameIndex
import java.nio.file.Files
import java.nio.file.Path

/**
 * Acceptance test against a real checkout of this repository. [REPO_ROOT_PROPERTY] is set by
 * `app/build.gradle.kts`; nothing in this class writes to the checkout.
 */
class RepositoryAcceptanceTest {
    private val repoRoot: Path by lazy {
        val configured = System.getProperty(REPO_ROOT_PROPERTY)
            ?: error("system property $REPO_ROOT_PROPERTY is missing; app/build.gradle.kts tasks.test sets it")
        val root = Path.of(configured).toAbsolutePath().normalize()
        check(Files.isDirectory(root.resolve("app"))) {
            "$REPO_ROOT_PROPERTY=$root is not a stride repository checkout"
        }
        root
    }

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(RepositoryAcceptanceTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @Test
    fun `tree shows the repository top level and hides generated directories`() {
        val state = AppState()
        try {
            state.openFolder(repoRoot)
            val session = requireNotNull(state.session) { "openFolder did not create a session" }

            val visible = session.tree.rows().filter { it.depth == 1 }.map { it.file.name }

            for (expected in TOP_LEVEL_DIRECTORIES) {
                assertTrue(expected in visible, "$expected missing from the repository tree: $visible")
            }
            assertFalse(".git" in visible, ".git must not be visible in the tree: $visible")
            assertFalse("build" in visible, "build must not be visible in the tree: $visible")
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `Main kt opens as the selected highlighted tab`() {
        val state = AppState()
        try {
            state.openFolder(repoRoot)
            val session = requireNotNull(state.session) { "openFolder did not create a session" }
            val file = repoRoot.resolve("app/src/main/kotlin/stride/app/Main.kt")

            val opened = requireNotNull(session.editors.openFile(VFile(file))) { "Main.kt did not open" }
            assertTrue(opened is TextFileEditor, "Main.kt opened with ${opened::class.simpleName}")
            val editor = opened as TextFileEditor

            assertSame(editor, session.editors.selected)
            assertFalse(editor.isReadOnly, "Main.kt opened read-only")
            val keyword = editor.spans.firstOrNull { it.key == TextAttributesKey.KEYWORD }
            assertNotNull(keyword, "Main.kt produced no keyword span")
            assertEquals(
                ColorScheme.DARK_ACCENT,
                ColorScheme.DARK.getAttributes(requireNotNull(keyword).key)?.foreground,
                "keyword span ${keyword.start}..${keyword.end} is not colored with the dark scheme keyword color",
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `double shift api-search enter opens docs api-search md`() = runComposeUiTest {
        val state = AppState()
        try {
            state.openFolder(repoRoot)
            val session = requireNotNull(state.session) { "openFolder did not create a session" }
            assertTrue(
                session.project.service<FileNameIndex>().awaitReady(MAX_INDEX_WAIT_MILLIS),
                "file index did not finish within $MAX_INDEX_WAIT_MILLIS ms",
            )

            val ranked = SearchEverywhere(session.project).fetch("api-search", limit = 5)
            println(
                "SEARCH api-search top ${ranked.size}: " +
                    ranked.mapIndexed { index, entry -> "${index + 1}. ${entry.presentationText} <- ${entry.secondaryText}" },
            )

            setContent { AcceptanceHarness(state) }

            onRoot().performKeyInput {
                pressKey(Key.ShiftLeft)
                pressKey(Key.ShiftLeft)
            }
            waitUntil(timeoutMillis = 5_000) { state.searchVisible }
            onNodeWithText("Search everywhere").assertExists()

            onNode(hasSetTextAction()).performTextInput("api-search")
            waitUntil(timeoutMillis = 10_000) {
                onAllNodesWithText("api-search.md").fetchSemanticsNodes().isNotEmpty()
            }

            onRoot().performKeyInput { pressKey(Key.Enter) }

            val target = repoRoot.resolve("docs/api-search.md")
            waitUntil(timeoutMillis = 10_000) {
                session.editors.selected?.file?.path?.toAbsolutePath()?.normalize() == target
            }
            assertFalse(state.searchVisible, "search popup stayed open after choosing a result")
            assertEquals(target, session.editors.selected?.file?.path?.toAbsolutePath()?.normalize())
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `repository kotlin tab snapshot`() {
        val state = AppState()
        try {
            state.openFolder(repoRoot)
            val session = requireNotNull(state.session) { "openFolder did not create a session" }
            session.editors.openFile(VFile(repoRoot.resolve("app/src/main/kotlin/stride/app/Main.kt")))
            SnapshotRenderer.render(
                SnapshotRenderer.snapshotDir().resolve("10-repo-kotlin-tab.png"),
                state,
            )
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `repository search snapshot`() {
        val state = AppState()
        try {
            state.openFolder(repoRoot)
            val session = requireNotNull(state.session) { "openFolder did not create a session" }
            check(session.project.service<FileNameIndex>().awaitReady(MAX_INDEX_WAIT_MILLIS)) {
                "file index did not finish within $MAX_INDEX_WAIT_MILLIS ms"
            }
            state.searchVisible = true
            SnapshotRenderer.render(
                SnapshotRenderer.snapshotDir().resolve("11-repo-search.png"),
                state,
                initialQuery = "api",
                renderMillis = 1_800,
            )
        } finally {
            state.dispose()
        }
    }

    private companion object {
        const val REPO_ROOT_PROPERTY = "stride.repoRoot"
        const val MAX_INDEX_WAIT_MILLIS = 20_000L
        val TOP_LEVEL_DIRECTORIES = listOf("app", "core", "docs", "lang", "plugins", "search")
    }
}

@Composable
private fun AcceptanceHarness(state: AppState) {
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
        App(state, onOpenFolder = {})
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
    }
}
