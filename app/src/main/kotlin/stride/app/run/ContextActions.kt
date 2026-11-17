package stride.app.run

import stride.lang.FileTypeRegistry
import stride.lang.IntentionAction
import stride.lang.IntentionCategory
import stride.lang.IntentionContext
import stride.lang.IntentionProvider
import stride.lang.LanguageFileType
import stride.lang.SymbolLookup
import stride.lang.TextEdit

enum class ContextActionKind { QUICK_FIX, INTENTION, RUN_MAIN, RUN_TEST, RUN_TEST_CLASS, RERUN, STOP }

enum class ContextActionGroup(val title: String) {
    QUICK_FIX("Quick fixes"),
    INTENTION("Intentions"),
    RUN("Run"),
}

data class ContextAction(
    val kind: ContextActionKind,
    val label: String,
    val edits: List<TextEdit> = emptyList(),
) {
    val group: ContextActionGroup
        get() = when (kind) {
            ContextActionKind.QUICK_FIX -> ContextActionGroup.QUICK_FIX
            ContextActionKind.INTENTION -> ContextActionGroup.INTENTION
            else -> ContextActionGroup.RUN
        }
}

/** The ⌥↩ menu entries for one caret position, ordered so the first is the default. */
object ContextActions {
    fun collect(
        text: String,
        fileName: String,
        caretOffset: Int,
        rerunName: String? = null,
        runningName: String? = null,
        intentions: List<IntentionAction> = emptyList(),
    ): List<ContextAction> {
        val main = MainLocator.locate(text, fileName)
        val caretLine = MainLocator.lineAt(text, caretOffset)
        val insideMain = main != null && caretLine in main.line..main.endLine
        val mainAction = main?.let {
            ContextAction(ContextActionKind.RUN_MAIN, "Run '${simpleName(it.className)}.main()'")
        }
        val test = TestLocator.locate(text, caretOffset)
        val testAction = test?.let {
            if (it.methodName != null) {
                ContextAction(ContextActionKind.RUN_TEST, "Run '${simpleName(it.className)}.${it.methodName}'")
            } else {
                ContextAction(ContextActionKind.RUN_TEST_CLASS, "Run tests in '${simpleName(it.className)}'")
            }
        }
        val head = if (insideMain) {
            listOfNotNull(mainAction, testAction)
        } else {
            listOfNotNull(testAction, mainAction)
        }
        val tail = buildList {
            if (rerunName != null) add(ContextAction(ContextActionKind.RERUN, "Rerun '$rerunName'"))
            if (runningName != null) add(ContextAction(ContextActionKind.STOP, "Stop '$runningName'"))
        }
        return intentions.filter { it.category == IntentionCategory.QUICK_FIX }.map { it.toContextAction() } +
            intentions.filter { it.category == IntentionCategory.INTENTION }.map { it.toContextAction() } +
            head +
            tail
    }

    /** The language intentions and quick fixes that apply at [caretOffset]. */
    fun intentionsAt(
        text: String,
        fileName: String,
        caretOffset: Int,
        symbols: SymbolLookup,
    ): List<IntentionAction> {
        val language = (FileTypeRegistry.getFileType(fileName) as? LanguageFileType)?.language ?: return emptyList()
        return IntentionProvider.collect(language, IntentionContext(text, caretOffset, fileName, symbols))
    }

    private fun IntentionAction.toContextAction(): ContextAction = ContextAction(
        kind = if (category == IntentionCategory.QUICK_FIX) ContextActionKind.QUICK_FIX else ContextActionKind.INTENTION,
        label = text,
        edits = edits,
    )

    private fun simpleName(className: String): String = className.substringAfterLast('.')
}
