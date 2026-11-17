package stride.app.navigation

import stride.app.AppState
import stride.search.Declaration

/** The chooser Go to Implementation, Type Declaration and Super Method open over several targets. */
class NavigationTargets(val title: String, val targets: List<Declaration>)

/**
 * Go to Implementation (⌥⌘B): the implementations of the class or member at the caret
 * ([stride.search.TypeGraph.implementations]). One opens at its name, several open the chooser,
 * none reports `No implementations of <name> found in project sources`.
 */
fun goToImplementation(state: AppState): Boolean = false

/**
 * Go to Type Declaration (⇧⌘B, ⌃⇧B): the class the declaration at the caret declares as its type,
 * only when the text says so ([stride.search.TypeReferences]); otherwise the status bar says why.
 */
fun goToTypeDeclaration(state: AppState): Boolean = false

/**
 * Go to Super Method (⌘U): the members the function around the caret overrides
 * ([stride.search.TypeGraph.superMembers]); in a class outside any function, its project supertypes.
 */
fun goToSuperMethod(state: AppState): Boolean = false

/** Type Hierarchy (⌃H): supertypes and subtypes of the class at or around the caret, in the Hierarchy tool window. */
fun showTypeHierarchy(state: AppState): Boolean = false

/** Call Hierarchy (⌃⌥H): callers and callees of the function at or around the caret, in the Hierarchy tool window. */
fun showCallHierarchy(state: AppState): Boolean = false
