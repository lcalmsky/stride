package stride.app.vcs

import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class GitRepository(val root: Path, val gitDir: Path)

enum class ChangeKind { MODIFIED, ADDED, DELETED, RENAMED, UNTRACKED, CONFLICTED }

/** One working-tree change; [path] is absolute. [originalPath] is set for a rename or copy. */
data class FileChange(val path: Path, val kind: ChangeKind, val originalPath: Path? = null)

/** [branch] is null on a detached HEAD; [head] is null before the first commit. */
data class BranchState(
    val head: String?,
    val branch: String?,
    val upstream: String?,
    val ahead: Int,
    val behind: Int,
)

data class GitSnapshot(
    val repository: GitRepository?,
    val branch: BranchState?,
    val changes: List<FileChange>,
    val error: String?,
)

/** The file at HEAD, or why it cannot be compared. */
sealed interface BaseText {
    /** The file at HEAD; `""` for a file added to the index but not committed yet. */
    data class Text(val content: String) : BaseText

    data object Untracked : BaseText

    data class Unavailable(val reason: String) : BaseText
}

/** One entry of the Git window's Console tab; [output] is stdout and stderr, truncated. */
data class GitConsoleEntry(
    val time: Instant,
    val command: String,
    val exitCode: Int,
    val duration: Duration,
    val output: String,
    val write: Boolean,
)

/**
 * The outcome of a write. [message] is the one-line status message; [addedToIndex] counts the new
 * files a failing commit had already added before git stopped.
 */
data class GitWriteResult(
    val ok: Boolean,
    val message: String,
    val commitId: String? = null,
    val subject: String? = null,
    val addedToIndex: Int = 0,
)

/** What a push would do, or why it cannot run. */
sealed interface PushPlan {
    data class Ready(
        val branch: String,
        val remote: String,
        val remoteRef: String,
        /** True when the push creates the remote branch (no upstream, or its remote ref is gone) with `-u`. */
        val newBranch: Boolean,
        val outgoing: List<LogEntry>,
        val moreOutgoing: Int,
        val behind: Int,
    ) : PushPlan

    data class Refused(val message: String) : PushPlan
}

data class LogEntry(
    val id: String,
    val shortId: String,
    val author: String,
    val time: Instant,
    val refs: List<String>,
    val subject: String,
)

data class GitTimeouts(
    val read: Duration = 15.seconds,
    val commit: Duration = 120.seconds,
    val push: Duration = 90.seconds,
    val fetch: Duration = 90.seconds,
    val update: Duration = 120.seconds,
)

enum class UpdateStrategy { FAST_FORWARD, MERGE, REBASE }

/** What an update would do, or why it cannot run. */
sealed interface UpdatePlan {
    data class Ready(
        val branch: String,
        val remote: String,
        val remoteRef: String,
        val incoming: List<LogEntry>,
        val moreIncoming: Int,
        val ahead: Int,
        /** [UpdateStrategy.FAST_FORWARD] alone when nothing is ahead, else merge and rebase. */
        val strategies: List<UpdateStrategy>,
        val defaultStrategy: UpdateStrategy,
        val unsavedEditors: Int = 0,
    ) : UpdatePlan

    data class Refused(val message: String) : UpdatePlan
}
