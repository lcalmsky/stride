package stride.app.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.ColorScheme
import stride.lang.TextAttributesKey
import stride.lang.TokenSpan

class CodeFoldingTest {
    private val kotlinSample = """
        package demo

        import alpha.One
        import beta.Two

        /**
         * Greets one person.
         *
         * Keeps the wording in one place.
         */
        class Greeter {
            fun greet(name: String): String {
                val message = "hi ${'$'}name"
                return listOf(1, 2).joinToString(", ") { it.toString() }
            }
        }
    """.trimIndent()

    @Test
    fun `candidates find imports, block comments, and multi-line brace pairs`() {
        val text = kotlinSample
        val importStart = text.indexOf("import ") + "import ".length
        val lastImport = text.indexOf("import beta.Two")
        val importEnd = text.indexOf('\n', lastImport)
        val kdocStart = text.indexOf("/**") + 3
        val kdocEnd = text.indexOf("*/")
        val classOpen = text.indexOf("class Greeter {") + "class Greeter ".length
        val classClose = text.lastIndexOf('}')
        val funOpen = text.indexOf("fun greet(name: String): String {") + "fun greet(name: String): String ".length
        val funClose = text.indexOf("\n    }") + 5

        assertEquals(
            listOf(
                FoldRegion(importStart, importEnd),
                FoldRegion(kdocStart, kdocEnd),
                FoldRegion(classOpen + 1, classClose),
                FoldRegion(funOpen + 1, funClose),
            ),
            FoldRegions.candidates(text),
        )
    }

    @Test
    fun `single-line braces are skipped`() {
        assertEquals(emptyList<FoldRegion>(), FoldRegions.candidates("fun f() { return 1 }\n"))
    }

    @Test
    fun `braces inside strings and comments are skipped`() {
        val text = "val a = \"{\"\nval b = \"}\"\n// {\n/* } */\n"
        assertEquals(emptyList<FoldRegion>(), FoldRegions.candidates(text))
    }

    @Test
    fun `an unterminated block comment is skipped`() {
        val text = "/* open\nstill open\n"
        assertEquals(emptyList<FoldRegion>(), FoldRegions.candidates(text))
    }

    @Test
    fun `a multi-line json list folds inside its object`() {
        val text = "{\n  \"items\": [\n    1,\n    2\n  ]\n}\n"
        val regions = FoldRegions.candidates(text)
        assertEquals(2, regions.size)
        val outer = regions.first()
        val inner = regions.last()
        assertEquals(1, outer.hiddenStart)
        assertEquals(text.lastIndexOf('}'), outer.hiddenEnd)
        assertTrue(outer.hiddenStart <= inner.hiddenStart && inner.hiddenEnd <= outer.hiddenEnd)
    }

