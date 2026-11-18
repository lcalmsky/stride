package stride.app.vcs

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiffRowsTest {

    private fun file(old: BaseText, new: BaseText, openable: Boolean = true): DiffFile =
        DiffFile(Path.of("/tmp/api-server/src/Main.kt"), "src/Main.kt", old, new, openable)

    private fun text(old: String, new: String): List<DiffRow> =
        DiffRows.build(file(BaseText.Text(old), BaseText.Text(new)))

    private fun code(rows: List<DiffRow>) = rows.filterIsInstance<DiffRow.Code>()

    @Test
    fun `changed lines carry both numbers, a sign and the text`() {
        val rows = text("alpha\nbeta\ngamma", "alpha\nBETA\ngamma")
        assertEquals(
            listOf(
                DiffRow.Code(1, 1, ' ', "alpha"),
                DiffRow.Code(2, null, '-', "beta"),
                DiffRow.Code(null, 2, '+', "BETA"),
                DiffRow.Code(3, 3, ' ', "gamma"),
            ),
            rows,
        )
    }

    @Test
    fun `numbers are one-based and a missing side is blank`() {
        val rows = code(text("", "first\nsecond"))
        assertEquals(listOf<Int?>(null, null), rows.map { it.oldNumber })
        assertEquals(listOf<Int?>(1, 2), rows.map { it.newNumber })
        assertEquals(listOf('+', '+'), rows.map { it.sign })
    }

    @Test
    fun `six unchanged lines are not folded and seven are`() {
        val six = (1..8).map { "line $it" }
        val changedSix = six.toMutableList().also { it[0] = "LINE 1"; it[7] = "LINE 8" }
        val sixRows = text(six.joinToString("\n"), changedSix.joinToString("\n"))
        assertTrue(sixRows.none { it is DiffRow.Fold }, "six context lines must stay whole")
        assertEquals(6, code(sixRows).count { it.sign == ' ' })

        val seven = (1..9).map { "line $it" }
        val changedSeven = seven.toMutableList().also { it[0] = "LINE 1"; it[8] = "LINE 9" }
        val sevenRows = text(seven.joinToString("\n"), changedSeven.joinToString("\n"))
        val fold = sevenRows.filterIsInstance<DiffRow.Fold>().single()
        assertEquals(1, fold.count)
        assertEquals(6, code(sevenRows).count { it.sign == ' ' })
    }

    @Test
    fun `a long run in the middle keeps three lines on each side of the fold`() {
        val lines = (1..20).map { "line $it" }
        val changed = lines.toMutableList().also { it[0] = "LINE 1"; it[19] = "LINE 20" }
        val rows = text(lines.joinToString("\n"), changed.joinToString("\n"))

        assertEquals(DiffRow.Code(1, null, '-', "line 1"), rows[0])
        assertEquals(DiffRow.Code(null, 1, '+', "LINE 1"), rows[1])
        val afterFirstChange = rows.drop(2)
        assertEquals(
            listOf("line 2", "line 3", "line 4"),
            afterFirstChange.take(3).map { (it as DiffRow.Code).text },
        )
        val fold = afterFirstChange[3] as DiffRow.Fold
        assertEquals(12, fold.count)
        assertEquals("line 5", fold.lines.first().text)
        assertEquals("line 16", fold.lines.last().text)
        assertEquals(
            listOf("line 17", "line 18", "line 19"),
            afterFirstChange.drop(4).take(3).map { (it as DiffRow.Code).text },
        )
    }

    @Test
    fun `the start of the file keeps only the inner three lines`() {
        val lines = (1..13).map { "line $it" }
        val changed = lines.toMutableList().also { it[12] = "LINE 13" }
        val rows = text(lines.joinToString("\n"), changed.joinToString("\n"))

        val fold = rows.first()
        assertTrue(fold is DiffRow.Fold, "the leading run folds: ${rows.first()}")
        fold as DiffRow.Fold
        assertEquals(9, fold.count)
        assertEquals("line 1", fold.lines.first().text)
        assertEquals("line 9", fold.lines.last().text)
        assertEquals(
            listOf("line 10", "line 11", "line 12", "line 13", "LINE 13"),
            rows.drop(1).map { (it as DiffRow.Code).text },
        )
        assertEquals(
            listOf(' ', ' ', ' ', '-', '+'),
            rows.drop(1).map { (it as DiffRow.Code).sign },
        )
    }

    @Test
    fun `the end of the file keeps only the inner three lines`() {
        val lines = (1..13).map { "line $it" }
        val changed = lines.toMutableList().also { it[0] = "LINE 1" }
        val rows = text(lines.joinToString("\n"), changed.joinToString("\n"))

        assertEquals(
            listOf("line 2", "line 3", "line 4"),
            rows.drop(2).take(3).map { (it as DiffRow.Code).text },
        )
        val fold = rows.last()
        assertTrue(fold is DiffRow.Fold)
        fold as DiffRow.Fold
        assertEquals(9, fold.count)
        assertEquals("line 5", fold.lines.first().text)
        assertEquals("line 13", fold.lines.last().text)
    }

    @Test
    fun `a CRLF line loses its trailing carriage return`() {
        val rows = code(text("alpha\r\nbeta\r\n", "alpha\r\ngamma\r\n"))
        assertEquals(listOf("alpha", "beta", "gamma", ""), rows.map { it.text })
        assertTrue(rows.none { it.text.endsWith("\r") })
    }

    @Test
    fun `an unavailable side is one message row with its reason`() {
        val binary = DiffRows.build(file(BaseText.Unavailable("Binary file"), BaseText.Text("x")))
        assertEquals(listOf(DiffRow.Message("Binary file")), binary)

        val large = DiffRows.build(file(BaseText.Text("x"), BaseText.Unavailable("Larger than 8 MB")))
        assertEquals(listOf(DiffRow.Message("Larger than 8 MB")), large)
    }

    @Test
    fun `an untracked old side makes every line added`() {
        val rows = code(DiffRows.build(file(BaseText.Untracked, BaseText.Text("alpha\nbeta\n"))))
        assertEquals(listOf('+', '+', '+'), rows.map { it.sign })
        assertEquals(listOf<Int?>(null, null, null), rows.map { it.oldNumber })
        assertEquals(listOf("alpha", "beta", ""), rows.map { it.text })
    }

    @Test
    fun `a fold expands into its own lines`() {
        val lines = (1..20).map { "line $it" }
        val changed = lines.toMutableList().also { it[0] = "LINE 1"; it[19] = "LINE 20" }
        val rows = text(lines.joinToString("\n"), changed.joinToString("\n"))
        val index = rows.indexOfFirst { it is DiffRow.Fold }

        val expanded = DiffRows.expand(rows, index)

        assertEquals(rows.size + 12 - 1, expanded.size)
        assertEquals("line 5", (expanded[index] as DiffRow.Code).text)
        assertEquals("line 16", (expanded[index + 11] as DiffRow.Code).text)
        assertTrue(expanded.none { it is DiffRow.Fold })
        assertEquals(rows, DiffRows.expand(rows, 0))
    }

    @Test
    fun `hunk starts and navigation skip expanded context`() {
        val lines = (1..20).map { "line $it" }
        val changed = lines.toMutableList().also { it[0] = "LINE 1"; it[19] = "LINE 20" }
        val rows = text(lines.joinToString("\n"), changed.joinToString("\n"))

        assertEquals(listOf(0, 9), DiffRows.hunkStarts(rows))
        assertEquals(9, DiffRows.nextHunk(rows, 0, forward = true))
        assertEquals(null, DiffRows.nextHunk(rows, 9, forward = true))
        assertEquals(0, DiffRows.nextHunk(rows, 9, forward = false))
        assertEquals(null, DiffRows.nextHunk(rows, 0, forward = false))
    }

    @Test
    fun `the row of a line is the new side row or the nearest one above`() {
        val rows = text("alpha\nbeta\ngamma", "alpha\nBETA\ngamma")
        assertEquals(0, DiffRows.rowAtLine(rows, 0))
        assertEquals(2, DiffRows.rowAtLine(rows, 1))
        assertEquals(3, DiffRows.rowAtLine(rows, 2))
        assertEquals(3, DiffRows.rowAtLine(rows, 99))
    }
}
