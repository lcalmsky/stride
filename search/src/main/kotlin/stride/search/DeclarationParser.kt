package stride.search

/**
 * A declaration inside one file, before [DeclarationIndex] attaches the file and path.
 *
 * [column] is the 0-based offset of the name in its line, inside the backticks of a quoted name.
 * [supertypes] lists, for a [DeclarationKind.CLASS], the types after `:` (Kotlin) or after
 * `extends` / `implements` (Java) as written, without type arguments, `?`, annotations, constructor
 * arguments or a `by` delegate, also when the header spans several lines:
 * `class A(x: Int) : b.Base<T>(x), Outer.Iface by d` gives `["b.Base", "Outer.Iface"]`.
 * [isInterface] marks a Kotlin or Java `interface`. [isOverride] marks a Kotlin member with the
 * `override` modifier, or a Java method annotated `@Override` on its own line or on the
 * annotation-only lines right above it.
 */
data class ParsedDeclaration(
    val name: String,
    val kind: DeclarationKind,
    val line: Int,
    val container: String? = null,
    val isPrivate: Boolean = false,
    val column: Int = 0,
    val supertypes: List<String> = emptyList(),
    val isInterface: Boolean = false,
    val isOverride: Boolean = false,
)

enum class OutlineKind { CLASS, FUNCTION, PROPERTY, INITIALIZER, CONSTRUCTOR }

/**
 * A member of one file with its text range, at any nesting of classes but never a local
 * declaration inside a body. [start] is the start of the declaration's first line (annotation lines
 * above it are not included), [nameStart] until [nameEnd] the name, and [end] the exclusive end:
 * just after the closing `}` of a block body, otherwise the start of the line of the next member at
 * the same level, the enclosing body's closing `}`, or the text end, whichever comes first.
 *
 * `init` blocks are [OutlineKind.INITIALIZER] named `init`; Kotlin secondary constructors are
 * [OutlineKind.CONSTRUCTOR] named `constructor` and Java constructors carry the class name.
 */
data class OutlineMember(
    val name: String,
    val kind: OutlineKind,
    val container: String?,
    val line: Int,
    val start: Int,
    val nameStart: Int,
    val nameEnd: Int,
    val end: Int,
    val isOverride: Boolean = false,
)

/** The innermost member whose `[start, end)` holds [offset], or `null`. */
fun List<OutlineMember>.memberAt(offset: Int): OutlineMember? =
    filter { offset >= it.start && offset < it.end }.minByOrNull { it.end - it.start }

/** The classes whose `[start, end)` holds [offset], innermost first. */
fun List<OutlineMember>.classesAt(offset: Int): List<OutlineMember> =
    filter { it.kind == OutlineKind.CLASS && offset >= it.start && offset < it.end }.sortedBy { it.end - it.start }

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

    /** The imports of a Kotlin or Java file in text order; empty for any other file. */
    fun imports(fileName: String, text: String): List<SourceImport> {
        val extension = extensionOf(fileName)
        if (extension !in SOURCE_EXTENSIONS) return emptyList()
        val kotlin = extension != JAVA_EXTENSION
        val result = ArrayList<SourceImport>()
        for (line in sanitizeSource(text).split('\n')) {
            val trimmed = line.trim().trimEnd('\r')
            if (trimmed.isEmpty() || trimmed.startsWith("@") || trimmed.startsWith("package ")) continue
            if (kotlin) {
                if (KOTLIN_DECLARATION_START.containsMatchIn(trimmed)) break
                val match = KOTLIN_IMPORT.matchEntire(trimmed) ?: continue
                result.add(sourceImport(match.groupValues[1], match.groupValues[2]))
            } else {
                if (JAVA_DECLARATION_START.containsMatchIn(trimmed)) break
                val match = JAVA_IMPORT.matchEntire(trimmed) ?: continue
                if (match.groupValues[1].isNotEmpty()) continue
                result.add(sourceImport(match.groupValues[2], ""))
            }
        }
        return result
    }

    /** The members of a Kotlin or Java file sorted by [OutlineMember.start]; empty for any other file. */
    fun outline(fileName: String, text: String): List<OutlineMember> = OutlineScanner(fileName, text).scan()

    private fun sourceImport(qualified: String, alias: String): SourceImport {
        val name = qualified.split('.').joinToString(".") { unquote(it.trim()) }
        return if (name.endsWith(".*")) {
            SourceImport(name.dropLast(2), isStar = true)
        } else {
            SourceImport(name, alias.takeIf { it.isNotEmpty() }?.let { unquote(it.trim()) })
        }
    }

    /**
     * The package declared by the first `package` statement of [text], or `null` when the file
     * has none. Kotlin and Java share the form; a trailing `;` is ignored.
     */
    fun packageOf(text: String): String? {
        var blockDepth = 0
        for (line in text.lineSequence()) {
            val code = StringBuilder(line.length)
            var index = 0
            while (index < line.length) {
                val c = line[index]
                when {
                    blockDepth > 0 -> when {
                        c == '/' && line.getOrNull(index + 1) == '*' -> {
                            blockDepth++
                            code.append("  ")
                            index += 2
                        }
                        c == '*' && line.getOrNull(index + 1) == '/' -> {
                            blockDepth--
                            code.append("  ")
                            index += 2
                        }
                        else -> {
                            code.append(' ')
                            index++
                        }
                    }
                    c == '/' && line.getOrNull(index + 1) == '/' -> break
                    c == '/' && line.getOrNull(index + 1) == '*' -> {
                        blockDepth = 1
                        code.append("  ")
                        index += 2
                    }
                    else -> {
                        code.append(c)
                        index++
                    }
                }
            }
            val trimmed = code.toString().trim()
            if (trimmed.isEmpty() || trimmed.startsWith("@")) continue
            val match = PACKAGE_STATEMENT.matchEntire(trimmed) ?: return null
            return match.groupValues[1].split('.').joinToString(".") { unquote(it) }
        }
        return null
    }

    private fun extensionOf(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    internal fun unquote(name: String): String =
        if (name.length >= 2 && name.startsWith('`') && name.endsWith('`')) name.substring(1, name.length - 1) else name
}

