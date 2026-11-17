package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RecentLocationsTest {
    private val root = Path.of("/project")
    private val app = Path.of("/project/src/App.kt")
    private val greet = Path.of("/project/src/Greeting.kt")

    private val appText = "class App {\n    fun main() {\n        greet()\n    }\n}\n"
    private val greetText = "fun greet() {\n    println(\"hi\")\n}\n"
    private val texts = mapOf(app to appText, greet to greetText)

    private fun rows(
        vararg locations: NavigationLocation,
        caret: NavigationLocation? = null,
    ): List<RecentLocationRow> = recentLocationRows(locations.toList(), root, caret) { texts[it] }

    @Test
    fun `rows run newest first and carry their line`() {
        val top = NavigationLocation(app, appText.indexOf("class App"))
        val newest = NavigationLocation(greet, greetText.indexOf("println"))

        val rows = rows(top, newest)

        assertEquals(listOf(greet, app), rows.map { it.location.path })
        assertEquals(listOf(2, 1), rows.map { it.line })
        assertEquals(listOf("Greeting.kt", "App.kt"), rows.map { it.fileName })
        assertEquals(listOf("src", "src"), rows.map { it.directory })
        assertEquals(listOf("src/Greeting.kt", "src/App.kt"), rows.map { it.relativePath })
    }

    @Test
    fun `the same file and line keeps the newest place`() {
        val lineStart = appText.indexOf("greet")
        val older = NavigationLocation(app, lineStart)
        val newer = NavigationLocation(app, lineStart + 2)
        val other = NavigationLocation(greet, 0)

        val rows = rows(older, newer, other)

        assertEquals(listOf(greet, app), rows.map { it.location.path })
        assertEquals(newer.offset, rows.single { it.location.path == app }.location.offset)
    }

    @Test
    fun `the caret place is left out`() {
        val caret = NavigationLocation(app, appText.indexOf("greet"))
        val rows = rows(
            NavigationLocation(app, appText.indexOf("class App")),
            NavigationLocation(greet, 0),
            caret,
            caret = caret,
        )

        assertEquals(listOf(greet, app), rows.map { it.location.path })
        assertTrue(rows.none { it.location == caret })
    }

    @Test
    fun `a row carries the place line and one line on each side`() {
        val row = rows(NavigationLocation(app, appText.indexOf("greet"))).single()

        assertEquals(listOf(2, 3, 4), row.code.map { it.number })
        assertEquals(listOf(false, true, false), row.code.map { it.place })
        assertEquals("greet()", row.code.single { it.place }.text)
    }

    @Test
    fun `the fragment is clamped at the first and the last line`() {
        val first = rows(NavigationLocation(app, 0)).single().code
        val last = rows(NavigationLocation(greet, greetText.length)).single().code

        assertEquals(listOf(1, 2), first.map { it.number })
        assertTrue(first.first().place)
        assertEquals(listOf(3, 4), last.map { it.number })
        assertTrue(last.last().place)
    }

    @Test
    fun `a file whose text is not loaded keeps its offset and shows no line`() {
        val rows = recentLocationRows(
            listOf(NavigationLocation(app, 5), NavigationLocation(app, 5), NavigationLocation(app, 9)),
            root,
            null,
        ) { null }

        assertEquals(2, rows.size)
        assertNull(rows.first().line)
        assertTrue(rows.all { it.code.isEmpty() })
        assertEquals(9, rows.first().location.offset)
    }

    @Test
    fun `a file outside the project keeps its absolute path`() {
        val outside = Path.of("/elsewhere/Other.kt")
        val rows = recentLocationRows(listOf(NavigationLocation(outside, 0)), root, null) { null }

        assertEquals("/elsewhere/Other.kt", rows.single().relativePath)
        assertEquals("Other.kt", rows.single().fileName)
        assertEquals("/elsewhere", rows.single().directory)
    }

    @Test
    fun `the selected place keeps its row when the rows collapse`() {
        val other = NavigationLocation(app, appText.indexOf("class App"))
        val sameLineOlder = NavigationLocation(greet, greetText.indexOf("println"))
        val sameLineNewer = NavigationLocation(greet, greetText.indexOf("println") + 2)
        val history = listOf(other, sameLineOlder, sameLineNewer)

        // Before the text arrives the rows are keyed by path and offset, so the two places stay.
        val before = recentLocationRows(history, root, null) { null }
        assertEquals(listOf(sameLineNewer, sameLineOlder, other), before.map { it.location })
        assertEquals(1, recentLocationIndex(before, sameLineOlder))

        // The text dedupes the two places of one line, so the row the user chose is gone: the
        // selection falls back to the first row, not to whatever ordinal it used to hold.
        val after = recentLocationRows(history, root, null) { texts[it] }
        assertEquals(listOf(sameLineNewer, other), after.map { it.location })
        assertEquals(0, recentLocationIndex(after, sameLineOlder))
        assertEquals(sameLineNewer, after[recentLocationIndex(after, sameLineOlder)].location)
        // The bare ordinal the selection used before now names a row the user never chose.
        assertEquals(other, after[1].location)
        assertEquals(1, recentLocationIndex(after, other))
        assertEquals(0, recentLocationIndex(after, null))
    }
}
