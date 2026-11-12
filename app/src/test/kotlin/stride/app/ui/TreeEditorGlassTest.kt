package stride.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.app.ui.theme.DesignTokens
import stride.app.ui.theme.blendOver
import stride.app.ui.theme.contrastRatio
import stride.app.ui.theme.relativeLuminance
import stride.core.services.Application
import stride.core.vfs.VFile

@ExtendWith(FixedUiFontExtension::class)
class TreeEditorGlassTest {
    @TempDir
    lateinit var temp: Path

    /**
     * The background extension is real content: the strip of code right of the sidebar is
     * mirrored behind it, so rewriting the document changes the pixels inside the sidebar.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `sidebar refracts the code mirrored from its right edge`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("mirror-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val text = editor.value.text
            val selection = editor.value.selection
            val first = captureToImage().pixels()

            // Same line count and length, no glyphs: the mirrored strip can only change if the
            // sidebar really samples the document instead of a texture.
            editor.updateValue(TextFieldValue(text.map { if (it == '\n') it else ' ' }.joinToString(""), selection))
            waitForIdle()
            val second = captureToImage().pixels()

            var total = 0.0
            var count = 0
            var darkest = 1.0
            var brightest = 0.0
            for (y in 260 until 700) {
                for (x in 40 until 270) {
                    val firstLuma = relativeLuminance(Color(first[index(x, y)]))
                    val secondLuma = relativeLuminance(Color(second[index(x, y)]))
                    total += abs(firstLuma - secondLuma)
                    count += 1
                    darkest = minOf(darkest, firstLuma)
                    brightest = maxOf(brightest, firstLuma)
                }
            }
            val difference = total / count
            System.err.println(
                "extension difference=%.5f range=%.4f".format(difference, brightest - darkest),
            )
            assertTrue(
                difference > 0.0005,
                "the sidebar should follow the mirrored code (difference %.5f)".format(difference),
            )
            assertTrue(
                // Measured 0.0034..0.0057 across the scenes; a flat fill is ~0.
                brightest - darkest > 0.0025,
                "the sidebar should not be a flat fill (range %.5f)".format(brightest - darkest),
            )
        } finally {
            state.dispose()
        }
    }

    /**
     * No full-width bar: the 8 dp gap between the project capsule and the tab group shows the
     * code scrolling under the floating controls, so it changes with the document.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the row between the top capsules follows the code under it`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("gap-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()

            val name = session.displayName()
            val nameBounds = onAllNodesWithText(name, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
            val tabBounds = onAllNodesWithText("Long.kt", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
            // The capsule keeps 12 dp of padding after its label; the tab's label starts 33 dp
            // into the group (group padding, tab padding, icon and spacer).
            val capsuleRight = nameBounds.right + 12
            val groupLeft = tabBounds.left - 33
            val gapX = ((capsuleRight + groupLeft) / 2).toInt()
            val y = (DesignTokens.chromeInset + DesignTokens.capsuleHeight / 2).value.toInt()
            System.err.println(
                "top-gap capsuleRight=%.1f groupLeft=%.1f x=%d y=%d".format(capsuleRight, groupLeft, gapX, y),
            )
            assertTrue(groupLeft > capsuleRight, "the gap is empty: ${capsuleRight}..${groupLeft}")

            val text = editor.value.text
            val selection = editor.value.selection
            val beforePixels = captureToImage().pixels()
            var before = 0.0
            var samples = 0
            for (dy in -4..4) {
                before += relativeLuminance(Color(beforePixels[index(gapX, y + dy)]))
                samples += 1
            }
            before /= samples

            editor.updateValue(TextFieldValue(text.map { if (it == '\n') it else ' ' }.joinToString(""), selection))
            waitForIdle()
            val pixels = captureToImage().pixels()
            var after = 0.0
            for (dy in -4..4) after += relativeLuminance(Color(pixels[index(gapX, y + dy)]))
            after /= samples

            System.err.println("top-gap before=%.5f after=%.5f".format(before, after))
            assertTrue(
                abs(before - after) > 0.0008,
                "the gap should be code, not a painted bar (before %.5f after %.5f)".format(before, after),
            )
        } finally {
            state.dispose()
        }
    }

    /** The top capsule's specular rim is brighter than its bottom edge. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `top capsule rim is brighter than its bottom edge`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("rim-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val nameBounds = onAllNodesWithText(session.displayName(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
            val pixels = captureToImage().pixels()
            val x = (nameBounds.right + 12 - 22).toInt()
            // The rim is the outermost painted row of the capsule; its frosted interior carries the
            // scroll edge fade, so the second row in is darker than the bottom interior.
            val top = relativeLuminance(Color(pixels[index(x, DesignTokens.chromeInset.value.toInt())]))
            val bottom = relativeLuminance(
                Color(
                    pixels[index(
                        x,
                        (DesignTokens.chromeInset + DesignTokens.capsuleHeight).value.toInt() - 1,
                    )],
                ),
            )
            System.err.println("capsule-rim top=%.4f bottom=%.4f".format(top, bottom))
            assertTrue(
                top > bottom + 0.02,
                "the capsule top rim should be brighter than its bottom edge: %.4f vs %.4f".format(top, bottom),
            )
        } finally {
            state.dispose()
        }
    }

    /** The selected tab pill moves to the newly selected tab and is its only signal. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tab pill moves to the newly selected tab`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = demoProject()
            state.openFolder(root)
            val session = requireNotNull(state.session)
            session.editors.openFile(VFile(root.resolve("src/Main.kt")))
            session.editors.openFile(VFile(root.resolve("src/Greeting.kt")))
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()

            onNode(
                SemanticsMatcher("top tab") { it.boundsInRoot.top < 100f } and
                    hasText("Main.kt") and hasClickAction(),
            ).performClick()
            waitForIdle()
            val pixels = captureToImage().pixels()
            val main = topPill(pixels, "Main.kt")
            val greeting = topPill(pixels, "Greeting.kt")
            System.err.println("tab-pill luma main=%.4f greeting=%.4f".format(main, greeting))
            assertTrue(main > greeting + 0.01, "the pill should sit under the newly selected tab ($main vs $greeting)")
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `editor body is the content tint over the same mesh pixel`() {
        val state = AppState()
        try {
            val root = project("tint-project")
            state.openFolder(root)
            requireNotNull(state.session).editors.openFile(VFile(root.resolve("src/Main.kt")))
            val app = renderAppPixels(state)
            val x = 900
            val y = 700
            val expected = blendOver(
                DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
                DesignTokens.background,
            )
            val actual = Color(app[index(x, y)])
            System.err.println(
                "editor-tint actual=#%02X%02X%02X expected=#%02X%02X%02X".format(
                    (actual.red * 255f).toInt(),
                    (actual.green * 255f).toInt(),
                    (actual.blue * 255f).toInt(),
                    (expected.red * 255f).toInt(),
                    (expected.green * 255f).toInt(),
                    (expected.blue * 255f).toInt(),
                ),
            )
            assertChannelClose(actual, expected, "editor pixel at ($x,$y)")
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `tree focus halo turns on when the tree has focus`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = project("focus-project")
            state.openFolder(root)
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val unfocused = captureToImage().pixels()
            // Rows are clickable (focusable) but Compose Desktop neither focuses on click nor
            // traverses with Tab, so a focus system requests focus through the semantics action.
            onNode(hasText("src") and hasClickAction()).requestFocus()
            waitForIdle()
            onNode(hasText("src") and hasClickAction()).assertIsFocused()
            val focused = captureToImage().pixels()

            // The sidebar's top edge: the halo line sits on the glass rim.
            for ((x, y) in listOf(200 to 10, 200 to 12)) {
                val before = Color(unfocused[index(x, y)])
                val after = Color(focused[index(x, y)])
                val green = channel(after) { green } - channel(before) { green }
                val blue = channel(after) { blue } - channel(before) { blue }
                val red = channel(after) { red } - channel(before) { red }
                assertTrue(
                    green > 1f && blue > 1f && red <= 0f,
                    "halo at ($x,$y): dR=%.2f dG=%.2f dB=%.2f".format(red, green, blue),
                )
                System.err.println("halo ($x,$y) dR=%.2f dG=%.2f dB=%.2f".format(red, green, blue))
            }
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `goto line popup blurs the document behind it`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val root = temp.resolve("dense-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("Dense.kt"), (1..80).joinToString("\n") { "#".repeat(200) })
        val state = AppState()
        try {
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Dense.kt"))) as TextFileEditor
            // Keep the first line under the popup: the default end-of-file caret scrolls it away.
            editor.updateValue(TextFieldValue(editor.value.text, TextRange(0)))
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val before = captureToImage().pixels()

            editor.gotoLineVisible = true
            waitUntil(timeoutMillis = 5_000) {
                onAllNodes(hasText("Go to line:")).fetchSemanticsNodes().isNotEmpty()
            }
            waitForIdle()
            val after = captureToImage().pixels()

            val label = onNodeWithText("Go to line:").fetchSemanticsNode().boundsInRoot
            val input = onNodeWithTag(TextFileEditor.GOTO_LINE_TAG).fetchSemanticsNode().boundsInRoot
            val left = label.left.toInt() + 2
            val right = input.right.toInt() - 2
            val top = label.top.toInt() - 6
            val bottom = top + 1
            assertTrue(left < right && top < bottom, "popup band is empty: ($left,$top)-($right,$bottom)")

            val pattern = meanAdjacentDiff(before, left, right, top, bottom)
            val backdrop = meanAdjacentDiff(after, left, right, top, bottom)
            val ratio = backdrop / pattern
            val band = (left until right).map { x -> after[index(x, top)] and 0xFF }
            System.err.println(
                "goto-backdrop pattern=%.5f backdrop=%.5f ratio=%.4f band=%d..%d".format(
                    pattern,
                    backdrop,
                    ratio,
                    band.min(),
                    band.max(),
                ),
            )
            assertTrue(
                band.max() - band.min() >= 2,
                "blurred document should still vary inside the popup (band ${band.min()}..${band.max()})",
            )
            assertTrue(
                backdrop < pattern * 0.5,
                "backdrop mean %.5f should be below half the pattern mean %.5f (ratio %.3f)".format(
                    backdrop,
                    pattern,
                    ratio,
                ),
            )
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `gutter line numbers clear 4_5 on the worst content tint`() {
        val worstTint = blendOver(
            DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
            DesignTokens.backgroundLift,
        )
        val ratio = contrastRatio(DesignTokens.muted, worstTint)
        System.err.println("gutter-contrast muted-on-worst-tint=%.3f".format(ratio))
        assertTrue(
            ratio >= DesignTokens.contrastText,
            "muted on the worst content tint = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
        )
    }

    /**
     * At or past the layered-scroll threshold the capsules cannot sample the backdrop, so they
     * paint the cached frost instead; the label must stay legible on that fill.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `layered scroll keeps the status capsule legible`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("layered-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.scrollLayerOverride = true
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            assertTrue(editor.verticalScroll.layered, "the forced layered path should be active")
            val bounds = onAllNodesWithText("UTF-8", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
            val pixels = captureToImage().pixels()
            val luminances = ArrayList<Double>()
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) {
                for (x in bounds.left.toInt() until bounds.right.toInt()) {
                    luminances += relativeLuminance(Color(pixels[index(x, y)]))
                }
            }
            luminances.sort()
            val background = luminances[luminances.size / 2]
            val text = luminances.last()
            val ratio = (text + 0.05) / (background + 0.05)
            System.err.println(
                "layered-contrast background=%.4f text=%.4f ratio=%.3f".format(background, text, ratio),
            )
            assertTrue(
                ratio >= DesignTokens.contrastText,
                "layered status capsule text = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
            )
        } finally {
            state.dispose()
        }
    }

    /** The status capsule sits over scrolled code; its label run keeps body contrast. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `status capsule text clears 4_5 over the scrolled code`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("status-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val bounds = onAllNodesWithText("UTF-8", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
            val pixels = captureToImage().pixels()
            val left = bounds.left.toInt()
            val right = bounds.right.toInt()
            val top = bounds.top.toInt()
            val bottom = bounds.bottom.toInt()
            val luminances = ArrayList<Double>((bottom - top) * (right - left))
            for (y in top until bottom) {
                for (x in left until right) luminances += relativeLuminance(Color(pixels[index(x, y)]))
            }
            luminances.sort()
            val background = luminances[luminances.size / 2]
            val text = luminances.last()
            val ratio = (text + 0.05) / (background + 0.05)
            val textPixels = luminances.count { it > background + 0.1 }
            System.err.println(
                "status-contrast background=%.4f text=%.4f ratio=%.3f text-pixels=%d".format(
                    background,
                    text,
                    ratio,
                    textPixels,
                ),
            )
            // Measured 102; a flat capsule would have almost none.
            assertTrue(textPixels > 51, "expected label pixels in the status capsule, found $textPixels")
            assertTrue(
                ratio >= DesignTokens.contrastText,
                "status capsule text over scrolled code = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `search capsule shortcut hint clears 4_5 on its glass`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            state.openFolder(project("search-capsule-project"))
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            // The same glyphs also label the shortcut list further down, so the topmost node is
            // the capsule's hint.
            val bounds = onAllNodesWithText("⇧⇧", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
            val pixels = captureToImage().pixels()
            val left = bounds.left.toInt()
            val right = maxOf(bounds.right.toInt(), left + 1)
            val top = bounds.top.toInt()
            val bottom = maxOf(bounds.bottom.toInt(), top + 1)
            val luminances = ArrayList<Double>((bottom - top) * (right - left))
            for (y in top until bottom) {
                for (x in left until right) luminances += relativeLuminance(Color(pixels[index(x, y)]))
            }
            luminances.sort()
            val background = luminances[luminances.size / 2]
            val best = luminances.last()
            val ratio = (best + 0.05) / (background + 0.05)
            System.err.println(
                "search-capsule-hint background=%.4f text=%.4f ratio=%.3f".format(background, best, ratio),
            )
            assertTrue(
                ratio >= DesignTokens.contrastText,
                "search capsule hint contrast = %.3f < %.1f".format(ratio, DesignTokens.contrastText),
            )
        } finally {
            state.dispose()
        }
    }

    /**
     * Horizontally scrolled glyphs stay right of the gutter: the gap between the sidebar and the
     * gutter shows only the background extension's soft field.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `scrolled text never draws left of the gutter`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("clip-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val pixels = captureToImage().pixels()

            val sidebarRight = (DesignTokens.chromeInset + DesignTokens.treeDefaultWidth).value
            val gutterLeft = sidebarRight + DesignTokens.contentGap.value
            var gapSpread = 0.0
            for (y in 120 until 700) {
                var low = 1.0
                var high = 0.0
                for (x in (sidebarRight + 1).toInt() until (gutterLeft - 1).toInt()) {
                    val luma = relativeLuminance(Color(pixels[index(x, y)]))
                    low = minOf(low, luma)
                    high = maxOf(high, luma)
                }
                gapSpread = maxOf(gapSpread, high - low)
            }
            var textLow = 1.0
            var textHigh = 0.0
            for (y in 120 until 700) {
                for (x in 420 until 700) {
                    val luma = relativeLuminance(Color(pixels[index(x, y)]))
                    textLow = minOf(textLow, luma)
                    textHigh = maxOf(textHigh, luma)
                }
            }
            System.err.println(
                "gutter-gap spread=%.5f text-range=%.4f".format(gapSpread, textHigh - textLow),
            )
            assertTrue(gapSpread < 0.02, "glyph contrast left of the gutter (spread %.5f)".format(gapSpread))
            assertTrue(
                textHigh - textLow > 0.2,
                "the text column should carry glyphs (range %.4f)".format(textHigh - textLow),
            )
        } finally {
            state.dispose()
        }
    }

    /**
     * The scroll edge effect: content keeps flowing to the window edge and the top and bottom
     * bands blur/fade it with no single-row luminance jump.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `code softens into the window edge instead of a cut`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("edge-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val pixels = captureToImage().pixels()
            val topBand = (DesignTokens.chromeInset + DesignTokens.capsuleHeight + DesignTokens.chromeGap)
                .value.toInt()
            val bottomBand = (DesignTokens.chromeInset + DesignTokens.statusCapsuleHeight +
                DesignTokens.chromeGap).value.toInt()

            val topMeans = rowMeans(pixels, 600, 1000, 0, topBand)
            var topJump = 0.0
            for (y in 1 until topMeans.size) topJump = maxOf(topJump, abs(topMeans[y] - topMeans[y - 1]))
            val bottomMeans = rowMeans(pixels, 450, 700, HEIGHT - bottomBand, HEIGHT)
            var bottomJump = 0.0
            for (y in 1 until bottomMeans.size) {
                bottomJump = maxOf(bottomJump, abs(bottomMeans[y] - bottomMeans[y - 1]))
            }
            System.err.println(
                "edge-band top-jump=%.5f top-range=%.4f bottom-jump=%.5f bottom-range=%.4f".format(
                    topJump,
                    topMeans.max() - topMeans.min(),
                    bottomJump,
                    bottomMeans.max() - bottomMeans.min(),
                ),
            )

            var before = 0.0
            for (y in 16 until 44) before += topMeans[y]
            before /= 28
            val text = editor.value.text
            val selection = editor.value.selection
            editor.updateValue(TextFieldValue(text.map { if (it == '\n') it else ' ' }.joinToString(""), selection))
            waitForIdle()
            val blank = captureToImage().pixels()
            var after = 0.0
            val blankMeans = rowMeans(blank, 600, 1000, 0, topBand)
            for (y in 16 until 44) after += blankMeans[y]
            after /= 28
            System.err.println("edge-band under-capsules code=%.5f blank=%.5f".format(before, after))
            assertTrue(
                topJump < 0.02,
                "the top band should not cut the code (largest row jump %.5f)".format(topJump),
            )
            assertTrue(
                bottomJump < 0.02,
                "the bottom band should not cut the code (largest row jump %.5f)".format(bottomJump),
            )
            assertTrue(
                abs(before - after) > 0.002,
                "the band should blur real code, not a flat fill (%.5f vs %.5f)".format(before, after),
            )
        } finally {
            state.dispose()
        }
    }

    /**
     * The background extension covers only the sidebar's rounded shape: the gap left of the
     * gutter is the editor's own field, so no vertical step appears at the gutter's left edge.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the gap left of the gutter is the editor field with no step`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("field-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val pixels = captureToImage().pixels()

            val gutterLeft = (DesignTokens.chromeInset + DesignTokens.treeDefaultWidth +
                DesignTokens.contentGap).value
            val lineCount = editor.value.text.count { it == '\n' } + 1
            val gutterWidth = (lineCount.toString().length.coerceAtLeast(2) * 9 + 16 + 14).toFloat()
            val fieldLeft = (gutterLeft + gutterWidth + 2).toInt()
            val gapX = (gutterLeft - 1).toInt()
            // A row between two line boxes: the scrolled glyphs fill the text column, so the
            // field right of the gutter is only visible in the gaps between lines.
            val row = (120 until 700).first { y ->
                var low = 1.0
                var high = 0.0
                for (x in fieldLeft until fieldLeft + 200) {
                    val luma = relativeLuminance(Color(pixels[index(x, y)]))
                    low = minOf(low, luma)
                    high = maxOf(high, luma)
                }
                high - low < 0.01
            }
            var gap = 0.0
            var field = 0.0
            for (x in fieldLeft until fieldLeft + 8) {
                gap += relativeLuminance(Color(pixels[index(gapX, row)]))
                field += relativeLuminance(Color(pixels[index(x, row)]))
            }
            gap /= 8
            field /= 8
            var textLow = 1.0
            var textHigh = 0.0
            for (x in fieldLeft until fieldLeft + 400) {
                val luma = relativeLuminance(Color(pixels[index(x, row + 10)]))
                textLow = minOf(textLow, luma)
                textHigh = maxOf(textHigh, luma)
            }
            System.err.println(
                "field-step row=%d gap=%.5f field=%.5f step=%.5f text-range=%.4f".format(
                    row, gap, field, abs(gap - field), textHigh - textLow,
                ),
            )
            assertTrue(
                abs(gap - field) < 0.003,
                "the gap should match the editor field (%.5f vs %.5f)".format(gap, field),
            )
            assertTrue(
                textHigh - textLow > 0.2,
                "the text column should carry glyphs (range %.4f)".format(textHigh - textLow),
            )
        } finally {
            state.dispose()
        }
    }

    /**
     * The window inset left of and above the sidebar is the editor's own field colour; the
     * extension never paints a slab or a strip there.
     */
    /**
     * The floating sidebar spans `chromeInset` .. `H - chromeInset`: the strip under it is the
     * editor's field and its rounded bottom corners stay inside the window, so the background
     * extension never ends on a line inside the glass.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the sidebar ends chromeInset above the window bottom`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("bottom-inset-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val pixels = captureToImage().pixels()

            val inset = DesignTokens.chromeInset.value.toInt()
            val bottomTop = HEIGHT - inset
            // The window inset left of the sidebar is the field outside the sidebar's shadow.
            fun insetMean(fromY: Int, toY: Int): Double {
                var total = 0.0
                for (y in fromY until toY) {
                    for (x in 1 until 9) total += relativeLuminance(Color(pixels[index(x, y)]))
                }
                return total / ((toY - fromY) * 8.0)
            }

            // The bottom strip under the sidebar's columns is the editor field (plus the panel's
            // soft shadow), not the sidebar glass, which would be several times brighter.
            var under = 0.0
            var count = 0
            for (y in bottomTop + 1 until HEIGHT) {
                for (x in 40 until 260) {
                    under += relativeLuminance(Color(pixels[index(x, y)]))
                }
                count += 1
            }
            under /= count * 220.0
            val field = insetMean(bottomTop + 1, HEIGHT)
            System.err.println(
                "bottom-strip under=%.5f field=%.5f delta=%.5f".format(under, field, abs(under - field)),
            )
            assertTrue(
                abs(under - field) < 0.002,
                "the strip under the sidebar should be the editor field (%.5f vs %.5f)".format(under, field),
            )

            // A point inside the sidebar's bounding rect but outside its rounded bottom-left
            // corner: only the field may show there.
            val corner = relativeLuminance(Color(pixels[index(12, bottomTop - 2)]))
            System.err.println("bottom-corner=%.5f field=%.5f delta=%.5f".format(corner, field, abs(corner - field)))
            assertTrue(
                abs(corner - field) < 0.002,
                "the rounded bottom corner should show the field (%.5f vs %.5f)".format(corner, field),
            )

            // No second horizontal boundary inside the glass near its bottom edge.
            val upper = rowMeans(pixels, 100, 200, bottomTop - 16, bottomTop - 12).average()
            val lower = rowMeans(pixels, 100, 200, bottomTop - 8, bottomTop - 4).average()
            System.err.println("bottom-seam upper=%.5f lower=%.5f delta=%.5f".format(upper, lower, abs(upper - lower)))
            assertTrue(
                abs(upper - lower) < 0.01,
                "the glass should stay continuous above its bottom edge (%.5f vs %.5f)".format(upper, lower),
            )
        } finally {
            state.dispose()
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `the window inset beside the sidebar is the editor field`() = runSkikoComposeUiTest(
        size = Size(WIDTH.toFloat(), HEIGHT.toFloat()),
        density = Density(1f),
    ) {
        val state = AppState()
        try {
            val root = longProject("inset-project", glyph = 'y')
            state.openFolder(root)
            val session = requireNotNull(state.session)
            val editor = session.editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            setContent {
                CompositionLocalProvider(
                    LocalAnimationsEnabled provides false,
                    LocalWordmarkCaretBlink provides false,
                ) {
                    App(state, onOpenFolder = {})
                }
            }
            waitForIdle()
            val pixels = captureToImage().pixels()

            val inset = (DesignTokens.chromeInset.value * 0.8f).toInt()
            var corner = 0.0
            var cornerCount = 0
            for (y in 0 until inset) {
                for (x in 0 until inset) {
                    corner += relativeLuminance(Color(pixels[index(x, y)]))
                    cornerCount += 1
                }
            }
            corner /= cornerCount
            // The same plane just above the sidebar, then the empty field's own colour.
            var plane = 0.0
            var planeCount = 0
            for (y in 0 until inset) {
                for (x in 120 until 280) {
                    plane += relativeLuminance(Color(pixels[index(x, y)]))
                    planeCount += 1
                }
            }
            plane /= planeCount
            val fieldColor = blendOver(
                DesignTokens.surface.copy(alpha = DesignTokens.contentTintAlpha),
                DesignTokens.background,
            )
            val field = relativeLuminance(fieldColor)
            System.err.println(
                "inset-corner mean=%.5f plane=%.5f plane-delta=%.5f field=%.5f field-delta=%.5f".format(
                    corner, plane, abs(corner - plane), field, abs(corner - field),
                ),
            )
            assertTrue(
                abs(corner - plane) < 0.001,
                "the top-left inset should be the editor plane (%.5f vs %.5f)".format(corner, plane),
            )
            assertTrue(
                abs(corner - field) < 0.003,
                "the top-left inset should be the editor field (%.5f vs %.5f)".format(corner, field),
            )
        } finally {
            state.dispose()
        }
    }

    /**
     * A dense document must not turn the sidebar into a grey slab: its interior stays at the
     * sparse document's brightness while the mirrored field still carries the code's variation.
     */
    @Test
    fun `dense code keeps the sidebar interior at the sparse brightness`() {
        val denseState = AppState()
        val dense = try {
            val root = longProject("dense-project", glyph = 'y')
            denseState.openFolder(root)
            val editor = requireNotNull(denseState.session)
                .editors.openFile(VFile(root.resolve("Long.kt"))) as TextFileEditor
            editor.gotoLine("100:290")
            renderAppPixels(denseState)
        } finally {
            denseState.dispose()
        }
        val sparseState = AppState()
        val sparse = try {
            val root = project("sparse-project")
            sparseState.openFolder(root)
            requireNotNull(sparseState.session).editors.openFile(VFile(root.resolve("src/Main.kt")))
            renderAppPixels(sparseState)
        } finally {
            sparseState.dispose()
        }
        fun sidebarMean(pixels: IntArray): Double {
            var total = 0.0
            var count = 0
            for (y in 400 until 700) {
                for (x in 40 until 270) {
                    total += relativeLuminance(Color(pixels[index(x, y)]))
                    count += 1
                }
            }
            return total / count
        }
        val denseMean = sidebarMean(dense)
        val sparseMean = sidebarMean(sparse)
        System.err.println(
            "sidebar-luminance dense=%.5f sparse=%.5f delta=%.5f".format(
                denseMean, sparseMean, abs(denseMean - sparseMean),
            ),
        )
        assertTrue(
            abs(denseMean - sparseMean) < 0.003,
            "the sidebar should stay at the sparse brightness (%.5f vs %.5f)"
                .format(denseMean, sparseMean),
        )
    }

