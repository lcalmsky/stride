package stride.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG relative luminance of a color's sRGB channels, linearised at the 0.04045 threshold. */
fun relativeLuminance(color: Color): Double {
    fun linear(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
}

/** WCAG contrast ratio `(L1 + 0.05) / (L2 + 0.05)` with the lighter color as the numerator. */
fun contrastRatio(a: Color, b: Color): Double {
    val first = relativeLuminance(a)
    val second = relativeLuminance(b)
    return (max(first, second) + 0.05) / (min(first, second) + 0.05)
}

/** True source-over composite of [fg] onto [bg]; the result is always opaque. */
fun blendOver(fg: Color, bg: Color): Color {
    val alpha = fg.alpha.toDouble()
    return Color(
        red = (alpha * fg.red + (1.0 - alpha) * bg.red).toFloat(),
        green = (alpha * fg.green + (1.0 - alpha) * bg.green).toFloat(),
        blue = (alpha * fg.blue + (1.0 - alpha) * bg.blue).toFloat(),
        alpha = 1f,
    )
}
