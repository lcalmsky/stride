package stride.search

/** A declaration inside one file, before [DeclarationIndex] attaches the file and path. */
data class ParsedDeclaration(
    val name: String,
    val kind: DeclarationKind,
    val line: Int,
    val container: String? = null,
)

/**
 * Regex-based declaration scanner for Kotlin and Java sources. The scan is line oriented and
 * best effort: it tracks brace depth to skip local declarations and to name the enclosing class,
 * and it ignores anything inside comments and string literals.
 */
object DeclarationParser {
    const val KOTLIN_EXTENSIONS: String = "kt"
    const val KOTLIN_SCRIPT_EXTENSION: String = "kts"
    const val JAVA_EXTENSION: String = "java"

    fun parse(fileName: String, text: String): List<ParsedDeclaration> = when (extensionOf(fileName)) {
        KOTLIN_EXTENSIONS, KOTLIN_SCRIPT_EXTENSION -> KotlinScanner().scan(text)
        JAVA_EXTENSION -> JavaScanner().scan(text)
        else -> emptyList()
    }

    fun isSourceFile(fileName: String): Boolean = extensionOf(fileName) in SOURCE_EXTENSIONS

    private fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    internal fun unquote(name: String): String =
        if (name.length >= 2 && name.startsWith('`') && name.endsWith('`')) name.substring(1, name.length - 1) else name
}

private val SOURCE_EXTENSIONS = setOf(
    DeclarationParser.KOTLIN_EXTENSIONS,
    DeclarationParser.KOTLIN_SCRIPT_EXTENSION,
    DeclarationParser.JAVA_EXTENSION,
)

private data class DeclaredClass(val name: String, val nameEnd: Int)

private class ContainerRef(val name: String, val bodyDepth: Int)

/** Shared line scanner: comments, string literals, brace depth and the enclosing class stack. */
private abstract class SourceScanner(private val kotlinSyntax: Boolean) {
    private val declarations = ArrayList<ParsedDeclaration>()
    private val containers = ArrayDeque<ContainerRef>()
    private var depth = 0
    private var blockCommentDepth = 0
    private var inString = false
    private var inRawString = false
    private var inChar = false
    private var pendingClass: String? = null
    private var pendingParens = 0

    fun scan(text: String): List<ParsedDeclaration> {
        text.split('\n').forEachIndexed { index, line -> scanLine(line.trimEnd('\r'), index) }
        return declarations
    }

    private fun scanLine(line: String, lineNumber: Int) {
        val code = sanitize(line)
        val content = code.trim()
        if (content.isEmpty()) return

        val container = containers.lastOrNull()?.name
        val membersAllowed = depth == 0 || containers.lastOrNull()?.bodyDepth == depth

        // A header whose parens already closed on an earlier line: only a leading brace can still
        // open its body, so anything else means the declaration had no body.
        if (pendingClass != null && pendingParens == 0 && !content.startsWith("{")) pendingClass = null

        val declared = if (pendingClass != null) {
            null
        } else if (kotlinSyntax) {
            scanKotlin(code, lineNumber, container, membersAllowed)
        } else {
            scanJava(code, lineNumber, container, membersAllowed)
        }

        var pushName = pendingClass ?: declared?.name
        val nameEnd = if (pendingClass != null) 0 else declared?.nameEnd ?: 0
        var pushed = false
        for (index in code.indices) {
            val c = code[index]
            val name = pushName
            if (name != null) {
                when {
                    c == '(' && index >= nameEnd -> pendingParens++
                    c == ')' && index >= nameEnd -> pendingParens--
                    // Braces inside the header parens belong to default values, not the body.
                    c == '{' && pendingParens <= 0 && index >= nameEnd -> {
                        depth++
                        containers.addLast(ContainerRef(name, depth))
                        pushed = true
                        pushName = null
                        pendingClass = null
                        pendingParens = 0
                        continue
                    }
                }
            }
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    while (containers.isNotEmpty() && depth < containers.last().bodyDepth) containers.removeLast()
                }
            }
        }
        if (!pushed) pendingClass = pushName
    }

    protected fun add(name: String, kind: DeclarationKind, line: Int, container: String?) {
        declarations.add(ParsedDeclaration(DeclarationParser.unquote(name), kind, line, container))
    }

    /** Scans one sanitized line; returns the class declared on it, if any. */
    protected abstract fun scanKotlin(
        code: String,
        line: Int,
        container: String?,
        membersAllowed: Boolean,
    ): DeclaredClass?

    protected abstract fun scanJava(
        code: String,
        line: Int,
        container: String?,
        membersAllowed: Boolean,
    ): DeclaredClass?

    private fun sanitize(line: String): String {
        val out = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            val c = line[index]
            if (blockCommentDepth > 0) {
                when {
                    c == '/' && line.getOrNull(index + 1) == '*' -> {
                        blockCommentDepth++
                        out.append("  ")
                        index += 2
                    }
                    c == '*' && line.getOrNull(index + 1) == '/' -> {
                        blockCommentDepth--
                        out.append("  ")
                        index += 2
                    }
                    else -> {
                        out.append(' ')
                        index++
                    }
                }
                continue
            }
            if (inRawString) {
                if (line.startsWith("\"\"\"", index)) {
                    inRawString = false
                    out.append("   ")
                    index += 3
                } else {
                    out.append(' ')
                    index++
                }
                continue
            }
            if (inString) {
                when {
                    c == '\\' -> {
                        out.append("  ")
                        index += 2
                    }
                    c == '"' -> {
                        inString = false
                        out.append(' ')
                        index++
                    }
                    else -> {
                        out.append(' ')
                        index++
                    }
                }
                continue
            }
            if (inChar) {
                when {
                    c == '\\' -> {
                        out.append("  ")
                        index += 2
                    }
                    c == '\'' -> {
                        inChar = false
                        out.append(' ')
                        index++
                    }
                    else -> {
                        out.append(' ')
                        index++
                    }
                }
                continue
            }
            when {
                c == '/' && line.getOrNull(index + 1) == '/' -> {
                    while (index < line.length) {
                        out.append(' ')
                        index++
                    }
                }
                c == '/' && line.getOrNull(index + 1) == '*' -> {
                    blockCommentDepth = 1
                    out.append("  ")
                    index += 2
                }
                line.startsWith("\"\"\"", index) -> {
                    inRawString = true
                    out.append("   ")
                    index += 3
                }
                c == '"' -> {
                    inString = true
                    out.append(' ')
                    index++
                }
                c == '\'' -> {
                    inChar = true
                    out.append(' ')
                    index++
                }
                else -> {
                    out.append(c)
                    index++
                }
            }
        }
        return out.toString()
    }

    protected fun parenBalance(code: String): Int {
        var balance = 0
        for (c in code) {
            when (c) {
                '(' -> balance++
                ')' -> balance--
            }
        }
        return balance
    }
}

