package stride.app.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MultiCaretTest {

    private fun carets(vararg pairs: Pair<Int, Int>): List<TextRange> = pairs.map { TextRange(it.first, it.second) }

    private fun caret(offset: Int): TextRange = TextRange(offset)

    // normalize

    @Test
    fun `overlapping ranges merge into their union`() {
        assertEquals(listOf(TextRange(0, 8)), MultiCaret.normalize(carets(0 to 5, 3 to 8)))
    }

    @Test
    fun `two empty carets at the same place merge`() {
        assertEquals(listOf(TextRange(2)), MultiCaret.normalize(listOf(caret(2), caret(2))))
    }

    @Test
    fun `carets stay in addition order`() {
        val result = MultiCaret.normalize(listOf(caret(0), caret(4), TextRange(2, 3)))
        assertEquals(listOf(caret(0), caret(4), TextRange(2, 3)), result)
    }

    @Test
    fun `the primary caret survives a merge and keeps its direction`() {
        val result = MultiCaret.normalize(listOf(TextRange(0, 3), TextRange(4, 1)))
        assertEquals(listOf(TextRange(4, 0)), result)
    }

    @Test
    fun `an empty caret strictly inside a selection merges but a boundary does not`() {
        assertEquals(listOf(TextRange(0, 5)), MultiCaret.normalize(listOf(TextRange(0, 5), caret(2))))
        assertEquals(2, MultiCaret.normalize(listOf(TextRange(0, 5), caret(0))).size)
    }

    // replicate

    private fun replicate(
        oldText: String,
        primary: TextRange,
        composition: TextRange?,
        new: TextFieldValue,
        secondaries: List<TextRange>,
    ): CaretEdit = requireNotNull(MultiCaret.replicate(oldText, primary, composition, new, secondaries))

    @Test
    fun `typing at every caret repeats the insertion`() {
        val edit = replicate("ab\ncxd", TextRange(4), null, TextFieldValue("ab\ncyxd", TextRange(5)), listOf(caret(1)))
        assertEquals("ayb\ncyxd", edit.text)
        assertEquals(listOf(caret(2), caret(6)), edit.carets)
        assertTrue(edit.typing)
    }

    @Test
    fun `typing between repeated characters keeps the change at the caret`() {
        val edit = replicate("aa", TextRange(1), null, TextFieldValue("aaa", TextRange(2)), listOf(caret(0)))
        assertEquals("aaaa", edit.text)
        assertEquals(listOf(caret(1), caret(3)), edit.carets)
    }

    @Test
    fun `typing at every caret reports one change per caret, the primary's piece last`() {
        val edit = replicate("ab\ncxd", TextRange(4), null, TextFieldValue("ab\ncyxd", TextRange(5)), listOf(caret(1)))
        assertEquals(listOf(TextChange(1, 0, "y"), TextChange(5, 0, "y")), edit.changes)
        assertEquals(1, edit.primaryChange)
    }

    @Test
    fun `typing where only the primary caret follows the same character`() {
        val edit = replicate(
            "foo(e) bar(x)",
            TextRange(10),
            null,
            TextFieldValue("foo(e) bar((x)", TextRange(11)),
            listOf(caret(4)),
        )
        assertEquals("foo((e) bar((x)", edit.text)
        assertEquals(listOf(caret(5), caret(12)), edit.carets)
        assertTrue(edit.typing)
    }

    @Test
    fun `typing the same character after both carets keeps both copies`() {
        val edit = replicate("a", TextRange(0), null, TextFieldValue("aa", TextRange(1)), listOf(caret(1)))
        assertEquals("aaa", edit.text)
        assertEquals(listOf(caret(3), caret(1)), edit.carets)
        assertTrue(edit.typing)
    }

    @Test
    fun `typing over a repeated character replaces every selection fully`() {
        val edit = replicate("abb", TextRange(0, 2), null, TextFieldValue("ab", TextRange(1)), listOf(TextRange(2, 3)))
        assertEquals("aa", edit.text)
        assertEquals(listOf(caret(2), caret(1)), edit.carets)
    }

    @Test
    fun `a replicated deletion left of the document start is clipped`() {
        val edit = replicate(
            "abc def ghi",
            TextRange(8),
            null,
            TextFieldValue("abc ghi", TextRange(4)),
            listOf(caret(1)),
        )
        assertEquals("bc ghi", edit.text)
        assertEquals(listOf(caret(0), caret(3)), edit.carets)
    }

    @Test
    fun `backspace deletes before every caret`() {
        val edit = replicate("abc\ndef", TextRange(5), null, TextFieldValue("abc\nef", TextRange(4)), listOf(caret(2)))
        assertEquals("ac\nef", edit.text)
        assertEquals(listOf(caret(1), caret(3)), edit.carets)
        assertFalse(edit.typing)
    }

    @Test
    fun `delete forward removes after every caret`() {
        val edit = replicate("abcabc", TextRange(1), null, TextFieldValue("acabc", TextRange(1)), listOf(caret(4)))
        assertEquals("acac", edit.text)
        assertEquals(listOf(caret(3), caret(1)), edit.carets)
    }

    @Test
    fun `typing over selections replaces each one`() {
        val edit = replicate("abc abc", TextRange(4, 7), null, TextFieldValue("abc x", TextRange(5)), listOf(TextRange(0, 3)))
        assertEquals("x x", edit.text)
        assertEquals(listOf(caret(1), caret(3)), edit.carets)
        assertFalse(edit.typing)
    }

    @Test
    fun `an IME composition is replicated and the range shifts past earlier carets`() {
        val edit = replicate("ab", TextRange(1), null, TextFieldValue("a한b", TextRange(2), TextRange(1, 2)), listOf(caret(0)))
        assertEquals("한a한b", edit.text)
        assertEquals(listOf(caret(1), caret(3)), edit.carets)
        assertEquals(TextRange(2, 3), edit.composition)
        assertFalse(edit.typing)
    }

    @Test
    fun `a composition update replaces the composed text at every caret`() {
        val edit = replicate(
            "a한b",
            TextRange(2),
            TextRange(1, 2),
            TextFieldValue("a하b", TextRange(2), TextRange(1, 2)),
            listOf(caret(3)),
        )
        assertEquals("a하하", edit.text)
        assertEquals(listOf(caret(3), caret(2)), edit.carets)
        assertEquals(TextRange(1, 2), edit.composition)
    }

    @Test
    fun `adjacent deletions leave one caret`() {
        val edit = replicate("abc", TextRange(1), null, TextFieldValue("bc", TextRange(0)), listOf(caret(2)))
        assertEquals("c", edit.text)
        assertEquals(listOf(caret(0)), edit.carets)
    }

    @Test
    fun `a space is not a typing change`() {
        val edit = replicate("ab", TextRange(1), null, TextFieldValue("a b", TextRange(2)), listOf(caret(0)))
        assertFalse(edit.typing)
    }

    // move

    private fun move(
        text: String,
        carets: List<TextRange>,
        direction: CaretMove,
        extend: Boolean = false,
        columns: List<Int>? = null,
    ) = MultiCaret.move(text, carets, direction, extend, columns)

    @Test
    fun `left and right collapse a selection and shift an empty caret`() {
        assertEquals(listOf(caret(0), caret(4)), move("abcdef", listOf(TextRange(0, 2), caret(5)), CaretMove.LEFT).first)
        assertEquals(listOf(caret(2), caret(6)), move("abcdef", listOf(TextRange(0, 2), caret(5)), CaretMove.RIGHT).first)
    }

    @Test
    fun `extend keeps the anchor`() {
        assertEquals(listOf(TextRange(1, 0)), move("abc", listOf(caret(1)), CaretMove.LEFT, extend = true).first)
        assertEquals(listOf(TextRange(1, 2)), move("abc", listOf(caret(1)), CaretMove.RIGHT, extend = true).first)
    }

    @Test
    fun `option word moves over one word`() {
        val result = move("foo bar", listOf(caret(0)), CaretMove.WORD_RIGHT).first
        assertEquals(listOf(caret(3)), result)
        assertEquals(listOf(caret(0)), move("foo bar", listOf(caret(4)), CaretMove.WORD_LEFT).first)
    }

    @Test
    fun `command line start and end stop before a CRLF`() {
        assertEquals(listOf(caret(4)), move("ab\r\ncd\r\nef", listOf(caret(6)), CaretMove.LINE_START).first)
        assertEquals(listOf(caret(6)), move("ab\r\ncd\r\nef", listOf(caret(5)), CaretMove.LINE_END).first)
    }

    @Test
    fun `vertical moves keep the desired column across short lines`() {
        val text = "abc\nx\nabcdef"
        val first = move(text, listOf(caret(2)), CaretMove.DOWN)
        assertEquals(listOf(caret(5)), first.first)
        assertEquals(listOf(2), first.second)
        val second = move(text, first.first, CaretMove.DOWN, columns = first.second)
        assertEquals(listOf(caret(8)), second.first)
        assertEquals(listOf(2), second.second)
    }

    @Test
    fun `vertical moves stop at the first and last line`() {
        assertEquals(listOf(caret(0)), move("ab\ncd", listOf(caret(0)), CaretMove.UP).first)
        assertEquals(listOf(caret(5)), move("ab\ncd", listOf(caret(5)), CaretMove.DOWN).first)
    }

    // delete

    @Test
    fun `delete removes selections and one code point at empty carets`() {
        val edit = MultiCaret.delete("abc def", listOf(TextRange(0, 3), caret(7)), forward = false, word = false)
        assertEquals(" de", edit.text)
        assertEquals(listOf(caret(0), caret(3)), edit.carets)
    }

    @Test
    fun `delete reports one change per removed range, the primary's piece last`() {
        val edit = MultiCaret.delete("abc def", listOf(TextRange(0, 3), caret(7)), forward = false, word = false)
        assertEquals(listOf(TextChange(0, 3, ""), TextChange(3, 1, "")), edit.changes)
        assertEquals(1, edit.primaryChange)
    }

    @Test
    fun `option delete removes a word`() {
        val edit = MultiCaret.delete("foo bar baz", listOf(caret(3), caret(11)), forward = false, word = true)
        assertEquals(" bar ", edit.text)
    }

    @Test
    fun `delete does not split a surrogate pair`() {
        val text = "a😀b"
        val edit = MultiCaret.delete(text, listOf(caret(3)), forward = false, word = false)
        assertEquals("ab", edit.text)
        assertEquals(listOf(caret(1)), edit.carets)
    }

    // paste

    @Test
    fun `as many lines as carets are distributed in document order`() {
        val edit = MultiCaret.paste("a\nb", listOf(caret(0), caret(2)), "x\ny")
        assertEquals("xa\nyb", edit.text)
        assertEquals(listOf(caret(1), caret(4)), edit.carets)
    }

    @Test
    fun `a line count mismatch pastes the whole clip at every caret`() {
        val edit = MultiCaret.paste("a\nb", listOf(caret(0), caret(2)), "x\ny\nz")
        assertEquals("x\ny\nza\nx\ny\nzb", edit.text)
    }

    @Test
    fun `a CRLF clip is split into lines`() {
        val edit = MultiCaret.paste("a\nb", listOf(caret(0), caret(2)), "x\r\ny\r\n")
        assertEquals("xa\nyb", edit.text)
    }

    // copy

    @Test
    fun `copy joins the selections when any caret has one`() {
        val carets = listOf(TextRange(0, 3), TextRange(4, 7), caret(9))
        val (text, ranges) = MultiCaret.copyText("abc abc x", carets)
        assertEquals("abc\nabc", text)
        assertEquals(listOf(0..2, 4..6), ranges)
    }

    @Test
    fun `copy takes the whole line of every caret, once per line`() {
        val carets = listOf(caret(0), caret(2), caret(4))
        val (text, ranges) = MultiCaret.copyText("one\ntwo", carets)
        assertEquals("one\ntwo", text)
        assertEquals(listOf(0..3, 4..6), ranges)
    }

    // occurrences

    @Test
    fun `the first select next occurrence selects the word and turns on whole words`() {
        val result = MultiCaret.selectNextOccurrence("foo bar foo", listOf(caret(1)), false)
        assertTrue(result.found)
        assertTrue(result.wholeWords)
        assertEquals(listOf(TextRange(0, 3)), result.carets)
    }

    @Test
    fun `select next occurrence adds the following match and skips existing carets`() {
        val first = MultiCaret.selectNextOccurrence("foo bar foo", listOf(TextRange(0, 3)), true)
        assertEquals(listOf(TextRange(0, 3), TextRange(8, 11)), first.carets)
        val exhausted = MultiCaret.selectNextOccurrence("foo bar foo", first.carets, true)
        assertFalse(exhausted.found)
        assertEquals(first.carets, exhausted.carets)
    }

    @Test
    fun `select next occurrence wraps to the start`() {
        val result = MultiCaret.selectNextOccurrence("foo x foo", listOf(TextRange(6, 9)), true)
        assertTrue(result.found)
        assertEquals(listOf(TextRange(6, 9), TextRange(0, 3)), result.carets)
    }

    @Test
    fun `whole words reject a match inside a longer word`() {
        val whole = MultiCaret.selectNextOccurrence("foo foobar", listOf(TextRange(0, 3)), true)
        assertFalse(whole.found)
        val partial = MultiCaret.selectNextOccurrence("foo foobar", listOf(TextRange(0, 3)), false)
        assertTrue(partial.found)
        assertEquals(TextRange(4, 7), partial.carets.last())
    }

    @Test
    fun `select next occurrence is case sensitive`() {
        val result = MultiCaret.selectNextOccurrence("Foo foo", listOf(TextRange(0, 3)), false)
        assertFalse(result.found)
    }

    @Test
    fun `select all occurrences keeps the match under the primary caret as primary`() {
        val result = MultiCaret.selectAllOccurrences("foo bar foo foo", listOf(caret(1)), false)
        assertTrue(result.found)
        assertTrue(result.wholeWords)
        assertEquals(listOf(TextRange(8, 11), TextRange(12, 15), TextRange(0, 3)), result.carets)
    }

    @Test
    fun `select all occurrences honours the whole word flag`() {
        val partial = MultiCaret.selectAllOccurrences("foo foobar", listOf(TextRange(0, 3)), false)
        assertEquals(listOf(TextRange(4, 7), TextRange(0, 3)), partial.carets)
        val whole = MultiCaret.selectAllOccurrences("foo foobar", listOf(TextRange(0, 3)), true)
        assertEquals(listOf(TextRange(0, 3)), whole.carets)
    }

    @Test
    fun `select all occurrences keeps a caret at the end of its word as primary`() {
        val result = MultiCaret.selectAllOccurrences("foo bar foo", listOf(caret(11)), false)
        assertTrue(result.found)
        assertEquals(listOf(TextRange(0, 3), TextRange(8, 11)), result.carets)
    }

    @Test
    fun `unselect drops the primary caret then collapses a lone selection`() {
        assertEquals(listOf(TextRange(0, 3)), MultiCaret.unselectOccurrence(listOf(TextRange(0, 3), TextRange(8, 11))))
        assertEquals(listOf(caret(3)), MultiCaret.unselectOccurrence(listOf(TextRange(0, 3))))
    }

    // clone

    @Test
    fun `clone adds an empty caret above or below`() {
        assertEquals(listOf(caret(1), caret(4)), MultiCaret.cloneCaret("ab\ncd", listOf(caret(1)), above = false, column = null))
        assertEquals(listOf(caret(4), caret(1)), MultiCaret.cloneCaret("ab\ncd", listOf(caret(4)), above = true, column = null))
    }

    @Test
    fun `clone clamps to a short line and stops at the document edges`() {
        assertEquals(listOf(caret(3), caret(7)), MultiCaret.cloneCaret("abcd\nab", listOf(caret(3)), above = false, column = 3))
        assertEquals(listOf(caret(0)), MultiCaret.cloneCaret("ab\ncd", listOf(caret(0)), above = true, column = null))
        assertEquals(listOf(caret(5)), MultiCaret.cloneCaret("ab\ncd", listOf(caret(5)), above = false, column = null))
    }

    @Test
    fun `clone continues with the first column`() {
        val first = MultiCaret.cloneCaret("ab\ncd\nef", listOf(caret(1)), above = false, column = null)
        assertEquals(listOf(caret(1), caret(4)), first)
        assertEquals(listOf(caret(1), caret(4), caret(7)), MultiCaret.cloneCaret("ab\ncd\nef", first, above = false, column = 1))
    }

    // block

    @Test
    fun `a block clips short lines and ends on the lead line`() {
        val result = MultiCaret.block("abcd\nab\nabcdef", anchorLine = 0, anchorCol = 1, leadLine = 2, leadCol = 3)
        assertEquals(listOf(TextRange(1, 3), TextRange(6, 7), TextRange(9, 11)), result)
        assertEquals(TextRange(9, 11), result.last())
    }

    @Test
    fun `a block reverses when the lead column is left of the anchor`() {
        val result = MultiCaret.block("abcd\nab\nabcdef", anchorLine = 2, anchorCol = 3, leadLine = 0, leadCol = 1)
        assertEquals(listOf(TextRange(11, 9), TextRange(7, 6), TextRange(3, 1)), result)
        assertEquals(TextRange(3, 1), result.last())
    }

    // toggle caret

    @Test
    fun `toggling adds a caret then removes it`() {
        val added = MultiCaret.toggleCaret("abcdef", listOf(caret(1)), 4)
        assertEquals(listOf(caret(1), caret(4)), added)
        assertEquals(listOf(caret(1)), MultiCaret.toggleCaret("abcdef", added, 4))
    }

    @Test
    fun `toggling a caret inside a selection removes that caret`() {
        val carets = listOf(TextRange(0, 3), caret(5))
        assertEquals(listOf(caret(5)), MultiCaret.toggleCaret("abcdef", carets, 1))
    }

    @Test
    fun `toggling the only caret keeps it`() {
        assertEquals(listOf(caret(2)), MultiCaret.toggleCaret("abcdef", listOf(caret(2)), 2))
    }

    // per caret

    private fun perCaret(
        text: String,
        carets: List<TextRange>,
        lineBased: Boolean,
        fn: (String, TextRange) -> EditResult,
    ): CaretEdit = MultiCaret.perCaret(text, carets, lineBased, fn)

    @Test
    fun `a line based run comments every caret's line`() {
        val edit = perCaret("one\ntwo", listOf(caret(0), caret(4)), lineBased = true) { text, range ->
            EditActions.toggleLineComment(text, range, "//")
        }
        assertEquals("// one\n// two", edit.text)
    }

    @Test
    fun `two carets on one line comment it once`() {
        val edit = perCaret("one", listOf(caret(0), caret(2)), lineBased = true) { text, range ->
            EditActions.toggleLineComment(text, range, "//")
        }
        assertEquals("// one", edit.text)
    }

    @Test
    fun `a line based run deletes or duplicates every touched line once`() {
        val deleted = perCaret("one\ntwo\nthree", listOf(caret(0), caret(8)), lineBased = true) { text, range ->
            EditActions.deleteLines(text, range)
        }
        assertEquals("two", deleted.text)

        val duplicated = perCaret("a\nb", listOf(caret(0), caret(2)), lineBased = true) { text, range ->
            EditActions.duplicateLineOrSelection(text, range)
        }
        assertEquals("a\na\nb\nb", duplicated.text)
    }

    @Test
    fun `a line based run reports one change per line group, the primary's group last`() {
        val edit = perCaret("one\ntwo", listOf(caret(0), caret(4)), lineBased = true) { text, range ->
            EditActions.toggleLineComment(text, range, "//")
        }
        assertEquals(listOf(TextChange(0, 0, "// "), TextChange(7, 0, "// ")), edit.changes)
        assertEquals(1, edit.primaryChange)
    }

    @Test
    fun `empty caret indentation runs at every caret`() {
        val edit = perCaret("a\nb", listOf(caret(0), caret(2)), lineBased = false) { text, range ->
            EditActions.indent(text, range)
        }
        assertEquals("    a\n    b", edit.text)
        assertEquals(listOf(caret(4), caret(10)), edit.carets)
    }

    @Test
    fun `toggling case twice in one word applies once`() {
        val edit = perCaret("abc", listOf(caret(0), caret(1)), lineBased = false) { text, range ->
            EditActions.toggleCase(text, range)
        }
        assertEquals("ABC", edit.text)
    }

    // thousands of carets

    @Test
    fun `replicating and moving thousands of carets stays under a second`() {
        val lines = 10_000
        val text = (0 until lines).joinToString("\n") { "v$it = ${"x".repeat(10)}" }
        // Every other line, so a downward move lands on free lines and keeps all carets.
        val offsets = ArrayList<Int>()
        var lineStart = 0
        for (line in 0 until lines) {
            if (line % 2 == 0) offsets.add(lineStart + 2)
            val break_ = text.indexOf('\n', lineStart)
            if (break_ < 0) break
            lineStart = break_ + 1
        }
        val carets = offsets.map { TextRange(it) }
        val primary = carets.last()
        val inserted = text.substring(0, primary.end) + "z" + text.substring(primary.end)

        val replicateStart = System.nanoTime()
        val edit = MultiCaret.replicate(
            text,
            primary,
            null,
            TextFieldValue(inserted, TextRange(primary.end + 1)),
            carets.dropLast(1),
        )
        val replicateMillis = (System.nanoTime() - replicateStart) / 1_000_000

        val moveStart = System.nanoTime()
        val moved = MultiCaret.move(text, carets, CaretMove.DOWN, extend = false, desiredColumns = null)
        val moveMillis = (System.nanoTime() - moveStart) / 1_000_000

        assertEquals(primary.end + 1 + carets.size - 1, edit?.carets?.last()?.end)
        assertEquals(carets.size, moved.first.size)
        assertTrue(replicateMillis < 1_000, "replicate took $replicateMillis ms")
        assertTrue(moveMillis < 1_000, "move took $moveMillis ms")
    }
}
