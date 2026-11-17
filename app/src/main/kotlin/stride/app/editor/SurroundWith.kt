package stride.app.editor

import androidx.compose.ui.text.TextRange
import stride.app.AppState
import stride.app.keys.KeyContext

/** One Surround With entry; [kind] decides the block the selected lines are put into. */
data class SurroundTemplate(
    val label: String,
    val kind: SurroundKind,
    /** The catch clause of the try templates, which differs between Kotlin and Java. */
    val catchHeader: String? = null,
)

enum class SurroundKind {
    IF, IF_ELSE, WHILE, DO_WHILE, FOR, TRY_CATCH, TRY_FINALLY, TRY_CATCH_FINALLY, RUN, SYNCHRONIZED, BLOCK,
}

/** The open Surround With popup: the editor it acts on and the templates its file type offers. */
data class SurroundRequest(val editor: TextFileEditor, val templates: List<SurroundTemplate>)

private const val EXCEPTION = "Exception"

/** Surround With templates per file type and the pure block construction they apply. */
object SurroundWith {
    const val UNIT: String = "    "
    const val NO_TEMPLATES_MESSAGE: String = "No surround templates for this file"

    private val KOTLIN: List<SurroundTemplate> = listOf(
        SurroundTemplate("if", SurroundKind.IF),
        SurroundTemplate("if / else", SurroundKind.IF_ELSE),
        SurroundTemplate("while", SurroundKind.WHILE),
        SurroundTemplate("do / while", SurroundKind.DO_WHILE),
        SurroundTemplate("for", SurroundKind.FOR),
        SurroundTemplate("try / catch", SurroundKind.TRY_CATCH, "catch (e: Exception)"),
        SurroundTemplate("try / finally", SurroundKind.TRY_FINALLY),
        SurroundTemplate("try / catch / finally", SurroundKind.TRY_CATCH_FINALLY, "catch (e: Exception)"),
        SurroundTemplate("run { }", SurroundKind.RUN),
    )

    private val JAVA: List<SurroundTemplate> = listOf(
        SurroundTemplate("if", SurroundKind.IF),
        SurroundTemplate("if / else", SurroundKind.IF_ELSE),
        SurroundTemplate("while", SurroundKind.WHILE),
        SurroundTemplate("do / while", SurroundKind.DO_WHILE),
        SurroundTemplate("for", SurroundKind.FOR),
        SurroundTemplate("try / catch", SurroundKind.TRY_CATCH, "catch (Exception e)"),
        SurroundTemplate("try / finally", SurroundKind.TRY_FINALLY),
        SurroundTemplate("try / catch / finally", SurroundKind.TRY_CATCH_FINALLY, "catch (Exception e)"),
        SurroundTemplate("synchronized", SurroundKind.SYNCHRONIZED),
        SurroundTemplate("{ }", SurroundKind.BLOCK),
    )

    fun templatesFor(extension: String?): List<SurroundTemplate> = when (extension?.lowercase()) {
        "kt", "kts" -> KOTLIN
        "java" -> JAVA
        else -> emptyList()
    }

