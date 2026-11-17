package stride.app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.ui.FixedUiFontExtension
import stride.app.ui.LocalAnimationsEnabled
import stride.app.ui.LocalWordmarkCaretBlink
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.blendOver
import stride.core.vfs.VFile

/**
 * The usage highlight is a background span built once per highlight list. A light/dark switch
 * replaces the palette under that span, so a colour kept across the switch has to be rebuilt.
 */
@ExtendWith(FixedUiFontExtension::class)
class UsageHighlightAppearanceTest {
    @TempDir
    lateinit var temp: Path

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 80
        const val HIGHLIGHT_ALPHA = 0.55f
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the usage highlight takes the new palette after a light switch`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(2f),
    ) {
        val path = temp.resolve("Main.kt")
        Files.writeString(path, "alpha beta\n")
        val editor = TextFileEditor(VFile(path))
        editor.updateValue(TextFieldValue("alpha beta\n", TextRange(0)))
        editor.usageHighlights = listOf(0..4)
        AppearanceState.set(Appearance.DARK)
        val darkAccent = DesignTokens.accent
        try {
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E))) {
                        editor.Content(Modifier.fillMaxSize())
                    }
                }
            }
            waitForIdle()

            AppearanceState.set(Appearance.LIGHT)
            waitForIdle()
            val lit = onRoot().captureToImage().pixels()
            val lightAccent = DesignTokens.accent

            editor.usageHighlights = emptyList()
            waitForIdle()
            val plain = onRoot().captureToImage().pixels()

            var compared = 0
            var offPalette = 0
            for (index in lit.indices) {
                val base = Color(plain[index])
                val actual = Color(lit[index])
                if (actual == base) continue
                val expected = blendOver(lightAccent.copy(alpha = HIGHLIGHT_ALPHA), base)
                val stale = blendOver(darkAccent.copy(alpha = HIGHLIGHT_ALPHA), base)
                if (near(actual, expected)) compared++ else if (near(actual, stale)) offPalette++
            }
            assertTrue(compared + offPalette > 0, "no pixel carries the usage highlight")
            assertTrue(offPalette == 0, "$offPalette pixels kept the previous palette's accent")
        } finally {
            AppearanceState.set(Appearance.DARK)
        }
    }

    private fun near(first: Color, second: Color): Boolean =
        abs(first.red * 255f - second.red * 255f) <= 2f &&
            abs(first.green * 255f - second.green * 255f) <= 2f &&
            abs(first.blue * 255f - second.blue * 255f) <= 2f

    private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }
}
