package stride.lang.kotlin.generate

import stride.lang.kotlin.refactor.KotlinCode

/** One generated member: its lines without the member indentation, and the caret inside it. */
internal class GeneratedMember(
    val lines: List<String>,
    val caretLine: Int,
    val caretColumn: Int,
)

internal sealed interface Placement {
    /** [text] replaces `[offset, end)`; [caret] is the offset in the text after the edit. */
    class At(val offset: Int, val text: String, val caret: Int, val end: Int = offset) : Placement

    class Refused(val reason: String) : Placement
}

private const val FOUR_SPACES = "    "

/** The indentation one step deeper than the class's own line. */
internal fun indentationStep(code: KotlinCode, klass: KtClazz): String {
    val classIndent = code.indentation(klass.keyword.start)
    val member = klass.members.firstOrNull()
    if (member != null) {
        val memberIndent = code.indentation(member.start)
        val step = if (memberIndent.startsWith(classIndent)) memberIndent.removePrefix(classIndent) else memberIndent
        if (step.isNotEmpty()) return step
    }
    val firstIndented = firstIndentedLine(code)
    if (firstIndented.isNotEmpty()) return firstIndented
    return FOUR_SPACES
}

private fun firstIndentedLine(code: KotlinCode): String {
    var offset = 0
    while (offset < code.text.length) {
        val end = code.lineEnd(offset)
        val indent = code.indentation(offset)
        if (indent.isNotEmpty() && code.text.substring(offset + indent.length, end).isNotBlank()) return indent
        if (end >= code.text.length) break
        offset = end + 1
        if (offset > 0 && offset <= code.text.length && code.text[offset - 1] == '\r' &&
            offset < code.text.length && code.text[offset] == '\n'
        ) {
            offset++
        }
    }
    return ""
}

/** The reason G3 cannot place members here, or `null`. */
internal fun placementRefusal(code: KotlinCode, klass: KtClazz, caretOffset: Int): String? {
    if (klass.isEnum && klass.hasEnumEntries && klass.enumEntriesEnd == null) {
        return "Add ; after the enum entries first"
    }
    val body = klass.body ?: return bodyRefusal(code, klass)
    return null
}

private fun bodyRefusal(code: KotlinCode, klass: KtClazz): String? {
    val headerEnd = klass.headerEnd
    if (code.lineHoldsCodeAfter(headerEnd, allowSemicolon = true)) {
        return "Add a body to ${klass.writtenName()} first"
    }
    val next = code.code.firstOrNull { it.start >= headerEnd }
    if (next != null && next.text != "}" && next.text != ";") {
        val before = code.code.lastOrNull { it.end <= headerEnd }
        if (before == null || !code.splitsAfter(before, next)) {
            return "Add a body to ${klass.writtenName()} first"
        }
    }
    return null
}

/** Places [members] in [klass] according to G3. */
internal fun placeMembers(code: KotlinCode, klass: KtClazz, caretOffset: Int, members: List<GeneratedMember>): Placement {
    if (members.isEmpty()) return Placement.At(code.lineStart(caretOffset), "", caretOffset)
    val classIndent = code.indentation(klass.keyword.start)
    val step = indentationStep(code, klass)
    val memberIndent = classIndent + step
    val block = renderBlock(members, memberIndent, lineBreak(code))

    if (klass.isEnum && klass.hasEnumEntries && klass.enumEntriesEnd == null) {
        return Placement.Refused("Add ; after the enum entries first")
    }
    val body = klass.body
    if (body == null) {
        return placeWithoutBody(code, klass, caretOffset, members, block, classIndent)
    }

    var anchor = when {
        caretOffset < body.open.start -> body.end
        else -> {
            val member = klass.members.firstOrNull { caretOffset >= it.start && caretOffset <= it.end }
            if (member != null) {
                // The member may share its last line with the class's `}`; insert before that brace.
                val close = body.close?.start ?: code.text.length
                val lineAfter = afterLine(code, member.end)
                if (lineAfter > close) minOf(member.end, close) else lineAfter
            } else {
                val next = klass.members.firstOrNull { it.start > caretOffset }
                if (next != null) code.lineStart(next.start) else body.end
            }
        }
    }
    var anchorEnd = anchor
    if (caretOffset >= body.open.start && caretOffset < body.end &&
        klass.members.none { caretOffset >= it.start && caretOffset <= it.end } &&
        code.isBlank(code.lineStart(caretOffset), code.lineEnd(caretOffset))
    ) {
        anchor = code.lineStart(caretOffset)
        anchorEnd = afterLine(code, caretOffset)
    }
    val entriesEnd = klass.enumEntriesEnd
    if (entriesEnd != null && anchor <= entriesEnd) {
        anchor = afterLine(code, entriesEnd)
        anchorEnd = anchor
    }

    val text = assemble(code, anchor, anchorEnd, block.text, lineBreak(code))
    val caret = anchor + text.indexOf(block.text).coerceAtLeast(0) + block.caret
    return Placement.At(anchor, text, caret, anchorEnd)
}

