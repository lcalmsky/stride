package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class NavigationHistoryTest {
    private val a = Paths.get("/project/a.kt")
    private val b = Paths.get("/project/b.kt")

    private fun location(path: String, offset: Int) = NavigationLocation(Paths.get(path), offset)

    @Test
    fun `a repeated location is not pushed again`() {
        val history = NavigationHistory()

        history.push(location("/project/a.kt", 3))
        history.push(location("/project/a.kt", 3))

        assertEquals(1, history.size)
    }

    @Test
    fun `back and forward walk the entries and stop at the ends`() {
        val history = NavigationHistory()
        history.push(location("/project/a.kt", 1))
        history.push(location("/project/b.kt", 2))

        assertEquals(location("/project/a.kt", 1), history.back())
        assertNull(history.back())
        assertEquals(location("/project/b.kt", 2), history.forward())
        assertNull(history.forward())
    }

    @Test
    fun `push after going back drops the forward entries`() {
        val history = NavigationHistory()
        history.push(location("/project/a.kt", 1))
        history.push(location("/project/b.kt", 2))
        history.push(location("/project/c.kt", 3))
        history.back()
        history.back()

        history.push(location("/project/d.kt", 4))

        assertEquals(listOf("/project/a.kt", "/project/d.kt"), history.locations().map { it.path.toString() })
        assertNull(history.forward())
    }

    @Test
    fun `the cap keeps the newest entries`() {
        val history = NavigationHistory(NavigationHistory.MAX_ENTRIES)
        repeat(NavigationHistory.MAX_ENTRIES + 5) { index -> history.push(location("/project/$index.kt", 0)) }

        assertEquals(NavigationHistory.MAX_ENTRIES, history.size)
        assertEquals("/project/5.kt", history.locations().first().path.toString())
        assertEquals("/project/104.kt", history.current?.path.toString())
    }

    @Test
    fun `updateCurrent moves the caret of the current file only`() {
        val history = NavigationHistory()
        history.push(NavigationLocation(a, 1))

        history.updateCurrent(a, 9)
        history.updateCurrent(b, 4)

        assertEquals(NavigationLocation(a, 9), history.current)
        assertEquals(1, history.size)
    }

    @Test
    fun `record is suspended inside withoutRecording`() {
        val history = NavigationHistory()
        history.push(NavigationLocation(a, 1))

        history.withoutRecording { history.record(NavigationLocation(b, 2)) }

        assertEquals(1, history.size)
        history.record(NavigationLocation(b, 2))
        assertEquals(2, history.size)
    }

    @Test
    fun `clear drops every entry`() {
        val history = NavigationHistory()
        history.push(NavigationLocation(a, 1))

        history.clear()

        assertEquals(0, history.size)
        assertNull(history.current)
    }

    @Test
    fun `dropCurrent removes the reached entry and keeps walking from before it`() {
        val history = NavigationHistory()
        history.push(location("/project/a.kt", 1))
        history.push(location("/project/b.kt", 2))
        history.push(location("/project/c.kt", 3))
        history.back()

        history.dropCurrent()

        assertEquals(location("/project/a.kt", 1), history.current)
        assertEquals(listOf("/project/a.kt", "/project/c.kt"), history.locations().map { it.path.toString() })
        assertEquals(location("/project/c.kt", 3), history.forward())
    }

    @Test
    fun `dropCurrent on an empty history changes nothing`() {
        val history = NavigationHistory()

        history.dropCurrent()

        assertEquals(0, history.size)
        assertNull(history.current)
    }
}
