package stride.app.run

/**
 * Finds the runnable entry point of a Kotlin or Java file using only regular expressions
 * over the file text. Generated main methods (for example `@JvmStatic` inside an anonymous
 * object) are not supported.
 */
object MainLocator {
    /** [line] and [endLine] are 0-based and inclusive. */
    data class MainTarget(val className: String, val line: Int, val endLine: Int)

    private val PACKAGE = Regex("(?m)^\\s*package\\s+([\\w.]+)")
    private val FILE_JVM_NAME = Regex("@file\\s*:\\s*JvmName\\s*\\(\\s*\"([^\"]+)\"\\s*\\)")
    private val KOTLIN_MAIN = Regex("\\bfun\\s+main\\s*\\(")
    private val JAVA_MAIN = Regex("\\bstatic\\s+void\\s+main\\s*\\(")
    private val JVM_STATIC = Regex("@JvmStatic\\b")
    private val COMPANION_OBJECT = Regex("\\bcompanion\\s+object\\b")
    private val OBJECT = Regex("\\bobject\\s+(\\w+)")
    private val CLASS = Regex("\\bclass\\s+(\\w+)")

    /** The first entry point declared in [text], or null when the file has none. */
    fun locate(text: String, fileName: String): MainTarget? {
        val source = Source(text)
        val packageName = PACKAGE.find(text)?.groupValues?.get(1)
        val fileClass = FILE_JVM_NAME.find(text)?.groupValues?.get(1)
            ?: fileName.substringBeforeLast('.', fileName).replaceFirstChar { it.uppercase() } + "Kt"

        for (index in source.lines.indices) {
            val line = source.lines[index]
            if (KOTLIN_MAIN.containsMatchIn(line)) {
                locateKotlin(source, index, packageName, fileClass)?.let { return it }
            }
            if (JAVA_MAIN.containsMatchIn(line)) {
                locateJava(source, index, packageName)?.let { return it }
            }
        }
        return null
    }

    /** The line (0-based) holding [offset]. */
    fun lineAt(text: String, offset: Int): Int {
        val clamped = offset.coerceIn(0, text.length)
        var line = 0
        for (index in 0 until clamped) if (text[index] == '\n') line++
        return line
    }

    private fun locateKotlin(source: Source, index: Int, packageName: String?, fileClass: String): MainTarget? {
        val depth = source.startDepth[index]
        val className = if (depth == 0) {
            qualify(packageName, fileClass)
        } else {
            if (!hasJvmStatic(source, index)) return null
            val owner = enclosingObject(source, index) ?: return null
            qualify(packageName, owner)
        }
        return MainTarget(className, index, source.blockEnd(index, depth))
    }

    private fun locateJava(source: Source, index: Int, packageName: String?): MainTarget? {
        val depth = source.startDepth[index]
        for (candidate in index - 1 downTo 0) {
            if (source.startDepth[candidate] >= depth) continue
            val match = CLASS.find(source.lines[candidate]) ?: continue
            if (source.startDepth[candidate] != 0) return null
            return MainTarget(qualify(packageName, match.groupValues[1]), index, source.blockEnd(index, depth))
        }
        return null
    }

    /** The name of the object or class that declares the `main` on [index]. */
    private fun enclosingObject(source: Source, index: Int): String? {
        val depth = source.startDepth[index]
        for (candidate in index - 1 downTo 0) {
            if (source.startDepth[candidate] != depth - 1) continue
            val line = source.lines[candidate]
            if (COMPANION_OBJECT.containsMatchIn(line)) return enclosingClass(source, candidate)
            OBJECT.find(line)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** The class that holds the companion object declared on [index]. */
    private fun enclosingClass(source: Source, index: Int): String? {
        val depth = source.startDepth[index]
        for (candidate in index - 1 downTo 0) {
            if (source.startDepth[candidate] >= depth) continue
            CLASS.find(source.lines[candidate])?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun hasJvmStatic(source: Source, index: Int): Boolean {
        if (JVM_STATIC.containsMatchIn(source.lines[index])) return true
        var current = index - 1
        while (current >= 0) {
            val line = source.lines[current].trim()
            if (!line.startsWith("@")) return false
            if (JVM_STATIC.containsMatchIn(line)) return true
            current--
        }
        return false
    }

    private fun qualify(packageName: String?, className: String): String =
        if (packageName.isNullOrEmpty()) className else "$packageName.$className"

    /** The text with comment and string-literal contents blanked and its brace depths. */
    private class Source(text: String) {
        val lines: List<String>
        val startDepth: IntArray

        init {
            lines = mask(text).split("\n")
            startDepth = IntArray(lines.size)
            var depth = 0
            lines.forEachIndexed { index, line ->
                startDepth[index] = depth
                for (character in line) {
                    if (character == '{') depth++
                    if (character == '}') depth--
                }
            }
        }

        /** The last line of the block opened by the declaration on [index], or [index] when there is none. */
        fun blockEnd(index: Int, depth: Int): Int {
            var current = depth
            for (line in index until lines.size) {
                // A declaration at the same depth before any brace means no block was opened.
                if (line > index && current == depth && startDepth[line] <= depth) return index
                for (character in lines[line]) {
                    if (character == '{') current++
                    if (character == '}') {
                        current--
                        if (current == depth) return line
                    }
                }
            }
            return index
        }

        private fun mask(text: String): String {
            val output = StringBuilder(text.length)
            var offset = 0
            while (offset < text.length) {
                val character = text[offset]
                when {
                    character == '/' && text.getOrNull(offset + 1) == '/' -> {
                        while (offset < text.length && text[offset] != '\n') {
                            output.append(' ')
                            offset++
                        }
                    }
                    character == '/' && text.getOrNull(offset + 1) == '*' -> {
                        while (offset < text.length && !(text[offset] == '*' && text.getOrNull(offset + 1) == '/')) {
                            output.append(if (text[offset] == '\n') '\n' else ' ')
                            offset++
                        }
                        repeat(2) {
                            if (offset < text.length) output.append(' ')
                            offset++
                        }
                    }
                    character == '"' && text.startsWith("\"\"\"", offset) -> {
                        output.append("\"\"\"")
                        offset += 3
                        while (offset < text.length && !text.startsWith("\"\"\"", offset)) {
                            output.append(if (text[offset] == '\n') '\n' else ' ')
                            offset++
                        }
                        if (offset < text.length) {
                            output.append("\"\"\"")
                            offset += 3
                        }
                    }
                    character == '"' || character == '\'' -> {
                        val quote = character
                        output.append(quote)
                        offset++
                        while (offset < text.length && text[offset] != quote && text[offset] != '\n') {
                            if (text[offset] == '\\' && offset + 1 < text.length) {
                                output.append("  ")
                                offset += 2
                            } else {
                                output.append(' ')
                                offset++
                            }
                        }
                        if (offset < text.length && text[offset] == quote) {
                            output.append(quote)
                            offset++
                        }
                    }
                    else -> {
                        output.append(character)
                        offset++
                    }
                }
            }
            return output.toString()
        }
    }
}
