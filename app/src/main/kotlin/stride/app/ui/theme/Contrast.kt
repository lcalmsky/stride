package stride.app.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

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

/** Rounds the composed channels the way the renderer does, so tests and the app agree. */
private fun rounded(color: Color): Color = Color(
    red = (color.red * 255f).roundToInt() / 255f,
    green = (color.green * 255f).roundToInt() / 255f,
    blue = (color.blue * 255f).roundToInt() / 255f,
    alpha = color.alpha,
)

/**
 * [color] moved toward black or white — whichever needs the smaller step — until it reaches
 * [ratio] against [background]. The step is a per-channel lerp, so hue and saturation are kept
 * as far as the contrast allows. A color that already clears the ratio is returned unchanged.
 */
fun readableOn(color: Color, background: Color, ratio: Double = DesignTokens.contrastText): Color {
    if (contrastRatio(color, background) >= ratio) return color
    fun step(target: Color): Pair<Int, Color>? {
        for (index in 1..100) {
            val t = index / 100f
            val candidate = rounded(
                Color(
                    red = color.red + (target.red - color.red) * t,
                    green = color.green + (target.green - color.green) * t,
                    blue = color.blue + (target.blue - color.blue) * t,
                    alpha = 1f,
                ),
            )
            if (contrastRatio(candidate, background) >= ratio) return index to candidate
        }
        return null
    }
    val black = step(Color.Black)
    val white = step(Color.White)
    return when {
        black != null && white != null -> if (black.first <= white.first) black.second else white.second
        black != null -> black.second
        white != null -> white.second
        else -> Color.Black
    }
}
