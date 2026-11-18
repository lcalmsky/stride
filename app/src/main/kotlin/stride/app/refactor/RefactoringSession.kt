package stride.app.refactor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.editor.openRename
import stride.app.generate.MemberChooseRequest
import stride.app.keys.KeyContext
import stride.app.navigation.ChooserRow
import stride.lang.FileTypeRegistry
import stride.lang.LanguageFileType
import stride.lang.NameCheck
import stride.lang.RefactoringContext
import stride.lang.RefactoringEdit
import stride.lang.RefactoringKind
import stride.lang.RefactoringOption
import stride.lang.RefactoringProvider
import stride.lang.RefactoringStep

/** One `Refactor This` entry: Rename runs its own popup, a prepared kind keeps its ready step. */
internal sealed interface RefactorThisEntry {
    data object Rename : RefactorThisEntry

    class Prepared(val kind: RefactoringKind, val step: RefactoringStep) : RefactorThisEntry
}

/** The open `Refactor This` popup and the text and selection its steps were prepared on. */
internal class RefactorThisRequest(
    val editor: TextFileEditor,
    val preparedText: String,
    val preparedSelection: TextRange,
    val rows: List<ChooserRow<RefactorThisEntry>>,
)

/**
 * The open chooser of a `Choose` step; [restoreHighlights] is what the editor highlighted before
 * the popup opened, put back when it closes.
 */
internal class RefactoringChooseRequest(
    val editor: TextFileEditor,
    val title: String,
    val preparedText: String,
    val preparedSelection: TextRange,
    val step: RefactoringStep.Choose,
    val restoreHighlights: List<IntRange>,
)

/** The open name popup of an `AskName` step; [errorMessage] is the last rejected name's reason. */
internal class RefactoringNameRequest(
    val editor: TextFileEditor,
    val title: String,
    val preparedText: String,
    val preparedSelection: TextRange,
    val step: RefactoringStep.AskName,
) {
    var errorMessage: String? by mutableStateOf(null)
}

/** Prepares [kind] with the providers of the editor's file language, if it has one. */
internal fun prepareWithProviders(editor: TextFileEditor, kind: RefactoringKind): RefactoringStep {
    val language = (FileTypeRegistry.getFileType(editor.file.name) as? LanguageFileType)?.language
    val value = editor.value
    return RefactoringProvider.prepare(
        language,
        kind,
        RefactoringContext(value.text, value.selection.min, value.selection.max, editor.file.name),
    )
}

/** Shows [step] or follows it when it needs no input; `true` when the action was handled. */
internal fun followStep(
    state: AppState,
    editor: TextFileEditor,
    title: String,
    preparedText: String,
    preparedSelection: TextRange,
    step: RefactoringStep,
): Boolean = when (step) {
    is RefactoringStep.Refused -> {
        state.statusMessage = step.reason
        true
    }
    is RefactoringStep.Apply ->
        applyRefactoringEdit(state, editor, title, preparedText, preparedSelection, step.edit)
    is RefactoringStep.ChooseMembers -> {
        state.memberChoose = MemberChooseRequest(editor, title, preparedText, preparedSelection, step)
        state.focus = KeyContext.CONTEXT_POPUP
        state.statusMessage = null
        true
    }
    is RefactoringStep.AskName -> {
        state.refactoringName = RefactoringNameRequest(editor, title, preparedText, preparedSelection, step)
        state.focus = KeyContext.CONTEXT_POPUP
        state.statusMessage = null
        true
    }
    is RefactoringStep.Choose -> {
        if (step.options.size == 1) {
            followStep(state, editor, title, preparedText, preparedSelection, step.options[0].next())
        } else {
            state.refactoringChoose = RefactoringChooseRequest(
                editor = editor,
                title = title,
                preparedText = preparedText,
                preparedSelection = preparedSelection,
                step = step,
                restoreHighlights = editor.usageHighlights,
            )
            editor.usageHighlights = step.options.firstOrNull()?.highlights.orEmpty()
            state.focus = KeyContext.CONTEXT_POPUP
            state.statusMessage = null
            true
        }
    }
}