    /** Mean luminance of each row across `x` from `fromY` to `toY`. */
    private fun rowMeans(pixels: IntArray, fromX: Int, toX: Int, fromY: Int, toY: Int): DoubleArray =
        DoubleArray(toY - fromY) { row ->
            var sum = 0.0
            for (x in fromX until toX) sum += relativeLuminance(Color(pixels[index(x, fromY + row)]))
            sum / (toX - fromX)
        }

    /** Mean luminance of the tab pill band above [label]'s topmost node. */
    @OptIn(ExperimentalTestApi::class)
    private fun ComposeUiTest.topPill(pixels: IntArray, label: String): Double {
        val bounds = onAllNodesWithText(label, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .minBy { it.boundsInRoot.top }
            .boundsInRoot
        val x = bounds.center.x.toInt()
        val y = (DesignTokens.chromeInset + 6.dp).value.toInt()
        var total = 0.0
        var count = 0
        for (row in y..y + 3) {
            for (dx in -4..4) {
                total += relativeLuminance(Color(pixels[index(x + dx, row)]))
                count++
            }
        }
        return total / count
    }

    private fun longProject(name: String, glyph: Char): Path {
        val root = temp.resolve(name)
        Files.createDirectories(root)
        val content = (1..120).joinToString("\n") { line -> "line $line " + glyph.toString().repeat(340) }
        Files.writeString(root.resolve("Long.kt"), content)
        return root
    }

    private fun project(name: String): Path {
        val root = temp.resolve(name)
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Main.kt"), "package demo\n\nfun main() {\n    println(\"hi\")\n}\n")
        Files.writeString(root.resolve("src/Other.kt"), "package demo\n")
        return root
    }

    private fun demoProject(): Path {
        val root = temp.resolve("demo-project")
        Files.createDirectories(root.resolve("src"))
        Files.writeString(root.resolve("src/Main.kt"), "package demo\n\nfun main() {\n    println(\"hi\")\n}\n")
        Files.writeString(root.resolve("src/Greeting.kt"), "package demo\n\nclass Greeting\n")
        return root
    }

    private fun renderAppPixels(state: AppState): IntArray {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            CompositionLocalProvider(
                LocalAnimationsEnabled provides false,
                LocalWordmarkCaretBlink provides false,
            ) {
                App(state, onOpenFolder = {})
            }
        }
        return try {
            var nanos = 0L
            repeat(3) {
                scene.render(nanos).close()
                nanos += 16_000_000L
            }
            scene.render(nanos).pixels()
        } finally {
            scene.close()
        }
    }

    private fun org.jetbrains.skia.Image.pixels(): IntArray {
        val bitmap = toComposeImageBitmap()
        return IntArray(WIDTH * HEIGHT).also { bitmap.readPixels(it) }
    }

    private fun ImageBitmap.pixels(): IntArray = IntArray(WIDTH * HEIGHT).also { readPixels(it) }

    private fun assertChannelClose(actual: Color, expected: Color, what: String) {
        val red = abs(actual.red * 255f - expected.red * 255f)
        val green = abs(actual.green * 255f - expected.green * 255f)
        val blue = abs(actual.blue * 255f - expected.blue * 255f)
        assertTrue(
            red <= 2f && green <= 2f && blue <= 2f,
            "%s: actual #%02X%02X%02X expected #%02X%02X%02X".format(
                what,
                (actual.red * 255f).toInt(),
                (actual.green * 255f).toInt(),
                (actual.blue * 255f).toInt(),
                (expected.red * 255f).toInt(),
                (expected.green * 255f).toInt(),
                (expected.blue * 255f).toInt(),
            ),
        )
    }

    private fun meanAdjacentDiff(pixels: IntArray, fromX: Int, toX: Int, fromY: Int, toY: Int): Double {
        var total = 0.0
        var count = 0
        for (y in fromY until toY) {
            for (x in fromX until toX) {
                total += abs(
                    relativeLuminance(Color(pixels[index(x + 1, y)])) -
                        relativeLuminance(Color(pixels[index(x, y)])),
                )
                count += 1
            }
        }
        return total / count
    }

    private fun channel(color: Color, pick: Color.() -> Float): Float = pick(color) * 255f

    private fun index(x: Int, y: Int): Int = y * WIDTH + x

    companion object {
        private const val WIDTH = 1280
        private const val HEIGHT = 820

        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            System.setProperty("java.awt.headless", "true")
            Application.resetForTests()
            Application.init(TreeEditorGlassTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
