package stride.app.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IdentifierSnapshotTest {
    @Test
    fun `names and ranges belong to the same revision`() {
        val snapshot = IdentifierSnapshot.scan("val value = x")

        assertEquals(listOf(0..2, 4..8, 12..12), snapshot.ranges)
        assertEquals(listOf("val", "value", "x"), snapshot.names)
        assertEquals(
            snapshot.ranges.map { snapshot.text.substring(it.first, it.last + 1) },
            snapshot.names,
        )
    }

    @Test
    fun `excluding leaves out only the exact range`() {
        val snapshot = IdentifierSnapshot.scan("val ma = main")

        assertEquals(listOf("val", "main"), snapshot.namesExcluding(4..5))
        assertEquals(listOf("val", "ma", "main"), snapshot.namesExcluding(20..25))
    }

    @Test
    fun `an unexcluded read returns the cached list itself`() {
        val snapshot = IdentifierSnapshot.scan("alpha beta")

        assertSame(snapshot.names, snapshot.namesExcluding(null))
    }

    @Test
    fun `a scan checks for cancellation while it runs and at the start`() {
        val text = "ab ".repeat(100_000)
        var checks = 0

        IdentifierSnapshot.scan(text) { checks++ }

        assertTrue(checks > 2, "checked only $checks times")
    }

    @Test
    fun `a cancelled scan throws before it finishes`() {
        val text = "ab ".repeat(100_000)
        var checks = 0

        val thrown = assertThrows(IllegalStateException::class.java) {
            IdentifierSnapshot.scan(text) {
                checks++
                if (checks == 3) throw IllegalStateException("popup closed")
            }
        }

        assertEquals("popup closed", thrown.message)
        assertTrue(checks > 1, "the scan stopped before any progress check")
    }
}
