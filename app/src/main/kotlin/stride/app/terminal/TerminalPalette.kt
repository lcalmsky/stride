package stride.app.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.jediterm.terminal.TerminalColor
import stride.app.ui.theme.DesignTokens

/** The terminal's ANSI palette, kept in the neon family the rest of the shell uses. */
object TerminalPalette {
    val foreground: Color = DesignTokens.text
    val background: Color = DesignTokens.background
    val cursor: Color = DesignTokens.accent2

    /** The 16 ANSI colors: normal 0–7 followed by their bright variants 8–15. */
    val ansi: List<Color> = run {
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
        normal + normal.map { lerp(it, Color.White, 0.35f) }
    }

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
