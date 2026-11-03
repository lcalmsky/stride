package stride.app.editor.find

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.app.editor.TextChange

class FindModelTest {
    @Test
    fun `search ignores case by default and honours it when sensitive`() {
        assertEquals(
            listOf(FindMatch(0, 3), FindMatch(4, 7)),
            FindModel.search("Foo foo", "foo").matches,
        )
        assertEquals(
            listOf(FindMatch(4, 7)),
            FindModel.search("Foo foo", "foo", FindOptions(caseSensitive = true)).matches,
        )
    }

    @Test
    fun `regex search finds repeated quantifiers`() {
        assertEquals(
            listOf(FindMatch(0, 2), FindMatch(3, 6)),
            FindModel.search("fo foo", "fo+", FindOptions(regex = true)).matches,
        )
    }

    @Test
    fun `invalid regex reports an error and no matches`() {
        val result = FindModel.search("abc", "[", FindOptions(regex = true))

        assertTrue(result.matches.isEmpty())
        assertNotNull(result.error)
    }

    @Test
    fun `empty query has no matches`() {
        assertTrue(FindModel.search("abc", "").matches.isEmpty())
    }

    @Test
    fun `next index wraps from the end and previous from the start`() {
        val matches = listOf(FindMatch(0, 3), FindMatch(9, 12))

        assertEquals(0, FindModel.nextIndex(matches, 0))
        assertEquals(1, FindModel.nextIndex(matches, 5))
        assertEquals(0, FindModel.nextIndex(matches, 12))
        assertEquals(1, FindModel.previousIndex(matches, 0))
        assertEquals(0, FindModel.previousIndex(matches, 9))
        assertEquals(1, FindModel.previousIndex(matches, 12))
    }

    @Test
    fun `replace current derives one change from the match`() {
        assertEquals(TextChange(4, 3, "x"), FindModel.replaceCurrent("foo foo", FindMatch(4, 7), "x"))
    }

    @Test
    fun `replace all is back to front and survives front to back application`() {
        val text = "foo foo foo"
        val matches = FindModel.search(text, "foo").matches

        val changes = FindModel.replaceAll(text, matches, "bar")

        assertEquals(listOf(8, 4, 0), changes.map { it.offset })
        val builder = StringBuilder()
        var consumed = 0
        for (change in changes.asReversed()) {
            builder.append(text, consumed, change.offset)
            builder.append(change.newText)
            consumed = change.offset + change.oldLength
        }
        builder.append(text, consumed, text.length)
        assertEquals("bar bar bar", builder.toString())
    }
}
