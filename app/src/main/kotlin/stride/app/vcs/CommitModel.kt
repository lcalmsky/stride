package stride.app.vcs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path

/** One file the Commit window lists; [path] is absolute and normalized. */
data class CommitRow(val path: Path, val kind: ChangeKind, val unsaved: Boolean = false) {

    /** Everything but an untracked file starts checked in the window. */
    val tracked: Boolean get() = kind != ChangeKind.UNTRACKED

    /** The status letter the contract lists for [kind]. */
    val letter: Char
        get() = when (kind) {
            ChangeKind.MODIFIED -> 'M'
            ChangeKind.ADDED -> 'A'
            ChangeKind.DELETED -> 'D'
            ChangeKind.RENAMED -> 'R'
            ChangeKind.CONFLICTED -> 'C'
            ChangeKind.UNTRACKED -> 'U'
        }
}

/** One titled group of the window's list; the header reads `title (rows.size)`. */
data class CommitGroup(val title: String, val rows: List<CommitRow>)

/** The Commit window's list: git's changes plus the open editors that have unsaved text. */
object CommitModel {
    const val CHANGES: String = "Changes"
    const val UNVERSIONED: String = "Unversioned Files"

    /**
     * [changes] in [root], plus every path in [modifiedEditors] under [root] that git did not
     * report, as `MODIFIED` and unsaved; a reported change with a modified editor is unsaved too.
     */
    fun rows(changes: List<FileChange>, root: Path, modifiedEditors: List<Path>): List<CommitRow> {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val byPath = LinkedHashMap<Path, CommitRow>()
        for (change in changes) {
            val path = change.path.toAbsolutePath().normalize()
            byPath[path] = CommitRow(path, change.kind)
        }
        for (editor in modifiedEditors) {
            val path = editor.toAbsolutePath().normalize()
            if (!path.startsWith(normalizedRoot)) continue
            val reported = byPath[path]
            byPath[path] = if (reported == null) {
                CommitRow(path, ChangeKind.MODIFIED, unsaved = true)
            } else {
                reported.copy(unsaved = true)
            }
        }
        return byPath.values.sortedBy { it.path.toString() }
    }

    /** `Changes` holds every kind but `UNTRACKED`; `Unversioned Files` holds the rest. */
    fun groups(rows: List<CommitRow>): List<CommitGroup> = listOf(
        CommitGroup(CHANGES, rows.filter { it.tracked }),
        CommitGroup(UNVERSIONED, rows.filterNot { it.tracked }),
    ).filter { it.rows.isNotEmpty() }

    /** The list order the window shows and walks with the arrow keys. */
    fun ordered(rows: List<CommitRow>): List<CommitRow> = groups(rows).flatMap { it.rows }
}

/**
 * The checked files. A row's kind decides its default, so only the user's own choices are kept;
 * a path keeps its choice while the window lists it and is forgotten once it disappears.
 */
class CommitInclusion {
    private val overrides = mutableStateMapOf<Path, Boolean>()

    fun isChecked(row: CommitRow): Boolean = overrides[row.path] ?: row.tracked

    fun toggle(row: CommitRow) {
        overrides[row.path] = !isChecked(row)
    }

    fun retain(rows: List<CommitRow>) {
        val listed = rows.mapTo(HashSet()) { it.path }
        overrides.keys.retainAll(listed)
    }
}

/** The Commit window's rows, selection, message and last error; it outlives a hidden window. */
class CommitWindowState {
    var rows: List<CommitRow> by mutableStateOf(emptyList())
        private set

    /** The selected file; `null` selects the first row. */
    var selectedPath: Path? by mutableStateOf(null)

    var message by mutableStateOf("")

    /** The last refused or failed commit, shown above the capsule until the next one. */
    var error: String? by mutableStateOf(null)

    val inclusion = CommitInclusion()

    fun syncRows(rows: List<CommitRow>) {
        this.rows = rows
        inclusion.retain(rows)
    }

    fun selectedIndex(): Int = CommitModel.ordered(rows)
        .indexOfFirst { it.path == selectedPath }
        .takeIf { it >= 0 } ?: 0

    fun selectedRow(): CommitRow? = CommitModel.ordered(rows).getOrNull(selectedIndex())

    fun checkedCount(): Int = rows.count { inclusion.isChecked(it) }

    fun checkedPaths(): List<Path> = rows.filter { inclusion.isChecked(it) }.map { it.path }
}
