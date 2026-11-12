package stride.app.glass

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlassRegistryTest {
    private val calls = ArrayList<List<GlassPiece>>()
    private val dragCalls = ArrayList<List<DragRect>>()
    private val registry = GlassRegistry(
        sync = { _, pieces ->
            calls += pieces
            pieces.size
        },
        dragSync = { _, rects ->
            dragCalls += rects
            rects.size
        },
    )

    private fun piece(key: String, x: Float = 10f) = GlassPiece(
        key = key,
        x = x,
        y = 20f,
        width = 100f,
        height = 30f,
        cornerRadius = 15f,
    )

    @Test
    fun `nothing is synced before the window is attached`() {
        registry.report(piece("a"))
        assertFalse(registry.flush())
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `one sync per changed frame and none when unchanged`() {
        registry.attach(7L)
        registry.report(piece("a"))
        registry.report(piece("b"))
        assertTrue(registry.flush())
        assertEquals(1, calls.size)
        assertEquals(setOf("a", "b"), calls.single().map { it.key }.toSet())

        registry.report(piece("b"))
        assertFalse(registry.flush(), "an identical report must not cross the bridge")
        assertEquals(1, calls.size)
    }

    @Test
    fun `a moved piece is synced once`() {
        registry.attach(7L)
        registry.report(piece("a"))
        registry.flush()
        registry.report(piece("a", x = 40f))
        assertTrue(registry.flush())
        assertEquals(2, calls.size)
        assertEquals(40f, calls.last().single().x)
    }

    @Test
    fun `removing a piece syncs the smaller set`() {
        registry.attach(7L)
        registry.report(piece("a"))
        registry.report(piece("b"))
        registry.flush()
        registry.remove("a")
        assertTrue(registry.flush())
        assertEquals(listOf("b"), calls.last().map { it.key })
    }

    @Test
    fun `detach stops syncing`() {
        registry.attach(7L)
        registry.report(piece("a"))
        registry.flush()
        registry.detach()
        registry.report(piece("b"))
        assertFalse(registry.flush())
        assertEquals(1, calls.size)
    }

    @Test
    fun `drag regions are the reported row minus the glass pieces`() {
        registry.attach(7L)
        registry.reportDragRegion("title-row", DragRect(0f, 0f, 500f, 28f))
        registry.flush()
        assertEquals(listOf(DragRect(0f, 0f, 500f, 28f)), dragCalls.last())

        registry.report(piece("a").copy(x = 100f, y = 0f, width = 100f, height = 28f))
        assertTrue(registry.flush())
        assertEquals(
            listOf(DragRect(0f, 0f, 100f, 28f), DragRect(200f, 0f, 300f, 28f)),
            dragCalls.last(),
        )
        assertEquals(dragCalls.last(), registry.dragRectangles)
    }

    @Test
    fun `an unchanged drag region does not cross the bridge twice`() {
        registry.attach(7L)
        registry.reportDragRegion("title-row", DragRect(0f, 0f, 500f, 28f))
        registry.flush()
        val count = dragCalls.size
        assertFalse(registry.flush())
        assertEquals(count, dragCalls.size)
    }

    @Test
    fun `removing the drag region clears the native rectangles`() {
        registry.attach(7L)
        registry.reportDragRegion("title-row", DragRect(0f, 0f, 500f, 28f))
        registry.flush()
        registry.removeDragRegion("title-row")
        assertTrue(registry.flush())
        assertTrue(dragCalls.last().isEmpty())
    }
}