private val PACKAGE_STATEMENT = Regex("""package\s+([^\s;]+)\s*;?""")

private val KOTLIN_IMPORT = Regex("""import\s+([^\s;]+)(?:\s+as\s+(`[^`]+`|[\w${'$'}]+))?\s*;?""")

private val JAVA_IMPORT = Regex("""import\s+(static\s+)?([^\s;]+)\s*;""")

private val KOTLIN_DECLARATION_START = Regex(
    """^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|internal|open|abstract|final|sealed|data|""" +
        """enum|annotation|value|inner|inline|companion|expect|actual|external)\s+)*""" +
        """(?:class|interface|object|fun|val|var|typealias|constructor|init)\b"""
)

private val JAVA_DECLARATION_START = Regex(
    """^(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|static|final|abstract|sealed|""" +
        """non-sealed|strictfp)\s+)*(?:class|interface|enum|record|module)\b"""
)

private val PRIVATE_MODIFIER = Regex("""\bprivate\b""")

private val OVERRIDE_MODIFIER = Regex("""\boverride\b""")

private val JAVA_OVERRIDE = Regex("""@Override\b""")

private val INTERFACE_KEYWORD = Regex("""\binterface\s*$""")

private val ANNOTATION_ONLY_LINE = Regex("""^(?:@[\w${'$'}.]+(?:\([^()]*\))?\s*)+$""")

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
    private var pendingHeader: HeaderAccumulator? = null

    fun scan(text: String): List<ParsedDeclaration> {
        text.split('\n').forEachIndexed { index, line -> scanLine(line.trimEnd('\r'), index) }
        pendingHeader?.let { finalizeHeader(it) }
        pendingHeader = null
        return declarations
    }

    private fun scanLine(line: String, lineNumber: Int) {
        val code = sanitize(line)
        val content = code.trim()
        if (content.isEmpty()) return
        continueHeader(code, content)

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

    protected fun add(
        name: String,
        kind: DeclarationKind,
        line: Int,
        container: String?,
        isPrivate: Boolean = false,
        column: Int = 0,
        supertypes: List<String> = emptyList(),
        isInterface: Boolean = false,
        isOverride: Boolean = false,
    ): Int {
        declarations.add(
            ParsedDeclaration(
                DeclarationParser.unquote(name),
                kind,
                line,
                container,
                isPrivate,
                column,
                supertypes,
                isInterface,
                isOverride,
            )
        )
        return declarations.lastIndex
    }

    /**
     * `true` when `private` appears between the last `{`, `}`, `;` or line start before
     * [nameStart] and [nameStart] itself, i.e. among the declaration's own modifiers.
     */
    protected fun isPrivate(code: String, nameStart: Int): Boolean =
        PRIVATE_MODIFIER.containsMatchIn(modifierRegion(code, nameStart))

    /** `true` when `override` appears among the declaration's own modifiers. */
    protected fun isOverrideModifier(code: String, nameStart: Int): Boolean =
        OVERRIDE_MODIFIER.containsMatchIn(modifierRegion(code, nameStart))

    private fun modifierRegion(code: String, nameStart: Int): String {
        var boundary = 0
        for (index in nameStart - 1 downTo 0) {
            val c = code[index]
            if (c == '{' || c == '}' || c == ';') {
                boundary = index + 1
                break
            }
        }
        return code.substring(boundary, nameStart)
    }

    protected fun columnOf(token: String, tokenStart: Int): Int = if (token.startsWith('`')) tokenStart + 1 else tokenStart

    protected fun isInterfaceKeyword(code: String, nameStart: Int): Boolean =
        INTERFACE_KEYWORD.containsMatchIn(code.substring(0, nameStart))

    /** Starts reading a class header after its name so [supertypes] can be attached later. */
    protected fun startHeader(index: Int, remainder: String, kotlin: Boolean) {
        pendingHeader = HeaderAccumulator(index, StringBuilder(remainder), kotlin)
        evaluateHeader(pendingHeader!!)
    }

    private fun continueHeader(code: String, content: String) {
        val header = pendingHeader ?: return
        val continues = content.startsWith(":") || content.startsWith(",") || content.startsWith("{") ||
            (header.kotlin && content.startsWith("where")) ||
            (!header.kotlin && (
                content.startsWith("extends") || content.startsWith("implements") || content.startsWith("permits")
                ))
        if (header.tentative && !continues) {
            finalizeHeader(header)
            pendingHeader = null
            return
        }
        header.text.append(' ').append(code)
        evaluateHeader(header)
    }

    private fun evaluateHeader(header: HeaderAccumulator) {
        val analysis = if (header.kotlin) analyzeKotlinHeader(header.text.toString()) else analyzeJavaHeader(header.text.toString())
        if (analysis.done) {
            finalizeHeader(header)
            pendingHeader = null
        } else {
            header.tentative = !analysis.unclosed && !analysis.continues
        }
    }

    private fun finalizeHeader(header: HeaderAccumulator) {
        val analysis = if (header.kotlin) analyzeKotlinHeader(header.text.toString()) else analyzeJavaHeader(header.text.toString())
        declarations[header.index] = declarations[header.index].copy(supertypes = analysis.supertypes)
    }

    /** The annotation-only lines directly above the current one, for Java `@Override`. */
    protected fun javaOverrideAbove(annotationLines: List<String>): Boolean =
        annotationLines.any { JAVA_OVERRIDE.containsMatchIn(it) }

    protected fun isAnnotationOnlyLine(content: String): Boolean = ANNOTATION_ONLY_LINE.matches(content)

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
            val nameStart = match.groups[1]!!.range.first
            val index = add(
                name,
                DeclarationKind.CLASS,
                line,
                container,
                isPrivate(code, nameStart),
                columnOf(name, nameStart),
                isInterface = isInterfaceKeyword(code, nameStart),
            )
            startHeader(index, code.substring(match.range.last + 1), kotlin = true)
            return DeclaredClass(DeclarationParser.unquote(name), match.range.last + 1)
        }
        KOTLIN_TYPEALIAS.find(code)?.let { match ->
            val nameStart = match.groups[1]!!.range.first
            add(
                match.groupValues[1],
                DeclarationKind.CLASS,
                line,
                container,
                isPrivate(code, nameStart),
                columnOf(match.groupValues[1], nameStart),
            )
            return null
        }
        KOTLIN_FUN.find(code)?.let { match ->
            val nameStart = match.groups[1]!!.range.first
            add(
                match.groupValues[1],
                DeclarationKind.SYMBOL,
                line,
                container,
                isPrivate(code, nameStart),
                columnOf(match.groupValues[1], nameStart),
                isOverride = isOverrideModifier(code, nameStart),
            )
        } ?: KOTLIN_PROPERTY.find(code)?.let { match ->
            val nameStart = match.groups[1]!!.range.first
            add(
                match.groupValues[1],
                DeclarationKind.SYMBOL,
                line,
                container,
                isPrivate(code, nameStart),
                columnOf(match.groupValues[1], nameStart),
                isOverride = isOverrideModifier(code, nameStart),
            )
        }
        return null
    }

    override fun scanJava(code: String, line: Int, container: String?, membersAllowed: Boolean): DeclaredClass? =
        error("Kotlin scanner cannot scan Java")
}

