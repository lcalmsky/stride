package stride.app.vcs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import stride.core.Disposable
import stride.core.services.Project
import stride.core.services.Service
import stride.core.vfs.VFS_CHANGES
import stride.core.vfs.VFileEvent
import stride.core.vfs.VfsListener

/**
 * One project's Git data. Reads run on an IO scope and never block typing; [snapshot] and
 * [console] are replaced inside a mutable snapshot, so composition reads a consistent value.
 * Writes run one at a time behind a mutex; a status refresh waits for a running write.
 */
@Service(Service.Level.PROJECT)
class GitService(private val project: Project) : Disposable {

    private val log = Logger.getLogger("stride.app.vcs")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshLock = Any()
    private val statusGate = Mutex()
    private val writeGate = Mutex()

    private var overrideExecutor: GitExecutor? = null
    private val defaultExecutor: GitExecutor by lazy {
        // The test JVM sets this so a composed App never runs git on a real repository; a test that
        // needs git sets [executor] itself.
        if (System.getProperty(DISABLE_DEFAULT_EXECUTOR) == "true") {
            MissingGitExecutor
        } else {
            GitBinary.resolve()?.let { CliGitExecutor(it, emptyMap()) } ?: MissingGitExecutor
        }
    }

    /** Test seam, like [stride.app.run.RunManager.starter]. */
    var executor: GitExecutor
        get() = overrideExecutor ?: defaultExecutor
        set(value) {
            overrideExecutor = value
        }

    /** Runs [block] on the service's IO scope, which is cancelled when the project closes. */
    fun launch(block: suspend CoroutineScope.() -> Unit): Job = scope.launch(block = block)

    /** Test seam for the read, commit, push, fetch and update limits. */
    var timeouts: GitTimeouts = GitTimeouts()

    /** Test seam for the message file write, so a full disk can be seen from a test. */
    internal var messageFileWriter: (Path, String) -> Unit = { file, text ->
        Files.writeString(file, text, Charsets.UTF_8)
    }

    var snapshot: GitSnapshot by mutableStateOf(GitSnapshot(null, null, emptyList(), null))
        private set

    private val consoleState = mutableStateListOf<GitConsoleEntry>()

    /** Newest last, at most [MAX_CONSOLE_ENTRIES]; every write and every failed read. */
    val console: List<GitConsoleEntry> get() = consoleState

    /** The write in progress ("Committing…"), else `null`. */
    var busy: String? by mutableStateOf(null)
        private set

    private val baseCache = ConcurrentHashMap<BaseKey, BaseText>()

    @Volatile
    private var repositoryCache: GitRepository? = null

    @Volatile
    private var headValue: String? = null

    @Volatile
    private var headKnown = false

    private var disposed = false
    private var debounceJob: Job? = null
    private var refreshing = false
    private var pendingRefresh = false
    private val runningLock = Any()
    private val runningThreads = HashSet<Thread>()
    private var interruptingCalls = false

    private val lexicalRoot: Path = project.root.toAbsolutePath().normalize()

    /** git reports real paths, so a project opened through a symlink is scoped by its real path. */
    private val projectRoot: Path = runCatching { lexicalRoot.toRealPath() }.getOrDefault(lexicalRoot)

    init {
        project.messageBus.connect(this).subscribe(
            VFS_CHANGES,
            object : VfsListener {
                override fun after(events: List<VFileEvent>) = refresh()
            },
        )
    }

    /** One debounced, single-flight refresh; a request during a refresh runs exactly one more. */
    fun refresh() {
        synchronized(refreshLock) {
            if (disposed) return
            if (refreshing) {
                pendingRefresh = true
                return
            }
            if (debounceJob != null) return
            debounceJob = scope.launch {
                delay(DEBOUNCE_MILLIS)
                refreshLoop()
            }
        }
    }

    /** Runs one refresh now and returns its snapshot; for tests and after writes. */
    suspend fun refreshNow(): GitSnapshot {
        statusGate.withLock { refreshStatus() }
        return snapshot
    }

    private suspend fun refreshLoop() {
        while (true) {
            // Clearing the debounce job and taking the refresh happen in one critical section; a
            // refresh() between them would start a second loop.
            synchronized(refreshLock) {
                if (disposed) return
                debounceJob = null
                pendingRefresh = false
                refreshing = true
            }
            try {
                statusGate.withLock { refreshStatus() }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.log(Level.WARNING, "Git refresh failed", t)
            }
            synchronized(refreshLock) {
                if (disposed || !pendingRefresh) {
                    refreshing = false
                    return
                }
            }
        }
    }

    private suspend fun refreshStatus() {
        if (disposed) return
        if (gitMissing()) {
            publish(GitSnapshot(null, null, emptyList(), GIT_NOT_INSTALLED))
            return
        }
        val repository = repository()
        if (repository == null) {
            publish(GitSnapshot(null, null, emptyList(), null))
            return
        }
        val result = read(statusCommand())
        if (!result.ok) {
            if (!result.timedOut && isNotRepository(result)) {
                repositoryCache = null
                publish(GitSnapshot(null, null, emptyList(), null))
                return
            }
            publish(snapshot.copy(repository = repository, error = firstLine(result)))
            return
        }
        val parsed = GitStatusParser.parse(result.stdoutText())
        val changes = parsed.records.mapNotNull { record ->
            val path = repository.root.resolve(record.path).normalize()
            if (!path.startsWith(projectRoot)) return@mapNotNull null
            val original = record.originalPath?.let { repository.root.resolve(it).normalize() }
            FileChange(path, record.kind, original)
        }
        val branch = BranchState(parsed.head, parsed.branch, parsed.upstream, parsed.ahead, parsed.behind)
        val previousHead = if (headKnown) headValue else null
        headValue = parsed.head
        headKnown = true
        // The cache is keyed by HEAD alone, but the index decides what a file's base is; a status
        // change (git add, git rm --cached) must not keep serving the answer it replaced.
        if (previousHead != parsed.head || changes != snapshot.changes) baseCache.clear()
        publish(
            GitSnapshot(
                repository = repository,
                branch = branch,
                changes = changes,
                error = null,
            ),
        )
    }

    /** The file at HEAD, cached per file and HEAD commit; V4's rules decide what is comparable. */
    suspend fun baseText(file: Path): BaseText = withContext(Dispatchers.IO) {
        val repository = repository() ?: return@withContext repositoryUnavailable()
        val path = normalizePath(file)
        if (!path.startsWith(repository.root)) return@withContext BaseText.Unavailable(OUTSIDE_REASON)
        val head = headId()
        val key = BaseKey(path, head)
        baseCache[key]?.let { return@withContext it }
        val read = readBlob(repository, path, head, checkTracked = true)
        if (read.cacheable) baseCache[key] = read.text
        read.text
    }

    /** [file]'s text at [revision]; `""` when it does not exist there; [baseText]'s rules otherwise. */
    suspend fun fileAt(revision: String, file: Path): BaseText = withContext(Dispatchers.IO) {
        val repository = repository() ?: return@withContext repositoryUnavailable()
        val path = normalizePath(file)
        if (!path.startsWith(repository.root)) return@withContext BaseText.Unavailable(OUTSIDE_REASON)
        readBlob(repository, path, revision, checkTracked = false).text
    }

