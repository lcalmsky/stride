package stride.app.navigation

import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.search.FindInPathScanner
import stride.core.vfs.VFile
import stride.search.Declaration
import stride.search.DeclarationIndex
import stride.search.DeclarationKind
import stride.search.DeclarationParser
import stride.search.NameContext
import stride.search.OutlineKind
import stride.search.OutlineMember
import stride.search.SourceImport
import stride.search.TypeGraph
import stride.search.classesAt
import stride.search.memberAt
import java.nio.file.Path

/** The current editor text with the parses the hierarchy actions resolve names against. */
internal class HierarchyCaret(val editor: TextFileEditor) {
    val text: String = editor.value.text
    val fileName: String = editor.file.name
    val offset: Int = editor.value.selection.start.coerceIn(0, text.length)
    val packageName: String? = DeclarationParser.packageOf(text)
    val imports: List<SourceImport> = DeclarationParser.imports(fileName, text)
    val outline: List<OutlineMember> = DeclarationParser.outline(fileName, text)
    val identifier: String? = identifierAt(text, fileName, offset)

    fun contextAt(position: Int = offset): NameContext = NameContext(
        file = editor.file,
        packageName = packageName,
        imports = imports,
        enclosingClasses = outline.classesAt(position).map { it.name },
    )

    /** The declaration whose name holds the caret, if any. */
    fun nameAtCaret(): OutlineMember? =
        outline.filter { offset >= it.nameStart && offset <= it.nameEnd }.minByOrNull { it.nameEnd - it.nameStart }

    fun declaration(member: OutlineMember, session: ProjectSession, snapshot: List<Declaration>): Declaration =
        declarationForMember(
            snapshot = snapshot,
            file = editor.file,
            relativePath = relativePath(session, editor.file),
            fileName = fileName,
            text = text,
            member = member,
            packageName = packageName,
        )
}

/**
 * The snapshot declaration matching an editor member by path, name, container and kind, nearest
 * to its line; a member the index has not seen yet (an unsaved declaration) is built from the text.
 */
internal fun declarationForMember(
    snapshot: List<Declaration>,
    file: VFile,
    relativePath: String,
    fileName: String,
    text: String,
    member: OutlineMember,
    packageName: String?,
): Declaration {
    val path = file.path.toAbsolutePath().normalize()
    val kind = member.declarationKind()
    val match = snapshot
        .filter {
            it.name == member.name && it.kind == kind && it.container == member.container &&
                it.file.path.toAbsolutePath().normalize() == path
        }
        .minByOrNull { kotlin.math.abs(it.line - member.line) }
    if (match != null) return match
    val parsed = DeclarationParser.parse(fileName, text)
        .filter { it.name == member.name && it.container == member.container && it.kind == kind }
        .minByOrNull { kotlin.math.abs(it.line - member.line) }
    return Declaration(
        name = member.name,
        kind = kind,
        file = file,
        relativePath = relativePath,
        line = member.line,
        container = member.container,
        packageName = packageName,
        isPrivate = parsed?.isPrivate ?: false,
        column = member.nameStart - lineStart(text, lineOf(text, member.nameStart)),
        supertypes = parsed?.supertypes.orEmpty(),
        isInterface = parsed?.isInterface ?: false,
        isOverride = parsed?.isOverride ?: member.isOverride,
    )
}

internal fun OutlineMember.declarationKind(): DeclarationKind =
    if (kind == OutlineKind.CLASS) DeclarationKind.CLASS else DeclarationKind.SYMBOL

/** Disk text under the call hierarchy's size rule, decoded leniently like Find in Path. */
internal object HierarchyText {
    const val MAX_FILE_SIZE: Long = 1024L * 1024L

    fun read(path: Path): String? {
        val file = VFile(path)
        if (file.length() > MAX_FILE_SIZE) return null
        return FindInPathScanner.readText(file)
    }

    /** The open editor's text for [path] (unsaved edits included), else the disk text. */
    fun read(state: AppState, path: Path): String? =
        state.textOfOpenEditor(path) ?: read(path)
}

/** The 0-based offset of the first character of [line]. */
internal fun lineStart(text: String, line: Int): Int {
    var start = 0
    var current = 0
    while (current < line) {
        val newline = text.indexOf('\n', start)
        if (newline < 0) return text.length
        start = newline + 1
        current++
    }
    return start
}

/** The 0-based line holding [offset]. */
internal fun lineOf(text: String, offset: Int): Int {
    var line = 0
    for (index in 0 until offset.coerceIn(0, text.length)) if (text[index] == '\n') line++
    return line
}

/** The name offset [line] and [column] point at, falling back to the line's first match of [name]. */
internal fun offsetOfName(text: String, line: Int, column: Int, name: String): Int {
    val start = lineStart(text, line.coerceAtLeast(0))
    if (start >= text.length) return text.length
    val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    val lineText = text.substring(start, end)
    val candidate = start + column.coerceIn(0, lineText.length)
    if (text.startsWith(name, candidate)) return candidate
    val found = lineText.indexOf(name)
    return if (found >= 0) start + found else candidate
}

private var cachedSnapshot: List<Declaration>? = null
private var cachedGraph: TypeGraph? = null

/** One [TypeGraph] per unchanged index snapshot; the same snapshot list is reused while it is current. */
@Synchronized
internal fun typeGraphOf(index: DeclarationIndex): TypeGraph {
    val snapshot = index.snapshot()
    val graph = cachedGraph
    if (graph != null && snapshot === cachedSnapshot) return graph
    val created = TypeGraph(snapshot, index::imports)
    cachedGraph = created
    cachedSnapshot = snapshot
    return created
}
