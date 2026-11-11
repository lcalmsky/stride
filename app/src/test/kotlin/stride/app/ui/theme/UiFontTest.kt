package stride.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import stride.app.AppState
import stride.app.keys.ActionId
import stride.app.keys.dispatchAction

class UiFontTest {
    private lateinit var node: Preferences

    @BeforeEach
    fun setUp() {
        node = Preferences.userRoot().node("stride-test-${UUID.randomUUID()}")
    }

    @AfterEach
    fun tearDown() {
        UiFont.preference = UiFontPreference(null)
        runCatching { node.removeNode() }
    }

    @Test
    fun `the preference reads back what it stored`() {
        val preference = UiFontPreference(node)
        assertEquals(UiFontChoice.PRETENDARD, preference.read(), "an empty node uses the default")

        preference.write(UiFontChoice.SYSTEM)
        assertEquals(UiFontChoice.SYSTEM, UiFontPreference(node).read(), "a stored value reads back")

        node.put(UiFontPreference.KEY, "unknown")
        assertEquals(UiFontChoice.PRETENDARD, UiFontPreference(node).read(), "an unknown value falls back")
        assertEquals("pretendard" to "system", UiFontChoice.PRETENDARD.preferenceValue to UiFontChoice.SYSTEM.preferenceValue)
    }
    @Test
    fun `the toggle action switches the family and stores the choice`() {
        UiFont.preference = UiFontPreference(node)
        val state = AppState()
        try {
            assertEquals(UiFontChoice.PRETENDARD, UiFont.choice)

            assertTrue(dispatchAction(ActionId.TOGGLE_UI_FONT, state, { null }))
            assertEquals(UiFontChoice.SYSTEM, UiFont.choice)
            assertEquals(FontFamily.Default, DesignTokens.uiFont)
            assertEquals("UI font: System", state.statusMessage)
            assertEquals(UiFontChoice.SYSTEM, UiFontPreference(node).read())

            assertTrue(dispatchAction(ActionId.TOGGLE_UI_FONT, state, { null }))
            assertEquals(UiFontChoice.PRETENDARD, UiFont.choice)
            assertEquals(PretendardFontFamily, DesignTokens.uiFont)
            assertEquals("UI font: Pretendard", state.statusMessage)
            assertEquals(UiFontChoice.PRETENDARD, UiFontPreference(node).read())
        } finally {
            state.dispose()
        }
    }
}
