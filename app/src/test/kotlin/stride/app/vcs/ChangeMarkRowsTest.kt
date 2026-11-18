package stride.app.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.app.editor.ChangeMarkRow
import stride.app.editor.FoldRegion
import stride.app.editor.FoldView
import stride.app.editor.changeMarkRows

/** The gutter rows a hunk is drawn on, and the row a click resolves to. */
class ChangeMarkRowsTest {

    private val text = "alpha\nbeta\ngamma\ndelta\nepsilon\nzeta\n"
    private val lineCount = 7

    /** Hides lines 2 and 3; lines 0, 1, 4, 5 and 6 stay on rows 0 to 4. */
    private fun folded(): FoldView =
        FoldView.of(text, listOf(FoldRegion(text.indexOf("beta") + 2, text.indexOf("delta"))))

    @Test
    fun `a hunk whose lines are all hidden is one mark on the fold's row`() {
        val hunk = LineHunk(2, 2, 2, 2)
        assertEquals(
            listOf(ChangeMarkRow(hunk, 1, 1, HunkKind.MODIFIED)),
            changeMarkRows(listOf(hunk), folded(), lineCount),
        )
    }

    @Test
    fun `a hunk with a visible line is drawn on that line's row`() {
        val hunk = LineHunk(1, 2, 1, 2)
        assertEquals(
            listOf(ChangeMarkRow(hunk, 1, 1, HunkKind.MODIFIED)),
            changeMarkRows(listOf(hunk), folded(), lineCount),
        )

        val below = LineHunk(4, 1, 4, 1)
        assertEquals(
            listOf(ChangeMarkRow(below, 2, 2, HunkKind.MODIFIED)),
            changeMarkRows(listOf(below), folded(), lineCount),
        )
    }

    @Test
    fun `a deleted hunk is one mark on its boundary line's row`() {
        val deleted = LineHunk(2, 1, 2, 0)
        assertEquals(
            listOf(ChangeMarkRow(deleted, 2, 2, HunkKind.DELETED)),
            changeMarkRows(listOf(deleted), FoldView.NONE, lineCount),
        )
        assertEquals(
            listOf(ChangeMarkRow(deleted, 1, 1, HunkKind.DELETED)),
            changeMarkRows(listOf(deleted), folded(), lineCount),
        )

        val past = LineHunk(0, 1, 99, 0)
        assertEquals(
            listOf(ChangeMarkRow(past, lineCount - 1, lineCount - 1, HunkKind.DELETED)),
            changeMarkRows(listOf(past), FoldView.NONE, lineCount),
        )
    }

    @Test
    fun `the row range a click resolves to is the mark's own`() {
        val first = LineHunk(0, 1, 0, 1)
        val second = LineHunk(4, 1, 4, 1)
        val marks = changeMarkRows(listOf(first, second), FoldView.NONE, lineCount)
        assertEquals(first, markAt(marks, 0))
        assertEquals(second, markAt(marks, 4))
        assertNull(markAt(marks, 2))

        val inside = LineHunk(2, 2, 2, 2)
        val foldedMarks = changeMarkRows(listOf(inside), folded(), lineCount)
        assertEquals(inside, markAt(foldedMarks, 1))
        assertNull(markAt(foldedMarks, 2))
    }

    private fun markAt(marks: List<ChangeMarkRow>, row: Int): LineHunk? =
        marks.firstOrNull { row in it.firstRow..it.lastRow }?.hunk
}