private class JavaScanner : SourceScanner(kotlinSyntax = false) {
    private var pendingMethod: PendingMethod? = null
    private var pendingMethodParens = 0
    private val annotationLines = ArrayList<String>()

    override fun scanKotlin(code: String, line: Int, container: String?, membersAllowed: Boolean): DeclaredClass? =
        error("Java scanner cannot scan Kotlin")

    override fun scanJava(
        code: String,
        line: Int,
        container: String?,
        membersAllowed: Boolean,
    ): DeclaredClass? {
        try {
            return scanJavaLine(code, line, container, membersAllowed)
        } finally {
            val content = code.trim()
            if (isAnnotationOnlyLine(content)) annotationLines.add(content) else annotationLines.clear()
        }
    }

    private fun scanJavaLine(
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
                add(
                    pending.name,
                    DeclarationKind.SYMBOL,
                    pending.line,
                    pending.container,
                    pending.isPrivate,
                    pending.column,
                    isOverride = pending.isOverride,
                )
            }
            return null
        }
        JAVA_TYPE.find(code)?.let { match ->
            val name = match.groupValues[1]
            val nameStart = match.groups[1]!!.range.first
            val index = add(
                name,
                DeclarationKind.CLASS,
                line,
                container,
                isPrivate(code, nameStart),
                columnOf(name, nameStart),
                isInterface = isInterfaceKeyword(code, nameStart),
            )
            startHeader(index, code.substring(match.range.last + 1), kotlin = false)
            return DeclaredClass(name, match.range.last + 1)
        }
        if (!membersAllowed || container == null) return null
        JAVA_METHOD.find(code)?.let { match ->
            if (isValidType(match.groupValues[1])) {
                val nameStart = match.groups[2]!!.range.first
                add(
                    match.groupValues[2],
                    DeclarationKind.SYMBOL,
                    line,
                    container,
                    isPrivate(code, nameStart),
                    columnOf(match.groupValues[2], nameStart),
                    isOverride = javaOverride(code, nameStart),
                )
            }
        } ?: JAVA_FIELD.find(code)?.let { match ->
            if (isValidType(match.groupValues[1])) {
                val nameStart = match.groups[2]!!.range.first
                add(
                    match.groupValues[2],
                    DeclarationKind.SYMBOL,
                    line,
                    container,
                    isPrivate(code, nameStart),
                    columnOf(match.groupValues[2], nameStart),
                    isOverride = javaOverride(code, nameStart),
                )
            }
        } ?: startMethod(code, line, container)
        return null
    }

    private fun javaOverride(code: String, nameStart: Int): Boolean =
        JAVA_OVERRIDE.containsMatchIn(code.substring(0, nameStart)) || javaOverrideAbove(annotationLines)

    /** Remembers the head of a method signature that continues on later lines. */
    private fun startMethod(code: String, line: Int, container: String) {
        val match = JAVA_METHOD_START.find(code) ?: return
        if (!isValidType(match.groupValues[1])) return
        val nameStart = match.groups[2]!!.range.first
        pendingMethod = PendingMethod(
            match.groupValues[2],
            line,
            container,
            isPrivate(code, nameStart),
            columnOf(match.groupValues[2], nameStart),
            javaOverride(code, nameStart),
        )
        pendingMethodParens = 1 + parenBalance(code.substring((match.range.last + 1).coerceAtMost(code.length)))
    }

    private fun isValidType(type: String): Boolean = type.trim() !in JAVA_KEYWORDS

    private class PendingMethod(
        val name: String,
        val line: Int,
        val container: String,
        val isPrivate: Boolean,
        val column: Int,
        val isOverride: Boolean,
    )
}

