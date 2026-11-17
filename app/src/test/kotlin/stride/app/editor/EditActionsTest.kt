package stride.app.editor

import androidx.compose.ui.text.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.lang.TextEdit

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
    fun `join lines merges the next line when the selection covers one line`() {
        val text = "one\n    two\nthree"
        assertEquals(
            EditResult("one two\nthree", TextRange(4)),
            EditActions.joinLines(text, TextRange(0, 3)),
        )
        assertEquals(
            EditResult("one two\nthree", TextRange(4)),
            EditActions.joinLines(text, TextRange(0, 4)),
        )
    }

    @Test
    fun `join lines keeps the carriage returns of a CRLF file`() {
        assertEquals(
            EditResult("a b c\r", TextRange(2)),
            EditActions.joinLines("a\r\n  b\r\n  c\r", TextRange(0, 12)),
        )
        assertEquals(
            EditResult("a b\r\nc", TextRange(2)),
            EditActions.joinLines("a\r\n  b\r\nc", TextRange(0)),
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
    fun `start new line keeps the CRLF endings of the file`() {
        assertEquals(
            EditResult("a\r\n    b\r\n    ", TextRange(14)),
            EditActions.startNewLine("a\r\n    b", TextRange(5), before = false),
        )
        assertEquals(
            EditResult("\r\na\r\nb", TextRange(0)),
            EditActions.startNewLine("a\r\nb", TextRange(0), before = true),
        )
        assertEquals(
            EditResult("a\r\n\r\nb", TextRange(3)),
            EditActions.startNewLine("a\r\nb", TextRange(0), before = false),
        )
    }

    @Test
    fun `duplicate of the last line keeps the CRLF ending of the file`() {
        assertEquals(
            EditResult("a\r\nb\r\nb", TextRange(6)),
            EditActions.duplicateLineOrSelection("a\r\nb", TextRange(3)),
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
    fun `identifier ranges use the same characters as the identifier prefix`() {
        assertEquals(listOf(0 until 4, 5 until 7), EditActions.identifierRanges("café au"))
        assertEquals(
            listOf("caféine"),
            EditActions.hippieCandidates("café + caféine", "café", 0, 4),
        )
    }

    @Test
    fun `whole line includes the newline when there is one`() {
        assertEquals(TextRange(0, 4), EditActions.wholeLine("one\ntwo", 1))
        assertEquals(TextRange(4, 7), EditActions.wholeLine("one\ntwo", 5))
        assertEquals(TextRange(0, 3), EditActions.wholeLine("one", 1))
    }

    @Test
    fun `move statement up swaps two function blocks with their documentation`() {
        val text = "/** a */\nfun a() {\n    one()\n}\n/** b */\n@Ann\nfun b() {\n    two()\n}"
        val caret = text.indexOf("fun b")
        assertEquals(
            EditResult("/** b */\n@Ann\nfun b() {\n    two()\n}\n/** a */\nfun a() {\n    one()\n}", TextRange(14)),
            EditActions.moveStatement(text, TextRange(caret), up = true),
        )
    }

    @Test
    fun `move statement down swaps two function blocks`() {
        val text = "fun a() {\n    one()\n}\nfun b() {\n    two()\n}"
        assertEquals(
            EditResult("fun b() {\n    two()\n}\nfun a() {\n    one()\n}", TextRange(22)),
            EditActions.moveStatement(text, TextRange(0), up = false),
        )
    }

    @Test
    fun `move statement does not cross the header of its block`() {
        val text = "fun f() {\n    val a = 1\n    val b = 2\n}"
        val caret = text.indexOf("val a")
        assertEquals(EditResult(text, TextRange(caret)), EditActions.moveStatement(text, TextRange(caret), up = true))
    }

    @Test
    fun `move statement swaps plain statement lines`() {
        val text = "val a = 1\nval b = 2"
        assertEquals(
            EditResult("val b = 2\nval a = 1", TextRange(2)),
            EditActions.moveStatement(text, TextRange(12), up = true),
        )
        assertEquals(
            EditResult("val b = 2\nval a = 1", TextRange(12)),
            EditActions.moveStatement(text, TextRange(2), up = false),
        )
    }

    @Test
    fun `move statement passes a blank line`() {
        assertEquals(
            EditResult("one\ntwo\n", TextRange(4)),
            EditActions.moveStatement("one\n\ntwo", TextRange(5), up = true),
        )
    }

    @Test
    fun `move statement keeps the selection with the unit`() {
        assertEquals(
            EditResult("two\none\nthree", TextRange(0, 4)),
            EditActions.moveStatement("one\ntwo\nthree", TextRange(4, 8), up = true),
        )
    }

    @Test
    fun `move statement carries the block its first selected line opens`() {
        val text = "fun a() {\n    one()\n}\nval b = 1"
        assertEquals(
            EditResult("val b = 1\nfun a() {\n    one()\n}", TextRange(10, 28)),
            EditActions.moveStatement(text, TextRange(0, 18), up = false),
        )
    }

    @Test
    fun `move statement does not move a line that closes a block above it`() {
        val text = "fun f() {\n    one()\n}"
        val caret = text.indexOf('}')
        assertEquals(EditResult(text, TextRange(caret)), EditActions.moveStatement(text, TextRange(caret), up = true))
    }

    @Test
    fun `complete statement closes open brackets before the line comment`() {
        assertEquals(
            EditResult("foo(bar(1)) // note\n", TextRange(20)),
            EditActions.completeStatement("foo(bar(1 // note", TextRange(16), semicolons = false),
        )
        assertEquals(
            EditResult("foo(bar(1))\n", TextRange(12)),
            EditActions.completeStatement("foo(bar(1", TextRange(9), semicolons = false),
        )
    }

    @Test
    fun `complete statement closes after the code that follows a block comment`() {
        assertEquals(
            EditResult("foo(a /* x // y */ , b)\n", TextRange(24)),
            EditActions.completeStatement("foo(a /* x // y */ , b", TextRange(22), semicolons = false),
        )
    }

    @Test
    fun `complete statement opens the block of an if line`() {
        assertEquals(
            EditResult("if (x > 0) {\n    \n}", TextRange(17)),
            EditActions.completeStatement("if (x > 0", TextRange(8), semicolons = false),
        )
    }

    @Test
    fun `complete statement leaves an expression body alone`() {
        assertEquals(
            EditResult("fun f() = 1\n", TextRange(12)),
            EditActions.completeStatement("fun f() = 1", TextRange(11), semicolons = false),
        )
    }

    @Test
    fun `complete statement leaves a generic return type expression body alone`() {
        assertEquals(
            EditResult("fun f(): Map<String, Int> = mapOf()\n", TextRange(36)),
            EditActions.completeStatement("fun f(): Map<String, Int> = mapOf()", TextRange(35), semicolons = false),
        )
        assertEquals(
            EditResult("fun f(): List<Int> = listOf()\n", TextRange(30)),
            EditActions.completeStatement("fun f(): List<Int> = listOf()", TextRange(29), semicolons = false),
        )
    }

    @Test
    fun `complete statement puts the new block after a trailing comment`() {
        assertEquals(
            EditResult("if (x > 0) { // why\n    \n}", TextRange(24)),
            EditActions.completeStatement("if (x > 0) { // why", TextRange(19), semicolons = false),
        )
    }

    @Test
    fun `complete statement does not close brackets on a line that ends with a brace`() {
        assertEquals(
            EditResult("foo({\n    \n}", TextRange(10)),
            EditActions.completeStatement("foo({", TextRange(5), semicolons = false),
        )
    }

    @Test
    fun `complete statement appends a semicolon in Java`() {
        assertEquals(
            EditResult("foo();\n", TextRange(7)),
            EditActions.completeStatement("foo()", TextRange(5), semicolons = true),
        )
    }

    @Test
    fun `complete statement adds the closing brace when the block is not matched`() {
        assertEquals(
            EditResult("fun f() {\n    \n}", TextRange(14)),
            EditActions.completeStatement("fun f() {", TextRange(9), semicolons = false),
        )
    }

    @Test
    fun `complete statement starts inside an already matched block`() {
        assertEquals(
            EditResult("fun f() {\n    \n}", TextRange(14)),
            EditActions.completeStatement("fun f() {\n}", TextRange(9), semicolons = false),
        )
    }

    @Test
    fun `complete statement keeps the CRLF endings of the file`() {
        assertEquals(
            EditResult("foo()\r\n\r\nbar", TextRange(7)),
            EditActions.completeStatement("foo()\r\nbar", TextRange(5), semicolons = false),
        )
    }

    @Test
    fun `complete statement does nothing on a blank line`() {
        val text = "one\n\ntwo"
        assertEquals(EditResult(text, TextRange(4)), EditActions.completeStatement(text, TextRange(4), semicolons = true))
    }

    @Test
    fun `unwrap removes an if and keeps its body`() {
        val text = "if (a) {\n    foo()\n}"
        assertEquals(EditResult("foo()", TextRange(0)), EditActions.unwrap(text, TextRange(text.indexOf("foo"))))
    }

    @Test
    fun `unwrap keeps the body of an else and drops the if`() {
        val text = "if (a) {\n    one()\n} else {\n    two()\n}"
        assertEquals(EditResult("two()", TextRange(0)), EditActions.unwrap(text, TextRange(text.indexOf("two"))))
    }

    @Test
    fun `unwrap of a middle else if keeps only that body`() {
        val text = "if (a) {\n    one()\n} else if (b) {\n    two()\n} else {\n    three()\n}"
        assertEquals(EditResult("two()", TextRange(0)), EditActions.unwrap(text, TextRange(text.indexOf("two"))))
    }

    @Test
    fun `unwrap of a try keeps the try body and drops catch and finally`() {
        val text = "try {\n    one()\n} catch (e: Exception) {\n    two()\n} finally {\n    three()\n}"
        assertEquals(EditResult("one()", TextRange(0)), EditActions.unwrap(text, TextRange(text.indexOf("one"))))
    }

    @Test
    fun `unwrap follows loops and removes a do-while tail`() {
        assertEquals(
            EditResult("foo()", TextRange(0)),
            EditActions.unwrap("for (x in xs) {\n    foo()\n}", TextRange(19)),
        )
        assertEquals(
            EditResult("foo()", TextRange(0)),
            EditActions.unwrap("while (x) {\n    foo()\n}", TextRange(15)),
        )
        assertEquals(
            EditResult("foo()", TextRange(0)),
            EditActions.unwrap("do {\n    foo()\n} while (x)", TextRange(9)),
        )
    }

    @Test
    fun `unwrap collapses a one-line block`() {
        val text = "if (a) { foo() }"
        assertEquals(EditResult("foo()", TextRange(0)), EditActions.unwrap(text, TextRange(9)))
    }

    @Test
    fun `unwrap reports no candidate outside a control block`() {
        assertNull(EditActions.unwrap("val x = 1", TextRange(4)))
        assertNull(EditActions.unwrap("fun f() {\n    foo()\n}", TextRange(14)))
        assertNull(EditActions.unwrap("val x = if (a) {\n    foo()\n}", TextRange(16)))
    }

    @Test
    fun `unwrap removes the chain entirely when the body is empty`() {
        val text = "if (a) {\n}"
        assertEquals(EditResult("", TextRange(0)), EditActions.unwrap(text, TextRange(8)))
    }

    @Test
    fun `unwrap keeps the text after the closing brace`() {
        val text = "if (a) {\n    one()\n} // note\nval b = 1"
        assertEquals(
            EditResult("one() // note\nval b = 1", TextRange(1)),
            EditActions.unwrap(text, TextRange(14)),
        )
    }

    @Test
    fun `unwrap keeps a statement after a one-line block`() {
        assertEquals(
            EditResult("foo() bar()", TextRange(3)),
            EditActions.unwrap("if (a) { foo() } bar()", TextRange(12)),
        )
    }

    @Test
    fun `unwrap puts the caret where it removed an empty body`() {
        val text = "fun f() {\n    if (a) {\n    }\n    bar()\n}"
        assertEquals(
            EditResult("fun f() {\n    bar()\n}", TextRange(10)),
            EditActions.unwrap(text, TextRange(24)),
        )
    }

    @Test
    fun `reformat normalizes the indentation of a Kotlin file`() {
        val text = "class A {\n  fun f() {\n        foo()\n  }\n}"
        assertEquals(
            "class A {\n    fun f() {\n        foo()\n    }\n}",
            applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = true)),
        )
    }

    @Test
    fun `reformat indents one level below a line that opens two brackets`() {
        val text = "foo(bar {\nx\n})"
        assertEquals(
            "foo(bar {\n    x\n})",
            applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = true)),
        )
    }

    @Test
    fun `reformat uses the opening line level for a closing bracket line`() {
        val text = "call(\nfoo()\n)"
        assertEquals(
            "call(\n    foo()\n)",
            applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = true)),
        )
    }

    @Test
    fun `reformat aligns a continuation line`() {
        val text = "val xs = listOf(1)\n.map { it * 2 }"
        assertEquals(
            "val xs = listOf(1)\n    .map { it * 2 }",
            applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = true)),
        )
    }

    @Test
    fun `reformat aligns KDoc stars`() {
        val text = "fun f() {\n/**\n * doc\n */\n}"
        assertEquals(
            "fun f() {\n    /**\n     * doc\n     */\n}",
            applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = true)),
        )
    }

    @Test
    fun `reformat keeps the lines of a raw string`() {
        val text = "val s = \"\"\"\n  keep   \n     me\n\"\"\""
        assertEquals(emptyList<TextEdit>(), EditActions.reformat(text, TextRange(0), braceLanguage = true))
    }

    @Test
    fun `reformat keeps a whitespace-only line of a raw string`() {
        val text = "val s = \"\"\"\n    \n  x\n\"\"\""
        assertEquals(emptyList<TextEdit>(), EditActions.reformat(text, TextRange(0), braceLanguage = true))
    }

    @Test
    fun `reformat only touches the selected lines`() {
        val text = "class A {\n  fun f() {}\n}"
        assertEquals(
            "class A {\n    fun f() {}\n}",
            applied(text, EditActions.reformat(text, TextRange(10, 22), braceLanguage = true)),
        )
    }

    @Test
    fun `reformat only trims trailing whitespace outside brace languages`() {
        val text = "def f():\n      x = 1   \n"
        assertEquals(
            "def f():\n      x = 1\n",
            applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = false)),
        )
    }

    @Test
    fun `reformat reduces a long run of blank lines to two`() {
        val text = "a\n\n\n\n\nb"
        assertEquals("a\n\n\nb", applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = false)))
    }

    @Test
    fun `reformat reduces a blank run at the end of the file to two lines`() {
        val text = "a\n\n\n\n   "
        assertEquals("a\n\n\n", applied(text, EditActions.reformat(text, TextRange(0), braceLanguage = false)))
    }

    @Test
    fun `reformat leaves an already formatted text alone`() {
        val text = "class A {\n    fun f() {}\n}\n"
        assertEquals(emptyList<TextEdit>(), EditActions.reformat(text, TextRange(0), braceLanguage = true))
    }

    private fun applied(text: String, edits: List<TextEdit>): String {
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }
}