private const val KOTLIN_MODIFIERS =
    "public|private|protected|internal|open|abstract|final|sealed|data|enum|annotation|value|inner|inline|" +
        "companion|expect|actual|external|fun"

private const val KOTLIN_PROPERTY_MODIFIERS =
    "public|private|protected|internal|open|abstract|final|override|lateinit|const|inline|" +
        "noinline|crossinline|suspend|tailrec|operator|infix|external|actual|expect|vararg"

private const val JAVA_TYPE_MODIFIERS =
    "public|private|protected|static|final|abstract|sealed|non-sealed|strictfp"

private const val JAVA_MEMBER_MODIFIERS =
    "public|private|protected|static|final|abstract|synchronized|native|default|strictfp"

private const val JAVA_FIELD_MODIFIERS =
    "public|private|protected|static|final|transient|volatile"

private const val JAVA_TYPE_PATTERN = """[\w${'$'}.\[\]]+(?:\s*<[^>]*>)?(?:\[\])*"""

private val KOTLIN_CLASS = Regex(
    """^\s*(?:@\w+(?:\([^)]*\))?\s+)*""" +
        """(?:(?:$KOTLIN_MODIFIERS)\s+)*(?:class|interface|object)\s+(`[^`]+`|[A-Za-z_]\w*)"""
)

private val KOTLIN_TYPEALIAS = Regex(
    """^\s*(?:(?:public|private|internal|expect|actual)\s+)*typealias\s+(`[^`]+`|[A-Za-z_]\w*)"""
)

private val KOTLIN_FUN = Regex("""\bfun\s+(?:<[^>]*>\s*)?.*?(`[^`]+`|[A-Za-z_]\w*)\s*\(""")

private val KOTLIN_PROPERTY = Regex(
    """(?:^|[\s;])(?:@\w+(?:\([^)]*\))?\s+)*""" +
        """(?:(?:$KOTLIN_PROPERTY_MODIFIERS)\s+)*(?:val|var)\s+(?:<[^>]*>\s+)?(`[^`]+`|[A-Za-z_]\w*)"""
)

private val JAVA_TYPE = Regex(
    """^\s*(?:@\w+(?:\([^)]*\))?\s+)*""" +
        """(?:(?:$JAVA_TYPE_MODIFIERS)\s+)*(?:class|interface|enum|record)\s+([A-Za-z_$][\w$]*)"""
)

private val JAVA_METHOD = Regex(
    """^\s*(?:@\w+(?:\([^)]*\))?\s+)*""" +
        """(?:(?:$JAVA_MEMBER_MODIFIERS)\s+)*(?:<[^>]+>\s*)?($JAVA_TYPE_PATTERN)\s+([A-Za-z_$][\w$]*)\s*""" +
        """\([^;{}]*\)\s*(?:throws\s+[\w$., ]+)?[{;]"""
)

