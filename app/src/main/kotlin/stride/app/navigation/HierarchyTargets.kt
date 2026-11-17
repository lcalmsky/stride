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
import stride.search.extensionReceiverAt
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
        line = lineOf(text, position),
        extensionReceiver = outline.extensionReceiverAt(position),
    )

    /** The declaration whose name holds the caret, if any. */
    fun nameAtCaret(): OutlineMember? =
        outline.filter { offset >= it.nameStart && offset <= it.nameEnd }.minByOrNull { it.nameEnd - it.nameStart }

    fun declaration(member: OutlineMember, session: ProjectSession, snapshot: List<Declaration>): Declaration =
        declarationForMember(
            declarations = snapshot,
            file = editor.file,
            relativePath = relativePath(session, editor.file),
            text = text,
            member = member,
            packageName = packageName,
        )
}

/**
 * The declaration of [member] in [declarations] by path, name, container, kind and line — the
 * overlay and the outline are parsed from the same text, so the line is the member's identity.
 * An outline member the parser does not record (`init`, a secondary constructor) is built from
 * the member itself.
 */
internal fun declarationForMember(
    declarations: List<Declaration>,
    file: VFile,
    relativePath: String,
    text: String,
    member: OutlineMember,
    packageName: String?,
): Declaration {
    val path = file.path.toAbsolutePath().normalize()
    val kind = member.declarationKind()
    val sameName = declarations.filter {
        it.name == member.name && it.kind == kind && it.container == member.container &&
            it.file.path.toAbsolutePath().normalize() == path
    }
    sameName.firstOrNull { it.line == member.line }?.let { return it }
    sameName.firstOrNull()?.let { return it }
    return Declaration(
        name = member.name,
        kind = kind,
        file = file,
        relativePath = relativePath,
        line = member.line,
        container = member.container,
        packageName = packageName,
        column = member.nameStart - lineStart(text, member.line),
        isOverride = member.isOverride,
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

/** One open editor's text, the part of the action sources that is not the index snapshot. */
internal class OpenEditorSource(val path: Path, val fileName: String, val text: String)

/** The index snapshot with the open editors' texts replacing their files, and the graph built from it. */
internal class HierarchyGraphState(
    val snapshot: List<Declaration>,
    val openEditors: List<OpenEditorSource>,
    val declarations: List<Declaration>,
    val graph: TypeGraph,
) {
    fun sameSources(snapshot: List<Declaration>, openEditors: List<OpenEditorSource>): Boolean =
        this.snapshot === snapshot && this.openEditors.size == openEditors.size &&
            this.openEditors.indices.all {
                this.openEditors[it].path == openEditors[it].path &&
                    this.openEditors[it].text === openEditors[it].text
            }
}

/**
 * The declarations and the graph the hierarchy actions search: the index snapshot with every
 * open editor's text replacing its file, so a declaration that is not saved yet takes part and
 * the lines of an edited buffer are the ones the actions open. Kept on [AppState] while the
 * snapshot list and the open texts stay the same.
 */
internal fun hierarchyGraphOf(
    state: AppState,
    session: ProjectSession,
    index: DeclarationIndex,
): HierarchyGraphState {
    val snapshot = index.snapshot()
    val openEditors = session.editors.openFiles
        .filterIsInstance<TextFileEditor>()
        .map { OpenEditorSource(it.file.path.toAbsolutePath().normalize(), it.file.name, it.value.text) }
        .sortedBy { it.path.toString() }
    state.hierarchyGraph?.let { cached -> if (cached.sameSources(snapshot, openEditors)) return cached }
    val created = buildHierarchyGraph(snapshot, openEditors, session.project.root, index)
    state.hierarchyGraph = created
    return created
}

private fun buildHierarchyGraph(
    snapshot: List<Declaration>,
    openEditors: List<OpenEditorSource>,
    root: Path,
    index: DeclarationIndex,
): HierarchyGraphState {
    val openPaths = openEditors.mapTo(HashSet()) { it.path.toString() }
    val importsByPath = HashMap<String, List<SourceImport>>()
    val declarations = ArrayList<Declaration>(snapshot.size)
    for (declaration in snapshot) {
        if (declaration.file.path.toAbsolutePath().normalize().toString() in openPaths) continue
        declarations.add(declaration)
    }
    for (source in openEditors) {
        importsByPath[source.path.toString()] = DeclarationParser.imports(source.fileName, source.text)
        if (!DeclarationParser.isSourceFile(source.fileName)) continue
        val relativePath = relativePathOf(root, source.path) ?: continue
        val file = VFile(source.path)
        val packageName = DeclarationParser.packageOf(source.text)
        for (parsed in DeclarationParser.parse(source.fileName, source.text)) {
            declarations.add(
                Declaration(
                    name = parsed.name,
                    kind = parsed.kind,
                    file = file,
                    relativePath = relativePath,
                    line = parsed.line,
                    container = parsed.container,
                    packageName = packageName,
                    isPrivate = parsed.isPrivate,
                    column = parsed.column,
                    supertypes = parsed.supertypes,
                    isInterface = parsed.isInterface,
                    isOverride = parsed.isOverride,
                ),
            )
        }
    }
    val graph = TypeGraph(declarations) { file ->
        importsByPath[file.path.toAbsolutePath().normalize().toString()] ?: index.imports(file)
    }
    return HierarchyGraphState(snapshot, openEditors, declarations, graph)
}

private fun relativePathOf(root: Path, path: Path): String? {
    val relative = runCatching { root.relativize(path) }.getOrNull() ?: return null
    if (relative.toString().isEmpty() || relative.any { it.toString() == ".." }) return null
    return relative.joinToString("/") { it.toString() }
}
