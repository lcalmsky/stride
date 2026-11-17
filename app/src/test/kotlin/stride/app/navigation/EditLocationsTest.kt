package stride.app.navigation

import stride.app.editor.TextChange
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EditLocationsTest {
    private val one = Path.of("/project/One.kt")
    private val two = Path.of("/project/Two.kt")

    /** The line of [offset] in [text], the same scan the app wires the locations to. */
    private fun lineOf(text: String): (Int) -> Int = { offset -> BookmarkManager.lineIndex(text, offset) }

    @Test
    fun `a second edit on the same line replaces the place instead of adding one`() {
        val locations = EditLocations()
        val text = "a\nbbbbbbbbb"

        locations.change(one, offset = 2, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 3, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)

        assertEquals(listOf(EditPlace(one, 3)), locations.places)
    }

    @Test
    fun `an edit on another line adds a place in front of the older one`() {
        val locations = EditLocations()
        val text = "aaaaaaaaaa\nbbbb\ncccccccccccccccccccccccccccccc"
        val fourth = text.indexOf("cccc")

        locations.change(one, offset = 10, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = fourth, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)
        locations.change(two, offset = 0, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)

        assertEquals(
            listOf(EditPlace(two, 0), EditPlace(one, fourth), EditPlace(one, 10)),
            locations.places,
        )
    }

    @Test
    fun `a place recorded on a line that moved above still merges with an edit on its new line`() {
        val locations = EditLocations()
        val before = "a\nb\nc\nX"
        val placeOffset = before.indexOf('X')
        locations.change(one, offset = placeOffset, oldLength = 0, newLength = 1, lineOf = lineOf(before), recorded = true)
        assertEquals(listOf(EditPlace(one, placeOffset)), locations.places)

        val after = "\n\na\nb\nc\nX"
        locations.change(one, offset = 0, oldLength = 0, newLength = 2, lineOf = lineOf(after), recorded = false)
        assertEquals(listOf(EditPlace(one, placeOffset + 2)), locations.places)

        locations.change(one, offset = placeOffset + 2, oldLength = 0, newLength = 1, lineOf = lineOf(after), recorded = true)

        assertEquals(listOf(EditPlace(one, placeOffset + 2)), locations.places)
    }

    @Test
    fun `the walk skips the place under the caret on its moved line`() {
        val locations = EditLocations()
        val before = "a\nb\nc\nX"
        val after = "\n\na\nb\nc\nX"
        val placeOffset = before.indexOf('X')
        locations.change(one, offset = placeOffset, oldLength = 0, newLength = 0, lineOf = lineOf(before), recorded = true)
        locations.change(one, offset = 0, oldLength = 0, newLength = 2, lineOf = lineOf(after), recorded = false)

        val moved = placeOffset + 2
        val caretLine = BookmarkManager.lineIndex(after, moved)
        assertNull(locations.next(one, caretLine, lineOf(after)))
    }

    @Test
    fun `a recorded set of changes adds one place at its first range`() {
        val locations = EditLocations()
        val text = "foo X foo Y foo"

        locations.changes(
            one,
            changes = listOf(TextChange(0, 3, "bar"), TextChange(6, 3, "bar"), TextChange(12, 3, "bar")),
            lineOf = lineOf(text),
            recorded = true,
        )

        assertEquals(listOf(EditPlace(one, 0)), locations.places)
    }

    @Test
    fun `a set of changes shifts a place between the ranges by the earlier ranges only`() {
        val locations = EditLocations()
        val text = "foo X foo Y foo"
        val place = text.indexOf('X')
        locations.change(one, offset = place, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)

        locations.changes(
            one,
            changes = listOf(TextChange(0, 3, "bar"), TextChange(6, 3, "bar"), TextChange(12, 3, "bar")),
            lineOf = lineOf(text),
            recorded = false,
        )

        assertEquals(place, locations.places.single().offset)
    }

    @Test
    fun `an unrecorded change adds nothing`() {
        val locations = EditLocations()
        val text = "abcdef"

        locations.change(one, offset = 4, oldLength = 2, newLength = 2, lineOf = lineOf(text), recorded = false)

        assertEquals(emptyList<EditPlace>(), locations.places)
    }

    @Test
    fun `at most fifty places are kept and the oldest is dropped`() {
        val locations = EditLocations()
        // A line per offset, 5 characters each ("line\n"), so every edit lands on its own line.
        val text = (0 until 55).joinToString("\n") { "line" }
        val lineStart = { line: Int -> line * 5 }

        for (line in 0 until 55) {
            locations.change(one, offset = lineStart(line), oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        }

        assertEquals(EditLocations.MAX_PLACES, locations.places.size)
        assertEquals(lineStart(54), locations.places.first().offset)
        assertEquals(lineStart(5), locations.places.last().offset)
    }

    @Test
    fun `a place after the replaced range moves by the length difference`() {
        val locations = EditLocations()
        val text = "a\n" + "b".repeat(20)

        locations.change(one, offset = 12, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 0, oldLength = 0, newLength = 3, lineOf = lineOf(text), recorded = true)

        assertEquals(15, locations.places.last().offset)
    }

    @Test
    fun `a place inside the replaced range moves to the range's new end`() {
        val locations = EditLocations()
        val text = "a".repeat(20)

        locations.change(one, offset = 7, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 5, oldLength = 4, newLength = 1, lineOf = lineOf(text), recorded = false)

        assertEquals(6, locations.places.single().offset)
    }

    @Test
    fun `a place before the replaced range stays where it is`() {
        val locations = EditLocations()
        val text = "a".repeat(20)

        locations.change(one, offset = 2, oldLength = 0, newLength = 1, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 5, oldLength = 2, newLength = 1, lineOf = lineOf(text), recorded = false)

        assertEquals(2, locations.places.single().offset)
    }

    @Test
    fun `the walk steps back one place per press and stops past the oldest`() {
        val locations = EditLocations()
        val text = "a\nbbbbbbbbbb\ncccccccccccccccccccc"
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 2, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 13, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)

        assertEquals(13, locations.next(two, 0, lineOf(text))?.offset)
        assertEquals(2, locations.next(two, 0, lineOf(text))?.offset)
        assertEquals(0, locations.next(two, 0, lineOf(text))?.offset)
        assertNull(locations.next(two, 0, lineOf(text)))
        assertEquals(13, locations.next(two, 0, lineOf(text))?.offset)
    }

    @Test
    fun `a walk skips the place under the caret`() {
        val locations = EditLocations()
        val text = "a\nbbbbbbbbbb\ncccccccccccccccccccc"
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 2, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)

        assertEquals(0, locations.next(one, 1, lineOf(text))?.offset)
    }

    @Test
    fun `a recorded edit starts the walk from the newest place again`() {
        val locations = EditLocations()
        val text = "a\nbbbbbbbbbb\ncccccccccccccccccccc"
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 2, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        assertEquals(2, locations.next(two, 0, lineOf(text))?.offset)
        assertEquals(0, locations.next(two, 0, lineOf(text))?.offset)

        locations.change(two, offset = 0, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)

        assertEquals(EditPlace(two, 0), locations.next(one, 0, lineOf(text)))
    }

    @Test
    fun `dropping a gone file drops all of its places`() {
        val locations = EditLocations()
        val text = "a".repeat(20)
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        locations.change(two, offset = 0, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)
        locations.change(one, offset = 5, oldLength = 0, newLength = 0, lineOf = lineOf(text), recorded = true)

        locations.drop(one)

        assertEquals(listOf(EditPlace(two, 0)), locations.places)
    }
}
