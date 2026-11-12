package stride.app.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jediterm.terminal.TerminalColor
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens

/** The terminal's ANSI palette, kept in the neon family the rest of the shell uses. */
object TerminalPalette {
    val foreground: Color get() = DesignTokens.text
    val background: Color get() = DesignTokens.background
    val cursor: Color get() = DesignTokens.accent2

    private var cachedFor: Appearance? = null
    private var cachedAnsi: List<Color> = emptyList()

    /** The 16 ANSI colors: normal 0–7 followed by their bright variants 8–15. */
    val ansi: List<Color>
        get() {
            val appearance = AppearanceState.appearance
            if (cachedFor != appearance) {
                cachedFor = appearance
                cachedAnsi = if (appearance == Appearance.LIGHT) lightAnsi else darkAnsi()
            }
            return cachedAnsi
        }

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

    /** The 0–15 palette lookup, 256-color or truecolor RGB, or [fallback] for no color. */
    fun colorOf(terminalColor: TerminalColor?, fallback: Color): Color {
        if (terminalColor == null) return fallback
        if (terminalColor.isIndexed) {
            val index = terminalColor.colorIndex
            return ansi.getOrNull(index) ?: fallback
        }
        return Color(terminalColor.toColor().rgb)
    }
}
