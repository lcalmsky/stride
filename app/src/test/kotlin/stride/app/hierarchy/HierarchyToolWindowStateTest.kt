package stride.app.hierarchy

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HierarchyToolWindowStateTest {
    private fun state(): HierarchyToolWindowState =
        HierarchyToolWindowState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))

    private fun tree(vararg roots: HierarchyNode): HierarchyTree =
        HierarchyTree("Type Hierarchy of demo.shapes.Circle", "Resolved through imports", roots.toList())

    private fun HierarchyToolWindowState.texts(): List<String> =
        rows.map { row ->
            when {
                row.searching -> "Searching…"
                row.error != null -> row.error
                else -> (if (row.expanded) "▾ " else if (row.expandable) "▸ " else "") + row.node?.text
            }
        }

    @Test
    fun `a row shows its depth and expansion marker`() {
        val state = state()
        state.show(
            tree(
                HierarchyNode(
                    "Circle",
                    detail = "demo.shapes.Circle",
                    expanded = true,
                    children = { listOf(HierarchyNode("Shape"), HierarchyNode("Geometry")) },
                ),
            ),
        )

        assertEquals(listOf("▾ Circle", "Shape", "Geometry"), state.texts())
        assertEquals(listOf(0, 1, 1), state.rows.map { it.depth })
        assertTrue(state.rows[0].expanded)
        assertFalse(state.rows[1].expandable)
        assertEquals("demo.shapes.Circle", state.listRows[0].secondary?.text)
    }

    @Test
    fun `a node marked expanded opens as soon as it is shown, and its expanded children follow`() {
        val state = state()
        state.show(
            tree(
                HierarchyNode(
                    "Circle",
                    expanded = true,
                    children = {
                        listOf(
                            HierarchyNode(
                                "Supertypes",
                                expanded = true,
                                children = { listOf(HierarchyNode("Shape")) },
                            ),
                        )
                    },
                ),
            ),
        )

        assertEquals(listOf("▾ Circle", "▾ Supertypes", "Shape"), state.texts())
    }

    @Test
    fun `children run once per node across collapsing and expanding again`() {
        val state = state()
        val calls = AtomicInteger()
        state.show(
            tree(
                HierarchyNode(
                    "Circle",
                    children = {
                        calls.incrementAndGet()
                        listOf(HierarchyNode("Shape"))
                    },
                ),
            ),
        )

        state.moveRight(0)
        assertEquals(listOf("▾ Circle", "Shape"), state.texts())
        state.moveLeft(0)
        assertEquals(listOf("▸ Circle"), state.texts())
        state.moveRight(0)

        assertEquals(listOf("▾ Circle", "Shape"), state.texts())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a searching row stands in for the children while they load`() {
        val state = state()
        val gate = CompletableDeferred<List<HierarchyNode>>()
        state.show(tree(HierarchyNode("Circle", children = { gate.await() })))

        state.moveRight(0)

        assertEquals(listOf("▾ Circle", "Searching…"), state.texts())
        assertNull(state.rows[1].node)

        gate.complete(listOf(HierarchyNode("Shape")))

        assertEquals(listOf("▾ Circle", "Shape"), state.texts())
    }

    @Test
    fun `a failing children call shows one error row and is not retried`() {
        val state = state()
        val calls = AtomicInteger()
        state.show(
            tree(
                HierarchyNode(
                    "Circle",
                    children = {
                        calls.incrementAndGet()
                        throw IllegalStateException("boom")
                    },
                ),
            ),
        )

        state.moveRight(0)
        assertEquals(listOf("▾ Circle", "Could not load: boom"), state.texts())

        state.moveLeft(0)
        state.moveRight(0)

        assertEquals(listOf("▾ Circle", "Could not load: boom"), state.texts())
        assertEquals(1, calls.get())
    }

    @Test
    fun `an empty children result makes the node a leaf`() {
        val state = state()
        state.show(tree(HierarchyNode("Circle", children = { emptyList() })))

        state.moveRight(0)

        assertEquals(listOf("Circle"), state.texts())
        assertFalse(state.rows[0].expandable)
        assertEquals(0, state.moveRight(0))
    }

    @Test
    fun `a replaced tree ignores the load the old tree started`() {
        val state = state()
        val gate = CompletableDeferred<List<HierarchyNode>>()
        val staleCalls = AtomicInteger()
        state.show(
            tree(
                HierarchyNode(
                    "Old",
                    children = {
                        staleCalls.incrementAndGet()
                        gate.await()
                    },
                ),
            ),
        )
        state.moveRight(0)
        assertEquals(listOf("▾ Old", "Searching…"), state.texts())

        state.show(tree(HierarchyNode("New")))

        assertEquals(listOf("New"), state.texts())
        gate.complete(listOf(HierarchyNode("Stale")))

        assertEquals(listOf("New"), state.texts())
        assertEquals(1, staleCalls.get())
    }

    @Test
    fun `right expands and moves to the first child, left collapses and moves to the parent`() {
        val state = state()
        state.show(
            tree(
                HierarchyNode(
                    "A",
                    children = {
                        listOf(
                            HierarchyNode("B", children = { listOf(HierarchyNode("C")) }),
                            HierarchyNode("D"),
                        )
                    },
                ),
            ),
        )
        assertEquals(listOf("▸ A"), state.texts())

        assertEquals(0, state.moveRight(0))
        assertEquals(listOf("▾ A", "▸ B", "D"), state.texts())
        assertEquals(1, state.moveRight(0))
        assertEquals(1, state.moveRight(1))
        assertEquals(listOf("▾ A", "▾ B", "C", "D"), state.texts())
        assertEquals(2, state.moveRight(1))

        // C is a leaf: left goes to its parent.
        assertEquals(1, state.moveLeft(2))
        assertEquals(1, state.moveLeft(1))
        assertEquals(listOf("▾ A", "▸ B", "D"), state.texts())
        assertEquals(0, state.moveLeft(1))
        assertEquals(1, state.moveRight(0))
        assertEquals(0, state.moveLeft(1))
        assertEquals(0, state.moveLeft(0))
        assertEquals(listOf("▸ A"), state.texts())
    }

    @Test
    fun `a leaf consumes the horizontal keys without moving`() {
        val state = state()
        state.show(tree(HierarchyNode("Leaf")))

        assertEquals(0, state.moveRight(0))
        assertEquals(0, state.moveLeft(0))
    }

    @Test
    fun `toggle flips the expansion of a node`() {
        val state = state()
        state.show(tree(HierarchyNode("Circle", children = { listOf(HierarchyNode("Shape")) })))

        state.toggle(0)
        assertEquals(listOf("▾ Circle", "Shape"), state.texts())
        state.toggle(0)
        assertEquals(listOf("▸ Circle"), state.texts())
    }

    @Test
    fun `clear drops the tree`() {
        val state = state()
        state.show(tree(HierarchyNode("Circle")))

        state.clear()

        assertNull(state.tree)
        assertTrue(state.rows.isEmpty())
        assertTrue(state.listRows.isEmpty())
    }

    @Test
    fun `a node location is what rows open`() {
        val state = state()
        val location = HierarchyLocation(Path.of("/project/demo/shapes/Circle.kt"), 12)
        state.show(tree(HierarchyNode("Circle", location = location)))

        assertEquals(location, state.rows[0].node?.location)
    }
}
