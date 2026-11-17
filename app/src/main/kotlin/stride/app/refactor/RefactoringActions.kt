package stride.app.refactor

import stride.app.AppState
import stride.lang.RefactoringKind

/** ⌃T: the refactorings available at the caret, Rename included. */
fun openRefactorThis(state: AppState): Boolean = false

/** ⌥⌘V, ⌥⌘M and ⌥⌘N: runs [kind] at the selected editor's selection. */
fun startRefactoring(state: AppState, kind: RefactoringKind): Boolean = false
