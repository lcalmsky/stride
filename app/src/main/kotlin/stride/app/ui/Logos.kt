package stride.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import java.io.InputStream
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState

private object LogoAssets {
    private const val ICON_SVG = "/logo/stride-icon.svg"
    private const val ICON_PNG = "/logo/stride-icon-256.png"
    private const val WORDMARK_DARK_SVG = "/logo/stride-wordmark-dark.svg"
    private const val WORDMARK_LIGHT_SVG = "/logo/stride-wordmark-light.svg"

    fun stream(path: String): InputStream? = LogoAssets::class.java.getResourceAsStream(path)

    fun svg(path: String, density: Density): Painter? = runCatching {
        stream(path)?.use { loadSvgPainter(it, density) }
    }.getOrNull()

    fun bitmap(path: String): Painter? = runCatching {
        stream(path)?.use { BitmapPainter(loadImageBitmap(it)) }
    }.getOrNull()

    fun icon(density: Density): Painter? = svg(ICON_SVG, density) ?: bitmap(ICON_PNG)

    fun pngIcon(): Painter? = bitmap(ICON_PNG)

    fun wordmark(density: Density, dark: Boolean): Painter? =
        svg(if (dark) WORDMARK_DARK_SVG else WORDMARK_LIGHT_SVG, density)
}

/** The 18 dp brand mark used in the top bar and as the window icon. */
@Composable
fun rememberLogoPainter(): Painter? {
    val density = LocalDensity.current
    return remember(density) { LogoAssets.icon(density) }
}

/**
 * Whether the wordmark's caret blinks. Tests and snapshots set this to false so the caret stays
 * visible and no endless animation keeps the test clock busy.
 */
val LocalWordmarkCaretBlink = staticCompositionLocalOf { true }

/** The full wordmark; `null` when SVG decoding is unavailable, so callers can fall back. */
@Composable
fun rememberWordmarkPainter(): Painter? {
    val density = LocalDensity.current
    val dark = AppearanceState.appearance == Appearance.DARK
    return remember(density, dark) { LogoAssets.wordmark(density, dark) }
}

/** Window icon for [stride.app.Main]; `null` is a valid no-icon value. */
fun loadWindowIcon(): Painter? = LogoAssets.pngIcon()