    /** The files a commit changed, against its first parent; all of them for a root commit. */
    suspend fun changedFiles(revision: String): List<FileChange> = withContext(Dispatchers.IO) {
        val repository = repository() ?: return@withContext emptyList()
        val parents = readQuiet(GitCommand(listOf("rev-list", "--parents", "-n", "1", revision), projectRoot, timeouts.read))
        if (!parents.ok) return@withContext emptyList()
        val ids = parents.stdoutText().trim().split(' ').filter { it.isNotEmpty() }
        val args = if (ids.size <= 1) {
            listOf("diff-tree", "-r", "-z", "--name-status", "-M", "--no-commit-id", "--root", revision, "--", ".")
        } else {
            listOf(
                "diff-tree", "-r", "-z", "--name-status", "-M", "--no-commit-id",
                ids[1], revision, "--", ".",
            )
        }
        val result = read(GitCommand(args, projectRoot, timeouts.read))
        if (!result.ok) return@withContext emptyList()
        parseNameStatus(repository, result.stdoutText())
    }

    /** HEAD's last [limit] commits, newest first; empty for a repository without commits. */
    suspend fun log(limit: Int = 200): List<LogEntry> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val repository = repository() ?: return@withContext emptyList()
        if (!headExists()) return@withContext emptyList()
        val result = read(GitCommand(listOf("log", "-n", limit.toString(), LOG_FORMAT), projectRoot, timeouts.read))
        if (!result.ok) emptyList() else parseLog(result.stdoutText())
    }

    /**
     * Commits the working-tree content of [include] with [message]. Untracked included files are
     * added first; staged changes in files that are not included stay staged. The caller saves
     * the included editors before calling. With [amend], HEAD is replaced by a new commit instead:
     * an empty [include] amends the message only.
     */
    suspend fun commit(
        include: List<Path>,
        message: String,
        amend: AmendBase? = null,
    ): GitWriteResult = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext GitWriteResult(false, GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext GitWriteResult(false, notRepositoryMessage())
        operationInProgress()?.let { return@withContext GitWriteResult(false, operationMessage(it)) }
        if (message.trim().isEmpty()) return@withContext GitWriteResult(false, "Enter a commit message")
        val paths = include
            .map(::normalizePath)
            .filter { it.startsWith(repository.root) }
            .distinct()
        if (paths.isEmpty() && amend == null) return@withContext GitWriteResult(false, "No changes to commit")
        if (!writeGate.tryLock()) return@withContext GitWriteResult(false, "Git is busy: ${busy ?: "another write"}")
        try {
            updateBusy(if (amend == null) COMMITTING else AMENDING)
            statusGate.withLock { commitLocked(repository, paths, message, amend) }
        } finally {
            updateBusy(null)
            writeGate.unlock()
        }
    }

    private suspend fun commitLocked(
        repository: GitRepository,
        paths: List<Path>,
        message: String,
        amend: AmendBase?,
    ): GitWriteResult {
        if (amend != null) {
            // The message on screen belongs to [amend]'s commit, and a merge can start while the
            // window is open, so both checks run again right before git.
            operationInProgress()?.let { return GitWriteResult(false, operationMessage(it)) }
            val current = readQuiet(GitCommand(listOf("rev-parse", "HEAD"), repository.root, timeouts.read))
            if (!current.ok) return GitWriteResult(false, "Amend failed: ${describe(current, timeouts.read)}")
            if (current.stdoutText().trim() != amend.head) {
                return GitWriteResult(false, "HEAD changed since Amend was turned on: turn it off and on again")
            }
            when (val pushed = pushedCheck(repository)) {
                is PushedState.Pushed -> return GitWriteResult(false, pushed.message)
                is PushedState.Failed -> return GitWriteResult(false, "Amend failed: ${pushed.reason}")
                PushedState.NotPushed -> {}
            }
        }
        val commitPaths = (paths + renamedOriginals(repository, paths)).distinct()
        val untracked = untrackedFiles(paths)
        var added = 0
        if (untracked.isNotEmpty()) {
            val add = write(
                GitCommand(
                    listOf("add", "--") + untracked.map { it.toString() },
                    projectRoot,
                    timeouts.read,
                    write = true,
                ),
            )
            if (!add.ok) {
                refreshStatus()
                return if (amend == null) {
                    GitWriteResult(false, commitFailure(add, timeouts.read, added = 0), addedToIndex = 0)
                } else {
                    GitWriteResult(false, amendFailure(add, timeouts.read, added = 0), addedToIndex = 0)
                }
            }
            added = untracked.size
        }
        val messageFile = try {
            Files.createTempFile("stride-commit-", ".txt")
        } catch (e: java.io.IOException) {
            refreshStatus()
            return if (amend == null) {
                GitWriteResult(false, commitFailureText(e.message ?: "cannot write the message", added))
            } else {
                GitWriteResult(false, amendFailureText(e.message ?: "cannot write the message", added))
            }
        }
        val commitArgs = buildList {
            add("commit")
            if (amend != null) add("--amend")
            add("--only")
            add("-F")
            add(messageFile.toString())
            if (commitPaths.isNotEmpty()) {
                add("--")
                addAll(commitPaths.map { it.toString() })
            }
        }
        return try {
            messageFileWriter(messageFile, message)
            val result = write(GitCommand(commitArgs, projectRoot, timeouts.commit, write = true))
            if (!result.ok) {
                refreshStatus()
                return if (amend == null) {
                    GitWriteResult(false, commitFailure(result, timeouts.commit, added), addedToIndex = added)
                } else {
                    GitWriteResult(false, amendFailure(result, timeouts.commit, added), addedToIndex = added)
                }
            }
            val id = readQuiet(GitCommand(listOf("rev-parse", "HEAD"), projectRoot, timeouts.read))
                .stdoutText().trim()
            val subject = readQuiet(GitCommand(listOf("log", "-1", "--format=%s"), projectRoot, timeouts.read))
                .stdoutText().trim()
            val short = readQuiet(GitCommand(listOf("rev-parse", "--short", "HEAD"), projectRoot, timeouts.read))
                .stdoutText().trim()
            val branch = currentBranch()
            refreshStatus()
            if (amend != null) {
                val previousShort = readQuiet(
                    GitCommand(listOf("rev-parse", "--short", amend.head), projectRoot, timeouts.read),
                ).stdoutText().trim()
                GitWriteResult(
                    ok = true,
                    message = "Amended $short $subject on ${branch ?: "detached HEAD"} (was $previousShort)",
                    commitId = id,
                    subject = subject,
                    previousCommitId = amend.head,
                )
            } else {
                GitWriteResult(
                    ok = true,
                    message = "Committed ${paths.size} files to ${branch ?: "HEAD"}: $short $subject",
                    commitId = id,
                    subject = subject,
                )
            }
        } catch (e: java.io.IOException) {
            refreshStatus()
            if (amend == null) {
                GitWriteResult(false, commitFailureText(e.message ?: "cannot write the message", added), addedToIndex = added)
            } else {
                GitWriteResult(false, amendFailureText(e.message ?: "cannot write the message", added), addedToIndex = added)
            }
        } finally {
            runCatching { Files.deleteIfExists(messageFile) }
        }
    }

    /**
     * Discards the working-tree and index changes of [paths] the way V13 decides: a path HEAD has
     * is restored from HEAD, and a path HEAD does not have only leaves the index, so its content
     * stays on disk as an unversioned file. The caller discards the open editors of the paths
     * that were restored.
     */
    suspend fun rollback(paths: List<Path>): GitWriteResult = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext GitWriteResult(false, GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext GitWriteResult(false, notRepositoryMessage())
        if (!writeGate.tryLock()) return@withContext GitWriteResult(false, busyMessage())
        try {
            updateBusy(ROLLING_BACK)
            statusGate.withLock { rollbackLocked(repository, paths) }
        } finally {
            updateBusy(null)
            writeGate.unlock()
        }
    }

    private suspend fun rollbackLocked(repository: GitRepository, paths: List<Path>): GitWriteResult {
        // The popup can stay open while a merge starts in the terminal, so the check runs here.
        operationInProgress()?.let { return GitWriteResult(false, operationMessage(it)) }
        val targets = paths
            .map(::normalizePath)
            .filter { it.startsWith(repository.root) }
            .distinct()
        if (targets.isEmpty()) return GitWriteResult(false, "Nothing to roll back")
        val head = readQuiet(GitCommand(listOf("rev-parse", "--verify", "-q", "HEAD"), repository.root, timeouts.read))
        val headExists = when {
            head.ok -> true
            head.exitCode == 1 && head.stdoutText().isEmpty() -> false
            else -> return GitWriteResult(false, "Rollback failed: ${describe(head, timeouts.read)}")
        }
        val inHead: List<Path>
        val notInHead: List<Path>
        if (headExists) {
            val listed = read(
                GitCommand(
                    listOf("ls-tree", "-z", "--name-only", "--full-tree", "HEAD", "--") +
                        targets.map { repoRelative(repository, it) },
                    repository.root,
                    timeouts.read,
                ),
            )
            if (!listed.ok) return GitWriteResult(false, "Rollback failed: ${describe(listed, timeouts.read)}")
            val listedPaths = listed.stdoutText().split('\u0000').filter { it.isNotEmpty() }.toSet()
            val groups = targets.partition { repoRelative(repository, it) in listedPaths }
            inHead = groups.first
            notInHead = groups.second
        } else {
            inHead = emptyList()
            notInHead = targets
        }
        var restored: List<Path> = emptyList()
        if (inHead.isNotEmpty()) {
            val result = write(
                GitCommand(
                    listOf("restore", "--source=HEAD", "--staged", "--worktree", "--") +
                        inHead.map { repoRelative(repository, it) },
                    repository.root,
                    timeouts.rollback,
                    write = true,
                ),
            )
            if (!result.ok) {
                refreshStatus()
                return GitWriteResult(false, "Rollback failed: ${describe(result, timeouts.rollback)}")
            }
            restored = inHead
        }
        var unstaged: List<Path> = emptyList()
        if (notInHead.isNotEmpty()) {
            val result = write(
                GitCommand(
                    listOf("rm", "--cached", "--force", "--quiet", "--") +
                        notInHead.map { repoRelative(repository, it) },
                    repository.root,
                    timeouts.rollback,
                    write = true,
                ),
            )
            if (!result.ok) {
                val reason = "Rollback failed: ${describe(result, timeouts.rollback)}"
                val message = if (restored.isEmpty()) reason else buildString {
                    append(reason).append(" (").append(restored.size)
                    append(if (restored.size == 1) " file was" else " files were")
                    append(" already rolled back)")
                }
                refreshStatus()
                return GitWriteResult(false, message, restored = restored)
            }
            unstaged = notInHead
        }
        refreshStatus()
        val message = buildString {
            append("Rolled back ").append(targets.size)
            append(if (targets.size == 1) " file" else " files")
            if (unstaged.isNotEmpty()) {
                append("; ").append(unstaged.size)
                append(if (unstaged.size == 1) " added file kept as unversioned" else " added files kept as unversioned")
            }
        }
        return GitWriteResult(true, message, restored = restored, unstaged = unstaged)
    }

    /** The path git prints and takes: relative to the repository root with `/` separators. */
    private fun repoRelative(repository: GitRepository, path: Path): String =
        repository.root.relativize(path).toString().replace('\\', '/')

    /** V14 step 1: what turning Amend on needs, or why it cannot. Reads only. */
    suspend fun amendStart(): AmendStart = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext AmendStart.Refused(GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext AmendStart.Refused(notRepositoryMessage())
        operationInProgress()?.let { return@withContext AmendStart.Refused(operationMessage(it)) }
        val head = readQuiet(GitCommand(listOf("rev-parse", "--verify", "-q", "HEAD"), repository.root, timeouts.read))
        when {
            head.ok -> {}
            head.exitCode == 1 && head.stdoutText().isEmpty() ->
                return@withContext AmendStart.Refused("Nothing to amend: the branch has no commits yet")
            else -> return@withContext AmendStart.Refused("Amend failed: ${describe(head, timeouts.read)}")
        }
        val log = readQuiet(GitCommand(listOf("log", "-1", "--format=%H%x00%B", "HEAD"), repository.root, timeouts.read))
        if (!log.ok) return@withContext AmendStart.Refused("Amend failed: ${describe(log, timeouts.read)}")
        val fields = log.stdoutText().split('\u0000', limit = 2)
        if (fields.size < 2) return@withContext AmendStart.Refused("Amend failed: HEAD's commit message cannot be read")
        when (val pushed = pushedCheck(repository)) {
            is PushedState.Pushed -> return@withContext AmendStart.Refused(pushed.message)
            is PushedState.Failed -> return@withContext AmendStart.Refused("Amend failed: ${pushed.reason}")
            PushedState.NotPushed -> {}
        }
        AmendStart.Ready(AmendBase(fields[0].trim(), fields[1].trimEnd()))
    }

    /** What [push] would do, or why it cannot run. */
    suspend fun pushPlan(): PushPlan = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext PushPlan.Refused(GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext PushPlan.Refused(notRepositoryMessage())
        val branch = currentBranch() ?: return@withContext PushPlan.Refused("Push needs a branch: HEAD is detached")
        val upstream = upstreamOf(branch)
        val tracksRemote = upstream != null && remoteTrackingExists(upstream.first, upstream.second)
        val remote: String
        val remoteRef: String
        val newBranch: Boolean
        val range: String?
        when {
            upstream != null && tracksRemote -> {
                remote = upstream.first
                remoteRef = upstream.second
                newBranch = false
                range = "${remote}/${shortRef(remoteRef)}..HEAD"
            }
            // The upstream setting outlives the remote branch (a prune leaves it behind), so this
            // is a new branch push to the same place, not a branch with nothing to send.
            upstream != null -> {
                remote = upstream.first
                remoteRef = upstream.second
                newBranch = true
                range = null
            }
            else -> {
                val remotes = remotes()
                remote = when {
                    "origin" in remotes -> "origin"
                    remotes.size == 1 -> remotes.single()
                    else -> return@withContext PushPlan.Refused("No upstream: set one in the terminal")
                }
                remoteRef = "refs/heads/$branch"
                newBranch = true
                range = null
            }
        }
        val count = readQuiet(GitCommand(listOf("rev-list", "--count", range ?: "HEAD"), projectRoot, timeouts.read))
        val total = count.stdoutText().trim().toIntOrNull()
        if (!count.ok || total == null) {
            return@withContext PushPlan.Refused("Push failed: ${firstLine(count)}")
        }
        if (total == 0) return@withContext PushPlan.Refused("Nothing to push")
        val limit = minOf(total, MAX_OUTGOING)
        val logArgs = mutableListOf("log", "-n", limit.toString(), LOG_FORMAT)
        range?.let(logArgs::add)
        val outgoing = read(GitCommand(logArgs, projectRoot, timeouts.read))
            .takeIf { it.ok }
            ?.let { parseLog(it.stdoutText()) }
            ?: emptyList()
        val behind = if (!tracksRemote) 0 else {
            readQuiet(
                GitCommand(
                    listOf("rev-list", "--count", "HEAD..${remote}/${shortRef(remoteRef)}"),
                    projectRoot,
                    timeouts.read,
                ),
            ).stdoutText().trim().toIntOrNull() ?: 0
        }
        PushPlan.Ready(
            branch = branch,
            remote = remote,
            remoteRef = remoteRef,
            newBranch = newBranch,
            outgoing = outgoing,
            moreOutgoing = (total - outgoing.size).coerceAtLeast(0),
            behind = behind,
        )
    }

    /** Pushes [plan]'s branch; never with a force option. */
    suspend fun push(plan: PushPlan.Ready): GitWriteResult = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext GitWriteResult(false, GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext GitWriteResult(false, notRepositoryMessage())
        if (!writeGate.tryLock()) return@withContext GitWriteResult(false, "Git is busy: ${busy ?: "another write"}")
        try {
            updateBusy(PUSHING)
            statusGate.withLock {
                val environment = runInterruptible(Dispatchers.IO) {
                    sshBatchModeEnvironment(executor, projectRoot, timeouts.read)
                }
                val args = buildList {
                    add("push")
                    add("--porcelain")
                    add("--no-follow-tags")
                    if (plan.newBranch) add("-u")
                    add(plan.remote)
                    add("refs/heads/${plan.branch}:${plan.remoteRef}")
                }
                val result = write(
                    GitCommand(args, projectRoot, timeouts.push, write = true, environment = environment),
                )
                refreshStatus()
                if (result.ok) {
                    val count = plan.outgoing.size + plan.moreOutgoing
                    GitWriteResult(true, "Pushed $count commits to ${plan.remote}/${shortRef(plan.remoteRef)}")
                } else {
                    GitWriteResult(false, pushFailure(result, plan))
                }
            }
        } finally {
            updateBusy(null)
            writeGate.unlock()
        }
    }

    /** Fetches every remote; remote-tracking refs are all that changes. */
    suspend fun fetch(): GitWriteResult = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext GitWriteResult(false, GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext GitWriteResult(false, notRepositoryMessage())
        if (!writeGate.tryLock()) return@withContext GitWriteResult(false, busyMessage())
        try {
            val remotes = remotes()
            if (remotes.isEmpty()) return@withContext GitWriteResult(false, "No remotes: add one in the terminal")
            updateBusy(FETCHING)
            statusGate.withLock {
                val environment = runInterruptible(Dispatchers.IO) {
                    sshBatchModeEnvironment(executor, projectRoot, timeouts.read)
                }
                val result = write(
                    GitCommand(listOf("fetch", "--all"), projectRoot, timeouts.fetch, write = true, environment = environment),
                )
                refreshStatus()
                if (result.ok) {
                    val message = buildString {
                        append("Fetched from ").append(remotes.joinToString(", "))
                        val branch = snapshot.branch
                        if (branch?.upstream != null && branch.behind > 0) {
                            append(": ").append(branch.behind).append(" new commits on ").append(branch.upstream)
                        }
                    }
                    GitWriteResult(true, message)
                } else {
                    GitWriteResult(false, fetchFailure(result))
                }
            }
        } finally {
            updateBusy(null)
            writeGate.unlock()
        }
    }

    /**
     * What [update] would do: the upstream's remote is fetched first. A merge or rebase needs a
     * clean tracked tree in the whole repository plus [unsavedEditors] (`git status
     * --porcelain=v2 -z --untracked-files=no` at the repository root).
     */
    suspend fun updatePlan(unsavedEditors: Int = 0): UpdatePlan = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext UpdatePlan.Refused(GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext UpdatePlan.Refused(notRepositoryMessage())
        if (!writeGate.tryLock()) return@withContext UpdatePlan.Refused(busyMessage())
        val upstream = try {
            fetchUpstreamForUpdate()
        } finally {
            updateBusy(null)
            writeGate.unlock()
        }
        val ready = when (upstream) {
            is UpstreamCheck.Refused -> return@withContext UpdatePlan.Refused(upstream.message)
            is UpstreamCheck.Ready -> upstream
        }
        val branch = ready.branch
        val trackRemote = "refs/remotes/${ready.remote}/${shortRef(ready.remoteRef)}"
        val tracks = readQuiet(
            GitCommand(listOf("rev-parse", "--verify", "--quiet", trackRemote), projectRoot, timeouts.read),
        ).ok
        if (!tracks) {
            return@withContext UpdatePlan.Refused(
                "The upstream ${ready.remote}/${shortRef(ready.remoteRef)} does not exist: set one in the terminal",
            )
        }
        val counts = readQuiet(
            GitCommand(listOf("rev-list", "--left-right", "--count", "HEAD...$trackRemote"), projectRoot, timeouts.read),
        )
        val numbers = counts.stdoutText().trim().split(Regex("\\s+"))
        val ahead = numbers.getOrNull(0)?.toIntOrNull()
        val behind = numbers.getOrNull(1)?.toIntOrNull()
        if (!counts.ok || ahead == null || behind == null) {
            return@withContext UpdatePlan.Refused("Update failed: ${firstLine(counts)}")
        }
        if (behind == 0) return@withContext UpdatePlan.Refused("Already up to date")
        if (ahead > 0) {
            when (val changed = changedTrackedFiles(repository)) {
                is TrackedChanges.Failed -> return@withContext UpdatePlan.Refused(changed.message)
                is TrackedChanges.Count -> {
                    val total = changed.count + unsavedEditors
                    if (total > 0) return@withContext UpdatePlan.Refused(cleanTreeMessage(total))
                }
            }
        }
        val limit = minOf(behind, MAX_OUTGOING)
        val log = read(
            GitCommand(listOf("log", "-n", limit.toString(), LOG_FORMAT, "HEAD..$trackRemote"), projectRoot, timeouts.read),
        )
        val incoming = if (log.ok) parseLog(log.stdoutText()) else emptyList()
        val moreIncoming = (behind - incoming.size).coerceAtLeast(0)
        val strategies: List<UpdateStrategy>
        val defaultStrategy: UpdateStrategy
        if (ahead == 0) {
            strategies = listOf(UpdateStrategy.FAST_FORWARD)
            defaultStrategy = UpdateStrategy.FAST_FORWARD
        } else {
            strategies = listOf(UpdateStrategy.MERGE, UpdateStrategy.REBASE)
            defaultStrategy = configuredStrategy(branch)
        }
        UpdatePlan.Ready(
            branch = branch,
            remote = ready.remote,
            remoteRef = ready.remoteRef,
            incoming = incoming,
            moreIncoming = moreIncoming,
            ahead = ahead,
            strategies = strategies,
            defaultStrategy = defaultStrategy,
            unsavedEditors = unsavedEditors,
        )
    }

    /** Integrates [plan]'s upstream with [strategy], never with a force option or a stash. */
    suspend fun update(plan: UpdatePlan.Ready, strategy: UpdateStrategy): GitWriteResult = withContext(Dispatchers.IO) {
        if (gitMissing()) return@withContext GitWriteResult(false, GIT_NOT_INSTALLED)
        val repository = repository() ?: return@withContext GitWriteResult(false, notRepositoryMessage())
        if (!writeGate.tryLock()) return@withContext GitWriteResult(false, busyMessage())
        try {
            if (strategy !in plan.strategies) {
                return@withContext GitWriteResult(false, "Update failed: ${strategy.name} is not possible here")
            }
            operationInProgress()?.let { return@withContext GitWriteResult(false, operationMessage(it)) }
            updateBusy(UPDATING)
            statusGate.withLock {
                // The popup can stay open while the tree changes, and an abort cannot restore an
                // uncommitted edit, so the clean-tree check is repeated here before git starts.
                if (strategy != UpdateStrategy.FAST_FORWARD) {
                    when (val changed = changedTrackedFiles(repository)) {
                        is TrackedChanges.Failed -> return@withLock GitWriteResult(false, changed.message)
                        is TrackedChanges.Count -> if (changed.count > 0) {
                            return@withLock GitWriteResult(false, cleanTreeMessage(changed.count))
                        }
                    }
                }
                updateLocked(repository, plan, strategy)
            }
        } finally {
            updateBusy(null)
            writeGate.unlock()
        }
    }

    /** The operation the repository is in the middle of, or `null` when it is not. */
    suspend fun operationInProgress(): String? {
        val repository = repository() ?: return null
        if (present(repository.gitDir.resolve("rebase-merge")) || present(repository.gitDir.resolve("rebase-apply"))) {
            return "rebase"
        }
        if (present(repository.gitDir.resolve("MERGE_HEAD"))) return "merge"
        if (present(repository.gitDir.resolve("CHERRY_PICK_HEAD"))) return "cherry-pick"
        if (present(repository.gitDir.resolve("REVERT_HEAD"))) return "revert"
        val unmerged = readQuiet(GitCommand(listOf("ls-files", "-u", "-z"), repository.root, timeouts.read))
        return if (unmerged.stdoutText().isNotEmpty()) "merge" else null
    }

    override fun dispose() {
        synchronized(refreshLock) {
            if (disposed) return
            disposed = true
            debounceJob?.cancel()
            debounceJob = null
        }
        scope.cancel()
        // Cancels the calls the service launched; the interrupt reaches calls a caller launched
        // from another scope, so their child processes die with the project too. The lock makes a
        // call that is leaving the set wait here, so its removal and this loop cannot cross.
        synchronized(runningLock) {
            interruptingCalls = true
            runningThreads.forEach(Thread::interrupt)
        }
        baseCache.clear()
    }

    private fun publish(value: GitSnapshot) {
        Snapshot.withMutableSnapshot { snapshot = value }
    }

    private fun updateBusy(value: String?) {
        Snapshot.withMutableSnapshot { busy = value }
    }

    private fun record(command: GitCommand, result: GitResult, elapsed: Long) {
        val output = buildString {
            append(result.stdoutText())
            if (result.stderr.isNotBlank()) {
                if (isNotEmpty() && last() != '\n') append('\n')
                append(result.stderr)
            }
        }
        val entry = GitConsoleEntry(
            time = Instant.now(),
            command = display(command),
            exitCode = result.exitCode,
            duration = elapsed.nanoseconds,
            output = output.take(MAX_CONSOLE_OUTPUT),
            write = command.write,
        )
        Snapshot.withMutableSnapshot {
            consoleState.add(entry)
            while (consoleState.size > MAX_CONSOLE_ENTRIES) consoleState.removeAt(0)
        }
    }

    /** A read that records a failure in the console; expected misses use [readQuiet]. */
    private suspend fun read(command: GitCommand): GitResult {
        val started = System.nanoTime()
        val result = runQuietly(command)
        if (!result.ok) record(command, result, System.nanoTime() - started)
        return result
    }

    /** A read whose failure is an expected answer (not a repository, no HEAD, no blob). */
    private suspend fun readQuiet(command: GitCommand): GitResult = runQuietly(command)

    private suspend fun write(command: GitCommand): GitResult {
        val started = System.nanoTime()
        val result = runQuietly(command)
        record(command, result, System.nanoTime() - started)
        return result
    }

    // runInterruptible interrupts the blocked thread on cancellation, which is what makes the
    // executor's interrupt path destroy the child process tree.
    private suspend fun runQuietly(command: GitCommand): GitResult = try {
        runInterruptible(Dispatchers.IO) {
            val thread = Thread.currentThread()
            synchronized(runningLock) { runningThreads += thread }
            try {
                executor.run(command)
            } finally {
                val interruptedByDispose = synchronized(runningLock) {
                    runningThreads -= thread
                    interruptingCalls
                }
                // The dispose interrupt is for this call only; the pooled thread must go back clean.
                if (interruptedByDispose) Thread.interrupted()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GitResult(1, ByteArray(0), e.message ?: e.toString(), timedOut = false)
    }

    private suspend fun repository(): GitRepository? {
        if (gitMissing()) return null
        repositoryCache?.let { return it }
        val result = readQuiet(
            GitCommand(
                listOf("rev-parse", "--show-toplevel", "--absolute-git-dir"),
                projectRoot,
                timeouts.read,
            ),
        )
        if (!result.ok) return null
        val lines = result.stdoutText().lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.size < 2) return null
        val repository = GitRepository(
            root = Path.of(lines[0]).toAbsolutePath().normalize(),
            gitDir = Path.of(lines[1]).toAbsolutePath().normalize(),
        )
        repositoryCache = repository
        return repository
    }

    private suspend fun headId(): String? {
        if (headKnown) return headValue
        val result = readQuiet(GitCommand(listOf("rev-parse", "--verify", "--quiet", "HEAD"), projectRoot, timeouts.read))
        val id = result.stdoutText().trim().takeIf { result.ok && it.isNotEmpty() }
        headValue = id
        headKnown = true
        return id
    }

    private suspend fun headExists(): Boolean = readQuiet(
        GitCommand(listOf("rev-parse", "--verify", "--quiet", "HEAD"), projectRoot, timeouts.read),
    ).ok

    /**
     * The blob rules shared by [baseText] and [fileAt]; [revision] null means no HEAD commit.
     * [cacheable] is false for answers that come from the index or a failed read, because a
     * `git add` leaves HEAD unchanged and the cached answer would stay stale.
     */
    private suspend fun readBlob(
        repository: GitRepository,
        file: Path,
        revision: String?,
        checkTracked: Boolean,
    ): BlobRead {
        val repoRelative = repository.root.relativize(file).toString().replace('\\', '/')
        if (checkTracked && !isTracked(repository, repoRelative)) return BlobRead(BaseText.Untracked, false)
        if (hasFilterAttribute(repository, repoRelative)) {
            return BlobRead(BaseText.Unavailable(FILTER_REASON), true)
        }
        if (revision == null) return BlobRead(BaseText.Text(""), false)
        val spec = "$revision:$repoRelative"
        val size = readQuiet(GitCommand(listOf("cat-file", "-s", spec), projectRoot, timeouts.read))
        if (!size.ok) return BlobRead(BaseText.Text(""), false)
        val bytes = size.stdoutText().trim().toLongOrNull() ?: 0L
        if (bytes > MAX_BASE_BYTES) return BlobRead(BaseText.Unavailable(LARGE_REASON), true)
        val blob = read(GitCommand(listOf("cat-file", "blob", spec), projectRoot, timeouts.read))
        if (!blob.ok) return BlobRead(BaseText.Unavailable("Cannot read $repoRelative"), false)
        return BlobRead(decode(blob.stdout), true)
    }

    /** A per-file command runs in the repository root with the repository-relative path. */
    private suspend fun isTracked(repository: GitRepository, repoRelative: String): Boolean {
        val result = readQuiet(GitCommand(listOf("ls-files", "-z", "--", repoRelative), repository.root, timeouts.read))
        return result.ok && result.stdoutText().isNotEmpty()
    }

    private suspend fun hasFilterAttribute(repository: GitRepository, repoRelative: String): Boolean {
        val result = readQuiet(
            GitCommand(listOf("check-attr", "-z", "filter", "--", repoRelative), repository.root, timeouts.read),
        )
        if (!result.ok) return false
        val fields = result.stdoutText().split('\u0000')
        val value = fields.getOrNull(2)?.trim().orEmpty()
        return value.isNotEmpty() && value != "unspecified" && value != "unset"
    }

    private fun decode(bytes: ByteArray): BaseText {
        val scan = minOf(bytes.size, NUL_SCAN_BYTES)
        for (index in 0 until scan) {
            if (bytes[index] == 0.toByte()) return BaseText.Unavailable(BINARY_REASON)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            BaseText.Text(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: CharacterCodingException) {
            BaseText.Unavailable(UTF8_REASON)
        }
    }

    /** Maps a path under the project root to the real root git uses, without touching the disk. */
    private fun normalizePath(file: Path): Path {
        val absolute = file.toAbsolutePath().normalize()
        if (absolute.startsWith(lexicalRoot) && lexicalRoot != projectRoot) {
            return projectRoot.resolve(lexicalRoot.relativize(absolute)).normalize()
        }
        return absolute
    }

    /**
     * `commit --only` records a rename only when both ends are on its command line. An original
     * that still exists on disk belongs to a copy, and taking it would include an unchecked file.
     */
    private fun renamedOriginals(repository: GitRepository, paths: List<Path>): List<Path> {
        val included = paths.toSet()
        return snapshot.changes.asSequence()
            .filter { it.kind == ChangeKind.RENAMED && it.path in included }
            .mapNotNull { it.originalPath }
            .filter { it.startsWith(repository.root) && !Files.exists(it) }
            .distinct()
            .toList()
    }

    private suspend fun untrackedFiles(paths: List<Path>): List<Path> {
        val result = readQuiet(
            GitCommand(
                listOf("ls-files", "-o", "--exclude-standard", "-z", "--") + paths.map { it.toString() },
                projectRoot,
                timeouts.read,
            ),
        )
        if (!result.ok) return emptyList()
        val untracked = result.stdoutText().split('\u0000')
            .filter { it.isNotEmpty() }
            .map { projectRoot.resolve(it).normalize() }
            .toSet()
        return paths.filter { it in untracked }
    }

    private fun statusCommand(): GitCommand = GitCommand(
        listOf("status", "--porcelain=v2", "-z", "--branch", "--untracked-files=all", "--", "."),
        projectRoot,
        timeouts.read,
    )

    private suspend fun currentBranch(): String? {
        val result = readQuiet(GitCommand(listOf("symbolic-ref", "--short", "-q", "HEAD"), projectRoot, timeouts.read))
        return result.stdoutText().trim().takeIf { result.ok && it.isNotEmpty() }
    }

    /** The remote and remote ref the branch tracks, or `null` when it has no upstream. */
    private suspend fun upstreamOf(branch: String): Pair<String, String>? {
        val result = readQuiet(
            GitCommand(
                listOf(
                    "for-each-ref",
                    "--format=%(upstream:remotename)%00%(upstream:remoteref)",
                    "refs/heads/$branch",
                ),
                projectRoot,
                timeouts.read,
            ),
        )
        if (!result.ok) return null
        val fields = result.stdoutText().trimEnd('\n').split('\u0000')
        val remote = fields.getOrNull(0)?.trim().orEmpty()
        val remoteRef = fields.getOrNull(1)?.trim().orEmpty()
        return if (remote.isEmpty() || remoteRef.isEmpty()) null else remote to remoteRef
    }

    private suspend fun remotes(): List<String> {
        val result = readQuiet(GitCommand(listOf("remote"), projectRoot, timeouts.read))
        if (!result.ok) return emptyList()
        return result.stdoutText().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** Whether the upstream's remote-tracking ref is still in the local repository. */
    private suspend fun remoteTrackingExists(remote: String, remoteRef: String): Boolean = readQuiet(
        GitCommand(
            listOf("rev-parse", "--verify", "--quiet", "refs/remotes/$remote/${shortRef(remoteRef)}"),
            projectRoot,
            timeouts.read,
        ),
    ).ok

    private fun parseNameStatus(repository: GitRepository, output: String): List<FileChange> {
        val tokens = output.split('\u0000')
        val changes = ArrayList<FileChange>()
        var index = 0
        while (index < tokens.size) {
            val status = tokens[index]
            if (status.isEmpty()) {
                index++
                continue
            }
            val marker = status.first()
            if (marker == 'R' || marker == 'C') {
                val original = tokens.getOrNull(index + 1)
                val path = tokens.getOrNull(index + 2)
                if (original != null && path != null) {
                    changes += FileChange(
                        repository.root.resolve(path).normalize(),
                        ChangeKind.RENAMED,
                        repository.root.resolve(original).normalize(),
                    )
                }
                index += 3
            } else {
                val path = tokens.getOrNull(index + 1)
                if (path != null) changes += FileChange(repository.root.resolve(path).normalize(), kindOfStatus(marker))
                index += 2
            }
        }
        return changes
    }

    private fun kindOfStatus(marker: Char): ChangeKind = when (marker) {
        'A' -> ChangeKind.ADDED
        'D' -> ChangeKind.DELETED
        'R', 'C' -> ChangeKind.RENAMED
        'U' -> ChangeKind.CONFLICTED
        else -> ChangeKind.MODIFIED
    }

    private fun parseLog(output: String): List<LogEntry> = output.split(RECORD_SEPARATOR).mapNotNull { record ->
        val text = record.trimStart('\n')
        if (text.isEmpty()) return@mapNotNull null
        val fields = text.split('\u0000')
        if (fields.size < 6) return@mapNotNull null
        LogEntry(
            id = fields[0],
            shortId = fields[1],
            author = fields[2],
            time = Instant.ofEpochSecond(fields[3].toLongOrNull() ?: 0L),
            refs = fields[4].split(',').map { it.trim() }.filter { it.isNotEmpty() },
            subject = fields[5],
        )
    }

    private fun gitMissing(): Boolean = executor === MissingGitExecutor

    private fun repositoryUnavailable(): BaseText =
        if (gitMissing()) BaseText.Unavailable(GIT_NOT_INSTALLED) else BaseText.Unavailable(notRepositoryMessage())

    private fun notRepositoryMessage(): String = "Not a Git repository: $lexicalRoot"

    private fun isNotRepository(result: GitResult): Boolean {
        val text = (result.stderr + result.stdoutText()).lowercase()
        return text.contains("not a git repository") || text.contains("not a repository")
    }

    private fun commitFailure(result: GitResult, timeout: Duration, added: Int): String =
        commitFailureText(describe(result, timeout), added)

    private fun amendFailure(result: GitResult, timeout: Duration, added: Int): String =
        amendFailureText(describe(result, timeout), added)

    /** A commit that fails after `git add` says how many new files are already in the index. */
    private fun commitFailureText(reason: String, added: Int): String =
        writeFailureText("Commit failed", reason, added)

    /** An amend that fails after `git add` ends with the same suffix [commitFailureText] adds. */
    private fun amendFailureText(reason: String, added: Int): String =
        writeFailureText("Amend failed", reason, added)

    private fun writeFailureText(prefix: String, reason: String, added: Int): String = buildString {
        append(prefix).append(": ").append(reason)
        if (added > 0) {
            append(" (").append(added)
            append(if (added == 1) " new file was" else " new files were")
            append(" added to the index)")
        }
    }

    private fun pushFailure(result: GitResult, plan: PushPlan.Ready): String {
        val text = result.stdoutText() + "\n" + result.stderr
        val lower = text.lowercase()
        return when {
            result.timedOut -> "Push failed: ${describe(result, timeouts.push)}"
            lower.contains("non-fast-forward") || lower.contains("[rejected]") || lower.contains("fetch first") ->
                "Push rejected: pull the remote changes first"
            AUTH_MARKERS.any(lower::contains) -> "Push needs credentials or a passphrase: push from the terminal (⌥F12)"
            else -> "Push failed: ${describe(result, timeouts.push)}"
        }
    }

    /**
     * Whether a remote-tracking ref contains HEAD (V14's check). The remote's symbolic `HEAD` is
     * not a branch and does not count.
     */
    private suspend fun pushedCheck(repository: GitRepository): PushedState {
        val result = readQuiet(
            GitCommand(
                listOf("for-each-ref", "--contains", "HEAD", "--format=%(refname)", "refs/remotes"),
                repository.root,
                timeouts.read,
            ),
        )
        if (!result.ok) return PushedState.Failed(describe(result, timeouts.read))
        val ref = result.stdoutText().lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.endsWith("/HEAD") }
            ?: return PushedState.NotPushed
        return PushedState.Pushed(
            "HEAD is already pushed to ${ref.removePrefix("refs/remotes/")}: amending it needs a force push",
        )
    }

    private suspend fun fetchUpstreamForUpdate(): UpstreamCheck {
        operationInProgress()?.let { return UpstreamCheck.Refused(operationMessage(it)) }
        val branch = currentBranch() ?: return UpstreamCheck.Refused("Update needs a branch: HEAD is detached")
        val (remote, remoteRef) = upstreamOf(branch) ?: return UpstreamCheck.Refused("No upstream: set one in the terminal")
        updateBusy(FETCHING)
        val result = statusGate.withLock {
            val environment = runInterruptible(Dispatchers.IO) {
                sshBatchModeEnvironment(executor, projectRoot, timeouts.read)
            }
            val fetched = write(
                GitCommand(listOf("fetch", remote), projectRoot, timeouts.fetch, write = true, environment = environment),
            )
            refreshStatus()
            fetched
        }
        if (!result.ok) return UpstreamCheck.Refused(updateFetchFailure(result))
        return UpstreamCheck.Ready(branch, remote, remoteRef)
    }

    /** The strategy `git pull` would use on [branch]; an interactive rebase is never one of them. */
    private suspend fun configuredStrategy(branch: String): UpdateStrategy {
        val branchConfig = readQuiet(
            GitCommand(listOf("config", "--get", "branch.$branch.rebase"), projectRoot, timeouts.read),
        )
        val branchValue = branchConfig.stdoutText().trim().takeIf { branchConfig.ok && it.isNotEmpty() }
        val setting = branchValue ?: run {
            val pullConfig = readQuiet(GitCommand(listOf("config", "--get", "pull.rebase"), projectRoot, timeouts.read))
            pullConfig.stdoutText().trim().takeIf { pullConfig.ok && it.isNotEmpty() }
        }
        return if (setting?.lowercase() in REBASE_TRUE_VALUES) UpdateStrategy.REBASE else UpdateStrategy.MERGE
    }

    /**
     * The tracked changes in the whole repository, read the way [updatePlan] and [update] share.
     * A status that cannot answer is a refusal, not a clean tree.
     */
    private suspend fun changedTrackedFiles(repository: GitRepository): TrackedChanges {
        val status = readQuiet(
            GitCommand(
                listOf("status", "--porcelain=v2", "-z", "--untracked-files=no"),
                repository.root,
                timeouts.read,
            ),
        )
        if (!status.ok) return TrackedChanges.Failed("Update failed: ${describe(status, timeouts.read)}")
        val count = GitStatusParser.parse(status.stdoutText()).records.count { it.kind != ChangeKind.UNTRACKED }
        return TrackedChanges.Count(count)
    }

    private fun cleanTreeMessage(changed: Int): String =
        "Update needs a clean working tree to merge or rebase: commit your $changed changed files first"

    private suspend fun updateLocked(
        repository: GitRepository,
        plan: UpdatePlan.Ready,
        strategy: UpdateStrategy,
    ): GitWriteResult {
        val ref = "refs/remotes/${plan.remote}/${shortRef(plan.remoteRef)}"
        val args = when (strategy) {
            UpdateStrategy.FAST_FORWARD -> listOf("merge", "--ff-only", "--no-autostash", ref)
            UpdateStrategy.MERGE -> listOf("merge", "--no-edit", "--no-autostash", ref)
            UpdateStrategy.REBASE -> listOf("rebase", "--no-autostash", ref)
        }
        val result = write(GitCommand(args, repository.root, timeouts.update, write = true))
        refreshStatus()
        if (result.ok) {
            val count = plan.incoming.size + plan.moreIncoming
            val word = when (strategy) {
                UpdateStrategy.FAST_FORWARD -> "fast-forward"
                UpdateStrategy.MERGE -> "merge"
                UpdateStrategy.REBASE -> "rebase"
            }
            return GitWriteResult(
                true,
                "Updated ${plan.branch}: $count commits from ${plan.remote}/${shortRef(plan.remoteRef)} ($word)",
            )
        }
        val text = result.stdoutText() + "\n" + result.stderr
        val lower = text.lowercase()
        // The in-progress marker decides first: only the abort restores a half-finished rebase,
        // whatever the output says.
        val operation = operationInProgress()
        if (operation != null) {
            val conflicts = readQuiet(
                GitCommand(
                    listOf("diff", "--name-only", "--diff-filter=U", "-z"),
                    repository.root,
                    timeouts.read,
                ),
            ).stdoutText().split('\u0000').count { it.isNotEmpty() }
            val abortArgs = if (operation == "rebase") listOf("rebase", "--abort") else listOf("merge", "--abort")
            val aborted = write(GitCommand(abortArgs, repository.root, timeouts.update, write = true))
            refreshStatus()
            if (!aborted.ok) {
                return GitWriteResult(
                    false,
                    "Update left a $operation in progress: finish or abort it in the terminal (⌥F12)",
                )
            }
            return if (conflicts > 0) {
                GitWriteResult(
                    false,
                    "Update had conflicts in $conflicts files and was undone: update from the terminal (⌥F12) to resolve them",
                )
            } else {
                GitWriteResult(false, "Update failed: ${firstLine(result)} (undone)")
            }
        }
        if (lower.contains("would be overwritten by merge") && lower.contains("your local changes to the following files")) {
            val count = countListedFiles(text, "your local changes to the following files")
            return GitWriteResult(
                false,
                "Update stopped: local changes to $count files would be overwritten: commit them first",
            )
        }
        if (lower.contains("untracked working tree files would be overwritten")) {
            val count = countListedFiles(text, "untracked working tree files would be overwritten")
            return GitWriteResult(false, "Update stopped: $count untracked files would be overwritten: move them first")
        }
        return GitWriteResult(false, "Update failed: ${describe(result, timeouts.update)}")
    }

    /** git lists the files it will not overwrite as the tab-indented lines after [marker]. */
    private fun countListedFiles(text: String, marker: String): Int {
        val lines = text.lineSequence().toList()
        val start = lines.indexOfFirst { it.lowercase().contains(marker) }
        if (start < 0) return 0
        return lines.drop(start + 1).takeWhile { it.startsWith('\t') }.size
    }

    private fun operationMessage(operation: String): String =
        "A $operation is in progress: finish it in the terminal (⌥F12)"

    private fun busyMessage(): String = "Git is busy: ${busy ?: "another write"}"

    /** An unreadable entry counts as present: a check that cannot answer must not start a write. */
    private fun present(path: Path): Boolean = !Files.notExists(path)

    private fun fetchFailure(result: GitResult): String {
        if (!result.timedOut && AUTH_MARKERS.any { lowerOutput(result).contains(it) }) {
            return "Fetch needs credentials or a passphrase: fetch from the terminal (⌥F12)"
        }
        return "Fetch failed: ${describe(result, timeouts.fetch)}"
    }

    private fun updateFetchFailure(result: GitResult): String {
        if (!result.timedOut && AUTH_MARKERS.any { lowerOutput(result).contains(it) }) {
            return "Update needs credentials or a passphrase: update from the terminal (⌥F12)"
        }
        return "Update failed: ${describe(result, timeouts.fetch)}"
    }

    private fun lowerOutput(result: GitResult): String =
        (result.stdoutText() + "\n" + result.stderr).lowercase()

    private fun describe(result: GitResult, timeout: Duration): String =
        if (result.timedOut) "timed out after ${timeout.inWholeSeconds} s" else firstLine(result)

    private fun firstLine(result: GitResult): String {
        val text = result.stderr.trim().ifEmpty { result.stdoutText().trim() }
        return text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: "git exited with ${result.exitCode}"
    }

    private fun shortRef(ref: String): String =
        ref.removePrefix("refs/heads/").removePrefix("refs/tags/").removePrefix("refs/remotes/")

    private fun display(command: GitCommand): String = (listOf("git") + command.args).joinToString(" ") { element ->
        if (element.any { it.isWhitespace() || it == '\'' || it == '"' }) "'$element'" else element
    }

    private data class BaseKey(val path: Path, val head: String?)

    private class BlobRead(val text: BaseText, val cacheable: Boolean)

    private sealed interface UpstreamCheck {
        data class Ready(val branch: String, val remote: String, val remoteRef: String) : UpstreamCheck
        data class Refused(val message: String) : UpstreamCheck
    }

    private sealed interface TrackedChanges {
        data class Count(val count: Int) : TrackedChanges
        data class Failed(val message: String) : TrackedChanges
    }

    private sealed interface PushedState {
        data object NotPushed : PushedState
        data class Pushed(val message: String) : PushedState
        data class Failed(val reason: String) : PushedState
    }

    companion object {
        /** System property that turns the default executor off; the app's test task sets it. */
        const val DISABLE_DEFAULT_EXECUTOR: String = "stride.git.disableDefaultExecutor"
        const val MAX_CONSOLE_ENTRIES: Int = 200
        const val MAX_CONSOLE_OUTPUT: Int = 64 * 1024
        const val MAX_BASE_BYTES: Long = 8L * 1024 * 1024
        const val NUL_SCAN_BYTES: Int = 8_000
        const val MAX_OUTGOING: Int = 100
        const val DEBOUNCE_MILLIS: Long = 300

        private const val COMMITTING = "Committing…"
        private const val AMENDING = "Amending…"
        private const val ROLLING_BACK = "Rolling back…"
        private const val PUSHING = "Pushing…"
        private const val FETCHING = "Fetching…"
        private const val UPDATING = "Updating…"
        private const val GIT_NOT_INSTALLED = "Git is not installed"
        private const val OUTSIDE_REASON = "Outside the repository"
        private const val FILTER_REASON = "Filtered by a Git attribute"
        private const val BINARY_REASON = "Binary file"
        private const val UTF8_REASON = "Not valid UTF-8"
        private const val LARGE_REASON = "Larger than 8 MB"
        private const val RECORD_SEPARATOR = '\u001f'
        private const val LOG_FORMAT = "--format=%H%x00%h%x00%an%x00%at%x00%D%x00%s%x1f"

        private val REBASE_TRUE_VALUES = setOf("true", "yes", "on", "1", "merges", "m", "interactive", "i")

        private val AUTH_MARKERS = listOf(
            "authentication failed",
            "permission denied",
            "could not read username",
            "could not read password",
            "terminal prompts disabled",
            "host key verification failed",
            "passphrase",
            "publickey",
            "no such identity",
            "support for password authentication was removed",
        )
    }
}
