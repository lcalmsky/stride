package stride.app.glass

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.ui.theme.DesignTokens

class ChromePlanTest {
    @Test
    fun `a missing library or macOS before 26 keeps the standard title bar`() {
        for (attached in listOf(false, true)) {
            val plan = chromePlan(eligible = false, attached = attached, failed = false)
            assertFalse(plan.titleBarProperties, "no properties without an eligible native path")
            assertFalse(plan.mergedTitleRow, "no merged row without an eligible native path")
        }
    }

    @Test
    fun `a failed attach reverts the properties and the merged row`() {
        val plan = chromePlan(eligible = true, attached = false, failed = true)
        assertFalse(plan.titleBarProperties)
        assertFalse(plan.mergedTitleRow)
    }

    @Test
    fun `before the first attach only the properties are on`() {
        val plan = chromePlan(eligible = true, attached = false, failed = false)
        assertTrue(plan.titleBarProperties, "the peer needs the properties at creation time")
        assertFalse(plan.mergedTitleRow, "the layout waits for a successful attach")
    }

    @Test
    fun `a successful attach turns on both`() {
        val plan = chromePlan(eligible = true, attached = true, failed = false)
        assertTrue(plan.titleBarProperties)
        assertTrue(plan.mergedTitleRow)
        assertTrue(!plan.mergedTitleRow || plan.titleBarProperties, "the merged row implies properties")
    }

    @Test
    fun `the fallback layout is the v1_3_0 chrome`() {
        val layout = chromeLayout(mergedTitleRow = false)
        assertEquals(DesignTokens.chromeInset, layout.topInset)
        assertEquals(DesignTokens.capsuleHeight, layout.topRowHeight)
        assertEquals(DesignTokens.chromeInset, layout.sidebarTop)
        assertEquals(DesignTokens.chromeInset + DesignTokens.capsuleHeight + DesignTokens.chromeGap, layout.topBand)
    }

    @Test
    fun `the merged layout starts the row at the window top and the sidebar below it`() {
        val layout = chromeLayout(mergedTitleRow = true)
        assertEquals(0.dp, layout.topInset)
        assertEquals(DesignTokens.nativeTitleRowHeight, layout.topRowHeight)
        assertEquals(DesignTokens.nativeTitleRowHeight + DesignTokens.chromeGap, layout.sidebarTop)
        assertEquals(layout.sidebarTop, layout.topBand)
    }
}
