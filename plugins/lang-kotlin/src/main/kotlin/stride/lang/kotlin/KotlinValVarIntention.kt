package stride.lang.kotlin

import stride.lang.IntentionAction
import stride.lang.IntentionCategory
import stride.lang.IntentionContext
import stride.lang.IntentionProvider
import stride.lang.TextEdit

/** Flips the first `val` on the caret's line to `var`, or the reverse. */
class KotlinValVarIntention : IntentionProvider {
    override fun collect(context: IntentionContext): List<IntentionAction> {
        val text = context.text
        val caret = context.caretOffset.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', caret - 1) + 1
        val newline = text.indexOf('\n', caret)
        val lineEnd = if (newline < 0) text.length else newline
        val keywords = codeTokens(text).filter {
            it.type == KotlinTokenTypes.KEYWORD && it.start in lineStart until lineEnd
        }
        val target = keywords.firstOrNull { it.text == "val" || it.text == "var" } ?: return emptyList()
        val previous = keywords.lastOrNull { it.start < target.start }?.text
        val following = keywords.filter { it.start > target.end }.map { it.text }
        if (previous == "when") return emptyList()
        if (following.any { it == "by" || it == "get" || it == "set" }) return emptyList()
        return when (target.text) {
            "val" -> if (previous == "const") emptyList() else listOf(change(target, "var"))
            "var" -> if (previous == "lateinit" || previous == "override") emptyList() else listOf(change(target, "val"))
            else -> emptyList()
        }
    }

    private fun change(token: CodeToken, replacement: String): IntentionAction =
        IntentionAction(
            "Change '${token.text}' to '$replacement'",
            IntentionCategory.INTENTION,
            listOf(TextEdit(token.start, token.end, replacement)),
        )
}
