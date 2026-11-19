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
     * report and [tracked] calls tracked, as `MODIFIED` and unsaved; a reported change with a
     * modified editor is unsaved too. [tracked] answers `null` while git has not said yet, and
     * the path is left out then.
     */
    fun rows(
        changes: List<FileChange>,
        root: Path,
        modifiedEditors: List<Path>,
        tracked: (Path) -> Boolean? = { null },
    ): List<CommitRow> {
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
            if (reported != null) {
                byPath[path] = reported.copy(unsaved = true)
            } else if (tracked(path) == true) {
                byPath[path] = CommitRow(path, ChangeKind.MODIFIED, unsaved = true)
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
 * a path keeps its choice while it is away from the list and gets it back when it returns.
 */
class CommitInclusion {
    private val overrides = mutableStateMapOf<Path, Boolean>()

    fun isChecked(row: CommitRow): Boolean = overrides[row.path] ?: row.tracked

    fun toggle(row: CommitRow) {
        overrides[row.path] = !isChecked(row)
    }

    /** Drops the choices of [paths]; the next time a path is listed its default applies again. */
    fun forget(paths: Collection<Path>) {
        overrides.keys.removeAll(paths.toSet())
    }
}

/** git's answer for one path, with the HEAD and status it was asked under. */
private data class TrackedAnswer(val head: String?, val status: ChangeKind?, val tracked: Boolean)

/** The Commit window's rows, selection, message and last error; it outlives a hidden window. */
class CommitWindowState {
    var rows: List<CommitRow> by mutableStateOf(emptyList())
        private set

    /** The selected file; `null` selects the first row. */
    var selectedPath: Path? by mutableStateOf(null)

    var message by mutableStateOf("")

    /** The last refused or failed commit, shown above the capsule until the next one. */
    var error: String? by mutableStateOf(null)

    /** The commit Amend replaces while Amend is on, `null` otherwise. */
    var amend: AmendBase? by mutableStateOf(null)
        private set

    /** The message the field had when Amend was turned on; turning it off puts it back. */
    var amendMessage: String by mutableStateOf("")
        private set

    val inclusion = CommitInclusion()

    fun turnAmendOn(base: AmendBase) {
        amendMessage = message
        message = base.message
        amend = base
    }

    fun turnAmendOff() {
        if (amend == null) return
        message = amendMessage
        amendMessage = ""
        amend = null
    }

    /** A successful amend leaves no base behind: the field is cleared by the caller. */
    fun amendFinished() {
        amend = null
        amendMessage = ""
    }

    /** A commit this window asked for and has not seen finish; blocks a second request. */
    var committing by mutableStateOf(false)

    private val trackedAnswers = mutableStateMapOf<Path, TrackedAnswer>()

    fun syncRows(rows: List<CommitRow>) {
        this.rows = rows
    }

    /** Whether git tracks [path]; `null` until asked, and once HEAD or the path's status moved. */
    fun tracked(path: Path, head: String?, status: ChangeKind?): Boolean? {
        val answer = trackedAnswers[path] ?: return null
        return if (answer.head == head && answer.status == status) answer.tracked else null
    }

    fun recordTracked(path: Path, head: String?, status: ChangeKind?, tracked: Boolean) {
        trackedAnswers[path] = TrackedAnswer(head, status, tracked)
    }

    /** Drops answers that [head] or the path's status has invalidated. */
    fun dropStaleTracked(head: String?, statuses: Map<Path, ChangeKind?>) {
        for (path in trackedAnswers.keys.toList()) {
            val answer = trackedAnswers[path] ?: continue
            if (answer.head != head || answer.status != statuses[path]) trackedAnswers.remove(path)
        }
    }

    /** `null` selects the first row; a selected path that is no longer listed selects nothing. */
    fun selectedIndex(): Int {
        val selected = selectedPath ?: return 0
        return CommitModel.ordered(rows).indexOfFirst { it.path == selected }
    }

    fun selectedRow(): CommitRow? = CommitModel.ordered(rows).getOrNull(selectedIndex())

    fun checkedCount(): Int = rows.count { inclusion.isChecked(it) }

    fun checkedPaths(): List<Path> = rows.filter { inclusion.isChecked(it) }.map { it.path }
}
