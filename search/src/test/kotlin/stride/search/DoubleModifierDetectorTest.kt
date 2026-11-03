package stride.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DoubleModifierDetectorTest {
    private var now = 0L
    private val fired = ArrayList<ModifierKey>()
    private lateinit var detector: DoubleModifierDetector

    @BeforeEach
    fun setUp() {
        now = 0L
        fired.clear()
        detector = DoubleModifierDetector({ now }, { fired.add(it) })
    }

    private fun press(at: Long, key: ModifierKey = ModifierKey.SHIFT) {
        now = at
        detector.modifierPressed(key)
    }

    private fun release(at: Long, key: ModifierKey = ModifierKey.SHIFT) {
        now = at
        detector.modifierReleased(key)
    }

    private fun tap(pressAt: Long, releaseAt: Long, key: ModifierKey = ModifierKey.SHIFT) {
        press(pressAt, key)
        release(releaseAt, key)
    }

    @Test
    fun `fires only on the second release`() {
        press(0)
        release(80)
        press(200)
        assertTrue(fired.isEmpty(), "must not fire on the second press")

        release(260)
        assertEquals(listOf(ModifierKey.SHIFT), fired)
    }

    @Test
    fun `second press exactly 500 ms after the first fires`() {
        tap(0, 10)
        press(500)
        release(510)
        assertEquals(listOf(ModifierKey.SHIFT), fired)
    }

    @Test
    fun `second press 501 ms after the first does not fire`() {
        tap(0, 10)
        press(501)
        release(600)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `first press held exactly 300 ms still fires`() {
        tap(0, 300)
        press(400)
        release(450)
        assertEquals(listOf(ModifierKey.SHIFT), fired)
    }

    @Test
    fun `first press held 301 ms resets the sequence`() {
        tap(0, 301)
        press(400)
        release(450)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `second press held 301 ms resets the sequence`() {
        tap(0, 10)
        press(100)
        release(401)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `another key between the taps cancels`() {
        tap(0, 10)
        now = 50
        detector.otherKeyPressed()
        press(200)
        release(250)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `another key during the second press cancels`() {
        tap(0, 10)
        press(200)
        now = 210
        detector.otherKeyPressed()
        release(220)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `another modifier between the taps cancels`() {
        tap(0, 10)
        press(50, ModifierKey.CONTROL)
        release(60, ModifierKey.CONTROL)
        tap(100, 150)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `another modifier pressed together cancels`() {
        press(0)
        press(20, ModifierKey.CONTROL)
        release(30)
        release(40, ModifierKey.CONTROL)
        tap(100, 150)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `triple tap fires only once`() {
        tap(0, 10)
        tap(200, 260)
        tap(300, 350)
        assertEquals(listOf(ModifierKey.SHIFT), fired)
    }
}
