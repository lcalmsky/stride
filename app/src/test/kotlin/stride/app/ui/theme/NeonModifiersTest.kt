package stride.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import stride.app.ui.LocalAnimationsEnabled

class NeonModifiersTest {
    @Test
    fun `neon underline glows below the bottom edge only`() {
        val image = renderPixels {
            NeonScene {
                Box(Modifier.size(40.dp).neonUnderline(DesignTokens.accent2, radius = 12.dp))
            }
        }
        val background = image.argb(4, 4)
        assertNotEquals(background, image.argb(80, 104), "below the bottom edge should glow")
        assertEquals(background, image.argb(80, 56), "above the top edge stays background")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `neon alpha is final when animations are disabled`() = runComposeUiTest {
        var alpha = -1f
        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides false) {
                alpha = rememberSelectionAlpha(true)
            }
        }
        waitForIdle()
        assertEquals(1f, alpha)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `neon alpha reaches 1 after the fade when animations are enabled`() = runComposeUiTest {
        var active by mutableStateOf(false)
        var alpha = -1f
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalAnimationsEnabled provides true) {
                alpha = rememberSelectionAlpha(active)
            }
        }
        mainClock.advanceTimeByFrame()
        active = true
        mainClock.advanceTimeBy(DesignTokens.neonFadeMillis.toLong() + 50)
        mainClock.advanceTimeByFrame()
        assertEquals(1f, alpha)
    }

    @Composable
    private fun NeonScene(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(DesignTokens.background), contentAlignment = Alignment.Center) {
            content()
        }
    }

    private fun renderPixels(content: @Composable () -> Unit): ImageBitmap {
        val scene = ImageComposeScene(160, 160, Density(1f)) { content() }
        return try {
            scene.render(0L)
            scene.render(16_000_000L).toComposeImageBitmap()
        } finally {
            scene.close()
        }
    }

    private fun ImageBitmap.argb(x: Int, y: Int): Int {
        val buffer = IntArray(1)
        readPixels(buffer, startX = x, startY = y, width = 1, height = 1)
        return buffer[0]
    }
}