private class HeaderAccumulator(val index: Int, val text: StringBuilder, val kotlin: Boolean) {
    var tentative: Boolean = false
}

private class HeaderAnalysis(
    val supertypes: List<String>,
    val done: Boolean,
    val unclosed: Boolean,
    val continues: Boolean,
)

private fun analyzeKotlinHeader(header: String): HeaderAnalysis {
    val scan = depthScan(header)
    val end = when {
        scan.brace >= 0 -> scan.brace
        scan.where >= 0 -> scan.where
        else -> header.length
    }
    val supertypes = if (scan.colon >= 0 && scan.colon < end) {
        splitSupertypes(header.substring(scan.colon + 1, end)).mapNotNull(::cleanSupertype)
    } else {
        emptyList()
    }
    val continues = scan.lastSignificant == ',' || scan.lastSignificant == ':' || scan.endsWithWhere
    return HeaderAnalysis(supertypes, scan.brace >= 0 || scan.where >= 0, scan.unclosed, continues)
}

private fun analyzeJavaHeader(header: String): HeaderAnalysis {
    val scan = depthScan(header)
    val end = if (scan.brace >= 0) scan.brace else header.length
    val supertypes = javaSupertypes(header.substring(0, end)).mapNotNull(::cleanSupertype)
    val continues = scan.lastSignificant == ',' ||
        scan.endsWithExtends || scan.endsWithImplements || scan.endsWithPermits
    return HeaderAnalysis(supertypes, scan.brace >= 0, scan.unclosed, continues)
}

private class DepthScan(
    val colon: Int,
    val brace: Int,
    val where: Int,
    val unclosed: Boolean,
    val lastSignificant: Char,
    val endsWithWhere: Boolean,
    val endsWithExtends: Boolean,
    val endsWithImplements: Boolean,
    val endsWithPermits: Boolean,
)

private fun depthScan(text: String): DepthScan {
    var angle = 0
    var paren = 0
    var bracket = 0
    var colon = -1
    var brace = -1
    var where = -1
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '<' -> angle++
            c == '>' -> if (angle > 0) angle--
            c == '(' -> paren++
            c == ')' -> if (paren > 0) paren--
            c == '[' -> bracket++
            c == ']' -> if (bracket > 0) bracket--
            c == '{' && angle == 0 && paren == 0 && bracket == 0 -> if (brace < 0) brace = i
            c == ':' && angle == 0 && paren == 0 && bracket == 0 -> if (colon < 0) colon = i
            else -> if (angle == 0 && paren == 0 && bracket == 0) {
                if (where < 0 && matchesWordAt(text, i, "where")) where = i
            }
        }
        i++
    }
    val trimmed = text.trimEnd()
    val last = trimmed.lastOrNull() ?: ' '
    return DepthScan(
        colon = colon,
        brace = brace,
        where = where,
        unclosed = angle != 0 || paren != 0 || bracket != 0,
        lastSignificant = last,
        endsWithWhere = endsWithWord(trimmed, "where"),
        endsWithExtends = endsWithWord(trimmed, "extends"),
        endsWithImplements = endsWithWord(trimmed, "implements"),
        endsWithPermits = endsWithWord(trimmed, "permits"),
    )
}

private fun endsWithWord(text: String, word: String): Boolean {
    if (!text.endsWith(word)) return false
    val before = text.getOrNull(text.length - word.length - 1) ?: ' '
    return !before.isLetterOrDigit() && before != '_' && before != '$'
}

private fun matchesWordAt(text: String, index: Int, word: String): Boolean {
    if (!text.startsWith(word, index)) return false
    val before = text.getOrNull(index - 1) ?: ' '
    val after = text.getOrNull(index + word.length) ?: ' '
    return !before.isLetterOrDigit() && before != '_' && before != '$' &&
        !after.isLetterOrDigit() && after != '_' && after != '$'
}

