package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.search.legacy.LegacyDeclarationParser
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

class DeclarationParserCompatibilityTest {
    private val root: File = File("..").canonicalFile

    @Test
    fun `existing fields are unchanged for every project source file`() {
        val files = sourceFiles(root.toPath())
        assertTrue(files.size >= 100, "expected at least 100 project sources, found ${files.size}")
        var compared = 0
        for (file in files) {
            val text = runCatching { Files.readString(file) }.getOrNull() ?: continue
            val name = file.fileName.toString()
            assertEquals(
                legacyFields(name, text),
                newFields(name, text),
                "parser output differs for $file",
            )
            compared++
        }
        assertEquals(files.size, compared)
    }

    @Test
    fun `existing fields are unchanged for multi line headers and annotations`() {
        val fixtures = listOf(
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
        for ((name, text) in fixtures) {
            assertEquals(legacyFields(name, text), newFields(name, text), "parser output differs for $name")
        }
    }

    private fun legacyFields(name: String, text: String): List<List<Any?>> =
        LegacyDeclarationParser.parse(name, text).map {
            listOf(it.name, it.kind.name, it.line, it.container, it.isPrivate)
        }

    private fun newFields(name: String, text: String): List<List<Any?>> =
        DeclarationParser.parse(name, text).map {
            listOf(it.name, it.kind.name, it.line, it.container, it.isPrivate)
        }

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
