package stride.app.problems

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.lang.ProblemSeverity

class CompilerMessageParserTest {
    @Test
    fun `a kotlin 1_8 error line reads its uri path and position`() {
        val message = requireNotNull(
            CompilerMessageParser.parse("e: file:///work/project/src/Main.kt:12:5 Unresolved reference: foo"),
        )

        assertEquals(Path.of("/work/project/src/Main.kt"), message.path)
        assertEquals(11, message.line)
        assertEquals(4, message.column)
        assertEquals(ProblemSeverity.ERROR, message.severity)
        assertEquals("Unresolved reference: foo", message.message)
    }

    @Test
    fun `a kotlin warning line reads as a warning`() {
        val message = requireNotNull(
            CompilerMessageParser.parse("w: file:///work/A.kt:3:1 Variable 'x' is never used"),
        )

        assertEquals(ProblemSeverity.WARNING, message.severity)
        assertEquals(2, message.line)
        assertEquals(0, message.column)
        assertEquals("Variable 'x' is never used", message.message)
    }

    @Test
    fun `a percent-encoded uri path is decoded`() {
        val message = requireNotNull(
            CompilerMessageParser.parse("e: file:///work/my%20project/A.kt:2:3 something broke"),
        )

        assertEquals(Path.of("/work/my project/A.kt"), message.path)
    }

    @Test
    fun `a uri path with a space parses whether or not it is encoded`() {
        val plain = requireNotNull(
            CompilerMessageParser.parse("e: file:///tmp/My Project/A.kt:3:5 something broke"),
        )
        val encoded = requireNotNull(
            CompilerMessageParser.parse("e: file:///tmp/My%20Project/A.kt:3:5 something broke"),
        )

        assertEquals(Path.of("/tmp/My Project/A.kt"), plain.path)
        assertEquals(Path.of("/tmp/My Project/A.kt"), encoded.path)
        assertEquals(2, plain.line)
        assertEquals(4, plain.column)
        assertEquals(2, encoded.line)
        assertEquals(4, encoded.column)
    }

    @Test
    fun `the kotlin 1_7 parenthesised form reads its absolute path`() {
        val message = requireNotNull(
            CompilerMessageParser.parse("e: /work/project/A.kt: (12, 5): Unresolved reference: foo"),
        )

        assertEquals(Path.of("/work/project/A.kt"), message.path)
        assertEquals(11, message.line)
        assertEquals(4, message.column)
        assertEquals("Unresolved reference: foo", message.message)
    }

    @Test
    fun `a javac error has no column and covers the line`() {
        val message = requireNotNull(
            CompilerMessageParser.parse("/work/project/App.java:7: error: cannot find symbol"),
        )

        assertEquals(Path.of("/work/project/App.java"), message.path)
        assertEquals(6, message.line)
        assertNull(message.column)
        assertEquals(ProblemSeverity.ERROR, message.severity)
        assertEquals("cannot find symbol", message.message)
    }

    @Test
    fun `a javac warning keeps its bracket text`() {
        val message = requireNotNull(
            CompilerMessageParser.parse("/work/project/App.java:9: warning: [deprecation] old() in Old has been deprecated"),
        )

        assertEquals(ProblemSeverity.WARNING, message.severity)
        assertEquals("[deprecation] old() in Old has been deprecated", message.message)
    }

    @Test
    fun `anything that is not a positioned compiler line reads as null`() {
        assertNull(CompilerMessageParser.parse("> Task :compileKotlin FAILED"))
        assertNull(CompilerMessageParser.parse("BUILD FAILED"))
        assertNull(CompilerMessageParser.parse("e: /work/A.kt:0:4 zero line"))
        assertNull(CompilerMessageParser.parse("e: file:///work/A.kt:1:0 bad column"))
        assertNull(CompilerMessageParser.parse("e: file:///work/A.kt:1:2 "))
        assertNull(CompilerMessageParser.parse("e: src/A.kt: (1, 2): relative legacy path"))
        assertNull(CompilerMessageParser.parse("src/App.java:3: error: relative javac path"))
        assertNull(CompilerMessageParser.parse("e: "))
        assertNull(CompilerMessageParser.parse(""))
    }
}