    @Test
    fun `the candidate set is laminar and only brace extensions fold`() {
        val regions = FoldRegions.candidates(kotlinSample)
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                val a = regions[i]
                val b = regions[j]
                val nested = a.hiddenStart <= b.hiddenStart && b.hiddenEnd <= a.hiddenEnd ||
                    b.hiddenStart <= a.hiddenStart && a.hiddenEnd <= b.hiddenEnd
                val disjoint = a.hiddenEnd <= b.hiddenStart || b.hiddenEnd <= a.hiddenStart
                assertTrue(nested || disjoint, "$a partially overlaps $b")
            }
        }
        assertTrue("json" in BRACE_EXTENSIONS)
        assertFalse("txt" in BRACE_EXTENSIONS)
        assertEquals(emptyList<FoldRegion>(), FoldRegions.candidates(""))
    }

    @Test
    fun `the view maps offsets around one fold`() {
        val text = "abcdefghij"
        val folds = FoldView.of(text, listOf(FoldRegion(3, 7)))

        assertEquals(9, folds.viewLength)
        assertEquals(0, folds.toView(0))
        assertEquals(2, folds.toView(2))
        assertEquals(3, folds.toView(3))
        assertEquals(3, folds.toView(4))
        assertEquals(3, folds.toView(6))
        assertEquals(6, folds.toView(7))
        assertEquals(9, folds.toView(10))
        assertEquals(9, folds.originalToTransformed(10))

        assertEquals(0, folds.toDoc(0))
        assertEquals(2, folds.toDoc(2))
        assertEquals(3, folds.toDoc(3))
        assertEquals(3, folds.toDoc(4))
        assertEquals(7, folds.toDoc(5))
        assertEquals(7, folds.toDoc(6))
        assertEquals(10, folds.toDoc(9))
    }

    @Test
    fun `two folds map both sides and round trip outside the hidden ranges`() {
        val text = "01234567890123456789"
        val first = FoldRegion(2, 5)
        val second = FoldRegion(10, 15)
        val folds = FoldView.of(text, listOf(first, second))

        assertEquals(18, folds.viewLength)
        assertEquals(1, folds.toView(1))
        assertEquals(2, folds.toView(2))
        assertEquals(2, folds.toView(4))
        assertEquals(5, folds.toView(5))
        assertEquals(9, folds.toView(9))
        assertEquals(10, folds.toView(10))
        assertEquals(10, folds.toView(12))
        assertEquals(13, folds.toView(15))
        assertEquals(18, folds.toView(20))

        assertEquals(2, folds.toDoc(2))
        assertEquals(2, folds.toDoc(3))
        assertEquals(5, folds.toDoc(4))
        assertEquals(5, folds.toDoc(5))
        assertEquals(9, folds.toDoc(9))
        assertEquals(10, folds.toDoc(10))
        assertEquals(10, folds.toDoc(11))
        assertEquals(15, folds.toDoc(12))
        assertEquals(15, folds.toDoc(13))
        assertEquals(20, folds.toDoc(18))

        for (offset in 0..text.length) {
            val hidden = listOf(first, second).any { it.hiddenStart <= offset && offset < it.hiddenEnd }
            if (!hidden) assertEquals(offset, folds.toDoc(folds.toView(offset)), "offset $offset")
        }
    }

    @Test
    fun `nested input keeps only the maximal folds`() {
        val outer = FoldRegion(2, 18)
        val inner = FoldRegion(5, 9)
        val folds = FoldView.of("0123456789012345678901", listOf(outer, inner))
        assertEquals(listOf(outer), folds.folds)
        assertEquals(FoldView.NONE, FoldView.of("abc", emptyList()))
        assertEquals(FoldView.NONE, FoldView.of("abc", listOf(FoldRegion(2, 2))), "empty range")
        assertEquals(FoldView.NONE, FoldView.of("abc", listOf(FoldRegion(5, 9))), "out of range")
    }

    @Test
    fun `rows skip the lines a fold hides`() {
        val text = "a\nb\nc\nd\ne\nf\n"
        val folds = FoldView.of(text, listOf(FoldRegion(1, 5), FoldRegion(7, 12)))

        assertEquals(2, folds.rowCount)
        assertEquals(listOf(0, 3), folds.visibleLines.toList())
        assertEquals(0, folds.rowOf(0))
        assertEquals(0, folds.rowOf(1))
        assertEquals(0, folds.rowOf(2))
        assertEquals(1, folds.rowOf(3))
        assertEquals(1, folds.rowOf(6))
        assertEquals(0, folds.visibleRowOf(0))
        assertNull(folds.visibleRowOf(1))
        assertNull(folds.visibleRowOf(2))
        assertEquals(1, folds.visibleRowOf(3))
        assertNull(folds.visibleRowOf(4))
        assertNull(folds.visibleRowOf(6), "the fold hides the last line")
        assertEquals(0, folds.lineOfRow(0))
        assertEquals(3, folds.lineOfRow(1))

        val none = FoldView.NONE
        assertTrue(none.isEmpty)
        assertEquals(4, none.rowOf(4))
        assertEquals(4, none.visibleRowOf(4))
        assertEquals(7, none.lineOfRow(7))
    }

    @Test
    fun `the transformation replaces a fold with the placeholder`() {
        val text = "abcdefghij"
        val folds = FoldView.of(text, listOf(FoldRegion(3, 7)))
        val transformation = HighlightingVisualTransformation(
            spans = listOf(
                TokenSpan(4, 6, TextAttributesKey.KEYWORD),
                TokenSpan(1, 5, TextAttributesKey.KEYWORD),
                TokenSpan(5, 9, TextAttributesKey.KEYWORD),
            ),
            backgroundSpans = emptyList(),
            scheme = ColorScheme.DARK,
            folds = folds,
            placeholderStyle = SpanStyle(color = Color.Red),
        )

        val transformed = transformation.filter(AnnotatedString(text))
        assertEquals("abc...hij", transformed.text.text)
        assertSame(folds, transformed.offsetMapping)
        assertTrue(transformed.text.spanStyles.any { it.start == 3 && it.end == 6 && it.item.color == Color.Red })
        val bold = transformed.text.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
        assertEquals(listOf(1 to 3, 6 to 8), bold.map { it.start to it.end })
        for (range in bold) {
            assertFalse(range.start < 6 && 3 < range.end, "span $range covers the placeholder")
        }
    }

    @Test
    fun `without folds the transformation keeps the text and the identity mapping`() {
        val text = "val answer = 42\n"
        val transformation = HighlightingVisualTransformation(
            spans = listOf(TokenSpan(0, 3, TextAttributesKey.KEYWORD)),
            scheme = ColorScheme.DARK,
        )
        val transformed = transformation.filter(AnnotatedString(text))
        assertEquals(text, transformed.text.text)
        assertTrue(transformed.offsetMapping === OffsetMapping.Identity)
    }

    @Test
    fun `the three argument constructor keeps the identity mapping`() {
        val transformation = HighlightingVisualTransformation(
            listOf(TokenSpan(0, 3, TextAttributesKey.KEYWORD)),
            listOf(BackgroundSpan(0, 3, Color.Red)),
            ColorScheme.DARK,
        )
        val transformed = transformation.filter(AnnotatedString("val x"))
        assertEquals("val x", transformed.text.text)
        assertSame(OffsetMapping.Identity, transformed.offsetMapping)
    }

    @Test
    fun `a crlf import fold ends before the carriage return`() {
        val text = "import alpha.One\r\nimport beta.Two\r\n\r\nfun main() {\r\n    run()\r\n}\r\n"
        val second = text.indexOf("import beta.Two")
        val fold = FoldRegion(text.indexOf("import ") + "import ".length, text.indexOf('\r', second))
        assertEquals(fold, FoldRegions.candidates(text).first { it.hiddenStart == fold.hiddenStart })

        val view = FoldView.of(text, listOf(fold))
        assertNull(view.visibleRowOf(1))
        assertEquals(0, view.rowOf(1))
        assertEquals(1, view.rowOf(2))
        assertEquals(listOf(0, 2), view.visibleLines.take(2).toList())
    }

    @Test
    fun `a fold reaching the last line maps the end without a trailing newline`() {
        val text = "fun f() {\n    one\n    two\n}"
        val fold = FoldRegion(text.indexOf('{') + 1, text.lastIndexOf('}'))
        val view = FoldView.of(text, listOf(fold))

        assertEquals(13, view.toView(text.length))
        assertEquals(view.viewLength, view.toView(text.length))
        assertEquals(1, view.rowCount)
        assertEquals(listOf(0), view.visibleLines.toList())
        assertNull(view.visibleRowOf(1))
        assertNull(view.visibleRowOf(3))
    }

    @Test
    fun `adjacent folds map both placeholder ends`() {
        val text = "0123456789abcdef"
        val first = FoldRegion(2, 5)
        val second = FoldRegion(5, 9)
        val view = FoldView.of(text, listOf(first, second))

        assertEquals(15, view.viewLength)
        assertEquals(2, view.placeholderStart(0))
        assertEquals(5, view.placeholderStart(1))
        assertEquals(2, view.toView(first.hiddenStart))
        assertEquals(5, view.toView(second.hiddenStart))
        assertEquals(first.hiddenEnd, view.toDoc(5))
        assertEquals(second.hiddenEnd, view.toDoc(8))
        assertEquals(8, view.toView(9))
        assertEquals(9, view.toDoc(8))
        assertEquals(15, view.toView(16))
    }

    @Test
    fun `effective folds drop partially overlapping regions`() {
        val kept = effectiveFolds(
            listOf(FoldRegion(0, 10), FoldRegion(5, 15), FoldRegion(20, 30), FoldRegion(25, 28)),
        )
        assertEquals(listOf(FoldRegion(0, 10), FoldRegion(20, 30)), kept)
        for (index in 1 until kept.size) {
            assertTrue(kept[index].hiddenStart >= kept[index - 1].hiddenEnd, "folds $kept overlap")
        }
    }

    @Test
    fun `twenty thousand sibling folds stay under a second`() {
        val blocks = 20_000
        val text = buildString(blocks * 32) {
            for (index in 0 until blocks) append("fun f$index() {\n    val value = $index\n}\n")
        }

        val candidateStart = System.nanoTime()
        val candidates = FoldRegions.candidates(text)
        val candidateMillis = (System.nanoTime() - candidateStart) / 1_000_000

        val effectiveStart = System.nanoTime()
        val effective = effectiveFolds(candidates)
        val effectiveMillis = (System.nanoTime() - effectiveStart) / 1_000_000

        val viewStart = System.nanoTime()
        val view = FoldView.of(text, effective)
        val viewMillis = (System.nanoTime() - viewStart) / 1_000_000

        assertEquals(blocks, candidates.size)
        assertEquals(blocks, effective.size)
        assertEquals(blocks, view.folds.size)
        assertTrue(candidateMillis < 1_000, "candidates took $candidateMillis ms")
        assertTrue(effectiveMillis < 1_000, "effectiveFolds took $effectiveMillis ms")
        assertTrue(viewMillis < 1_000, "FoldView.of took $viewMillis ms")
    }

    @Test
    fun `a token or background span covering a fold leaves the placeholder unstyled`() {
        val text = "/**\n * x\n */\nfun f() {}\n"
        val folds = FoldView.of(text, listOf(FoldRegion(3, 10)))
        val placeholderStyle = SpanStyle(color = Color.Red)
        val transformation = HighlightingVisualTransformation(
            spans = listOf(TokenSpan(1, 12, TextAttributesKey.KEYWORD)),
            backgroundSpans = listOf(BackgroundSpan(1, 12, Color.Yellow)),
            scheme = ColorScheme.DARK,
            folds = folds,
            placeholderStyle = placeholderStyle,
        )

        val transformed = transformation.filter(AnnotatedString(text))
        assertEquals("/**...*/\nfun f() {}\n", transformed.text.text)
        val start = folds.placeholderStart(0)
        val scheme = ColorScheme.DARK
        val defaultColor = textAttributesToSpanStyle(
            requireNotNull(scheme.getAttributes(TextAttributesKey.DEFAULT_TEXT)),
        ).color
        for (offset in start until start + FOLD_PLACEHOLDER.length) {
            val applied = transformed.text.spanStyles.filter { it.start <= offset && offset < it.end }
            assertTrue(applied.isNotEmpty(), "no style at placeholder offset $offset")
            for (range in applied) {
                val item = range.item
                val plain = (item.color == defaultColor || item.color == placeholderStyle.color) &&
                    item.fontWeight == null &&
                    item.fontStyle == null &&
                    item.textDecoration == null &&
                    (item.background == Color.Unspecified || item.background.alpha == 0f)
                assertTrue(item == placeholderStyle || plain, "$range covers placeholder offset $offset")
            }
        }
    }
}