/** The head of a method signature whose parameters and body may continue on later lines. */
private val JAVA_METHOD_START = Regex(
    """^\s*(?:@\w+(?:\([^)]*\))?\s+)*""" +
        """(?:(?:$JAVA_MEMBER_MODIFIERS)\s+)*(?:<[^>]+>\s*)?($JAVA_TYPE_PATTERN)\s+([A-Za-z_$][\w$]*)\s*\("""
)

private val JAVA_FIELD = Regex(
    """^\s*(?:@\w+(?:\([^)]*\))?\s+)*""" +
        """(?:(?:$JAVA_FIELD_MODIFIERS)\s+)*(?:<[^>]+>\s*)?($JAVA_TYPE_PATTERN)\s+([A-Za-z_$][\w$]*)\s*""" +
        """(?:=[^;]*)?;\s*$"""
)

private val JAVA_KEYWORDS = setOf(
    "if", "for", "while", "do", "switch", "case", "default", "else", "return", "throw", "new",
    "assert", "try", "catch", "finally", "synchronized", "yield", "import", "package", "break",
    "continue", "instanceof", "extends", "implements", "throws",
    // Without these, `public Sample(int x)` backtracks into type `public`, name `Sample`.
    "public", "private", "protected", "static", "final", "abstract", "native", "strictfp",
)

private class KotlinScanner : SourceScanner(kotlinSyntax = true) {
    override fun scanKotlin(
        code: String,
        line: Int,
        container: String?,
        membersAllowed: Boolean,
    ): DeclaredClass? {
        if (!membersAllowed) return null
        KOTLIN_CLASS.find(code)?.let { match ->
            val name = match.groupValues[1]
            add(name, DeclarationKind.CLASS, line, container)
            return DeclaredClass(DeclarationParser.unquote(name), match.range.last + 1)
        }
        KOTLIN_TYPEALIAS.find(code)?.let { match ->
            add(match.groupValues[1], DeclarationKind.CLASS, line, container)
            return null
        }
        KOTLIN_FUN.find(code)?.let { match ->
            add(match.groupValues[1], DeclarationKind.SYMBOL, line, container)
        } ?: KOTLIN_PROPERTY.find(code)?.let { match ->
            add(match.groupValues[1], DeclarationKind.SYMBOL, line, container)
        }
        return null
    }

    override fun scanJava(code: String, line: Int, container: String?, membersAllowed: Boolean): DeclaredClass? =
        error("Kotlin scanner cannot scan Java")
}

private class JavaScanner : SourceScanner(kotlinSyntax = false) {
    private var pendingMethod: PendingMethod? = null
    private var pendingMethodParens = 0

    override fun scanKotlin(code: String, line: Int, container: String?, membersAllowed: Boolean): DeclaredClass? =
        error("Java scanner cannot scan Kotlin")

    override fun scanJava(
        code: String,
        line: Int,
        container: String?,
        membersAllowed: Boolean,
    ): DeclaredClass? {
        val pending = pendingMethod
        if (pending != null) {
            pendingMethodParens += parenBalance(code)
            if (pendingMethodParens <= 0 && code.any { it == '{' || it == ';' }) {
                pendingMethod = null
                add(pending.name, DeclarationKind.SYMBOL, pending.line, pending.container)
            }
            return null
        }
        JAVA_TYPE.find(code)?.let { match ->
            val name = match.groupValues[1]
            add(name, DeclarationKind.CLASS, line, container)
            return DeclaredClass(name, match.range.last + 1)
        }
        if (!membersAllowed || container == null) return null
        JAVA_METHOD.find(code)?.let { match ->
            if (isValidType(match.groupValues[1])) add(match.groupValues[2], DeclarationKind.SYMBOL, line, container)
        } ?: JAVA_FIELD.find(code)?.let { match ->
            if (isValidType(match.groupValues[1])) add(match.groupValues[2], DeclarationKind.SYMBOL, line, container)
        } ?: startMethod(code, line, container)
        return null
    }

    /** Remembers the head of a method signature that continues on later lines. */
    private fun startMethod(code: String, line: Int, container: String) {
        val match = JAVA_METHOD_START.find(code) ?: return
        if (!isValidType(match.groupValues[1])) return
        pendingMethod = PendingMethod(match.groupValues[2], line, container)
        pendingMethodParens = 1 + parenBalance(code.substring((match.range.last + 1).coerceAtMost(code.length)))
    }

    private fun isValidType(type: String): Boolean = type.trim() !in JAVA_KEYWORDS

    private class PendingMethod(val name: String, val line: Int, val container: String)
}