/** Applies [edit] over the text it was prepared on; a changed text or bad edit changes nothing. */
internal fun applyRefactoringEdit(
    state: AppState,
    editor: TextFileEditor,
    title: String,
    preparedText: String,
    preparedSelection: TextRange,
    edit: RefactoringEdit,
): Boolean {
    if (editor.value.text != preparedText) {
        state.statusMessage = "The file changed; run $title again"
        return true
    }
    // The undo step records the selection present when the edits land, so put the prepared one back first.
    restorePreparedSelection(editor, preparedText, preparedSelection)
    if (!editor.applyEdits(edit.edits, edit.caret)) {
        state.statusMessage = "$title failed: the edits overlap"
        return true
    }
    state.statusMessage = edit.message
    return true
}

/** Moves the chooser's highlight to [option]. */
internal fun selectRefactoringOption(state: AppState, option: RefactoringOption) {
    val request = state.refactoringChoose ?: return
    request.editor.usageHighlights = option.highlights
}

/** Follows the chosen option's next step and closes the chooser. */
internal fun chooseRefactoringOption(state: AppState, option: RefactoringOption): Boolean {
    val request = state.refactoringChoose ?: return false
    state.refactoringChoose = null
    request.editor.usageHighlights = request.restoreHighlights
    return followStep(
        state,
        request.editor,
        request.title,
        request.preparedText,
        request.preparedSelection,
        option.next(),
    )
}

/** Closes the chooser, puts back the highlights it replaced and restores the prepared selection. */
internal fun closeRefactoringChoose(state: AppState) {
    val request = state.refactoringChoose ?: return
    state.refactoringChoose = null
    request.editor.usageHighlights = request.restoreHighlights
    restorePreparedSelection(request.editor, request.preparedText, request.preparedSelection)
}

/** Checks [name] with the step; an invalid name keeps the popup open and shows its reason. */
internal fun submitRefactoringName(state: AppState, name: String): Boolean {
    val request = state.refactoringName ?: return false
    return when (val check = request.step.complete(name)) {
        is NameCheck.Invalid -> {
            request.errorMessage = check.message
            true
        }
        is NameCheck.Valid -> {
            state.refactoringName = null
            applyRefactoringEdit(
                state,
                request.editor,
                request.title,
                request.preparedText,
                request.preparedSelection,
                check.edit,
            )
        }
    }
}

/** Closes the name popup without changing the editor and restores the prepared selection. */
internal fun closeRefactoringName(state: AppState) {
    val request = state.refactoringName ?: return
    state.refactoringName = null
    restorePreparedSelection(request.editor, request.preparedText, request.preparedSelection)
}

/** Runs the chosen `Refactor This` entry: Rename opens its popup, a prepared kind follows its step. */
internal fun chooseRefactorThis(state: AppState, entry: RefactorThisEntry): Boolean {
    val request = state.refactorThis ?: return false
    state.refactorThis = null
    return when (entry) {
        RefactorThisEntry.Rename -> {
            restorePreparedSelection(request.editor, request.preparedText, request.preparedSelection)
            openRename(state)
        }
        is RefactorThisEntry.Prepared -> followStep(
            state,
            request.editor,
            entry.kind.title,
            request.preparedText,
            request.preparedSelection,
            entry.step,
        )
    }
}

/** Closes the `Refactor This` popup without running an entry and restores the prepared selection. */
internal fun closeRefactorThis(state: AppState) {
    val request = state.refactorThis ?: return
    state.refactorThis = null
    restorePreparedSelection(request.editor, request.preparedText, request.preparedSelection)
}

/**
 * Puts [preparedSelection] back when the editor still holds [preparedText]. A popup that takes
 * focus collapses the selection; restoring it is what `Esc` and the undo step rely on.
 */
internal fun restorePreparedSelection(
    editor: TextFileEditor,
    preparedText: String,
    preparedSelection: TextRange,
) {
    if (editor.value.text != preparedText) return
    if (editor.value.selection == preparedSelection) return
    editor.updateValue(editor.value.copy(selection = preparedSelection))
}
