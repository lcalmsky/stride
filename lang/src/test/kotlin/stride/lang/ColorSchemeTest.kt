package stride.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorSchemeTest {
    @Test
    fun `dark scheme uses the logo palette`() {
        val default = ColorScheme.DARK.getAttributes(TextAttributesKey.DEFAULT_TEXT)!!
        val keyword = ColorScheme.DARK.getAttributes(TextAttributesKey.KEYWORD)!!
        val number = ColorScheme.DARK.getAttributes(TextAttributesKey.NUMBER)!!

        assertEquals(ColorScheme.DARK_BACKGROUND, default.background)
        assertEquals(ColorScheme.DARK_ACCENT, keyword.foreground)
        assertEquals(ColorScheme.DARK_ACCENT_2, number.foreground)
        assertTrue(keyword.bold)
    }

    @Test
    fun `light scheme has its own background`() {
        val default = ColorScheme.LIGHT.getAttributes(TextAttributesKey.DEFAULT_TEXT)!!
        assertEquals(0xFFFFFFFFL, default.background)
        assertTrue(default.foreground != ColorScheme.DARK_BACKGROUND)
    }

    @Test
    fun `lookup follows the fallback chain`() {
        val custom = TextAttributesKey("CUSTOM_KEYWORD", TextAttributesKey.KEYWORD)
        assertEquals(ColorScheme.DARK.getAttributes(TextAttributesKey.KEYWORD), ColorScheme.DARK.getAttributes(custom))
    }

    @Test
    fun `undefined key without fallback returns null`() {
        assertNull(ColorScheme.DARK.getAttributes(TextAttributesKey("UNKNOWN")))
    }
}
