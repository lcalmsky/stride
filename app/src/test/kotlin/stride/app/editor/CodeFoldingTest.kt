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
}
