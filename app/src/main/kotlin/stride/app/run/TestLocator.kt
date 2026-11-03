package stride.app.run

import java.nio.file.Files
import java.nio.file.Path

/**
 * Finds the JUnit test under a caret and the Gradle project a file belongs to, using only
 * regular expressions over the file text. Nested classes are not supported.
 */
object TestLocator {
    data class TestTarget(val className: String, val methodName: String?)

    data class TestMarker(val line: Int, val target: TestTarget)

    private val PACKAGE = Regex("(?m)^\\s*package\\s+([\\w.]+)")
    private val CLASS = Regex("^\\s*(?:\\w+\\s+)*class\\s+(\\w+)")
    private val METHOD = Regex("^\\s*(?:\\w+\\s+)*fun\\s+(?:`([^`]+)`|(\\w+))\\s*\\(")
    private val TEST_ANNOTATION = Regex("^@Test\\b")

    fun locate(text: String, caretOffset: Int): TestTarget? {
        val lines = text.split("\n")
        val caretLine = lineIndexAt(text, caretOffset)
        val className = classNameAt(lines, caretLine) ?: return null
        val packageName = PACKAGE.find(text)?.groupValues?.get(1)
        val targetClass = if (packageName.isNullOrEmpty()) className else "$packageName.$className"

        val method = nearestTestMethod(lines, caretLine)
        return if (method != null) {
            TestTarget(targetClass, method)
        } else {
            TestTarget(targetClass, null)
        }
    }

    fun markers(text: String): List<TestMarker> {
        val lines = text.split("\n")
        val classLine = lines.indexOfFirst { CLASS.containsMatchIn(it) }.takeIf { it >= 0 } ?: return emptyList()
        val className = CLASS.find(lines[classLine])?.groupValues?.get(1) ?: return emptyList()
        val packageName = PACKAGE.find(text)?.groupValues?.get(1)
        val targetClass = if (packageName.isNullOrEmpty()) className else "$packageName.$className"

        val markers = mutableListOf(TestMarker(classLine, TestTarget(targetClass, null)))
        lines.forEachIndexed { index, line ->
            val methodName = testMethodName(lines, index) ?: return@forEachIndexed
            markers += TestMarker(index, TestTarget(targetClass, methodName))
        }
        return markers
    }

    /**
     * The Gradle project path for [file], e.g. `:plugins:lang-kotlin`. It is the path from
     * [root] to the nearest ancestor directory that holds a build file; `""` for the root.
     */
    fun gradleProjectPath(file: Path, root: Path): String? {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val absoluteFile = file.toAbsolutePath().normalize()
        if (!absoluteFile.startsWith(absoluteRoot)) return null
        var directory: Path? = absoluteFile.parent
        while (directory != null && directory.startsWith(absoluteRoot)) {
            if (hasBuildFile(directory)) {
                if (directory == absoluteRoot) return ""
                return ":" + absoluteRoot.relativize(directory).joinToString(":") { it.toString() }
            }
            if (directory == absoluteRoot) break
            directory = directory.parent
        }
        return null
    }

    private fun hasBuildFile(directory: Path): Boolean =
        Files.exists(directory.resolve("build.gradle.kts")) || Files.exists(directory.resolve("build.gradle"))

    private fun classNameAt(lines: List<String>, caretLine: Int): String? {
        for (index in caretLine downTo 0) {
            CLASS.find(lines[index])?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun nearestTestMethod(lines: List<String>, caretLine: Int): String? {
        for (index in caretLine downTo 0) {
            testMethodName(lines, index)?.let { return it }
        }
        return null
    }

    /** The name of the `@Test fun` declared on [index], or null. */
    private fun testMethodName(lines: List<String>, index: Int): String? {
        val match = METHOD.find(lines[index]) ?: return null
        if (!hasTestAnnotationAbove(lines, index)) return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private fun hasTestAnnotationAbove(lines: List<String>, index: Int): Boolean {
        var current = index - 1
        while (current >= 0) {
            val line = lines[current].trim()
            if (!line.startsWith("@")) return false
            if (TEST_ANNOTATION.containsMatchIn(line)) return true
            current--
        }
        return false
    }

    private fun lineIndexAt(text: String, offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        var line = 0
        for (index in 0 until clamped) if (text[index] == '\n') line++
        return line
    }
}