    /**
     * Wraps the lines [selection] covers (the caret's line when it is collapsed) in [template].
     * The template header takes the first line's indentation, the body gains one [unit], and the
     * returned selection sits where the template can be typed into next.
     */
    fun surround(text: String, selection: TextRange, template: SurroundTemplate, unit: String = UNIT): EditResult {
        val starts = starts(text)
        val first = lineIndex(text, selection.min)
        var last = lineIndex(text, selection.max)
        if (selection.max > selection.min && last > first && selection.max == starts[last]) last--
        val rangeStart = starts[first]
        val rangeEnd = if (last + 1 < starts.size) starts[last + 1] else text.length
        val separator = lineSeparator(text, starts, first)
        val target = (first..last).map { lineText(text, starts, it) }
        val base = target.first().takeWhile { it == ' ' || it == '\t' }
        val keepBody = template.kind == SurroundKind.RUN || template.kind == SurroundKind.BLOCK
        val body = target.map { line ->
            when {
                line.isBlank() -> ""
                keepBody -> line
                else -> unit + line
            }
        }

        val out = ArrayList<String>()
        var markLine = 0
        var markStart = 0
        var markEnd = 0
        when (template.kind) {
            SurroundKind.IF -> {
                out += base + "if () {"
                out += body
                out += base + "}"
                markStart = (base + "if (").length
                markEnd = markStart
            }
            SurroundKind.IF_ELSE -> {
                out += base + "if () {"
                out += body
                out += base + "} else {"
                out += base + "}"
                markStart = (base + "if (").length
                markEnd = markStart
            }
            SurroundKind.WHILE -> {
                out += base + "while () {"
                out += body
                out += base + "}"
                markStart = (base + "while (").length
                markEnd = markStart
            }
            SurroundKind.DO_WHILE -> {
                out += base + "do {"
                out += body
                out += base + "} while ()"
                markLine = out.lastIndex
                markStart = out.last().length - 1
                markEnd = markStart
            }
            SurroundKind.FOR -> {
                out += base + "for () {"
                out += body
                out += base + "}"
                markStart = (base + "for (").length
                markEnd = markStart
            }
            SurroundKind.TRY_CATCH -> {
                out += base + "try {"
                out += body
                val catch = base + "} " + template.catchHeader.orEmpty() + " {"
                out += catch
                out += base + "}"
                markLine = out.size - 2
                markStart = catch.indexOf(EXCEPTION)
                markEnd = markStart + EXCEPTION.length
            }
            SurroundKind.TRY_FINALLY -> {
                out += base + "try {"
                out += body
                out += base + "} finally {"
                out += ""
                out += base + "}"
                markLine = out.size - 2
                markStart = 0
                markEnd = 0
            }
            SurroundKind.TRY_CATCH_FINALLY -> {
                out += base + "try {"
                out += body
                val catch = base + "} " + template.catchHeader.orEmpty() + " {"
                out += catch
                out += base + "} finally {"
                out += ""
                out += base + "}"
                markLine = out.size - 4
                markStart = catch.indexOf(EXCEPTION)
                markEnd = markStart + EXCEPTION.length
            }
            SurroundKind.RUN, SurroundKind.BLOCK -> {
                out += base + (if (template.kind == SurroundKind.RUN) "run {" else "{")
                out += body
                out += base + "}"
            }
            SurroundKind.SYNCHRONIZED -> {
                out += base + "synchronized () {"
                out += body
                out += base + "}"
                markStart = (base + "synchronized (").length
                markEnd = markStart
            }
        }

        val block = out.joinToString(separator)
        val blockStarts = starts(block)
        // The range now consumes the last line's terminator, so the block supplies its own.
        val terminator = if (last + 1 < starts.size) separator else ""
        val newText = text.substring(0, rangeStart) + block + terminator + text.substring(rangeEnd)
        val selectionInBlock = if (template.kind == SurroundKind.RUN || template.kind == SurroundKind.BLOCK) {
            val shift = out.first().length + separator.length
            TextRange(
                (selection.min + shift).coerceIn(0, newText.length),
                (selection.max + shift).coerceIn(0, newText.length),
            )
        } else {
            val start = rangeStart + blockStarts[markLine] + markStart
            if (markEnd > markStart) {
                TextRange(start, rangeStart + blockStarts[markLine] + markEnd)
            } else {
                TextRange(start)
            }
        }
        return EditResult(newText, selectionInBlock)
    }

    /** Opens Surround With for the selected editor, or reports why it cannot. */
    fun open(state: AppState): Boolean {
        val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
        val templates = templatesFor(editor.file.extension)
        when {
            editor.isReadOnly -> state.statusMessage = "File is read-only"
            templates.isEmpty() -> state.statusMessage = NO_TEMPLATES_MESSAGE
            else -> {
                state.surround = SurroundRequest(editor, templates)
                state.focus = KeyContext.CONTEXT_POPUP
                state.statusMessage = null
            }
        }
        return true
    }

    /** Applies [template] to the request's editor; `false` when the editor refused the edit. */
    fun apply(request: SurroundRequest, template: SurroundTemplate): Boolean {
        val editor = request.editor
        val result = surround(editor.value.text, editor.value.selection, template)
        return editor.applyResult(result)
    }

    private fun starts(text: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (index in text.indices) if (text[index] == '\n') starts.add(index + 1)
        return starts.toIntArray()
    }

    private fun lineEnd(text: String, starts: IntArray, line: Int): Int =
        if (line + 1 < starts.size) starts[line + 1] - 1 else text.length

    private fun lineSeparator(text: String, starts: IntArray, line: Int): String {
        if (line + 1 >= starts.size) return "\n"
        val carriageReturn = starts[line + 1] - 2
        return if (carriageReturn >= starts[line] && text[carriageReturn] == '\r') "\r\n" else "\n"
    }

    private fun lineText(text: String, starts: IntArray, line: Int): String =
        if (line in starts.indices) text.substring(starts[line], lineEnd(text, starts, line)).trimEnd('\r') else ""

    private fun lineIndex(text: String, offset: Int): Int {
        var line = 0
        for (index in 0 until offset.coerceIn(0, text.length)) if (text[index] == '\n') line++
        return line
    }
}

/** Opens the Surround With popup for the selected text editor. */
fun openSurroundWith(state: AppState): Boolean = SurroundWith.open(state)
