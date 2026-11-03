package stride.app.editor

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EditActionsTest {
    @Test
    fun `duplicate without selection copies the caret line below`() {
        assertEquals(
            EditResult("one\ntwo\ntwo\nthree", TextRange(9)),
            EditActions.duplicateLineOrSelection("one\ntwo\nthree", TextRange(5)),
        )
    }

    @Test
    fun `duplicate without selection handles the last line without a newline`() {
        assertEquals(
            EditResult("one\ntwo\ntwo", TextRange(9)),
            EditActions.duplicateLineOrSelection("one\ntwo", TextRange(5)),
        )
    }

    @Test
    fun `duplicate with selection appends the selection and selects the copy`() {
        assertEquals(
            EditResult("one\ntwotwo\nthree", TextRange(7, 10)),
            EditActions.duplicateLineOrSelection("one\ntwo\nthree", TextRange(4, 7)),
        )
    }

    @Test
    fun `duplicate with a partial-line selection appends inside the line`() {
        assertEquals(
            EditResult("onone\ntwo", TextRange(2, 4)),
            EditActions.duplicateLineOrSelection("one\ntwo", TextRange(0, 2)),
        )
    }

    @Test
    fun `duplicate on an empty text adds a blank line`() {
        assertEquals(
            EditResult("\n", TextRange(1)),
            EditActions.duplicateLineOrSelection("", TextRange(0)),
        )
    }

    @Test
    fun `delete removes the caret line`() {
        assertEquals(
            EditResult("one\nthree", TextRange(5)),
            EditActions.deleteLines("one\ntwo\nthree", TextRange(5)),
        )
    }

    @Test
    fun `delete removes the first line and keeps the column`() {
        assertEquals(
            EditResult("two\nthree", TextRange(1)),
            EditActions.deleteLines("one\ntwo\nthree", TextRange(1)),
        )
    }

    @Test
    fun `delete of the last line without a newline removes the previous newline`() {
        assertEquals(
            EditResult("one", TextRange(1)),
            EditActions.deleteLines("one\ntwo", TextRange(5)),
        )
    }

    @Test
    fun `delete of the trailing empty line removes the previous newline`() {
        assertEquals(
            EditResult("one", TextRange(0)),
            EditActions.deleteLines("one\n", TextRange(4)),
        )
    }

    @Test
    fun `delete removes every line the selection covers`() {
        assertEquals(
            EditResult("three", TextRange(0)),
            EditActions.deleteLines("one\ntwo\nthree", TextRange(0, 8)),
        )
    }

    @Test
    fun `delete of a selection ending at the next line start leaves that line`() {
        assertEquals(
            EditResult("two\nthree", TextRange(0)),
            EditActions.deleteLines("one\ntwo\nthree", TextRange(0, 4)),
        )
    }

    @Test
    fun `delete of everything leaves an empty text`() {
        assertEquals(
            EditResult("", TextRange(0)),
            EditActions.deleteLines("one\ntwo\nthree", TextRange(0, 13)),
        )
    }

    @Test
    fun `move up on the first line changes nothing`() {
        assertEquals(
            EditResult("one\ntwo", TextRange(1)),
            EditActions.moveLines("one\ntwo", TextRange(1), up = true),
        )
    }

    @Test
    fun `move down on the last line without a newline changes nothing`() {
        assertEquals(
            EditResult("one\ntwo", TextRange(5)),
            EditActions.moveLines("one\ntwo", TextRange(5), up = false),
        )
    }

    @Test
    fun `move up swaps the caret line with the previous one`() {
        assertEquals(
            EditResult("two\none\nthree", TextRange(1)),
            EditActions.moveLines("one\ntwo\nthree", TextRange(5), up = true),
        )
    }

    @Test
    fun `move down swaps the caret line with the next one`() {
        assertEquals(
            EditResult("two\none", TextRange(5)),
            EditActions.moveLines("one\ntwo", TextRange(1), up = false),
        )
    }

    @Test
    fun `move up carries every selected line`() {
        assertEquals(
            EditResult("two\nthree\none\nfour", TextRange(0, 9)),
            EditActions.moveLines("one\ntwo\nthree\nfour", TextRange(4, 13), up = true),
        )
    }

    @Test
    fun `move up of the last line without a newline keeps its text`() {
        assertEquals(
            EditResult("two\none", TextRange(1)),
            EditActions.moveLines("one\ntwo", TextRange(5), up = true),
        )
    }

    @Test
    fun `line comment adds the prefix at column zero`() {
        assertEquals(
            EditResult("one\n// two", TextRange(7)),
            EditActions.toggleLineComment("one\ntwo", TextRange(4), "//"),
        )
    }

    @Test
    fun `line comment adds the prefix at the smallest indentation`() {
        assertEquals(
            EditResult("  // one\n  //   two", TextRange(0, 19)),
            EditActions.toggleLineComment("  one\n    two", TextRange(0, 13), "//"),
        )
    }

    @Test
    fun `line comment removes the prefix from every covered line`() {
        assertEquals(
            EditResult("one\ntwo", TextRange(0, 7)),
            EditActions.toggleLineComment("// one\n// two", TextRange(0, 13), "//"),
        )
    }

    @Test
    fun `line comment removes only the prefix when no space follows`() {
        assertEquals(
            EditResult("one", TextRange(0, 3)),
            EditActions.toggleLineComment("//one", TextRange(0, 5), "//"),
        )
    }

    @Test
    fun `line comment leaves blank lines alone`() {
        val text = "// one\n\n// two"
        assertEquals(
            EditResult("one\n\ntwo", TextRange(0, 8)),
            EditActions.toggleLineComment(text, TextRange(0, 14), "//"),
        )
    }

    @Test
    fun `line comment skips blank lines when adding`() {
        assertEquals(
            EditResult("// one\n\n// two", TextRange(3, 14)),
            EditActions.toggleLineComment("one\n\ntwo", TextRange(0, 8), "//"),
        )
    }

    @Test
    fun `block comment wraps a selection`() {
        assertEquals(
            EditResult("a/*b*/c", TextRange(3, 4)),
            EditActions.toggleBlockComment("abc", TextRange(1, 2), "/*", "*/"),
        )
    }

    @Test
    fun `block comment unwraps a selection that is already wrapped`() {
        assertEquals(
            EditResult("abc", TextRange(1, 2)),
            EditActions.toggleBlockComment("a/*b*/c", TextRange(1, 6), "/*", "*/"),
        )
    }

    @Test
    fun `block comment wraps a collapsed caret`() {
        assertEquals(
            EditResult("a/**/bc", TextRange(3)),
            EditActions.toggleBlockComment("abc", TextRange(1), "/*", "*/"),
        )
    }

    @Test
    fun `indent inserts the unit at a collapsed caret`() {
        assertEquals(
            EditResult("a    bc", TextRange(5)),
            EditActions.indent("abc", TextRange(1)),
        )
    }

    @Test
    fun `indent covers the whole lines of a selection`() {
        assertEquals(
            EditResult("    one\ntwo", TextRange(0, 7)),
            EditActions.indent("one\ntwo", TextRange(0, 3)),
        )
    }

    @Test
    fun `indent of a multi-line selection selects every line`() {
        assertEquals(
            EditResult("    one\n    two", TextRange(0, 15)),
            EditActions.indent("one\ntwo", TextRange(0, 5)),
        )
    }

    @Test
    fun `indent of an empty text inserts the unit`() {
        assertEquals(
            EditResult("    ", TextRange(4)),
            EditActions.indent("", TextRange(0)),
        )
    }

    @Test
    fun `unindent removes up to the unit length of leading whitespace`() {
        assertEquals(
            EditResult("one", TextRange(2)),
            EditActions.unindent("    one", TextRange(6)),
        )
    }

    @Test
    fun `unindent removes only the whitespace a line has`() {
        assertEquals(
            EditResult("one", TextRange(2)),
            EditActions.unindent("  one", TextRange(4)),
        )
    }

    @Test
    fun `unindent adjusts the selection on every line`() {
        assertEquals(
            EditResult("one\ntwo", TextRange(0, 7)),
            EditActions.unindent("    one\n  two", TextRange(0, 13)),
        )
    }

    @Test
    fun `unindent leaves an unindented line unchanged`() {
        assertEquals(
            EditResult("one", TextRange(1)),
            EditActions.unindent("one", TextRange(1)),
        )
    }

    @Test
    fun `goto line is one-based and clamps the column to the line`() {
        val text = "one\ntwo\nthree"
        assertEquals(TextRange(4), EditActions.gotoLine(text, 2))
        assertEquals(TextRange(5), EditActions.gotoLine(text, 2, 2))
        assertEquals(TextRange(8), EditActions.gotoLine(text, 99))
        assertEquals(TextRange(3), EditActions.gotoLine(text, 1, 99))
    }

    @Test
    fun `goto line clamps a line below one to the first line`() {
        assertEquals(TextRange(0), EditActions.gotoLine("one\ntwo", 0))
        assertEquals(TextRange(0), EditActions.gotoLine("one\ntwo", 1, 0))
    }
}
