package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.keys.ActionId
import stride.core.vfs.VFile
import stride.lang.TextEdit

class TextFileEditorFoldingTest {
    @TempDir
    lateinit var temp: Path

    private val sample = """
        package demo

        import alpha.One
        import beta.Two

        class Greeter {
            fun greet(): String {
                val word = "hi"
                return word
            }
        }
    """.trimIndent()

    private val funOpen: Int get() = sample.indexOf("fun greet(): String {") + "fun greet(): String ".length
    private val functionFold: FoldRegion
        get() = FoldRegion(funOpen + 1, sample.indexOf("\n    }") + 5)

    private val classOpen: Int get() = sample.indexOf("class Greeter {") + "class Greeter ".length
    private val classFold: FoldRegion get() = FoldRegion(classOpen + 1, sample.lastIndexOf('}'))

    private val importFold: FoldRegion
        get() = FoldRegion(
            sample.indexOf("import ") + "import ".length,
            sample.indexOf('\n', sample.indexOf("import beta.Two")),
        )

    private fun editor(text: String = sample, name: String = "Main.kt", readOnly: Boolean = false): TextFileEditor {
        val path = temp.resolve(name)
        Files.writeString(path, text)
        return TextFileEditor(VFile(path), readOnly = readOnly)
    }

    /** A primary caret at [primary] with empty secondary carets at [secondaries], in that order. */
    private fun TextFileEditor.placeCarets(primary: Int, vararg secondaries: Int) {
        updateValue(TextFieldValue(value.text, TextRange(secondaries.first())))
        for (offset in secondaries.drop(1)) toggleCaretAt(offset)
        toggleCaretAt(primary)
    }

