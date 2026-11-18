package stride.app.vcs

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CommitModelTest {

    @TempDir
    lateinit var root: Path

    private fun path(relative: String): Path = root.resolve(relative).toAbsolutePath().normalize()

    @Test
    fun `rows group tracked changes apart from unversioned files`() {
        val rows = CommitModel.rows(
            changes = listOf(
                FileChange(path("src/Main.kt"), ChangeKind.MODIFIED),
                FileChange(path("notes.txt"), ChangeKind.UNTRACKED),
                FileChange(path("src/New.kt"), ChangeKind.ADDED),
            ),
            root = root,
            modifiedEditors = emptyList(),
        )

        val groups = CommitModel.groups(rows)
        assertEquals(listOf("Changes", "Unversioned Files"), groups.map { it.title })
        assertEquals(listOf("Main.kt", "New.kt"), groups[0].rows.map { it.path.fileName.toString() }.sorted())
        assertEquals(listOf("notes.txt"), groups[1].rows.map { it.path.fileName.toString() })
        assertEquals(setOf('M', 'A', 'U'), rows.map { it.letter }.toSet())
    }

    @Test
    fun `a modified open editor marks its row and a tracked unreported file becomes a modified unsaved row`() {
        val rows = CommitModel.rows(
            changes = listOf(FileChange(path("src/Main.kt"), ChangeKind.MODIFIED)),
            root = root,
            modifiedEditors = listOf(path("src/Main.kt"), path("src/Typed.kt")),
            tracked = { true },
        )

        val main = rows.single { it.path.fileName.toString() == "Main.kt" }
        val typed = rows.single { it.path.fileName.toString() == "Typed.kt" }
        assertTrue(main.unsaved)
        assertEquals(ChangeKind.MODIFIED, main.kind)
        assertTrue(typed.unsaved)
        assertEquals(ChangeKind.MODIFIED, typed.kind)
        assertTrue(rows.all { it.unsaved })
    }

    @Test
    fun `an unreported editor is listed only when git says it is tracked`() {
        val editor = path("src/Typed.kt")

        val ignored = CommitModel.rows(
            changes = emptyList(),
            root = root,
            modifiedEditors = listOf(editor),
            tracked = { false },
        )
        assertTrue(ignored.isEmpty())

        val unknown = CommitModel.rows(
            changes = emptyList(),
            root = root,
            modifiedEditors = listOf(editor),
            tracked = { null },
        )
        assertTrue(unknown.isEmpty())
    }

    @Test
    fun `an editor outside the project root is left out`() {
        val rows = CommitModel.rows(
            changes = emptyList(),
            root = root,
            modifiedEditors = listOf(root.parent.resolve("elsewhere/Other.kt")),
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun `tracked rows start checked, unversioned rows start unchecked and a toggle sticks`() {
        val tracked = CommitRow(path("src/Main.kt"), ChangeKind.MODIFIED)
        val unversioned = CommitRow(path("notes.txt"), ChangeKind.UNTRACKED)
        val inclusion = CommitInclusion()

        assertTrue(inclusion.isChecked(tracked))
        assertFalse(inclusion.isChecked(unversioned))

        inclusion.toggle(tracked)
        inclusion.toggle(unversioned)

        assertFalse(inclusion.isChecked(tracked))
        assertTrue(inclusion.isChecked(unversioned))
    }

    @Test
    fun `a choice survives a refresh, a path that leaves the list keeps it and forget drops it`() {
        val row = CommitRow(path("src/Main.kt"), ChangeKind.MODIFIED)
        val inclusion = CommitInclusion()
        inclusion.toggle(row)
        assertFalse(inclusion.isChecked(row))

        inclusion.forget(emptyList())
        assertFalse(inclusion.isChecked(row))

        inclusion.forget(listOf(path("notes.txt")))
        assertFalse(inclusion.isChecked(row))

        inclusion.forget(listOf(row.path))
        assertTrue(inclusion.isChecked(row))
    }

    @Test
    fun `the selection walks the grouped order and the checked count follows the boxes`() {
        val window = CommitWindowState()
        val unversioned = CommitRow(path("notes.txt"), ChangeKind.UNTRACKED)
        val tracked = CommitRow(path("src/Main.kt"), ChangeKind.MODIFIED)
        window.syncRows(listOf(unversioned, tracked))

        assertEquals(tracked, window.selectedRow())
        assertEquals(1, window.checkedCount())

        window.selectedPath = unversioned.path
        assertEquals(unversioned, window.selectedRow())
        window.inclusion.toggle(unversioned)
        assertEquals(2, window.checkedCount())
        assertEquals(listOf(unversioned.path, tracked.path), window.checkedPaths())
    }

    @Test
    fun `a selected path that is no longer listed selects nothing`() {
        val window = CommitWindowState()
        val tracked = CommitRow(path("src/Main.kt"), ChangeKind.MODIFIED)
        window.syncRows(listOf(tracked))
        assertEquals(0, window.selectedIndex())

        window.selectedPath = path("src/Gone.kt")
        assertEquals(-1, window.selectedIndex())
        assertNull(window.selectedRow())

        window.selectedPath = null
        assertEquals(0, window.selectedIndex())
        assertEquals(tracked, window.selectedRow())
    }

    @Test
    fun `a tracked answer is dropped when HEAD or the path's status changes`() {
        val window = CommitWindowState()
        val file = path("src/Main.kt")
        window.recordTracked(file, head = "a", status = null, tracked = true)
        assertEquals(true, window.tracked(file, "a", null))
        assertNull(window.tracked(file, "b", null), "a new HEAD invalidates the answer")
        assertNull(window.tracked(file, "a", ChangeKind.MODIFIED), "a new status invalidates the answer")

        window.dropStaleTracked("b", emptyMap())
        assertNull(window.tracked(file, "a", null))
    }
}
