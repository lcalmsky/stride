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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.dispatchAction
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.core.services.Application
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class BranchesUiTest {
    @TempDir
    lateinit var temp: Path

    @BeforeEach
    fun loadPlugins() {
        Application.resetForTests()
        Application.init(BranchesUiTest::class.java.classLoader)
    }

    @AfterEach
    fun resetPlugins() {
        Application.resetForTests()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `control V then zero opens the groups and the search field filters them`() = runComposeUiTest {
        val repo = repoWithRemote()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)
            waitForIdle()

            assertTrue(exists("Branches"), "the popup title is missing")
            assertTrue(exists("Local (1)"), "the local group is missing")
            assertTrue(exists("Remote (2)"), "the remote group is missing")
            assertTrue(existsInPopup("main"))
            assertTrue(existsInPopup("origin/feature/login"))
            assertEquals(1, onAllNodes(hasTestTag(rowTag("main"))).fetchSemanticsNodes().size)

            onNodeWithTag(BRANCHES_SEARCH_TAG).performTextInput("feature")
            waitUntil(timeoutMillis = 5_000) { existsInPopup("origin/feature/login") && !exists("Local (1)") }
            waitForIdle()
            assertTrue(
                onAllNodes(hasTestTag(rowTag("main"))).fetchSemanticsNodes().isEmpty(),
                "the local row survived the search",
            )
            assertEquals(1, countInPopup("main"), "only the header's current branch label is left")
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the menu checks out the selected branch and the open editor reads it`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        try {
            open(repo, state)
            val editor = state.session!!.editors.openFile(VFile(main)) as TextFileEditor
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "side" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            assertTrue(exists("Checkout"))
            press(Key.Enter)

            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Checked out side" }
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup == null }
            assertEquals("side", repo.git("rev-parse", "--abbrev-ref", "HEAD").trim())
            waitUntil(timeoutMillis = 5_000) { editor.value.text == "side\n" }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `checkout saves unsaved editors first and git then refuses the carried change`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        try {
            open(repo, state)
            val editor = state.session!!.editors.openFile(VFile(main)) as TextFileEditor
            editor.setText("mine\n")
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "side" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            assertTrue(exists("Saves 1 unsaved editors first"))
            press(Key.Enter)

            waitUntil(timeoutMillis = 15_000) {
                state.statusMessage == "Checkout stopped: local changes to 1 files would be overwritten: commit them first"
            }
            assertEquals("mine\n", Files.readString(main), "the editor was not saved before git ran")
            assertFalse(editor.isModified)
            assertEquals("main", repo.git("rev-parse", "--abbrev-ref", "HEAD").trim())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `checking out a remote branch creates the local branch that tracks it`() = runComposeUiTest {
        val repo = repoWithRemote()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "origin/feature/login" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.Enter)

            waitUntil(timeoutMillis = 20_000) {
                state.statusMessage == "Checked out feature/login, tracking origin/feature/login"
            }
            assertEquals(
                "origin/feature/login",
                repo.git("rev-parse", "--abbrev-ref", "feature/login@{upstream}").trim(),
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `alt command N opens the name page, Tab turns Checkout off and creates only the branch`() =
        runComposeUiTest {
            val repo = repoWithRemote()
            val state = AppState()
            try {
                open(repo, state)
                setContent { KeyHarness(state) }
                openBranchesFromVcs(state)

                pressAltCommand(Key.N)
                waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.NAME }
                waitForIdle()
                assertTrue(exists("New Branch"))
                assertTrue(exists("from HEAD"))

                onNodeWithTag(BRANCHES_NAME_FIELD_TAG).performTextInput("feature/new")
                waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.name?.text == "feature/new" }
                press(Key.Tab)
                waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.checkout == false }
                press(Key.Enter)

                waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Created feature/new from HEAD" }
                assertEquals("main", repo.git("rev-parse", "--abbrev-ref", "HEAD").trim())
                assertEquals("feature/new", repo.git("rev-parse", "--abbrev-ref", "feature/new").trim())
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an invalid name shows its problem and return does nothing`() = runComposeUiTest {
        val repo = repoWithRemote()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)
            pressAltCommand(Key.N)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.NAME }

            onNodeWithTag(BRANCHES_NAME_FIELD_TAG).performTextInput("bad name")
            waitUntil(timeoutMillis = 5_000) { exists("bad name is not a valid branch name") }
            press(Key.Enter)
            waitForIdle()

            assertNotNull(state.branchesPopup, "the name page closed on a name git would refuse")
            assertNull(state.statusMessage)
            assertTrue(repo.git("branch", "--list", "bad").trim().isEmpty())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Rename from the menu renames the branch`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "side" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.selected == 2 }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.NAME }

            onNodeWithTag(BRANCHES_NAME_FIELD_TAG).performTextInput("side-2")
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.name?.text == "side-2" }
            press(Key.Enter)

            waitUntil(timeoutMillis = 15_000) { state.statusMessage == "Renamed side to side-2" }
            assertEquals("side-2", repo.git("rev-parse", "--abbrev-ref", "side-2").trim())
            assertTrue(repo.git("branch", "--list", "side").trim().isEmpty())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `a merged branch is deleted at once from the menu`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        repo.git("branch", "merged")
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "merged" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.Enter)

            waitUntil(timeoutMillis = 15_000) { state.statusMessage?.startsWith("Deleted branch merged (was ") == true }
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup == null }
            assertTrue(repo.git("branch", "--list", "merged").trim().isEmpty())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `an unmerged branch opens the delete page and return force-deletes it`() = runComposeUiTest {
        val repo = repoWithUnmergedBranch()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "unmerged" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.Enter)

            waitUntil(timeoutMillis = 15_000) { state.branchesPopup?.page == BranchesPage.DELETE }
            waitPanelFocused()
            waitForIdle()
            assertTrue(exists("Delete unmerged?"))
            assertTrue(exists("1 commit is not merged into HEAD"))
            press(Key.Enter)

            waitUntil(timeoutMillis = 15_000) { state.statusMessage?.startsWith("Deleted branch unmerged (was ") == true }
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup == null }
            assertTrue(repo.git("branch", "--list", "unmerged").trim().isEmpty())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape goes back through the delete page and the menu, then closes the list`() = runComposeUiTest {
        val repo = repoWithUnmergedBranch()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "unmerged" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            assertTrue(exists("Rename…"))
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            press(Key.Enter)
            waitUntil(timeoutMillis = 15_000) { state.branchesPopup?.page == BranchesPage.DELETE }
            waitPanelFocused()

            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.DirectionLeft)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.LIST }
            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup == null }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `clicking a row selects it and opens its menu, and escape returns to that row`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            onNodeWithTag(rowTag("side")).performClick()
            waitUntil(timeoutMillis = 5_000) {
                state.branchesPopup?.page == BranchesPage.MENU && state.branchesPopup?.menu?.display == "side"
            }
            waitPanelFocused()
            assertTrue(exists("Checkout"))

            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.LIST }
            assertEquals("side", selectedName(state), "the row the menu was opened from is not selected")
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `escape from a name page goes back to the page it came from`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            // A menu visited and left behind must not pull the list's name page back to it.
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "side" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.LIST }

            onNodeWithText("New Branch…").performClick()
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.NAME }
            waitForIdle()
            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.LIST }

            onNodeWithTag(rowTag("side")).performClick()
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.DirectionDown)
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.NAME }
            waitForIdle()
            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the menu counts only the editors a checkout would save`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val outside = temp.resolve("outside.txt")
        Files.writeString(outside, "outside\n")
        val state = AppState()
        try {
            open(repo, state)
            val editor = state.session!!.editors.openFile(VFile(outside)) as TextFileEditor
            editor.setText("edited outside the project\n")
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "side" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            waitForIdle()

            assertFalse(
                exists("Saves 1 unsaved editors first"),
                "an editor outside the project root was counted",
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the rename page does not call the old name taken`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            openBranchesFromVcs(state)

            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { selectedName(state) == "side" }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.MENU }
            waitPanelFocused()
            press(Key.DirectionDown)
            press(Key.DirectionDown)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.selected == 2 }
            press(Key.Enter)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.NAME }
            waitForIdle()

            assertEquals("side", state.branchesPopup?.name?.text)
            assertTrue(
                onAllNodes(hasTestTag(BRANCHES_NAME_PROBLEM_TAG)).fetchSemanticsNodes().isEmpty(),
                "the old name was reported as taken",
            )
            press(Key.Enter)
            waitForIdle()
            assertEquals(BranchesPage.NAME, state.branchesPopup?.page)
            assertNull(state.statusMessage)

            onNodeWithTag(BRANCHES_NAME_FIELD_TAG).performTextInput("main")
            waitUntil(timeoutMillis = 5_000) { exists("A branch named main already exists") }
            press(Key.Enter)
            waitForIdle()
            assertEquals(BranchesPage.NAME, state.branchesPopup?.page)
            assertEquals("side", repo.git("rev-parse", "--abbrev-ref", "side").trim())
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing after moving inside the popup gives the commit window's list the keyboard back`() =
        runComposeUiTest {
            val repo = repoWithSideBranch()
            repo.write("src/Main.kt", "changed\n")
            repo.write("src/Other.kt", "other\n")
            val state = AppState()
            try {
                open(repo, state)
                setContent { KeyHarness(state) }
                openCommitWindow(state)
                focusCommitList()

                pressControl(Key.V)
                waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
                press(Key.Zero)
                waitUntil(timeoutMillis = 5_000) { state.branchesPopup != null }
                waitUntil(timeoutMillis = 10_000) { state.branchesPopup?.list is BranchList.Ready }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }

                press(Key.DirectionDown)
                waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.selected != 0 }
                onNodeWithTag(BRANCHES_SEARCH_TAG).performTextInput("side")
                waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.query == "side" }

                press(Key.Escape)

                waitUntil(timeoutMillis = 5_000) { state.branchesPopup == null }
                waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.COMMIT_WINDOW }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
                }
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `closing from the editor returns the keyboard to the editor`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        val main = repo.root.resolve("src/Main.kt")
        try {
            open(repo, state)
            state.session!!.editors.openFile(VFile(main))
            state.focusSelectedEditor()
            setContent { KeyHarness(state) }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }

            openBranchesFromVcs(state)
            press(Key.Escape)

            waitUntil(timeoutMillis = 5_000) { state.branchesPopup == null }
            waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.EDITOR }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the status bar shows the branch inside a repository and opens the popup on a click`() =
        runComposeUiTest {
            val repo = repoWithSideBranch()
            val state = AppState()
            try {
                open(repo, state)
                setContent { KeyHarness(state) }
                waitUntil(timeoutMillis = 5_000) {
                    runCatching { onNodeWithTag("status-branch").assertExists() }.isSuccess
                }
                onNodeWithTag("status-branch").assertTextEquals("main")

                onNodeWithTag("status-branch").performClick()
                waitUntil(timeoutMillis = 5_000) { state.branchesPopup != null }
                waitUntil(timeoutMillis = 10_000) { state.branchesPopup?.list is BranchList.Ready }
            } finally {
                state.dispose()
            }
        }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `outside a repository the status bar draws no branch item`() = runComposeUiTest {
        val root = Files.createDirectories(temp.resolve("plain-project"))
        val state = AppState()
        try {
            state.openFolder(root)
            state.session!!.git.executor = CliGitExecutor(
                TEST_GIT,
                TestGitRepo.isolatedEnvironment(
                    ceiling = temp,
                    home = Files.createDirectories(temp.resolve("plain-home")),
                ),
            )
            runBlocking { state.session!!.git.refreshNow() }
            setContent { KeyHarness(state) }
            waitForIdle()

            assertEquals(
                0,
                onAllNodes(hasTestTag("status-branch")).fetchSemanticsNodes().size,
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `Find Action opens the name page directly and escape closes it`() = runComposeUiTest {
        val repo = repoWithSideBranch()
        val state = AppState()
        try {
            open(repo, state)
            setContent { KeyHarness(state) }
            waitForIdle()

            assertTrue(dispatchAction(ActionId.NEW_BRANCH, state) { null })
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup?.page == BranchesPage.NAME }
            assertTrue(state.branchesPopup!!.openedDirectly)
            waitForIdle()
            assertTrue(exists("New Branch"))

            press(Key.Escape)
            waitUntil(timeoutMillis = 5_000) { state.branchesPopup == null }
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `closeSession clears the branch popup and the rollback request`() {
        val state = AppState()
        val root = Files.createDirectories(temp.resolve("closing"))
        try {
            state.openFolder(root)
            state.branchesPopup = BranchesRequest(token = 1L)
            state.rollbackRequest = RollbackRequest(emptyList())
            state.closeSession()
            assertNull(state.branchesPopup)
            assertNull(state.rollbackRequest)
        } finally {
            state.dispose()
        }
    }

    /** `main` with `side` one commit ahead on `src/Main.kt`, and both checked into `repo`. */
    private fun repoWithSideBranch(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.git("checkout", "-q", "-b", "side")
        repo.write("src/Main.kt", "side\n")
        repo.commitAll("Side")
        repo.git("checkout", "-q", "main")
        return repo
    }

    /** `main` with `merged` at its commit and `unmerged` one commit ahead of it. */
    private fun repoWithUnmergedBranch(): TestGitRepo {
        val repo = repoWithSideBranch()
        repo.git("checkout", "-q", "-b", "unmerged")
        repo.write("src/Other.kt", "other\n")
        repo.commitAll("Unmerged work")
        repo.git("checkout", "-q", "main")
        return repo
    }

    /** `main` pushed to `origin` and `origin/feature/login` pushed with no local branch tracking it. */
    private fun repoWithRemote(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "first\n")
        repo.commitAll("Initial commit")
        repo.bareRemote()
        repo.git("push", "-q", "-u", "origin", "main")
        repo.git("branch", "feature/login")
        repo.git("push", "-q", "origin", "feature/login")
        repo.git("branch", "-D", "feature/login")
        return repo
    }

    private fun open(repo: TestGitRepo, state: AppState) {
        state.openFolder(repo.root)
        val git = requireNotNull(state.session).git
        git.executor = CliGitExecutor(TEST_GIT, repo.environment)
        runBlocking { git.refreshNow() }
    }

    private fun selectedName(state: AppState): String? {
        val request = state.branchesPopup ?: return null
        val list = request.list as? BranchList.Ready ?: return null
        val names = buildList {
            add("New Branch…")
            if (list.local.isNotEmpty()) {
                add("Local")
                addAll(list.local.map { it.name })
            }
            if (list.remote.isNotEmpty()) {
                add("Remote")
                addAll(list.remote.map { it.display })
            }
        }
        return names.getOrNull(request.selected)?.takeUnless { it == "Local" || it == "Remote" }
    }
}

private fun rowTag(name: String): String = BRANCH_ROW_TAG_PREFIX + name

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.exists(text: String): Boolean =
    onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.countInPopup(text: String): Int =
    onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(BRANCHES_POPUP_TAG))).fetchSemanticsNodes().size

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.existsInPopup(text: String): Boolean =
    onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(BRANCHES_POPUP_TAG)))
        .fetchSemanticsNodes()
        .isNotEmpty()

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.press(key: Key) {
    onRoot().performKeyInput { pressKey(key) }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.pressControl(key: Key) {
    onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(key) } }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.pressAltCommand(key: Key) {
    onRoot().performKeyInput {
        withKeyDown(Key.AltLeft) { withKeyDown(Key.MetaLeft) { pressKey(key) } }
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.waitPanelFocused() {
    waitUntil(timeoutMillis = 5_000) {
        runCatching { onNodeWithTag(BRANCHES_POPUP_TAG).assertIsFocused() }.isSuccess
    }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openBranchesFromVcs(state: AppState) {
    pressControl(Key.V)
    waitUntil(timeoutMillis = 5_000) { state.vcsPopup != null }
    press(Key.Zero)
    waitUntil(timeoutMillis = 5_000) { state.branchesPopup != null }
    waitUntil(timeoutMillis = 10_000) { state.branchesPopup?.list is BranchList.Ready }
    waitUntil(timeoutMillis = 5_000) { state.focus == KeyContext.CONTEXT_POPUP }
}

@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.openCommitWindow(state: AppState) {
    onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.K) } }
    waitUntil(timeoutMillis = 5_000) { state.toolWindows.visible && state.toolWindows.activeId == "commit" }
    waitUntil(timeoutMillis = 5_000) {
        runCatching { onNodeWithTag(COMMIT_MESSAGE_TAG).assertIsFocused() }.isSuccess
    }
}

/** Tabs from the message field to the file list. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.focusCommitList() {
    press(Key.Tab)
    waitUntil(timeoutMillis = 5_000) {
        runCatching { onNodeWithTag(COMMIT_LIST_TAG).assertIsFocused() }.isSuccess
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
