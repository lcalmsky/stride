package stride.app.generate

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
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
import stride.lang.GenerateKind
import stride.lang.MemberOption
import stride.lang.NameCheck
import stride.lang.RefactoringEdit
import stride.lang.RefactoringStep
import stride.lang.TextEdit

class GenerateActionsTest {
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
    fun `generate lists the kinds that did not refuse in order with their strokes`() {
        val editor = editorWith("class Alpha\n")
        state.prepareGenerate = { _, kind ->
            when (kind) {
                GenerateKind.SECONDARY_CONSTRUCTOR, GenerateKind.TO_STRING ->
                    step("val $kind = 1\n")
                GenerateKind.OVERRIDE_METHODS, GenerateKind.IMPLEMENT_METHODS ->
                    step("val $kind = 1\n")
                GenerateKind.EQUALS_HASH_CODE -> RefactoringStep.Refused("equals() already exists in Alpha")
            }
        }

        assertTrue(openGenerate(state))

        val request = requireNotNull(state.generate)
        assertEquals(
            listOf("Secondary Constructor…", "toString()…", "Override Methods…", "Implement Methods…"),
            request.rows.map { it.text },
        )
        assertEquals(listOf("", "", "⌃O", "⌃I"), request.rows.map { it.hint.orEmpty() })
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)
        assertNull(state.statusMessage)
        assertEquals("class Alpha\n", editor.value.text)
    }

    @Test
    fun `with every kind refused the status says nothing to generate`() {
        editorWith("class Alpha\n")
        state.prepareGenerate = { _, _ -> RefactoringStep.Refused("not here") }

        assertTrue(openGenerate(state))

        assertNull(state.generate)
        assertEquals("Nothing to generate here", state.statusMessage)
    }

    @Test
    fun `choosing a row follows the prepared step without preparing again`() {
        val editor = editorWith("class Alpha\n")
        var prepared = 0
        state.prepareGenerate = { _, _ ->
            prepared++
            step("    fun alpha() {}\n")
        }

        assertTrue(openGenerate(state))
        val request = requireNotNull(state.generate)
        assertEquals(5, prepared)
        val entry = request.rows.first().value

        assertTrue(chooseGenerate(state, entry))

        assertEquals(5, prepared)
        assertNull(state.generate)
        assertEquals("    fun alpha() {}\nclass Alpha\n", editor.value.text)
    }

    @Test
    fun `override and implement run their kind directly`() {
        val editor = editorWith("class Alpha\n")
        val asked = ArrayList<GenerateKind>()
        state.prepareGenerate = { _, kind ->
            asked += kind
            step("    override fun alpha() {}\n")
        }

        assertTrue(startGenerate(state, GenerateKind.OVERRIDE_METHODS))
        assertEquals(listOf(GenerateKind.OVERRIDE_METHODS), asked)
        assertEquals("    override fun alpha() {}\nclass Alpha\n", editor.value.text)

        editor.updateValue(TextFieldValue("class Alpha\n", TextRange(0)))
        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))
        assertEquals(listOf(GenerateKind.OVERRIDE_METHODS, GenerateKind.IMPLEMENT_METHODS), asked)
    }

    @Test
    fun `a refused step writes its reason and opens no popup`() {
        editorWith("class Alpha\n")
        state.prepareGenerate = { _, _ -> RefactoringStep.Refused("No class at the caret") }

        assertTrue(startGenerate(state, GenerateKind.OVERRIDE_METHODS))

        assertEquals("No class at the caret", state.statusMessage)
        assertNull(state.generate)
        assertNull(state.memberChoose)
    }

    @Test
    fun `a read-only editor reports it without asking the provider`() {
        val root = temp.resolve("read-only-project")
        Files.createDirectories(root)
        state.openFolder(root)
        val editor = requireNotNull(state.session).editors.openFile(VFile(root.resolve("missing.kt")))
        assertTrue((editor as TextFileEditor).isReadOnly)
        var prepared = false
        state.prepareGenerate = { _, _ ->
            prepared = true
            RefactoringStep.Refused("no")
        }

        assertTrue(openGenerate(state))
        assertEquals("File is read-only", state.statusMessage)
        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))
        assertEquals("File is read-only", state.statusMessage)
        assertFalse(prepared)
    }

    @Test
    fun `without a selected text editor the actions do nothing`() {
        assertFalse(openGenerate(state))
        assertFalse(startGenerate(state, GenerateKind.TO_STRING))
        assertFalse(chooseGenerate(state, GeneratePrepared(GenerateKind.TO_STRING, RefactoringStep.Refused("no"))))
        assertFalse(submitMemberChoose(state, emptyList()))
    }

    @Test
    fun `applying a generation is one undo step`() {
        val text = "class Alpha\n"
        val editor = editorWith(text)
        editor.updateValue(TextFieldValue(text, TextRange(0, 5)))
        state.prepareGenerate = { _, _ -> step("    fun alpha() {}\n", caret = 4, message = "Generated alpha") }

        assertTrue(startGenerate(state, GenerateKind.TO_STRING))
        assertEquals("    fun alpha() {}\nclass Alpha\n", editor.value.text)
        assertEquals("Generated alpha", state.statusMessage)

        assertTrue(editor.undo())
        assertEquals(text, editor.value.text)
        assertEquals(TextRange(0, 5), editor.value.selection)
    }

    @Test
    fun `a choose members step opens the member chooser and applies the checked rows`() {
        val text = "class Alpha : Shape\n"
        val editor = editorWith(text)
        editor.updateValue(TextFieldValue(text, TextRange(0, 5)))
        state.prepareGenerate = { _, _ ->
            membersStep { checked ->
                assertEquals(listOf("area(): Int", "name(): String"), checked.map { it.text })
                NameCheck.Valid(
                    RefactoringEdit(
                        listOf(TextEdit(text.length, text.length, "    override fun area(): Int = 0\n")),
                        caret = text.length,
                        message = "Implemented 2 members",
                    ),
                )
            }
        }

        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))
        val request = requireNotNull(state.memberChoose)
        assertEquals("Implement Methods", request.title)
        assertEquals(text, request.preparedText)
        assertEquals(TextRange(0, 5), request.preparedSelection)
        assertEquals(KeyContext.CONTEXT_POPUP, state.focus)

        assertTrue(submitMemberChoose(state, request.step.members.filter { it.text != "hashCode(): Int" }))

        assertNull(state.memberChoose)
        assertEquals("Implemented 2 members", state.statusMessage)
        assertTrue(editor.value.text.startsWith(text))
    }

    @Test
    fun `an invalid member choice keeps the chooser open with its message`() {
        editorWith("class Alpha : Shape\n")
        state.prepareGenerate = { _, _ ->
            membersStep { NameCheck.Invalid("Choose a member first") }
        }
        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))

        assertTrue(submitMemberChoose(state, emptyList()))

        val request = requireNotNull(state.memberChoose)
        assertEquals("Choose a member first", request.errorMessage)
    }

    @Test
    fun `the file changed after preparing refuses the edit`() {
        val text = "class Alpha : Shape\n"
        val editor = editorWith(text)
        state.prepareGenerate = { _, _ -> membersStep { NameCheck.Valid(RefactoringEdit(emptyList(), caret = 0)) } }
        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))

        editor.updateValue(TextFieldValue("changed\n", TextRange(0)))
        assertTrue(submitMemberChoose(state, listOf(MemberOption("area(): Int"))))

        assertEquals("changed\n", editor.value.text)
        assertEquals("The file changed; run Implement Methods again", state.statusMessage)
    }

    @Test
    fun `closing the member chooser restores the prepared selection`() {
        val text = "class Alpha : Shape\n"
        val editor = editorWith(text)
        editor.updateValue(TextFieldValue(text, TextRange(0, 5)))
        state.prepareGenerate = { _, _ -> membersStep { NameCheck.Invalid("no") } }
        assertTrue(startGenerate(state, GenerateKind.IMPLEMENT_METHODS))

        editor.updateValue(editor.value.copy(selection = TextRange(5)))
        closeMemberChoose(state)

        assertNull(state.memberChoose)
        assertEquals(TextRange(0, 5), editor.value.selection)
    }

    @Test
    fun `starting a generation drops the secondary carets`() {
        val editor = editorWith("class Alpha\n")
        editor.updateValue(TextFieldValue("class Alpha\n", TextRange(0)))
        editor.toggleCaretAt(6)
        assertTrue(editor.secondaryCarets.isNotEmpty())
        state.prepareGenerate = { _, _ -> RefactoringStep.Refused("nothing here") }

        assertTrue(startGenerate(state, GenerateKind.TO_STRING))

        assertTrue(editor.secondaryCarets.isEmpty())
    }

    @Test
    fun `the provider path refuses files without a generate provider`() {
        val java = prepareGenerateWithProviders(
            TextFileEditor(VFile(temp.resolve("Main.java"))),
            GenerateKind.IMPLEMENT_METHODS,
            types = null,
        )
        assertEquals(
            "Implement Methods is not available for .java files",
            (java as RefactoringStep.Refused).reason,
        )
        val text = prepareGenerateWithProviders(
            TextFileEditor(VFile(temp.resolve("notes.txt"))),
            GenerateKind.TO_STRING,
            types = null,
        )
        assertEquals("toString() is not available for .txt files", (text as RefactoringStep.Refused).reason)
    }

    private fun step(insert: String, caret: Int = 0, message: String? = null): RefactoringStep.Apply =
        RefactoringStep.Apply(RefactoringEdit(listOf(TextEdit(0, 0, insert)), caret = caret, message = message))

    private fun membersStep(complete: (List<MemberOption>) -> NameCheck): RefactoringStep.ChooseMembers =
        RefactoringStep.ChooseMembers(
            "Implement Methods",
            listOf(
                MemberOption("area(): Int", group = "Shape", checked = true),
                MemberOption("hashCode(): Int", group = "Any", disabledReason = "refers to another name here"),
                MemberOption("name(): String", group = "Shape", checked = true),
            ),
            complete,
        )

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
            Application.init(GenerateActionsTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
