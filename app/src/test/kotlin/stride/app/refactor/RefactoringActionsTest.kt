package stride.app.refactor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
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
import stride.app.editor.TextFileEditor
import stride.app.keys.KeyContext
import stride.core.services.Application
import stride.core.vfs.VFile
import stride.lang.NameCheck
import stride.lang.RefactoringEdit
import stride.lang.RefactoringKind
import stride.lang.RefactoringOption
import stride.lang.RefactoringStep
import stride.lang.TextEdit
import java.nio.file.Files
import java.nio.file.Path

class RefactoringActionsTest {
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
    fun `a refused step writes its reason and opens no popup`() {
        val editor = editorWith("Introduce Variable works inside a function body\n")
        state.prepareRefactoring = { _, _ ->
            RefactoringStep.Refused("Introduce Variable works inside a function body")
        }

        assertTrue(startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE))

        assertEquals("Introduce Variable works inside a function body", state.statusMessage)
        assertNull(state.refactoringName)
        assertNull(state.refactoringChoose)
        assertEquals("Introduce Variable works inside a function body\n", editor.value.text)
    }

    @Test
    fun `a read-only editor reports it without asking the provider`() {
        val root = temp.resolve("read-only-project")
        Files.createDirectories(root)
        state.openFolder(root)
        val editor = requireNotNull(state.session).editors.openFile(VFile(root.resolve("missing.kt")))
        assertTrue((editor as TextFileEditor).isReadOnly)
        var prepared = false
        state.prepareRefactoring = { _, _ ->
            prepared = true
            RefactoringStep.Refused("no")
        }

        assertTrue(startRefactoring(state, RefactoringKind.INLINE))

        assertEquals("File is read-only", state.statusMessage)
        assertFalse(prepared)
    }

    @Test
    fun `without a selected text editor the actions do nothing`() {
        assertFalse(startRefactoring(state, RefactoringKind.EXTRACT_METHOD))
        assertFalse(openRefactorThis(state))
        assertFalse(chooseRefactorThis(state, RefactorThisEntry.Rename))
    }

    @Test
    fun `an applied edit changes the text, places the caret and shows the message`() {
        val editor = editorWith("val total = 1\n")
        editor.updateValue(TextFieldValue("val total = 1\n", TextRange(4)))
        state.prepareRefactoring = { _, _ ->
            RefactoringStep.Apply(
                RefactoringEdit(listOf(TextEdit(12, 13, "2")), caret = 13, message = "Introduced variable total"),
            )
        }

        assertTrue(startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE))

        assertEquals("val total = 2\n", editor.value.text)
        assertEquals(TextRange(13), editor.value.selection)
        assertEquals("Introduced variable total", state.statusMessage)

        assertTrue(editor.undo())
        assertEquals("val total = 1\n", editor.value.text)
        assertEquals(TextRange(4), editor.value.selection)

        assertTrue(editor.redo())
        assertEquals("val total = 2\n", editor.value.text)
        assertEquals(TextRange(13), editor.value.selection)
    }

    @Test
    fun `an invalid name keeps the popup open and a valid one applies`() {
        val editor = editorWith("foo bar\n")
        editor.updateValue(TextFieldValue("foo bar\n", TextRange(0)))
        state.prepareRefactoring = { _, _ ->
            RefactoringStep.AskName("Introduce Variable", "foo") { name ->
                if (name == "used") {
                    NameCheck.Invalid("'used' is already used in this function")
                } else {
                    NameCheck.Valid(
                        RefactoringEdit(listOf(TextEdit(0, 3, name)), caret = name.length, message = "Introduced variable $name"),
                    )
                }
            }
        }

        assertTrue(startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE))
        val request = requireNotNull(state.refactoringName)
        assertEquals("Introduce Variable", request.step.title)
        assertEquals("foo", request.step.suggested)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

        assertTrue(submitRefactoringName(state, "used"))
        assertNotNull(state.refactoringName)
        assertEquals("'used' is already used in this function", state.refactoringName?.errorMessage)
        assertEquals("foo bar\n", editor.value.text)

        assertTrue(submitRefactoringName(state, "total"))
        assertNull(state.refactoringName)
        assertEquals("total bar\n", editor.value.text)
        assertEquals(TextRange(5), editor.value.selection)
        assertEquals("Introduced variable total", state.statusMessage)
    }

    @Test
    fun `a choose step with one option is followed without a popup`() {
        val editor = editorWith("alpha\n")
        state.prepareRefactoring = { _, _ ->
            RefactoringStep.Choose(
                "Expressions",
                listOf(
                    RefactoringOption("alpha") {
                        RefactoringStep.Apply(
                            RefactoringEdit(listOf(TextEdit(0, 0, "val value = ")), caret = 12, message = "Introduced variable value"),
                        )
                    },
                ),
            )
        }

        assertTrue(startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE))

        assertNull(state.refactoringChoose)
        assertEquals("val value = alpha\n", editor.value.text)
        assertEquals("Introduced variable value", state.statusMessage)
    }

    @Test
    fun `overlapping edits are refused and leave the text`() {
        val editor = editorWith("alpha beta\n")
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))
        state.prepareRefactoring = { _, _ ->
            RefactoringStep.Apply(
                RefactoringEdit(listOf(TextEdit(0, 5, "ALPHA"), TextEdit(3, 8, "BETA")), caret = 0),
            )
        }

        assertTrue(startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE))

        assertEquals("Introduce Variable failed: the edits overlap", state.statusMessage)
        assertEquals("alpha beta\n", editor.value.text)
    }

    @Test
    fun `refactor this reuses the prepared steps`() {
        val editor = editorWith("alpha beta\n")
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))
        var prepared = 0
        state.prepareRefactoring = { _, _ ->
            prepared++
            RefactoringStep.Apply(RefactoringEdit(listOf(TextEdit(0, 0, "val x = 1\n")), caret = 0))
        }

        assertTrue(openRefactorThis(state))
        assertEquals(3, prepared)
        val request = requireNotNull(state.refactorThis)

        assertTrue(chooseRefactorThis(state, request.rows[1].value))
        assertEquals(3, prepared)
        assertEquals("val x = 1\nalpha beta\n", editor.value.text)
    }

    @Test
    fun `a text changed after preparing refuses the edit and keeps it`() {
        val editor = editorWith("foo bar\n")
        editor.updateValue(TextFieldValue("foo bar\n", TextRange(0)))
        state.prepareRefactoring = { _, _ ->
            RefactoringStep.AskName("Introduce Variable", "foo") { name ->
                NameCheck.Valid(RefactoringEdit(listOf(TextEdit(0, 3, name)), caret = 0))
            }
        }
        assertTrue(startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE))

        editor.updateValue(TextFieldValue("changed\n", TextRange(0)))
        assertTrue(submitRefactoringName(state, "total"))

        assertEquals("changed\n", editor.value.text)
        assertEquals("The file changed; run Introduce Variable again", state.statusMessage)
        assertNull(state.refactoringName)
    }

    @Test
    fun `starting a refactoring drops the secondary carets`() {
        val editor = editorWith("alpha beta\n")
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))
        editor.toggleCaretAt(6)
        assertTrue(editor.secondaryCarets.isNotEmpty())
        state.prepareRefactoring = { _, _ -> RefactoringStep.Refused("nothing here") }

        assertTrue(startRefactoring(state, RefactoringKind.INLINE))

        assertTrue(editor.secondaryCarets.isEmpty())
    }

    @Test
    fun `refactorings run while the search popup has the focus`() {
        val editor = editorWith("alpha\n")
        state.focus = KeyContext.SEARCH_POPUP
        state.prepareRefactoring = { _, _ ->
            RefactoringStep.Apply(
                RefactoringEdit(listOf(TextEdit(0, 5, "beta")), caret = 4, message = "Inlined a call of alpha"),
            )
        }

        assertTrue(startRefactoring(state, RefactoringKind.INLINE))

        assertEquals("beta\n", editor.value.text)
        assertEquals("Inlined a call of alpha", state.statusMessage)
    }

    @Test
    fun `refactor this lists the applicable entries in order with their strokes`() {
        val editor = editorWith("alpha beta\n")
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))
        state.prepareRefactoring = { _, kind ->
            when (kind) {
                RefactoringKind.INTRODUCE_VARIABLE ->
                    RefactoringStep.Apply(RefactoringEdit(listOf(TextEdit(0, 0, "val x = 1\n")), caret = 0))
                RefactoringKind.EXTRACT_METHOD -> RefactoringStep.Refused("Select the code to extract")
                RefactoringKind.INLINE ->
                    RefactoringStep.Apply(RefactoringEdit(listOf(TextEdit(0, 0, "val y = 2\n")), caret = 0))
            }
        }

        assertTrue(openRefactorThis(state))

        val request = requireNotNull(state.refactorThis)
        assertEquals(listOf("Rename…", "Introduce Variable…", "Inline…"), request.rows.map { it.text })
        assertEquals(listOf("⇧F6", "⌥⌘V", "⌥⌘N"), request.rows.map { it.hint })
        assertEquals(RefactorThisEntry.Rename, request.rows[0].value)
        assertEquals(RefactoringKind.INTRODUCE_VARIABLE, (request.rows[1].value as RefactorThisEntry.Prepared).kind)
        assertEquals(RefactoringKind.INLINE, (request.rows[2].value as RefactorThisEntry.Prepared).kind)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        assertNull(state.statusMessage)

        assertTrue(chooseRefactorThis(state, request.rows[1].value))
        assertNull(state.refactorThis)
        assertEquals("val x = 1\nalpha beta\n", editor.value.text)
    }

    @Test
    fun `refactor this reports an empty list`() {
        val editor = editorWith("   \n")
        editor.updateValue(TextFieldValue("   \n", TextRange(0)))
        state.prepareRefactoring = { _, _ -> RefactoringStep.Refused("not here") }

        assertTrue(openRefactorThis(state))

        assertNull(state.refactorThis)
        assertEquals("No refactorings available at the caret", state.statusMessage)
    }

    @Test
    fun `the rename row opens the rename popup`() {
        val editor = editorWith("alpha beta\n")
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))
        state.prepareRefactoring = { _, _ -> RefactoringStep.Refused("not here") }
        assertTrue(openRefactorThis(state))

        assertTrue(chooseRefactorThis(state, RefactorThisEntry.Rename))

        assertNull(state.refactorThis)
        assertEquals("alpha", state.rename?.original)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        assertEquals("alpha beta\n", editor.value.text)
    }

    @Test
    fun `a chooser highlights the selected option and puts the old highlights back`() {
        val editor = editorWith("alpha beta\n")
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))
        editor.usageHighlights = listOf(0..1)
        val first = RefactoringOption("Replace this occurrence only", listOf(0..4)) {
            RefactoringStep.Refused("first")
        }
        val second = RefactoringOption("Replace all 2 occurrences", listOf(0..4, 6..9)) {
            RefactoringStep.Refused("second")
        }
        state.prepareRefactoring = { _, _ -> RefactoringStep.Choose("Occurrences", listOf(first, second)) }

        assertTrue(startRefactoring(state, RefactoringKind.INTRODUCE_VARIABLE))
        assertEquals(listOf(0..4), editor.usageHighlights)
        assertEquals("Occurrences", state.refactoringChoose?.step?.title)

        selectRefactoringOption(state, second)
        assertEquals(listOf(0..4, 6..9), editor.usageHighlights)

        closeRefactoringChoose(state)
        assertNull(state.refactoringChoose)
        assertEquals(listOf(0..1), editor.usageHighlights)
    }

    @Test
    fun `the provider path refuses java and plain text files`() {
        val java = prepareWithProviders(TextFileEditor(VFile(temp.resolve("Main.java"))), RefactoringKind.INTRODUCE_VARIABLE)
        assertEquals(
            "Introduce Variable is not available for .java files",
            (java as RefactoringStep.Refused).reason,
        )
        val text = prepareWithProviders(TextFileEditor(VFile(temp.resolve("notes.txt"))), RefactoringKind.INLINE)
        assertEquals(
            "Inline is not available for .txt files",
            (text as RefactoringStep.Refused).reason,
        )
    }

    private fun editorWith(text: String): TextFileEditor {
        val root = temp.resolve("project")
        Files.createDirectories(root)
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val file = root.resolve("Main.kt")
        Files.writeString(file, text)
        return session.editors.openFile(VFile(file)) as TextFileEditor
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(RefactoringActionsTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
