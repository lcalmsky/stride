package stride.app.glass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlassPieceTest {
    private fun piece(key: String = "a", x: Float = 10f) = GlassPiece(
        key = key,
        x = x,
        y = 20f,
        width = 100f,
        height = 30f,
        cornerRadius = 15f,
        style = GlassStyle.REGULAR,
        tint = 0x9914151B.toInt(),
    )

    @Test
    fun `same piece compares every reported field`() {
        assertTrue(sameGlassPiece(piece(), piece()))
        assertFalse(sameGlassPiece(piece(), piece(x = 11f)))
        assertFalse(sameGlassPiece(piece(), piece().copy(style = GlassStyle.CLEAR)))
        assertFalse(sameGlassPiece(piece(), piece().copy(tint = null)))
    }

    @Test
    fun `diff reports added updated and removed keys`() {
        val previous = listOf(piece("a"), piece("b"))
        val next = listOf(piece("b", x = 40f), piece("c"))
        val diff = diffGlassPieces(previous, next)
        assertEquals(listOf("c"), diff.added.map { it.key })
        assertEquals(listOf("b"), diff.updated.map { it.key })
        assertEquals(listOf("a"), diff.removed)
    }

    @Test
    fun `equal piece lists ignore key order`() {
        val first = piece("a")
        val second = piece("b")
        assertTrue(glassPiecesEqual(listOf(first, second), listOf(second, first)))
        assertFalse(glassPiecesEqual(listOf(first, second), listOf(first)))
    }

    @Test
    fun `corner radius is clamped to half the shorter side`() {
        assertEquals(5f, effectiveCornerRadius(50f, 100f, 10f))
        assertEquals(50f, effectiveCornerRadius(50f, 100f, 200f))
        assertEquals(0f, effectiveCornerRadius(0f, 100f, 200f))
        assertEquals(0f, effectiveCornerRadius(Float.NaN, 100f, 200f))
    }
}