private fun splitSupertypes(body: String): List<String> {
    val items = ArrayList<String>()
    var angle = 0
    var paren = 0
    var bracket = 0
    var brace = 0
    var start = 0
    var i = 0
    while (i < body.length) {
        when (body[i]) {
            '<' -> angle++
            '>' -> if (angle > 0) angle--
            '(' -> paren++
            ')' -> if (paren > 0) paren--
            '[' -> bracket++
            ']' -> if (bracket > 0) bracket--
            '{' -> brace++
            '}' -> if (brace > 0) brace--
            ',' -> if (angle == 0 && paren == 0 && bracket == 0 && brace == 0) {
                items.add(body.substring(start, i))
                start = i + 1
            }
        }
        i++
    }
    if (start <= body.length) items.add(body.substring(start))
    return items
}

private fun cleanSupertype(item: String): String? {
    var rest = stripLeadingAnnotations(item)
    val by = topLevelWordIndex(rest, "by")
    if (by >= 0) rest = rest.substring(0, by)
    val name = cleanTypeName(rest)
    return name.takeIf { it.isNotEmpty() }
}

internal fun stripLeadingAnnotations(text: String): String {
    var rest = text.trimStart()
    while (rest.startsWith("@")) {
        var i = 1
        while (i < rest.length && (rest[i].isLetterOrDigit() || rest[i] == '_' || rest[i] == '$' || rest[i] == '.')) i++
        if (i < rest.length && rest[i] == '(') i = skipDelimited(rest, i, '(', ')')
        rest = rest.substring(i).trimStart()
    }
    return rest
}

internal fun cleanTypeName(raw: String): String {
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        when (c) {
            '<' -> i = skipDelimited(raw, i, '<', '>')
            '(' -> i = skipDelimited(raw, i, '(', ')')
            '[' -> i = skipDelimited(raw, i, '[', ']')
            '{' -> i = skipDelimited(raw, i, '{', '}')
            '?' -> i++
            ' ', '\t', '\n', '\r' -> i++
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

internal fun topLevelWordIndex(text: String, word: String): Int {
    var angle = 0
    var paren = 0
    var bracket = 0
    var brace = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '<' -> angle++
            c == '>' -> if (angle > 0) angle--
            c == '(' -> paren++
            c == ')' -> if (paren > 0) paren--
            c == '[' -> bracket++
            c == ']' -> if (bracket > 0) bracket--
            c == '{' -> brace++
            c == '}' -> if (brace > 0) brace--
            angle == 0 && paren == 0 && bracket == 0 && brace == 0 && matchesWordAt(text, i, word) -> return i
        }
        i++
    }
    return -1
}

private fun javaSupertypes(header: String): List<String> {
    val items = ArrayList<String>()
    var angle = 0
    var paren = 0
    var bracket = 0
    var segmentStart = -1
    var i = 0
    while (i < header.length) {
        val c = header[i]
        when {
            c == '<' -> angle++
            c == '>' -> if (angle > 0) angle--
            c == '(' -> paren++
            c == ')' -> if (paren > 0) paren--
            c == '[' -> bracket++
            c == ']' -> if (bracket > 0) bracket--
            c == '{' && angle == 0 && paren == 0 && bracket == 0 -> break
            angle == 0 && paren == 0 && bracket == 0 -> when {
                matchesWordAt(header, i, "extends") || matchesWordAt(header, i, "implements") -> {
                    if (segmentStart >= 0) items.addAll(splitSupertypes(header.substring(segmentStart, i)))
                    val word = if (matchesWordAt(header, i, "extends")) 7 else 10
                    segmentStart = i + word
                    i += word
                    continue
                }
                matchesWordAt(header, i, "permits") -> {
                    if (segmentStart >= 0) items.addAll(splitSupertypes(header.substring(segmentStart, i)))
                    segmentStart = -1
                    i += 7
                    continue
                }
            }
        }
        i++
    }
    if (segmentStart >= 0) items.addAll(splitSupertypes(header.substring(segmentStart)))
    return items
}

