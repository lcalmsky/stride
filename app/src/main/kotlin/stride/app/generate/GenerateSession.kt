package stride.app.generate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.KeyRouter
import stride.app.keys.Keymap
import stride.app.keys.shortcutText
import stride.app.navigation.ChooserRow
import stride.app.refactor.applyRefactoringEdit
import stride.app.refactor.followStep
import stride.app.refactor.restorePreparedSelection
import stride.lang.FileTypeRegistry
import stride.lang.GenerateContext
import stride.lang.GenerateKind
import stride.lang.GenerateProvider
import stride.lang.LanguageFileType
import stride.lang.MemberOption
import stride.lang.NameCheck
import stride.lang.ProjectTypes
import stride.lang.RefactoringStep

/** One `Generate` entry: the kind and the step it prepared. */
internal class GeneratePrepared(val kind: GenerateKind, val step: RefactoringStep)

/** The open Generate popup and the text and selection its steps were prepared on. */
internal class GenerateRequest(
    val editor: TextFileEditor,
    val preparedText: String,
    val preparedSelection: TextRange,
    val rows: List<ChooserRow<GeneratePrepared>>,
)

/** The open member chooser of a `ChooseMembers` step; [errorMessage] is the last rejected choice's reason. */
internal class MemberChooseRequest(
    val editor: TextFileEditor,
    val title: String,
    val preparedText: String,
    val preparedSelection: TextRange,
    val step: RefactoringStep.ChooseMembers,
) {
    var errorMessage: String? by mutableStateOf(null)
}

/** ⌘N and ⌃↩: the generators available at the caret, in the documented menu order (G7). */
fun openGenerate(
    state: AppState,
    keymap: Keymap = Keymap.forPlatform(KeyRouter.isMacOs()),
): Boolean {
    val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    val preparedText = editor.value.text
    val preparedSelection = editor.value.selection
    val rows = ArrayList<ChooserRow<GeneratePrepared>>()
    for (kind in GENERATE_KINDS) {
        val step = state.prepareGenerate(editor, kind)
        if (step !is RefactoringStep.Refused) {
            rows += ChooserRow(
                "${kind.title}…",
                GeneratePrepared(kind, step),
                KIND_ACTIONS[kind]?.let { shortcutText(keymap, it) },
            )
        }
    }
    if (rows.isEmpty()) {
        state.statusMessage = "Nothing to generate here"
        return true
    }
    state.generate = GenerateRequest(editor, preparedText, preparedSelection, rows)
    state.focus = KeyContext.CONTEXT_POPUP
    state.statusMessage = null
    return true
}

/** ⌃O and ⌃I: prepares [kind] and follows it. */
fun startGenerate(state: AppState, kind: GenerateKind): Boolean {
    val editor = state.session?.editors?.selected as? TextFileEditor ?: return false
    if (editor.isReadOnly) {
        state.statusMessage = "File is read-only"
        return true
    }
    editor.removeSecondaryCarets()
    val preparedText = editor.value.text
    val preparedSelection = editor.value.selection
    return followStep(
        state,
        editor,
        kind.title,
        preparedText,
        preparedSelection,
        state.prepareGenerate(editor, kind),
    )
}

/** Follows the chosen Generate entry's prepared step. */
internal fun chooseGenerate(state: AppState, entry: GeneratePrepared): Boolean {
    val request = state.generate ?: return false
    state.generate = null
    return followStep(
        state,
        request.editor,
        entry.kind.title,
        request.preparedText,
        request.preparedSelection,
        entry.step,
    )
}

/** Closes the Generate popup without running an entry and restores the prepared selection. */
internal fun closeGenerate(state: AppState) {
    val request = state.generate ?: return
    state.generate = null
    restorePreparedSelection(request.editor, request.preparedText, request.preparedSelection)
}

/** Checks [members] with the step; an invalid choice keeps the popup open and shows its reason. */
internal fun submitMemberChoose(state: AppState, members: List<MemberOption>): Boolean {
    val request = state.memberChoose ?: return false
    return when (val check = request.step.complete(members)) {
        is NameCheck.Invalid -> {
            request.errorMessage = check.message
            true
        }
        is NameCheck.Valid -> {
            state.memberChoose = null
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

/** Closes the member chooser without changing the editor and restores the prepared selection. */
internal fun closeMemberChoose(state: AppState) {
    val request = state.memberChoose ?: return
    state.memberChoose = null
    restorePreparedSelection(request.editor, request.preparedText, request.preparedSelection)
}

/** Prepares [kind] with the providers of the editor's file language, if it has one. */
internal fun prepareGenerateWithProviders(
    editor: TextFileEditor,
    kind: GenerateKind,
    types: ProjectTypes?,
): RefactoringStep {
    val language = (FileTypeRegistry.getFileType(editor.file.name) as? LanguageFileType)?.language
    val value = editor.value
    return GenerateProvider.prepare(
        language,
        kind,
        GenerateContext(
            text = value.text,
            caretOffset = value.selection.start.coerceIn(0, value.text.length),
            fileName = editor.file.name,
            path = editor.file.path.toAbsolutePath().normalize().toString(),
            types = types,
        ),
    )
}

/** The kinds in the order the Generate menu lists them. */
private val GENERATE_KINDS = listOf(
    GenerateKind.SECONDARY_CONSTRUCTOR,
    GenerateKind.EQUALS_HASH_CODE,
    GenerateKind.TO_STRING,
    GenerateKind.OVERRIDE_METHODS,
    GenerateKind.IMPLEMENT_METHODS,
)

/** The kinds the keymap binds directly, so the menu shows their stroke. */
private val KIND_ACTIONS: Map<GenerateKind, ActionId> = mapOf(
    GenerateKind.OVERRIDE_METHODS to ActionId.OVERRIDE_METHODS,
    GenerateKind.IMPLEMENT_METHODS to ActionId.IMPLEMENT_METHODS,
)
