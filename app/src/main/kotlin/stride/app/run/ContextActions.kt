package stride.app.run

enum class ContextActionKind { RUN_MAIN, RUN_TEST, RUN_TEST_CLASS, RERUN, STOP }

data class ContextAction(val kind: ContextActionKind, val label: String)

/** The ⌥↩ menu entries for one caret position, ordered so the first is the default. */
object ContextActions {
    fun collect(
        text: String,
        fileName: String,
        caretOffset: Int,
        rerunName: String? = null,
        runningName: String? = null,
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
        return head + tail
    }

    private fun simpleName(className: String): String = className.substringAfterLast('.')
}
