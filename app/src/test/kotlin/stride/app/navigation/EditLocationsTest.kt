package stride.app.navigation

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EditLocationsTest {
    private val one = Path.of("/project/One.kt")
    private val two = Path.of("/project/Two.kt")

    @Test
    fun `a second edit on the same line replaces the place instead of adding one`() {
        val locations = EditLocations()

        locations.change(one, offset = 10, oldLength = 0, newLength = 1, line = 2, recorded = true)
        locations.change(one, offset = 11, oldLength = 0, newLength = 1, line = 2, recorded = true)

        assertEquals(listOf(EditPlace(one, 11, 2)), locations.places)
    }

    @Test
    fun `an edit on another line adds a place in front of the older one`() {
        val locations = EditLocations()

        locations.change(one, offset = 10, oldLength = 0, newLength = 1, line = 2, recorded = true)
        locations.change(one, offset = 30, oldLength = 0, newLength = 1, line = 4, recorded = true)
        locations.change(two, offset = 0, oldLength = 0, newLength = 1, line = 0, recorded = true)

        assertEquals(
            listOf(EditPlace(two, 0, 0), EditPlace(one, 30, 4), EditPlace(one, 10, 2)),
            locations.places,
        )
    }

    @Test
    fun `an unrecorded change adds nothing`() {
        val locations = EditLocations()

        locations.change(one, offset = 4, oldLength = 2, newLength = 2, line = 1, recorded = false)

        assertEquals(emptyList<EditPlace>(), locations.places)
    }

    @Test
    fun `at most fifty places are kept and the oldest is dropped`() {
        val locations = EditLocations()

        for (line in 0 until 55) {
            locations.change(one, offset = 0, oldLength = 0, newLength = 0, line = line, recorded = true)
        }

        assertEquals(EditLocations.MAX_PLACES, locations.places.size)
        assertEquals(54, locations.places.first().line)
        assertEquals(5, locations.places.last().line)
    }

    @Test
    fun `a place after the replaced range moves by the length difference`() {
        val locations = EditLocations()
        locations.change(one, offset = 20, oldLength = 0, newLength = 1, line = 3, recorded = true)

        locations.change(one, offset = 5, oldLength = 0, newLength = 3, line = 0, recorded = true)

        assertEquals(23, locations.places.last().offset)
    }

    @Test
    fun `a place inside the replaced range moves to the range's new end`() {
        val locations = EditLocations()
        locations.change(one, offset = 7, oldLength = 0, newLength = 1, line = 1, recorded = true)

        locations.change(one, offset = 5, oldLength = 4, newLength = 1, line = 1, recorded = false)

        assertEquals(6, locations.places.single().offset)
    }

    @Test
    fun `a place before the replaced range stays where it is`() {
        val locations = EditLocations()
        locations.change(one, offset = 2, oldLength = 0, newLength = 1, line = 0, recorded = true)

        locations.change(one, offset = 5, oldLength = 2, newLength = 1, line = 1, recorded = false)

        assertEquals(2, locations.places.single().offset)
    }

    @Test
    fun `the walk steps back one place per press and stops past the oldest`() {
        val locations = EditLocations()
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, line = 0, recorded = true)
        locations.change(one, offset = 10, oldLength = 0, newLength = 0, line = 1, recorded = true)
        locations.change(one, offset = 20, oldLength = 0, newLength = 0, line = 2, recorded = true)

        assertEquals(2, locations.next(two, 0)?.line)
        assertEquals(1, locations.next(two, 0)?.line)
        assertEquals(0, locations.next(two, 0)?.line)
        assertNull(locations.next(two, 0))
        assertEquals(2, locations.next(two, 0)?.line)
    }

    @Test
    fun `a walk skips the place under the caret`() {
        val locations = EditLocations()
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, line = 0, recorded = true)
        locations.change(one, offset = 10, oldLength = 0, newLength = 0, line = 1, recorded = true)

        assertEquals(0, locations.next(one, 1)?.line)
    }

    @Test
    fun `a recorded edit starts the walk from the newest place again`() {
        val locations = EditLocations()
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, line = 0, recorded = true)
        locations.change(one, offset = 10, oldLength = 0, newLength = 0, line = 1, recorded = true)
        assertEquals(1, locations.next(two, 0)?.line)
        assertEquals(0, locations.next(two, 0)?.line)

        locations.change(two, offset = 0, oldLength = 0, newLength = 0, line = 5, recorded = true)

        assertEquals(EditPlace(two, 0, 5), locations.next(one, 0))
    }

    @Test
    fun `dropping a gone file drops all of its places`() {
        val locations = EditLocations()
        locations.change(one, offset = 0, oldLength = 0, newLength = 0, line = 0, recorded = true)
        locations.change(two, offset = 0, oldLength = 0, newLength = 0, line = 0, recorded = true)
        locations.change(one, offset = 5, oldLength = 0, newLength = 0, line = 2, recorded = true)

        locations.drop(one)

        assertEquals(listOf(EditPlace(two, 0, 0)), locations.places)
    }
}
