package stride.app.keys

import androidx.compose.ui.input.key.Key
import stride.search.DoubleModifierDetector
import stride.search.ModifierKey

enum class KeyEventType { DOWN, UP }

/**
 * Maps window key events to [ActionId]s without touching a window, so the rules are
 * unit-testable. Shortcuts come from [keymap]; Double Shift is detected by
 * [DoubleModifierDetector] and is the only action the keymap does not carry.
 */
class KeyRouter(
    private val keymap: Keymap,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    constructor(
        isMac: Boolean,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ) : this(Keymap.forPlatform(isMac), clock)

    private var pending: ActionId? = null
    private var cloneWaiting = false
    private val pressed = HashSet<ModifierKey>()
    private val detector = DoubleModifierDetector(clock) { modifier ->
        if (modifier == ModifierKey.SHIFT) pending = ActionId.SEARCH_EVERYWHERE
    }

    fun route(
        type: KeyEventType,
        key: Key,
        meta: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        focus: KeyContext = KeyContext.GLOBAL,
    ): ActionId? {
        pending = null
        val modifier = modifierOf(key)
        if (type == KeyEventType.DOWN) {
            if (modifier != null) {
                if (pressed.add(modifier)) {
                    detector.modifierPressed(modifier)
                    if (modifier == ModifierKey.ALT && detector.isSecondPressHeld(ModifierKey.ALT)) {
                        cloneWaiting = true
                    }
                }
            } else {
                if (cloneWaiting && alt && !meta && !ctrl && !shift && focus == KeyContext.EDITOR) {
                    // The second Option stays down after the clone key; the wait survives so
                    // holding it and pressing the arrow again clones again.
                    when (key) {
                        Key.DirectionUp -> return ActionId.EDITOR_CLONE_CARET_ABOVE
                        Key.DirectionDown -> return ActionId.EDITOR_CLONE_CARET_BELOW
                    }
                }
                cloneWaiting = false
                detector.otherKeyPressed()
                return keymap.actionFor(KeyStroke(key, meta, ctrl, alt, shift), focus)
            }
        } else if (modifier != null) {
            pressed.remove(modifier)
            detector.modifierReleased(modifier)
            if (modifier == ModifierKey.ALT) cloneWaiting = false
        }
        return pending
    }

    companion object {
        /** Double Shift is the only action the keymap does not carry; the hint bar shows this. */
        const val SEARCH_EVERYWHERE_GESTURE = "⇧⇧"

        /** The clone-caret gestures shown in the Actions tab; they have no keymap binding. */
        const val CLONE_CARET_ABOVE_GESTURE = "⌥⌥↑"
        const val CLONE_CARET_BELOW_GESTURE = "⌥⌥↓"

        fun modifierOf(key: Key): ModifierKey? = when (key) {
            Key.ShiftLeft, Key.ShiftRight -> ModifierKey.SHIFT
            Key.CtrlLeft, Key.CtrlRight -> ModifierKey.CONTROL
            Key.AltLeft, Key.AltRight -> ModifierKey.ALT
            Key.MetaLeft, Key.MetaRight -> ModifierKey.META
            else -> null
        }

        fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")
    }
}