private fun skipDelimited(text: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var i = start
    while (i < text.length) {
        when (text[i]) {
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
        i++
    }
    return text.length
}

/** Blanks comments and string or character literals, keeping every offset and newline in place. */
internal fun sanitizeSource(text: String): String {
    val out = StringBuilder(text.length)
    var blockDepth = 0
    var inString = false
    var inRaw = false
    var inChar = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            blockDepth > 0 -> when {
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    blockDepth++
                    out.append("  ")
                    i += 2
                }
                c == '*' && text.getOrNull(i + 1) == '/' -> {
                    blockDepth--
                    out.append("  ")
                    i += 2
                }
                else -> {
                    out.append(blank(c))
                    i++
                }
            }
            inRaw -> if (text.startsWith("\"\"\"", i)) {
                inRaw = false
                out.append("   ")
                i += 3
            } else {
                out.append(blank(c))
                i++
            }
            inString -> when {
                c == '\\' -> {
                    out.append(blank(c)).append(blank(text.getOrNull(i + 1) ?: ' '))
                    i += 2
                }
                c == '"' -> {
                    inString = false
                    out.append(' ')
                    i++
                }
                else -> {
                    out.append(blank(c))
                    i++
                }
            }
            inChar -> when {
                c == '\\' -> {
                    out.append(blank(c)).append(blank(text.getOrNull(i + 1) ?: ' '))
                    i += 2
                }
                c == '\'' -> {
                    inChar = false
                    out.append(' ')
                    i++
                }
                else -> {
                    out.append(blank(c))
                    i++
                }
            }
            else -> when {
                c == '/' && text.getOrNull(i + 1) == '/' -> {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                }
                c == '/' && text.getOrNull(i + 1) == '*' -> {
                    blockDepth = 1
                    out.append("  ")
                    i += 2
                }
                text.startsWith("\"\"\"", i) -> {
                    inRaw = true
                    out.append("   ")
                    i += 3
                }
                c == '"' -> {
                    inString = true
                    out.append(' ')
                    i++
                }
                c == '\'' -> {
                    inChar = true
                    out.append(' ')
                    i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
    }
    return out.toString()
}

private fun blank(c: Char): Char = if (c == '\n') '\n' else ' '

private class OutlineDraft(
    val name: String,
    val kind: OutlineKind,
    val container: String?,
    val line: Int,
    val start: Int,
    val nameStart: Int,
    val nameEnd: Int,
    val isOverride: Boolean,
) {
    var bodyOpen: Int? = null
    var bodyClose: Int? = null
    var parent: OutlineDraft? = null
    var end: Int = -1
}

private val KOTLIN_INIT_BLOCK = Regex("""^\s*init\s*\{""")

private val KOTLIN_CONSTRUCTOR = Regex(
    """^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected|internal)\s+)*constructor\s*\("""
)

private val JAVA_CONSTRUCTOR = Regex(
    """^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|protected)\s+)*([A-Za-z_${'$'}][\w${'$'}]*)\s*\("""
)

private class OutlineScanner(private val fileName: String, private val text: String) {
    private val extension = fileName.substringAfterLast('.', "").lowercase()
    private val kotlin = extension == DeclarationParser.KOTLIN_EXTENSIONS ||
        extension == DeclarationParser.KOTLIN_SCRIPT_EXTENSION
    private val java = extension == DeclarationParser.JAVA_EXTENSION
    private val sanitized = sanitizeSource(text)
    private val starts = lineStartsOf(text)
    private val depthAtLineStart = IntArray(starts.size)

    fun scan(): List<OutlineMember> {
        if (!kotlin && !java) return emptyList()
        var depth = 0
        for (line in starts.indices) {
            depthAtLineStart[line] = depth
            var i = starts[line]
            val end = lineEnd(line)
            while (i < end) {
                when (sanitized[i]) {
                    '{' -> depth++
                    '}' -> depth = (depth - 1).coerceAtLeast(0)
                }
                i++
            }
        }

        val drafts = ArrayList<OutlineDraft>()
        for (declaration in DeclarationParser.parse(fileName, text)) {
            val line = declaration.line.coerceIn(0, starts.size - 1)
            val lineText = lineText(line)
            val draft = if (declaration.kind == DeclarationKind.CLASS) {
                classDraft(declaration, line, lineText)
            } else {
                symbolDraft(declaration, line, lineText)
            }
            if (draft != null) drafts.add(draft)
        }

        val classes = drafts.filter { it.kind == OutlineKind.CLASS }
        for (draft in classes) {
            val open = findClassBodyOpen(draft.nameEnd) ?: continue
            draft.bodyOpen = open
            draft.bodyClose = matchingDelimiter(sanitized, open, '{', '}')
        }
        for (draft in drafts) {
            if (draft.kind == OutlineKind.FUNCTION) {
                val open = findFunctionBodyOpen(draft.nameEnd) ?: continue
                draft.bodyOpen = open
                draft.bodyClose = matchingDelimiter(sanitized, open, '{', '}')
            }
        }
        addExtraMembers(drafts, classes)
        for (draft in drafts) {
            if (draft.kind == OutlineKind.CONSTRUCTOR && draft.bodyClose == null) {
                val open = findFunctionBodyOpen(draft.nameEnd) ?: continue
                draft.bodyOpen = open
                draft.bodyClose = matchingDelimiter(sanitized, open, '{', '}')
            }
        }

        for (draft in drafts) {
            draft.parent = drafts.filter {
                it !== draft && it.kind == OutlineKind.CLASS &&
                    it.bodyOpen != null && draft.nameStart > it.bodyOpen!! &&
                    draft.nameStart < (it.bodyClose ?: text.length)
            }.minByOrNull { (it.bodyClose ?: text.length) - it.bodyOpen!! }
        }

        for ((parent, group) in drafts.groupBy { it.parent }) {
            val sorted = group.sortedBy { it.start }
            for ((index, draft) in sorted.withIndex()) {
                if (draft.bodyClose != null) {
                    draft.end = draft.bodyClose!! + 1
                    continue
                }
                val next = sorted.getOrNull(index + 1)?.start ?: Int.MAX_VALUE
                val enclosing = parent?.bodyClose ?: text.length
                draft.end = minOf(next, enclosing, text.length)
            }
        }

        return drafts
            .filter { it.end > it.start }
            .map {
                OutlineMember(
                    it.name,
                    it.kind,
                    it.container,
                    it.line,
                    it.start,
                    it.nameStart,
                    it.nameEnd,
                    it.end,
                    it.isOverride,
                )
            }
            .sortedBy { it.start }
    }

    private fun classDraft(declaration: ParsedDeclaration, line: Int, lineText: String): OutlineDraft? {
        if (kotlin && KOTLIN_TYPEALIAS.find(lineText) != null) return null
        val match = if (kotlin) KOTLIN_CLASS.find(lineText) else JAVA_TYPE.find(lineText)
        val named = match?.takeIf { DeclarationParser.unquote(it.groupValues[1]) == declaration.name } ?: return null
        val token = named.groupValues[1]
        val nameStart = starts[line] + named.groups[1]!!.range.first
        return OutlineDraft(
            declaration.name,
            OutlineKind.CLASS,
            declaration.container,
            line,
            starts[line],
            nameStart,
            nameStart + token.length,
            false,
        )
    }

    private fun symbolDraft(declaration: ParsedDeclaration, line: Int, lineText: String): OutlineDraft? {
        val kind: OutlineKind
        val match: MatchResult?
        if (kotlin) {
            val function = namedMatch(KOTLIN_FUN, lineText, declaration.name, 1)
            val property = namedMatch(KOTLIN_PROPERTY, lineText, declaration.name, 1)
            kind = if (function != null) OutlineKind.FUNCTION else OutlineKind.PROPERTY
            match = function ?: property
        } else {
            val function = namedMatch(JAVA_METHOD, lineText, declaration.name, 2)
                ?: namedMatch(JAVA_METHOD_START, lineText, declaration.name, 2)
            val field = namedMatch(JAVA_FIELD, lineText, declaration.name, 2)
            kind = if (function != null) OutlineKind.FUNCTION else OutlineKind.PROPERTY
            match = function ?: field
        }
        if (match == null) return null
        val group = if (kotlin) 1 else 2
        val token = match.groupValues[group]
        val relativeStart = match.groups[group]!!.range.first
        val nameStart = starts[line] + relativeStart
        val isOverride = if (kotlin) {
            OVERRIDE_MODIFIER.containsMatchIn(modifierRegion(lineText, relativeStart))
        } else {
            javaOverrideAtLine(line, relativeStart)
        }
        return OutlineDraft(
            declaration.name,
            kind,
            declaration.container,
            line,
            starts[line],
            nameStart,
            nameStart + token.length,
            isOverride,
        )
    }

    private fun addExtraMembers(drafts: MutableList<OutlineDraft>, classes: List<OutlineDraft>) {
        val bodyOpens = drafts.mapNotNull { it.bodyOpen }.toSet()
        for (line in starts.indices) {
            val lineText = lineText(line)
            if (lineText.isBlank()) continue
            val container = innermostClassAt(starts[line], classes) ?: continue
            val bodyDepth = depthOf(container.bodyOpen!! + 1)
            if (depthAtLineStart[line] != bodyDepth) continue
            if (kotlin) {
                KOTLIN_INIT_BLOCK.find(lineText)?.let { match ->
                    val nameStart = starts[line] + match.value.indexOf("init")
                    val brace = starts[line] + match.value.lastIndexOf('{')
                    if (brace !in bodyOpens) {
                        drafts.add(
                            OutlineDraft(
                                "init",
                                OutlineKind.INITIALIZER,
                                container.name,
                                line,
                                starts[line],
                                nameStart,
                                nameStart + 4,
                                false,
                            ).also {
                                it.bodyOpen = brace
                                it.bodyClose = matchingDelimiter(sanitized, brace, '{', '}')
                            }
                        )
                    }
                    continue
                }
                KOTLIN_CONSTRUCTOR.find(lineText)?.let { match ->
                    val nameStart = starts[line] + match.value.indexOf("constructor")
                    if (nameStart <= container.bodyOpen!!) continue
                    drafts.add(
                        OutlineDraft(
                            "constructor",
                            OutlineKind.CONSTRUCTOR,
                            container.name,
                            line,
                            starts[line],
                            nameStart,
                            nameStart + "constructor".length,
                            false,
                        )
                    )
                }
            } else {
                if (matchesJavaInitializer(lineText)) {
                    val brace = starts[line] + lineText.indexOf('{')
                    if (brace !in bodyOpens) {
                        val name = if (lineText.trimStart().startsWith("static")) "static" else "init"
                        val nameStart = starts[line] + lineText.indexOf(name)
                        drafts.add(
                            OutlineDraft(
                                name,
                                OutlineKind.INITIALIZER,
                                container.name,
                                line,
                                starts[line],
                                nameStart,
                                nameStart + name.length,
                                false,
                            ).also {
                                it.bodyOpen = brace
                                it.bodyClose = matchingDelimiter(sanitized, brace, '{', '}')
                            }
                        )
                    }
                    continue
                }
                JAVA_CONSTRUCTOR.find(lineText)?.let { match ->
                    if (match.groupValues[1] != container.name) continue
                    val nameStart = starts[line] + match.groups[1]!!.range.first
                    if (nameStart <= container.bodyOpen!!) continue
                    drafts.add(
                        OutlineDraft(
                            container.name,
                            OutlineKind.CONSTRUCTOR,
                            container.name,
                            line,
                            starts[line],
                            nameStart,
                            nameStart + container.name.length,
                            javaOverrideAtLine(line, match.groups[1]!!.range.first),
                        )
                    )
                }
            }
        }
    }

    private fun matchesJavaInitializer(lineText: String): Boolean =
        lineText.trimStart().startsWith("static {") || lineText.trim() == "{"

    private fun innermostClassAt(offset: Int, classes: List<OutlineDraft>): OutlineDraft? =
        classes.filter {
            it.bodyOpen != null && offset > it.bodyOpen!! && offset < (it.bodyClose ?: text.length)
        }.minByOrNull { (it.bodyClose ?: text.length) - it.bodyOpen!! }

    private fun depthOf(offset: Int): Int {
        if (offset <= 0 || starts.isEmpty()) return 0
        val target = offset.coerceAtMost(text.length)
        val line = lineIndexOf(starts, target)
        var depth = depthAtLineStart[line]
        var i = starts[line]
        while (i < target) {
            when (sanitized[i]) {
                '{' -> depth++
                '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
            i++
        }
        return depth
    }

    private fun javaOverrideAtLine(line: Int, nameStart: Int): Boolean {
        if (JAVA_OVERRIDE.containsMatchIn(lineText(line).substring(0, nameStart))) return true
        var probe = line - 1
        while (probe >= 0) {
            val content = lineText(probe).trim()
            if (content.isEmpty()) {
                probe--
                continue
            }
            if (!ANNOTATION_ONLY_LINE.matches(content)) return false
            if (JAVA_OVERRIDE.containsMatchIn(content)) return true
            probe--
        }
        return false
    }

    private fun findClassBodyOpen(from: Int): Int? {
        var line = lineIndexOf(starts, from)
        var i = from
        var angle = 0
        var paren = 0
        var bracket = 0
        while (line < starts.size) {
            val end = lineEnd(line)
            while (i < end) {
                when (sanitized[i]) {
                    '<' -> angle++
                    '>' -> if (angle > 0) angle--
                    '(' -> paren++
                    ')' -> if (paren > 0) paren--
                    '[' -> bracket++
                    ']' -> if (bracket > 0) bracket--
                    '{' -> if (angle == 0 && paren == 0 && bracket == 0) return i
                }
                i++
            }
            if (angle != 0 || paren != 0 || bracket != 0) {
                line++
                if (line >= starts.size) return null
                i = starts[line]
                continue
            }
            if (!headerContinues(line)) return null
            line++
            if (line >= starts.size) return null
            i = starts[line]
        }
        return null
    }

    private fun findFunctionBodyOpen(from: Int): Int? {
        val open = sanitized.indexOf('(', from)
        if (open < 0) return null
        val close = matchingDelimiter(sanitized, open, '(', ')') ?: return null
        var line = lineIndexOf(starts, close)
        var i = close + 1
        var angle = 0
        var paren = 0
        var bracket = 0
        while (line < starts.size) {
            val end = lineEnd(line)
            while (i < end) {
                val c = sanitized[i]
                when {
                    c == '<' -> angle++
                    c == '>' -> if (angle > 0) angle--
                    c == '(' -> paren++
                    c == ')' -> if (paren > 0) paren--
                    c == '[' -> bracket++
                    c == ']' -> if (bracket > 0) bracket--
                    angle == 0 && paren == 0 && bracket == 0 && c == '{' -> return i
                    angle == 0 && paren == 0 && bracket == 0 && c == '=' -> return null
                    angle == 0 && paren == 0 && bracket == 0 && c == ';' -> return null
                }
                i++
            }
            if (angle != 0 || paren != 0 || bracket != 0) {
                line++
                if (line >= starts.size) return null
                i = starts[line]
                continue
            }
            if (!headerContinues(line)) return null
            line++
            if (line >= starts.size) return null
            i = starts[line]
        }
        return null
    }

    private fun headerContinues(line: Int): Boolean {
        val current = lineText(line).trim()
        val next = if (line + 1 < starts.size) lineText(line + 1).trim() else ""
        if (current.endsWith(",") || current.endsWith(":") || current.endsWith("->")) return true
        if (kotlin) {
            return next.startsWith(":") || next.startsWith(",") || next.startsWith("{") ||
                next.startsWith("=") || next.startsWith("where") || current.endsWith("where")
        }
        if (current.endsWith("throws")) return true
        return next.startsWith("{") ||
            next.startsWith("extends") || next.startsWith("implements") || next.startsWith("permits") ||
            next.startsWith("throws")
    }

    private fun namedMatch(regex: Regex, lineText: String, name: String, group: Int): MatchResult? =
        regex.findAll(lineText).firstOrNull { DeclarationParser.unquote(it.groupValues[group]) == name }

    private fun modifierRegion(code: String, nameStart: Int): String {
        var boundary = 0
        for (index in nameStart - 1 downTo 0) {
            if (code[index] == '{' || code[index] == '}' || code[index] == ';') {
                boundary = index + 1
                break
            }
        }
        return code.substring(boundary, nameStart)
    }

    private fun lineText(line: Int): String = sanitized.substring(starts[line], lineEnd(line))

    private fun lineEnd(line: Int): Int = if (line + 1 < starts.size) starts[line + 1] - 1 else text.length
}

private fun lineStartsOf(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts.add(0)
    for (i in text.indices) if (text[i] == '\n') starts.add(i + 1)
    return starts.toIntArray()
}

private fun lineIndexOf(starts: IntArray, offset: Int): Int {
    var low = 0
    var high = starts.size - 1
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (starts[mid] <= offset) low = mid else high = mid - 1
    }
    return low
}

internal fun matchingDelimiter(text: String, openIndex: Int, open: Char, close: Char): Int? {
    var depth = 0
    var i = openIndex
    while (i < text.length) {
        when (text[i]) {
            open -> depth++
            close -> {
                depth--
                if (depth == 0) return i
            }
        }
        i++
    }
    return null
}
