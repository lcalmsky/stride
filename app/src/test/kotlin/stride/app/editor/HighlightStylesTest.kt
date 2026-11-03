package stride.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import stride.lang.ColorScheme
import stride.lang.LineStateHighlighter
import stride.lang.kotlin.KotlinLexer
import stride.lang.kotlin.KotlinSyntaxHighlighter

class HighlightStylesTest {
    @Test
    fun `kotlin snippet maps to dark scheme styles`() {
        val text = "val answer = 42\n// note\n"
        val highlighter = LineStateHighlighter(KotlinLexer(), text, KotlinSyntaxHighlighter()::getTokenHighlights)
        val styles = spanStyles(highlighter.spans(), ColorScheme.DARK)

        fun styleAt(index: Int) = styles.firstNotNullOfOrNull { range ->
            range.item.takeIf { index >= range.start && index < range.end }
        }

        val keyword = styleAt(text.indexOf("val"))!!
        assertEquals(Color(0xFF7C5CFF.toInt()), keyword.color)
        assertEquals(FontWeight.Bold, keyword.fontWeight)

        val number = styleAt(text.indexOf("42"))!!
        assertEquals(Color(0xFF00D1FF.toInt()), number.color)

        val comment = styleAt(text.indexOf("// note"))!!
        assertEquals(FontStyle.Italic, comment.fontStyle)
        assertEquals(Color(0xFF6E7686.toInt()), comment.color)
    }
}
