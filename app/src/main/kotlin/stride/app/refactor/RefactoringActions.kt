package stride.app.refactor

import stride.app.AppState
import stride.app.editor.EditActions
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
import stride.app.keys.shortcutText
import stride.app.navigation.ChooserRow
import stride.lang.RefactoringKind
import stride.lang.RefactoringStep

/** ⌃T: the refactorings available at the caret, Rename included. */
fun openRefactorThis(
    state: AppState,
    keymap: Keymap = Keymap.forPlatform(KeyRouter.isMacOs()),
): Boolean {
    val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    val preparedText = editor.value.text
    val rows = ArrayList<ChooserRow<RefactorThisEntry>>()
    if (EditActions.wordAt(preparedText, editor.value.selection.start) != null) {
        rows += ChooserRow(
            "Rename…",
            RefactorThisEntry.Rename,
            shortcutText(keymap, ActionId.RENAME_ELEMENT),
        )
    }
    for (kind in KIND_ACTIONS.keys) {
        val step = state.prepareRefactoring(editor, kind)
        if (step !is RefactoringStep.Refused) {
            rows += ChooserRow(
                "${kind.title}…",
                RefactorThisEntry.Prepared(kind, step),
                shortcutText(keymap, KIND_ACTIONS.getValue(kind)),
            )
        }
    }
    if (rows.isEmpty()) {
        state.statusMessage = "No refactorings available at the caret"
        return true
    }
    state.refactorThis = RefactorThisRequest(editor, preparedText, rows)
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** ⌥⌘V, ⌥⌘M and ⌥⌘N: runs [kind] at the selected editor's selection. */
fun startRefactoring(state: AppState, kind: RefactoringKind): Boolean {
    val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    editor.removeSecondaryCarets()
    val preparedText = editor.value.text
    return followStep(state, editor, kind, preparedText, state.prepareRefactoring(editor, kind))
}

/** The action each kind runs as, so Refactor This shows the stroke the keymap binds it to. */
private val KIND_ACTIONS: Map<RefactoringKind, ActionId> = mapOf(
    RefactoringKind.INTRODUCE_VARIABLE to ActionId.INTRODUCE_VARIABLE,
    RefactoringKind.EXTRACT_METHOD to ActionId.EXTRACT_METHOD,
    RefactoringKind.INLINE to ActionId.INLINE,
)
