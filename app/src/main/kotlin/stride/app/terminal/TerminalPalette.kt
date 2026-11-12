package stride.app.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jediterm.terminal.TerminalColor
import stride.app.glass.GlassRuntime
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.editorGlassFloor
import stride.app.ui.theme.paletteOf
import stride.app.ui.theme.readableOn

/** The terminal's ANSI palette, kept in the neon family the rest of the shell uses. */
object TerminalPalette {
    val foreground: Color get() = DesignTokens.text
    val background: Color get() = DesignTokens.background
    val cursor: Color get() = DesignTokens.accent2

    /** The unpainted cell background: transparent over the native glass so the material shows. */
    val defaultCellBackground: Color
        get() = if (GlassRuntime.active.value) Color.Transparent else background

    private var cachedFor: Appearance? = null
    private var cachedAnsi: List<Color> = emptyList()
    private var cachedOriginalFor: Appearance? = null
    private var cachedOriginal: List<Color> = emptyList()
    private var cachedGlassFor: Appearance? = null
    private var cachedGlassAnsi: List<Color> = emptyList()

    /**
     * The 16 ANSI colors as foregrounds: normal 0–7 followed by their bright variants 8–15.
     * On the native glass each entry is moved to clear 4.5 on the worst plate.
     */
    val ansi: List<Color>
        get() {
            val appearance = AppearanceState.appearance
            if (GlassRuntime.active.value) {
                if (cachedGlassFor != appearance) {
                    cachedGlassFor = appearance
                    val floor = editorGlassFloor(paletteOf(appearance))
                    cachedGlassAnsi = baseAnsi(appearance).map { readableOn(it, floor) }
                }
                return cachedGlassAnsi
            }
            if (cachedFor != appearance) {
                cachedFor = appearance
                cachedAnsi = baseAnsi(appearance)
            }
            return cachedAnsi
        }

    /** The unadjusted 16 ANSI colors. Backgrounds, including inverse-video blocks, use these. */
    val originalAnsi: List<Color>
        get() {
            val appearance = AppearanceState.appearance
            if (cachedOriginalFor != appearance) {
                cachedOriginalFor = appearance
                cachedOriginal = baseAnsi(appearance)
            }
            return cachedOriginal
        }

    private fun baseAnsi(appearance: Appearance): List<Color> =
        if (appearance == Appearance.LIGHT) lightAnsi else darkAnsi()

    private fun darkAnsi(): List<Color> {
        val normal = listOf(
            Color(0xFF0F1117),
            DesignTokens.neonPink,
            DesignTokens.neonLime,
            DesignTokens.neonAmber,
            DesignTokens.accent,
            Color(0xFFD65CFF),
            DesignTokens.accent2,
            DesignTokens.text,
        )
        return normal + normal.map { lerp(it, Color.White, 0.35f) }
    }

    /**
     * Darkened ANSI colors for the light background: every entry keeps at least 4.5:1 against
     * white, where the dark palette's bright cyan and lime would wash out.
     */
    private val lightAnsi: List<Color> = listOf(
        Color(0xFF24292F),
        Color(0xFFB3261E),
        Color(0xFF146C43),
        Color(0xFF7A5B00),
        Color(0xFF1F4FD8),
        Color(0xFF8323A8),
        Color(0xFF0B6E85),
        Color(0xFF4A5261),
        Color(0xFF3A4150),
        Color(0xFFC4256E),
        Color(0xFF3F7A00),
        Color(0xFF8A5A00),
        Color(0xFF3457C4),
        Color(0xFF7A3FC4),
        Color(0xFF0E7490),
        Color(0xFF10151F),
    )

    /** A glyph colour: 0–15 from [ansi], so readable over the glass, or [fallback] for none. */
    fun foregroundOf(terminalColor: TerminalColor?, fallback: Color = foreground): Color =
        colorOf(terminalColor, fallback, ansi)

    /** A fill colour: 0–15 from [originalAnsi]; a background never takes the glyph adjustment. */
    fun backgroundOf(terminalColor: TerminalColor?, fallback: Color = defaultCellBackground): Color =
        colorOf(terminalColor, fallback, originalAnsi)

    private fun colorOf(terminalColor: TerminalColor?, fallback: Color, palette: List<Color>): Color {
        if (terminalColor == null) return fallback
        if (terminalColor.isIndexed) {
            return palette.getOrNull(terminalColor.colorIndex) ?: fallback
        }
        return Color(terminalColor.toColor().rgb)
    }

    /**
     * The drawn foreground and background of one cell. Inverse video swaps the intended colours,
     * so both sides come from the unadjusted palette: a black block stays black rather than
     * turning into a readable grey glyph colour.
     */
    fun cellColors(
        foreground: TerminalColor?,
        background: TerminalColor?,
        inverse: Boolean,
    ): Pair<Color, Color> {
        if (inverse) {
            return colorOf(background, this.background, originalAnsi) to
                colorOf(foreground, this.foreground, originalAnsi)
        }
        return foregroundOf(foreground) to backgroundOf(background)
    }
}
