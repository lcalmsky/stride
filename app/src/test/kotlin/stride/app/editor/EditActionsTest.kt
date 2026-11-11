package stride.app.editor

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun `word at uses the identifier touching the caret`() {
        val text = "val value = 1"
        assertEquals(TextRange(4, 9), EditActions.wordAt(text, 6))
        assertEquals(TextRange(4, 9), EditActions.wordAt(text, 9))
        assertEquals(TextRange(4, 9), EditActions.wordAt(text, 4))
        assertNull(EditActions.wordAt("a + b", 2))
        assertEquals(TextRange(0, 1), EditActions.wordAt("a + b", 0))
    }

    @Test
    fun `identifier prefix is the run before the caret`() {
        val text = "val value"
        assertEquals(0 until 3, EditActions.identifierPrefix(text, 3))
        assertEquals(4 until 9, EditActions.identifierPrefix(text, 9))
        assertEquals(3 until 3, EditActions.identifierPrefix(text, 4))
    }

    @Test
    fun `selection grows from word through brackets and lines to the whole text`() {
        val text = "call(alpha, beta)"
        assertEquals(TextRange(5, 10), EditActions.selectWord(text, TextRange(6)))
        assertEquals(TextRange(5, 16), EditActions.selectWord(text, TextRange(5, 10)))
        assertEquals(TextRange(4, 17), EditActions.selectWord(text, TextRange(5, 16)))
        assertEquals(TextRange(0, 17), EditActions.selectWord(text, TextRange(4, 17)))
        assertNull(EditActions.selectWord(text, TextRange(0, 17)))
    }

    @Test
    fun `selection grows into the contents of a string literal`() {
        val text = "val s = \"hello world\""
        assertEquals(TextRange(9, 14), EditActions.selectWord(text, TextRange(10)))
        assertEquals(TextRange(9, 20), EditActions.selectWord(text, TextRange(9, 14)))
        assertEquals(TextRange(0, 21), EditActions.selectWord(text, TextRange(9, 20)))
        assertNull(EditActions.selectWord(text, TextRange(0, 21)))
    }

    @Test
    fun `selection grows to the whole text when it covers the last line`() {
        val text = "one\ntwo"
        assertEquals(TextRange(0, 3), EditActions.selectWord(text, TextRange(0)))
        assertEquals(TextRange(0, 7), EditActions.selectWord(text, TextRange(0, 3)))
        assertNull(EditActions.selectWord(text, TextRange(0, 7)))
    }

    @Test
    fun `toggle case upper-cases when a lower-case letter is present`() {
        assertEquals(
            EditResult("HELLO world", TextRange(0, 5)),
            EditActions.toggleCase("hello world", TextRange(0, 5)),
        )
        assertEquals(
            EditResult("HELLO WORLD", TextRange(0, 11)),
            EditActions.toggleCase("HELLO World", TextRange(0, 11)),
        )
    }

    @Test
    fun `toggle case uses the word at the caret when nothing is selected`() {
        assertEquals(
            EditResult("ABC", TextRange(0, 3)),
            EditActions.toggleCase("abC", TextRange(1)),
        )
        assertEquals(
            EditResult("a + b", TextRange(2)),
            EditActions.toggleCase("a + b", TextRange(2)),
        )
    }

    @Test
    fun `join lines removes indentation and leaves one space`() {
        assertEquals(
            EditResult("one two", TextRange(4)),
            EditActions.joinLines("one\n    two", TextRange(1)),
        )
        assertEquals(
            EditResult("foo()", TextRange(4)),
            EditActions.joinLines("foo(\n    )", TextRange(0)),
        )
        assertEquals(
            EditResult("value.call()", TextRange(5)),
            EditActions.joinLines("value\n    .call()", TextRange(0)),
        )
        assertEquals(
            EditResult("a,b", TextRange(1)),
            EditActions.joinLines("a\n    ,b", TextRange(0)),
        )
    }

    @Test
    fun `join lines merges every selected line`() {
        assertEquals(
            EditResult("a b c\nd", TextRange(2)),
            EditActions.joinLines("a\n  b\n  c\nd", TextRange(0, 8)),
        )
    }

    @Test
    fun `join lines drops a blank line and leaves the last line alone`() {
        assertEquals(
            EditResult("a\nb", TextRange(1)),
            EditActions.joinLines("a\n\nb", TextRange(0)),
        )
        assertEquals(
            EditResult("one\ntwo", TextRange(5)),
            EditActions.joinLines("one\ntwo", TextRange(5)),
        )
    }

    @Test
    fun `start new line keeps the current line indentation`() {
        assertEquals(
            EditResult("  val x = 1\n  \nnext", TextRange(14)),
            EditActions.startNewLine("  val x = 1\nnext", TextRange(4), before = false),
        )
        assertEquals(
            EditResult("  \n  val x = 1\nnext", TextRange(2)),
            EditActions.startNewLine("  val x = 1\nnext", TextRange(4), before = true),
        )
        assertEquals(
            EditResult("a\n    b\n    ", TextRange(12)),
            EditActions.startNewLine("a\n    b", TextRange(6), before = false),
        )
    }

    @Test
    fun `match brace jumps between brackets outside strings and comments`() {
        val text = "fun f() {}"
        assertEquals(TextRange(5), EditActions.matchBrace(text, 6))
        assertEquals(TextRange(8), EditActions.matchBrace(text, 9))
        assertEquals(TextRange(8), EditActions.matchBrace(text, 10))
        assertNull(EditActions.matchBrace(text, 3))
        assertEquals(TextRange(4), EditActions.matchBrace("(\")\")", 0))
        assertEquals(TextRange(7), EditActions.matchBrace("(\n// )\n)", 0))
    }

    @Test
    fun `code block start and end find the enclosing braces`() {
        val text = "fun f() {\n    if (x) {\n        y\n    }\n}\n"
        val innerOpen = text.lastIndexOf('{', text.indexOf('y'))
        val innerClose = text.indexOf('}', text.indexOf('y'))
        val outerClose = text.lastIndexOf('}')

        assertEquals(TextRange(innerOpen), EditActions.codeBlockStart(text, text.indexOf('y')))
        assertEquals(TextRange(innerClose), EditActions.codeBlockEnd(text, text.indexOf('y')))
        assertEquals(TextRange(text.indexOf('{')), EditActions.codeBlockStart(text, innerOpen))
        assertEquals(TextRange(outerClose), EditActions.codeBlockEnd(text, innerOpen))
        assertNull(EditActions.codeBlockStart(text, 0))
        assertNull(EditActions.codeBlockEnd(text, 0))
    }

    @Test
    fun `declaration target moves to the previous or next declaration line`() {
        val text = "fun a() {}\n\nclass B\nval c = 1"
        val lines = listOf(0, 2, 3)
        val caretLine = 11
        assertEquals(TextRange(0), EditActions.declarationTarget(text, caretLine, lines, up = true))
        assertEquals(TextRange(12), EditActions.declarationTarget(text, caretLine, lines, up = false))
        assertNull(EditActions.declarationTarget(text, 0, lines, up = true))
        assertNull(EditActions.declarationTarget(text, text.length, lines, up = false))
    }

    @Test
    fun `whole word ranges skip identifiers that contain the word`() {
        val text = "foo fooFoo foo_x foo"
        assertEquals(listOf(0 until 3, 17 until 20), EditActions.wholeWordRanges(text, "foo"))
        assertEquals(emptyList<IntRange>(), EditActions.wholeWordRanges("\$foo", "foo"))
        assertEquals(emptyList<IntRange>(), EditActions.wholeWordRanges("bar", ""))
    }

    @Test
    fun `hippie candidates prefer the nearest preceding word`() {
        val text = "val value = validator + val"
        val caret = text.length
        val prefixStart = text.lastIndexOf("val")
        assertEquals(
            listOf("validator", "value"),
            EditActions.hippieCandidates(text, "val", prefixStart, caret),
        )
    }

    @Test
    fun `hippie candidates append other editors without duplicates`() {
        val text = "val value = val"
        val caret = text.length
        val prefixStart = text.lastIndexOf("val")
        assertEquals(
            listOf("value", "valley"),
            EditActions.hippieCandidates(text, "val", prefixStart, caret, listOf("value\nvalley")),
        )
    }

    @Test
    fun `hippie candidates are empty without a prefix`() {
        assertEquals(emptyList<String>(), EditActions.hippieCandidates("val", "", 3, 3))
    }

    @Test
    fun `whole line includes the newline when there is one`() {
        assertEquals(TextRange(0, 4), EditActions.wholeLine("one\ntwo", 1))
        assertEquals(TextRange(4, 7), EditActions.wholeLine("one\ntwo", 5))
        assertEquals(TextRange(0, 3), EditActions.wholeLine("one", 1))
    }
}
