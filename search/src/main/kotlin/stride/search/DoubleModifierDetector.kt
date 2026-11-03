package stride.search

enum class ModifierKey { SHIFT, CONTROL, ALT, META }

/**
 * Detects a double tap of the same modifier key (Shift twice by default).
 *
 * The callback fires on the release of the second press, provided the second press came
 * within [PRESS_WINDOW_MILLIS] of the first press and neither press was held longer than
 * [HOLD_TIMEOUT_MILLIS]. Any other key or modifier between the taps cancels the sequence.
 * All inputs are plain calls; no AWT types are involved.
 */
class DoubleModifierDetector(
    private val clock: () -> Long,
    private val onDoubleModifier: (ModifierKey) -> Unit = {}
) {
    private enum class Phase { IDLE, FIRST_DOWN, FIRST_UP, SECOND_DOWN }

    private var phase = Phase.IDLE
    private var modifier: ModifierKey? = null
    private var firstPressTime = 0L
    private var secondPressTime = 0L

    fun modifierPressed(key: ModifierKey) {
        val now = clock()
        when (phase) {
            Phase.IDLE -> start(key, now)
            Phase.FIRST_DOWN -> when {
                key != modifier -> reset()
                now - firstPressTime > HOLD_TIMEOUT_MILLIS -> reset()
            }
            Phase.FIRST_UP -> when {
                key != modifier -> reset()
                now - firstPressTime > PRESS_WINDOW_MILLIS -> start(key, now)
                else -> {
                    phase = Phase.SECOND_DOWN
                    secondPressTime = now
                }
            }
            Phase.SECOND_DOWN -> when {
                key != modifier -> reset()
                now - secondPressTime > HOLD_TIMEOUT_MILLIS -> reset()
            }
        }
    }

    fun modifierReleased(key: ModifierKey) {
        val now = clock()
        when (phase) {
            Phase.IDLE -> Unit
            Phase.FIRST_DOWN -> {
                if (key != modifier || now - firstPressTime > HOLD_TIMEOUT_MILLIS) {
                    reset()
                } else {
                    phase = Phase.FIRST_UP
                }
            }
            Phase.FIRST_UP -> Unit
            Phase.SECOND_DOWN -> {
                if (key != modifier || now - secondPressTime > HOLD_TIMEOUT_MILLIS) {
                    reset()
                    return
                }
                val fired = modifier
                reset()
                if (fired != null) onDoubleModifier(fired)
            }
        }
    }

    fun otherKeyPressed() {
        reset()
    }

    private fun start(key: ModifierKey, now: Long) {
        phase = Phase.FIRST_DOWN
        modifier = key
        firstPressTime = now
    }

    private fun reset() {
        phase = Phase.IDLE
        modifier = null
        firstPressTime = 0L
        secondPressTime = 0L
    }

    companion object {
        const val PRESS_WINDOW_MILLIS: Long = 500L
        const val HOLD_TIMEOUT_MILLIS: Long = 300L
    }
}
