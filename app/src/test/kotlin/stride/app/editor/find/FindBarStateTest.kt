package stride.app.editor.find

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.editor.TextFileEditor
import stride.app.ui.theme.DesignTokens
import stride.core.vfs.VFile
import java.nio.file.Files
import java.nio.file.Path

class FindBarStateTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `open paints every match and marks the current one pink`() {
        val editor = editor("fun a()\nfun b()\n")
        val state = FindBarState()

        state.open(editor, replace = false)
        state.query = "fun"
        state.refresh(editor)

        assertTrue(state.visible)
        assertEquals(2, state.matches.size)
        assertEquals(2, editor.searchSpans.size)
        assertEquals(DesignTokens.neonPinkGlow, editor.searchSpans[0].color)
        assertEquals(DesignTokens.neonAmberGlow, editor.searchSpans[1].color)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `next selects the current match and wraps around`() {
        val editor = editor("fun a()\nfun b()\n")
        val state = FindBarState()
        state.open(editor, replace = false)
        state.query = "fun"
        state.refresh(editor)

        assertTrue(state.next(editor))
        assertEquals(TextRange(0, 3), editor.value.selection)
        assertEquals(DesignTokens.neonPinkGlow, editor.searchSpans[0].color)

        assertTrue(state.next(editor))
        assertEquals(TextRange(8, 11), editor.value.selection)
        assertEquals(DesignTokens.neonPinkGlow, editor.searchSpans[1].color)
        assertEquals(1, state.currentIndex)
        assertEquals(DesignTokens.neonAmberGlow, editor.searchSpans[0].color)

        assertTrue(state.next(editor))
        assertEquals(TextRange(0, 3), editor.value.selection)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `current index follows the selected match in both directions`() {
        val editor = editor("fun a()\nfun b()\nfun c()\n")
        val state = FindBarState()
        state.open(editor, replace = false)
        state.query = "fun"
        state.refresh(editor)

        assertTrue(state.next(editor))
        assertEquals(TextRange(0, 3), editor.value.selection)
        assertEquals(0, state.currentIndex)

        assertTrue(state.previous(editor))
        assertEquals(TextRange(16, 19), editor.value.selection)
        assertEquals(2, state.currentIndex)
        assertEquals(DesignTokens.neonPinkGlow, editor.searchSpans[2].color)
        assertEquals(DesignTokens.neonAmberGlow, editor.searchSpans[0].color)

        assertTrue(state.previous(editor))
        assertEquals(TextRange(8, 11), editor.value.selection)
        assertEquals(1, state.currentIndex)
    }

    @Test
    fun `replace all rewrites the text and clears the matches`() {
        val editor = editor("fun a()\nfun b()\n")
        val state = FindBarState()
        state.open(editor, replace = true)
        state.query = "fun"
        state.replacement = "fn"
        state.refresh(editor)

        state.replaceAll(editor)

        assertEquals("fn a()\nfn b()\n", editor.value.text)
        assertTrue(state.matches.isEmpty())
        assertTrue(editor.searchSpans.isEmpty())
    }

    @Test
    fun `close hides the bar and clears the painted spans`() {
        val editor = editor("fun a()\n")
        val state = FindBarState()
        state.open(editor, replace = false)
        state.query = "fun"
        state.refresh(editor)
        assertFalse(editor.searchSpans.isEmpty())

        state.close()

        assertFalse(state.visible)
        assertFalse(state.replaceVisible)
        assertTrue(editor.searchSpans.isEmpty())
    }

    @Test
    fun `next from the caret at the end selects the first match`() {
        val editor = editor("fun a()\nfun b()\n")
        val state = FindBarState()
        state.open(editor, replace = false)
        state.query = "fun"
        state.refresh(editor)

        assertEquals(TextRange(16), editor.value.selection)

        assertTrue(state.next(editor))
        assertEquals(TextRange(0, 3), editor.value.selection)
        assertEquals(0, state.currentIndex)
        assertEquals(DesignTokens.neonPinkGlow, editor.searchSpans[0].color)
    }

    @Test
    fun `next with a single match keeps selecting it`() {
        val editor = editor("fun a()\n")
        val state = FindBarState()
        state.open(editor, replace = false)
        state.query = "fun"

        assertTrue(state.next(editor))
        assertEquals(TextRange(0, 3), editor.value.selection)
        assertEquals(0, state.currentIndex)

        assertTrue(state.next(editor))
        assertEquals(TextRange(0, 3), editor.value.selection)
        assertEquals(0, state.currentIndex)
    }

    @Test
    fun `next without a query does nothing`() {
        val editor = editor("fun a()\n")
        val state = FindBarState()
        state.open(editor, replace = false)
        state.refresh(editor)

        assertTrue(state.matches.isEmpty())
        assertFalse(state.next(editor))
        assertFalse(state.previous(editor))
        assertEquals(TextRange(8), editor.value.selection)
        assertNull(state.currentIndex)
    }

    private fun editor(text: String): TextFileEditor {
        val path = temp.resolve("Find.kt")
        Files.writeString(path, text)
        return TextFileEditor(VFile(path))
    }
}