private class BlockText(val text: String, val caret: Int)

private fun renderBlock(members: List<GeneratedMember>, indent: String, lb: String): BlockText {
    val lines = ArrayList<String>()
    var caret = 0
    var offset = 0
    for ((index, member) in members.withIndex()) {
        if (index > 0) {
            lines.add("")
            offset += lb.length
        }
        for ((lineIndex, line) in member.lines.withIndex()) {
            val rendered = if (line.isEmpty()) "" else indent + line
            if (member === members.first() && lineIndex == member.caretLine) {
                caret = offset + indent.length + member.caretColumn
            }
            lines.add(rendered)
            offset += rendered.length + lb.length
        }
    }
    return BlockText(lines.joinToString(lb), caret)
}

/** The target file's line separator: `\r\n` when the file has one anywhere, `\n` otherwise. */
private fun lineBreak(code: KotlinCode): String = if (code.text.contains("\r\n")) "\r\n" else "\n"

private fun assemble(code: KotlinCode, offset: Int, end: Int, block: String, lb: String): String {
    val bounded = offset.coerceIn(0, code.text.length)
    val boundedEnd = end.coerceIn(bounded, code.text.length)
    val before = code.text.substring(0, bounded)
    val after = code.text.substring(boundedEnd)
    val atLineStart = bounded == 0 || before.endsWith("\n")
    val prefix = when {
        !atLineStart -> lb
        previousLineBlank(code, bounded) -> ""
        before.trimEnd().endsWith("{") -> ""
        else -> lb
    }
    val trimmed = after.trimStart(' ', '\t')
    val suffix = when {
        after.isEmpty() -> ""
        trimmed.startsWith("}") -> ""
        after.startsWith(lb) -> ""
        else -> lb
    }
    return prefix + block + lb + suffix
}

private fun previousLineBlank(code: KotlinCode, offset: Int): Boolean {
    if (offset <= 0) return true
    val previousLineStart = code.lineStart(offset - 1)
    return code.isBlank(previousLineStart, offset - 1)
}

private fun afterLine(code: KotlinCode, offset: Int): Int {
    val end = code.lineEnd(offset)
    if (end >= code.text.length) return code.text.length
    if (code.text.startsWith("\r\n", end)) return end + 2
    return end + 1
}

private fun placeWithoutBody(
    code: KotlinCode,
    klass: KtClazz,
    caretOffset: Int,
    members: List<GeneratedMember>,
    block: BlockText,
    classIndent: String,
): Placement {
    bodyRefusal(code, klass)?.let { return Placement.Refused(it) }
    val headerEnd = klass.headerEnd
    val lb = lineBreak(code)
    val cursorInBlock = block.caret
    val text = " {" + lb + block.text + lb + classIndent + "}"
    return Placement.At(headerEnd, text, headerEnd + 2 + lb.length + cursorInBlock)
}