    @Test
    fun `collapse on a function line folds its body and a second collapse folds the class`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))

        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        assertEquals(listOf(functionFold), editor.foldedRegions)

        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        assertEquals(listOf(classFold, functionFold), editor.foldedRegions)
        assertEquals(TextRange(classFold.hiddenStart), editor.value.selection)
    }

    @Test
    fun `expand opens the collapsed region at the caret and expand all clears every fold`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        editor.applyAction(ActionId.COLLAPSE_REGION)
        assertEquals(listOf(classFold, functionFold), editor.foldedRegions)

        assertTrue(editor.applyAction(ActionId.EXPAND_REGION))
        assertEquals(listOf(functionFold), editor.foldedRegions)

        assertTrue(editor.applyAction(ActionId.EXPAND_ALL_REGIONS))
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `expand on the class line after collapse all leaves the function body collapsed`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(classOpen)))

        assertTrue(editor.applyAction(ActionId.COLLAPSE_ALL_REGIONS))
        assertEquals(listOf(importFold, classFold, functionFold), editor.foldedRegions)

        assertTrue(editor.applyAction(ActionId.EXPAND_REGION))
        assertEquals(listOf(importFold, functionFold), editor.foldedRegions)
    }

    @Test
    fun `a caret inside a folded range moves to its start and secondary carets survive`() {
        val editor = editor()
        val inside = sample.indexOf("return word")
        val outside = sample.indexOf("fun greet")
        editor.placeCarets(inside, outside)

        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        assertEquals(listOf(functionFold), editor.foldedRegions)
        assertEquals(listOf(TextRange(outside), TextRange(functionFold.hiddenStart)), editor.carets)

        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        assertEquals(listOf(classFold, functionFold), editor.foldedRegions)
        assertEquals(listOf(TextRange(classFold.hiddenStart)), editor.carets)
    }

    @Test
    fun `go to line and a program selection inside a fold expand it`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        assertEquals(listOf(functionFold), editor.foldedRegions)

        assertTrue(editor.gotoLine("9:1"))
        assertTrue(editor.foldedRegions.isEmpty())

        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        assertEquals(listOf(functionFold), editor.foldedRegions)

        editor.updateValue(TextFieldValue(sample, TextRange(sample.indexOf("val word") + 2)))
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `typing before the fold shifts it and deleting hidden text expands it`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        editor.updateValue(TextFieldValue(sample, TextRange(0)))

        val shifted = "x$sample"
        editor.onFieldValueChange(TextFieldValue(shifted, TextRange(1)))
        assertEquals(
            listOf(FoldRegion(functionFold.hiddenStart + 1, functionFold.hiddenEnd + 1)),
            editor.foldedRegions,
        )

        editor.updateValue(TextFieldValue(shifted, TextRange(0)))
        val hiddenStart = functionFold.hiddenStart + 1
        editor.onFieldValueChange(TextFieldValue(shifted.removeRange(hiddenStart, hiddenStart + 1), TextRange(0)))
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `typing over a fold removes it and undo restores the text without folds`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        editor.updateValue(TextFieldValue(sample, TextRange(0, functionFold.hiddenEnd)))
        assertEquals(listOf(functionFold), editor.foldedRegions)

        val typed = "q" + sample.substring(functionFold.hiddenEnd)
        editor.onFieldValueChange(TextFieldValue(typed, TextRange(1)))
        assertEquals(typed, editor.value.text)
        assertTrue(editor.foldedRegions.isEmpty())

        assertTrue(editor.undo())
        assertEquals(sample, editor.value.text)
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `a horizontal field movement into a fold snaps out of it`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        val start = functionFold.hiddenStart
        val end = functionFold.hiddenEnd

        editor.updateValue(TextFieldValue(sample, TextRange(start)))
        editor.onFieldValueChange(TextFieldValue(sample, TextRange(start + 1)))
        assertEquals(TextRange(end), editor.value.selection)
        assertEquals(listOf(functionFold), editor.foldedRegions)

        editor.updateValue(TextFieldValue(sample, TextRange(end)))
        editor.onFieldValueChange(TextFieldValue(sample, TextRange(end - 1)))
        assertEquals(TextRange(start), editor.value.selection)
        assertEquals(listOf(functionFold), editor.foldedRegions)

        editor.updateValue(TextFieldValue(sample, TextRange(end)))
        editor.onFieldValueChange(TextFieldValue(sample, TextRange(end, end - 1)))
        assertEquals(TextRange(end, start), editor.value.selection)
        assertEquals(listOf(functionFold), editor.foldedRegions)
    }

    @Test
    fun `moving right with secondary carets snaps every caret out of its fold`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(sample.indexOf("import alpha"))))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        assertEquals(listOf(importFold, functionFold), editor.foldedRegions)

        editor.placeCarets(functionFold.hiddenStart, importFold.hiddenStart)
        assertEquals(
            listOf(TextRange(importFold.hiddenStart), TextRange(functionFold.hiddenStart)),
            editor.carets,
        )

        assertTrue(editor.moveCarets(CaretMove.RIGHT, extend = false))
        assertEquals(
            listOf(TextRange(importFold.hiddenEnd), TextRange(functionFold.hiddenEnd)),
            editor.carets,
        )
        assertEquals(listOf(importFold, functionFold), editor.foldedRegions)
    }

    @Test
    fun `a vertical move into a fold expands it and keeps the other caret`() {
        val text = "fun a() {\n    one\n    two }\nafter\n"
        val editor = editor(text)
        editor.updateValue(TextFieldValue(text, TextRange(text.indexOf('{') + 1)))
        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        val fold = editor.foldedRegions.single()

        editor.placeCarets(text.indexOf("after"), 0)
        assertTrue(editor.moveCarets(CaretMove.UP, extend = false))
        assertTrue(editor.foldedRegions.isEmpty())
        assertEquals(
            listOf(TextRange(0), TextRange(text.indexOf("    two"))),
            editor.carets,
        )
        assertTrue(fold.hiddenStart < text.indexOf("    two") && text.indexOf("    two") < fold.hiddenEnd)
    }

    @Test
    fun `replaceRange before a fold shifts it and inside expands it`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        val fold = editor.foldedRegions.single()

        val inserted = "// note\n"
        editor.replaceRange(0, 0, inserted)
        assertEquals(
            listOf(FoldRegion(fold.hiddenStart + inserted.length, fold.hiddenEnd + inserted.length)),
            editor.foldedRegions,
        )

        val shifted = editor.foldedRegions.single()
        editor.replaceRange(shifted.hiddenStart + 1, shifted.hiddenStart + 2, "")
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `applyEdits before a fold shifts it and inside expands it`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        val fold = editor.foldedRegions.single()

        assertTrue(editor.applyEdits(listOf(TextEdit(0, 0, "x"))))
        assertEquals(listOf(FoldRegion(fold.hiddenStart + 1, fold.hiddenEnd + 1)), editor.foldedRegions)

        val shifted = editor.foldedRegions.single()
        assertTrue(editor.applyEdits(listOf(TextEdit(shifted.hiddenStart, shifted.hiddenStart + 2, ""))))
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `renameWord before a fold shifts it and inside expands it`() {
        val editor = editor()
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))
        editor.applyAction(ActionId.COLLAPSE_REGION)
        val fold = editor.foldedRegions.single()

        assertEquals(1, editor.renameWord("Greeter", "Thing"))
        assertEquals(listOf(FoldRegion(fold.hiddenStart - 2, fold.hiddenEnd - 2)), editor.foldedRegions)

        assertTrue(editor.renameWord("word", "text") > 0)
        assertTrue(editor.foldedRegions.isEmpty())
    }

    @Test
    fun `collapsing without moving a caret keeps the composition`() {
        val editor = editor()
        val composition = TextRange(funOpen, funOpen + 3)
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen), composition))
        assertEquals(composition, editor.value.composition)

        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        assertEquals(listOf(functionFold), editor.foldedRegions)
        assertEquals(composition, editor.value.composition)
    }

    @Test
    fun `a read-only editor still folds`() {
        val editor = editor(readOnly = true)
        editor.updateValue(TextFieldValue(sample, TextRange(funOpen)))

        assertTrue(editor.applyAction(ActionId.COLLAPSE_REGION))
        assertEquals(listOf(functionFold), editor.foldedRegions)
    }

    @Test
    fun `files without candidates consume the actions and change nothing`() {
        val text = "fun f() {\n    val a = 1\n}\n"
        val textFile = editor(text, name = "notes.txt")
        textFile.updateValue(TextFieldValue(text, TextRange(0)))
        for (action in FOLDING_ACTIONS) {
            assertTrue(textFile.applyAction(action), "txt $action")
        }
        assertTrue(textFile.foldedRegions.isEmpty())

        val plain = editor("val a = 1\n")
        for (action in FOLDING_ACTIONS) {
            assertTrue(plain.applyAction(action), "kt $action")
        }
        assertTrue(plain.foldedRegions.isEmpty())
    }

    private companion object {
        val FOLDING_ACTIONS = listOf(
            ActionId.EXPAND_REGION,
            ActionId.COLLAPSE_REGION,
            ActionId.EXPAND_ALL_REGIONS,
            ActionId.COLLAPSE_ALL_REGIONS,
        )
    }
}
