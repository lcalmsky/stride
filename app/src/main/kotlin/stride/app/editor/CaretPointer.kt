package stride.app.editor

import kotlin.math.roundToInt

/** What a press with the given modifiers does to the caret set; `null` leaves it to the field. */
internal enum class CaretPointerGesture { TOGGLE, BLOCK }

internal fun caretPointerGesture(
    meta: Boolean,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    columnMode: Boolean,
): CaretPointerGesture? = when {
    alt && shift && !meta && !ctrl -> CaretPointerGesture.TOGGLE
    alt && !shift && !meta && !ctrl -> CaretPointerGesture.BLOCK
    columnMode && !alt && !shift && !meta && !ctrl -> CaretPointerGesture.BLOCK
    else -> null
}

/**
 * The column [x] points at on a line of [lineLength] characters that ends at [lineRight].
 * Right of the line the column continues in virtual steps of [charWidth]; without a usable
 * glyph width the line end stays the last column.
 */
internal fun virtualColumn(x: Float, lineRight: Float, lineLength: Int, charWidth: Float): Int =
    if (x <= lineRight || charWidth <= 0f) {
        lineLength
    } else {
        lineLength + ((x - lineRight) / charWidth).roundToInt()
    }
