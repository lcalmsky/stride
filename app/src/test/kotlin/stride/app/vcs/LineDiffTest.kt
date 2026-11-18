package stride.app.vcs

import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LineDiffTest {

    @Test
    fun `applying every hunk to the base gives the current text`() {
        for (seed in 0 until 1_000) {
            val random = Random(seed)
            val base = randomLines(random)
            val current = mutate(random, base)
            val hunks = LineDiff.diff(base, current)
            assertNotNull(hunks, "seed $seed")
            assertEquals(current, applyHunks(base, current, hunks!!), "seed $seed")
            assertEquals(base, applyRollback(base, current, hunks), "seed $seed")
            assertInvariants(hunks, base.size, current.size, seed)
        }
    }

    @Test
    fun `a text with its own line separator and a missing final newline rolls back byte for byte`() {
        for (seed in 0 until 1_000) {
            val random = Random(10_000 + seed)
            val separator = if (random.nextBoolean()) "\n" else "\r\n"
            val baseText = randomText(random, separator)
            val currentText = randomText(random, separator)
            val base = LineDiff.split(baseText)
            val current = LineDiff.split(currentText)
            val hunks = LineDiff.diff(base, current)
            assertNotNull(hunks, "seed $seed")
            assertEquals(current, applyHunks(base, current, hunks!!), "seed $seed")
            assertEquals(
                baseText,
                LineDiff.rollback(base, current, hunks, hunks, separator),
                "seed $seed",
            )
        }
    }

    @Test
    fun `split ignores one trailing carriage return and rebuilds the text`() {
        assertEquals(listOf("a", "b", ""), LineDiff.split("a\r\nb\r\n"))
        assertEquals(listOf("a", "b"), LineDiff.split("a\nb"))
        assertEquals(listOf(""), LineDiff.split(""))
        assertEquals("a\r\nb", LineDiff.join(listOf("a", "b"), "\r\n"))
        assertEquals("\r\n", LineDiff.lineSeparator("a\r\nb"))
        assertEquals("\n", LineDiff.lineSeparator("a\nb"))
        assertEquals("\n", LineDiff.lineSeparator("abc"))
    }

    @Test
    fun `CRLF and LF documents with the same lines have no hunks`() {
        val base = LineDiff.split("one\ntwo\nthree\n")
        val current = LineDiff.split("one\r\ntwo\r\nthree\r\n")
        assertEquals(emptyList<LineHunk>(), LineDiff.diff(base, current))
    }

    @Test
    fun `a rewrite larger than the limit returns null`() {
        val base = List(10) { "old $it" }
        val current = List(10) { "new $it" }
        assertNull(LineDiff.diff(base, current, maxEdits = 5))
        assertNotNull(LineDiff.diff(base, current, maxEdits = 20))
    }

    @Test
    fun `an empty base gives one added hunk and an empty current one deleted hunk`() {
        val added = LineDiff.diff(emptyList(), listOf("a", "b"))
        assertEquals(listOf(LineHunk(0, 0, 0, 2)), added)
        assertEquals(HunkKind.ADDED, added!!.single().kind)

        val deleted = LineDiff.diff(listOf("a", "b"), emptyList())
        assertEquals(listOf(LineHunk(0, 2, 0, 0)), deleted)
        assertEquals(HunkKind.DELETED, deleted!!.single().kind)
    }

    @Test
    fun `equal texts have no hunks`() {
        assertEquals(emptyList<LineHunk>(), LineDiff.diff(listOf("a", "b"), listOf("a", "b")))
        assertEquals(emptyList<LineHunk>(), LineDiff.diff(emptyList(), emptyList()))
    }

    @Test
    fun `rolling back only the selected hunks keeps the others`() {
        val base = listOf("a", "b", "c", "d", "e")
        val current = listOf("a", "B", "c", "d", "E")
        val hunks = LineDiff.diff(base, current)!!
        assertEquals(2, hunks.size)
        val rolled = LineDiff.rollback(base, current, hunks, hunks.take(1), "\n")
        assertEquals("a\nb\nc\nd\nE", rolled)
    }

    @Test
    fun `a hunk past the end of either list is clamped`() {
        val hunk = LineHunk(oldStart = 5, oldCount = 3, newStart = 9, newCount = 4)
        assertEquals(
            "A",
            LineDiff.rollback(listOf("a", "b"), listOf("A"), listOf(hunk), listOf(hunk), "\n"),
        )
        val negative = LineHunk(oldStart = -2, oldCount = 3, newStart = -1, newCount = 2)
        assertEquals(
            "a\nb",
            LineDiff.rollback(listOf("a", "b"), listOf("a", "b"), listOf(negative), listOf(negative), "\n"),
        )
    }

    private fun randomLines(random: Random): List<String> = List(random.nextInt(0, 18)) { line(random) }

    private fun line(random: Random): String = ALPHABET[random.nextInt(ALPHABET.size)]

    private fun randomText(random: Random, separator: String): String {
        val count = random.nextInt(0, 12)
        val lines = List(count) { line(random) }
        val trailing = count > 0 && random.nextBoolean()
        return lines.joinToString(separator) + if (trailing) separator else ""
    }

    private fun mutate(random: Random, base: List<String>): List<String> {
        val result = base.toMutableList()
        repeat(random.nextInt(0, 5)) {
            when (random.nextInt(3)) {
                0 -> result.add(random.nextInt(0, result.size + 1), line(random))
                1 -> if (result.isNotEmpty()) result.removeAt(random.nextInt(result.size))
                else -> if (result.isNotEmpty()) result[random.nextInt(result.size)] = line(random)
            }
        }
        return result
    }

    private fun applyHunks(base: List<String>, current: List<String>, hunks: List<LineHunk>): List<String> {
        val output = ArrayList<String>(base.size)
        var cursor = 0
        for (hunk in hunks) {
            for (index in cursor until hunk.oldStart) output += base[index]
            for (index in hunk.newStart until hunk.newStart + hunk.newCount) output += current[index]
            cursor = hunk.oldStart + hunk.oldCount
        }
        for (index in cursor until base.size) output += base[index]
        return output
    }

    private fun applyRollback(base: List<String>, current: List<String>, hunks: List<LineHunk>): List<String> {
        val output = ArrayList<String>(current.size)
        var cursor = 0
        for (hunk in hunks) {
            for (index in cursor until hunk.newStart) output += current[index]
            for (index in hunk.oldStart until hunk.oldStart + hunk.oldCount) output += base[index]
            cursor = hunk.newStart + hunk.newCount
        }
        for (index in cursor until current.size) output += current[index]
        return output
    }

    private fun assertInvariants(hunks: List<LineHunk>, oldSize: Int, newSize: Int, seed: Int) {
        var oldCursor = 0
        var newCursor = 0
        for (hunk in hunks) {
            assertFalse(hunk.oldCount == 0 && hunk.newCount == 0, "empty hunk at seed $seed")
            assertTrue(hunk.oldStart >= oldCursor, "old hunks overlap at seed $seed")
            assertTrue(hunk.newStart >= newCursor, "new hunks overlap at seed $seed")
            assertTrue(hunk.oldStart + hunk.oldCount <= oldSize, "old hunk past the end at seed $seed")
            assertTrue(hunk.newStart + hunk.newCount <= newSize, "new hunk past the end at seed $seed")
            oldCursor = hunk.oldStart + hunk.oldCount
            newCursor = hunk.newStart + hunk.newCount
        }
    }

    private companion object {
        val ALPHABET = listOf("alpha", "beta", "gamma", "", "delta", "epsilon")
    }
}
