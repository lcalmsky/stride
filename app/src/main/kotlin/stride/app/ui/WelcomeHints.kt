package stride.app.ui

import stride.app.keys.ActionId
import stride.app.keys.Keymap
import stride.app.keys.KeyRouter
import stride.app.keys.formatKeyStroke

/** One welcome-screen hint; [action] is the action whose keymap stroke is shown next to [label]. */
data class WelcomeHint(val label: String, val action: ActionId, val keys: String)

/**
 * The welcome hints with every shortcut read from [keymap], so they cannot drift from it. With a
 * folder open the hints cover the search entry points; without one, `meta O` opens a folder
 * through [ActionId.GOTO_CLASS] because [ActionId.OPEN_FOLDER] has no default stroke.
 */
fun welcomeHints(keymap: Keymap, hasProject: Boolean): List<WelcomeHint> {
    val entries = if (hasProject) {
        listOf(
            "Search Everywhere" to ActionId.SEARCH_EVERYWHERE,
            "Go to File" to ActionId.GOTO_FILE,
            "Recent Files" to ActionId.RECENT_FILES,
            "Find Action" to ActionId.GOTO_ACTION,
        )
    } else {
        listOf("Open Folder" to ActionId.GOTO_CLASS)
    }
    return entries.map { (label, action) -> WelcomeHint(label, action, hintStroke(keymap, action)) }
}

/** Double Shift is owned by [KeyRouter], not the keymap, so it is the one literal gesture. */
private fun hintStroke(keymap: Keymap, action: ActionId): String =
    if (action == ActionId.SEARCH_EVERYWHERE) {
        KeyRouter.SEARCH_EVERYWHERE_GESTURE
    } else {
        keymap.strokesFor(action).firstOrNull()?.let(::formatKeyStroke).orEmpty()
    }
