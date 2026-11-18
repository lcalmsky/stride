package stride.app.vcs

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `a modified open editor marks its row and an unreported file becomes a modified unsaved row`() {
        val rows = CommitModel.rows(
            changes = listOf(FileChange(path("src/Main.kt"), ChangeKind.MODIFIED)),
            root = root,
            modifiedEditors = listOf(path("src/Main.kt"), path("src/Typed.kt")),
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
    fun `a choice survives a refresh and a path that disappears is forgotten`() {
        val kept = CommitRow(path("src/Main.kt"), ChangeKind.MODIFIED)
        val unversioned = CommitRow(path("notes.txt"), ChangeKind.UNTRACKED)
        val inclusion = CommitInclusion()
        inclusion.toggle(kept)
        inclusion.toggle(unversioned)

        inclusion.retain(listOf(kept))
        assertEquals(false, inclusion.isChecked(kept))

        inclusion.retain(emptyList())
        inclusion.retain(listOf(kept, unversioned))
        assertTrue(inclusion.isChecked(kept))
        assertFalse(inclusion.isChecked(unversioned))
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
}
