package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.search.legacy.LegacyDeclarationParser
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * The parser may now read declarations the earlier one missed (a class body opening on a later
 * line, a member on the class line or after a closing brace), so a legacy declaration must still be
 * there with the same name, kind, line and privacy but the new result may hold more.
 */
class DeclarationParserCompatibilityTest {
    private val root: File = File("..").canonicalFile

    @Test
    fun `existing fields are unchanged for every project source file`() {
        val files = sourceFiles(root.toPath())
        assertTrue(files.size >= 100, "expected at least 100 project sources, found ${files.size}")
        val report = CompatibilityReport()
        for (file in files) {
            val text = runCatching { Files.readString(file) }.getOrNull() ?: continue
            val relative = root.toPath().relativize(file).toString()
            compare(relative, text, report)
            report.files++
        }
        assertEquals(files.size, report.files)
        println(report.summary())
    }

    @Test
    fun `existing fields are unchanged for multi line headers and annotations`() {
        val report = CompatibilityReport()
        for ((name, text) in fixtures) compare(name, text, report)
    }

    private fun compare(displayName: String, text: String, report: CompatibilityReport) {
        val name = displayName.substringAfterLast('/')
        val legacy = LegacyDeclarationParser.parse(name, text)
        val unmatched = DeclarationParser.parse(name, text).toMutableList()
        for (old in legacy) {
            val candidates = unmatched.filter {
                it.name == old.name && it.kind == old.kind && it.line == old.line && it.isPrivate == old.isPrivate
            }
            val match = candidates.firstOrNull { it.container == old.container } ?: candidates.firstOrNull()
            assertNotNull(match, "${old.name} at $displayName:${old.line} disappeared")
            unmatched.remove(match)
            if (match!!.container != old.container) {
                assertNull(old.container, "${old.name} at $displayName:${old.line} changed container from ${old.container}")
                report.containerChanges.add("${old.name}@$displayName:${old.line} -> ${match.container}")
            }
        }
        report.added += unmatched.size
        for (declaration in unmatched) {
            if (report.examples.size < 5) {
                report.examples.add("${declaration.name}@$displayName:${declaration.line} -> ${declaration.container}")
            }
        }
    }

    private class CompatibilityReport {
        var files = 0
        var added = 0
        val containerChanges = ArrayList<String>()
        val examples = ArrayList<String>()

        fun summary(): String {
            val shown = (examples + containerChanges).take(5).joinToString(",")
            return "DeclarationParserCompatibility: added=$added containerChanges=${containerChanges.size} examples=$shown"
        }
    }

    private val fixtures = listOf(
        "Sample.kt" to """
            package demo

            @Deprecated("x")
            data class Circle<T : Comparable<T>>(
                val radius: T,
            ) : Shape(radius),
                Drawable by renderer where T : Number {
                override fun draw() {}
            }

            sealed interface Shape
            fun interface Factory {
                fun create(): Shape
            }
            object Registry : Factory {
                companion object Named : Factory by factory {
                    const val SIZE = 1
                }
                override fun create(): Shape = error("x")
            }
            enum class Color : Named { RED, GREEN }
            typealias Handler = (String) -> Unit
        """.trimIndent(),
        "Sample.java" to """
            package demo;

            @Deprecated
            public sealed class Sample<T extends Number>
                    extends Base implements Runnable, AutoCloseable {
                @Override
                public void run() {
                }

                public <R> R map(
                    R value
                ) {
                    return value;
                }

                enum Mode implements Runnable {
                    ON { public void run() {} }
                }

                record Point(int x, int y) implements Comparable<Point> {
                    public int compareTo(Point other) { return 0; }
                }
            }
        """.trimIndent(),
        "Functional.kt" to """
            fun String.shout(): String = uppercase()
            private val top = 1
            class Outer {
                inner class Inner
                private fun hidden() {}
            }
            val lambda = { value: Int -> value }
        """.trimIndent(),
    )

    private fun sourceFiles(root: Path): List<Path> {
        val excluded = setOf("build", ".gradle", ".git", "out", "node_modules", ".idea")
        Files.walk(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) }
                .filter { path ->
                    val name = path.fileName.toString()
                    name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".java")
                }
                .filter { path -> root.relativize(path).none { it.toString() in excluded } }
                .collect(Collectors.toList())
        }
    }
}
