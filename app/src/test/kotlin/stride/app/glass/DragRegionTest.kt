package stride.app.glass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DragRegionTest {
    private val row = DragRect(0f, 0f, 1000f, 28f)

    @Test
    fun `capsules are cut out of the drag row`() {
        val rects = dragRectangles(
            listOf(row),
            listOf(DragRect(100f, 0f, 100f, 28f), DragRect(300f, 0f, 200f, 28f)),
        )
        assertEquals(
            listOf(
                DragRect(0f, 0f, 100f, 28f),
                DragRect(200f, 0f, 100f, 28f),
                DragRect(500f, 0f, 500f, 28f),
            ),
            rects,
        )
    }

    @Test
    fun `a blocker outside the band leaves the row whole`() {
        assertEquals(
            listOf(row),
            dragRectangles(listOf(row), listOf(DragRect(100f, 40f, 100f, 30f))),
        )
    }

    @Test
    fun `a partially overlapping blocker is clipped to the row`() {
        assertEquals(
            listOf(DragRect(0f, 0f, 900f, 28f)),
            dragRectangles(listOf(row), listOf(DragRect(900f, 0f, 300f, 28f))),
        )
        assertEquals(
            listOf(DragRect(100f, 0f, 900f, 28f)),
            dragRectangles(listOf(row), listOf(DragRect(-100f, 0f, 200f, 28f))),
        )
    }

    @Test
    fun `touching and overlapping blockers merge without zero width strips`() {
        val rects = dragRectangles(
            listOf(row),
            listOf(DragRect(100f, 0f, 100f, 28f), DragRect(200f, 0f, 100f, 28f), DragRect(250f, 0f, 50f, 28f)),
        )
        assertEquals(listOf(DragRect(0f, 0f, 100f, 28f), DragRect(300f, 0f, 700f, 28f)), rects)
    }

    @Test
    fun `a row fully covered by capsules yields nothing`() {
        assertTrue(dragRectangles(listOf(row), listOf(DragRect(0f, 0f, 1000f, 28f))).isEmpty())
    }

    @Test
    fun `empty regions and blockers are ignored`() {
        assertTrue(dragRectangles(listOf(DragRect(0f, 0f, 0f, 28f)), emptyList()).isEmpty())
        assertEquals(listOf(row), dragRectangles(listOf(row), listOf(DragRect(100f, 0f, 0f, 28f))))
    }
}
