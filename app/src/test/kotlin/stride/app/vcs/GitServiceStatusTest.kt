package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.service

class GitServiceStatusTest {

    @TempDir
    lateinit var temp: Path

    private fun repoWithCommit(): TestGitRepo {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "fun main() {}\n")
        repo.write("src/deleted.kt", "gone soon\n")
        repo.write("src/old.txt", "old name\n")
        repo.commitAll("Initial commit")
        return repo
    }

    @Test
    fun `the snapshot reports every change kind with absolute paths`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "fun main() {}\n")
        repo.write("src/deleted.kt", "gone soon\n")
        repo.write("src/old.txt", "old name\n")
        repo.write("src/shared.txt", "base\n")
        repo.commitAll("Initial commit")
        repo.git("checkout", "-b", "other")
        repo.write("src/shared.txt", "other\n")
        repo.commitAll("other side")
        repo.git("checkout", "main")
        repo.write("src/shared.txt", "main\n")
        repo.commitAll("main side")
        repo.git("merge", "other", check = false)

        repo.write("src/Main.kt", "fun main() { println() }\n")
        Files.delete(repo.root.resolve("src/deleted.kt"))
        repo.write("src/added.kt", "added\n")
        repo.git("add", "--", "src/added.kt")
        repo.git("mv", "src/old.txt", "src/new.txt")
        repo.write("web/static/site.js", "console.log(1)\n")

        val snapshot = withService(repo) { it.refreshNow() }

        val changes = snapshot.changes.associate { repo.root.relativize(it.path).toString() to it.kind }
        assertEquals(ChangeKind.MODIFIED, changes["src/Main.kt"])
        assertEquals(ChangeKind.DELETED, changes["src/deleted.kt"])
        assertEquals(ChangeKind.ADDED, changes["src/added.kt"])
        assertEquals(ChangeKind.RENAMED, changes["src/new.txt"])
        assertEquals(ChangeKind.UNTRACKED, changes["web/static/site.js"])
        assertEquals(ChangeKind.CONFLICTED, changes["src/shared.txt"])
        assertEquals(repo.root.resolve("src/old.txt"), snapshot.changes.single { it.kind == ChangeKind.RENAMED }.originalPath)
        assertEquals("main", snapshot.branch?.branch)
        assertEquals(0, snapshot.branch?.ahead)
    }

    @Test
    fun `a project in a subfolder only shows changes under it`() {
        val repo = repoWithCommit()
        repo.write("sub/tracked.txt", "tracked\n")
        repo.commitAll("sub file")
        repo.write("sub/tracked.txt", "changed\n")
        repo.write("outside.txt", "outside\n")
        repo.write("sub/inner/note.txt", "inside\n")
        val projectRoot = repo.root.resolve("sub")

        val snapshot = withService(repo, root = projectRoot) { it.refreshNow() }

        assertEquals(repo.root, snapshot.repository?.root)
        val paths = snapshot.changes.map { repo.root.relativize(it.path).toString() }.toSet()
        assertEquals(setOf("sub/tracked.txt", "sub/inner/note.txt"), paths)
    }

    @Test
    fun `a project opened through a symlink is scoped by its real path`() {
        val repo = repoWithCommit()
        val link = temp.resolve("web-dashboard")
        Files.createSymbolicLink(link, repo.root)
        repo.write("src/Main.kt", "changed\n")

        val snapshot = withService(repo, root = link) { it.refreshNow() }

        assertEquals(repo.root, snapshot.repository?.root)
        assertEquals(listOf(repo.root.resolve("src/Main.kt")), snapshot.changes.map { it.path })
    }

    @Test
    fun `a directory outside any repository has no repository and no error`() {
        val root = temp.resolve("api-server").createDirectories()
        val home = temp.resolve("home").createDirectories()
        val environment = TestGitRepo.isolatedEnvironment(ceiling = temp, home = home)
        val project = stride.core.services.Project(root)
        try {
            val service = project.service<GitService>()
            service.executor = CliGitExecutor(TEST_GIT, environment)

            val snapshot = runBlocking { service.refreshNow() }
            assertNull(snapshot.repository)
            assertNull(snapshot.error)
            assertEquals(emptyList<FileChange>(), snapshot.changes)

            val base = runBlocking { service.baseText(root.resolve("file.txt")) }
            assertEquals(BaseText.Unavailable("Not a Git repository: ${root.toAbsolutePath().normalize()}"), base)
            val commit = runBlocking { service.commit(listOf(root.resolve("file.txt")), "message") }
            assertEquals("Not a Git repository: ${root.toAbsolutePath().normalize()}", commit.message)
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `a linked worktree is its own repository root`() {
        val repo = repoWithCommit()
        val worktree = temp.resolve("web-dashboard")
        repo.git("worktree", "add", "-b", "feature", worktree.toString())

        val snapshot = withService(repo, root = worktree) { it.refreshNow() }

        assertEquals(worktree.toRealPath(), snapshot.repository?.root)
        assertEquals("feature", snapshot.branch?.branch)
        assertTrue(snapshot.changes.isEmpty())
    }

    @Test
    fun `a repository without commits has no head and an untracked file is not compared`() {
        val repo = TestGitRepo.create(temp)
        val file = repo.write("src/New.kt", "new\n")
        repo.git("add", "--", "src/New.kt")

        withService(repo) { service ->
            val snapshot = service.refreshNow()
            assertNull(snapshot.branch?.head)
            assertEquals("main", snapshot.branch?.branch)
            assertEquals(listOf(ChangeKind.ADDED), snapshot.changes.map { it.kind })
            assertEquals(BaseText.Text(""), service.baseText(file))
            assertEquals(emptyList<LogEntry>(), service.log())
            assertTrue(service.log(0).isEmpty())
        }
    }

    @Test
    fun `base text is the file at head and an untracked file is not compared`() {
        val repo = repoWithCommit()
        val tracked = repo.write("src/Plain.txt", "hello\n")
        repo.commitAll("plain")
        val untracked = repo.write("src/Untracked.txt", "later\n")

        withService(repo) { service ->
            assertEquals(BaseText.Text("hello\n"), service.baseText(tracked))
            assertEquals(BaseText.Untracked, service.baseText(untracked))
        }
    }

    @Test
    fun `a CRLF checkout of an LF blob has no marks`() {
        val repo = TestGitRepo.create(temp)
        repo.git("config", "core.autocrlf", "true")
        val file = repo.write("src/Windows.txt", "one\r\ntwo\r\n")
        repo.commitAll("windows")
        val current = Files.readString(file)

        withService(repo) { service ->
            val base = service.baseText(file) as BaseText.Text
            assertEquals("one\ntwo\n", base.content)
            val hunks = LineDiff.diff(LineDiff.split(base.content), LineDiff.split(current))
            assertEquals(emptyList<LineHunk>(), hunks)
        }
    }

    @Test
    fun `a binary, an invalid UTF-8 file, a filtered file and a large file are unavailable`() {
        val repo = TestGitRepo.create(temp)
        Files.createDirectories(repo.root.resolve("src"))
        Files.write(repo.root.resolve("src/Binary.dat"), byteArrayOf(1, 2, 0, 3))
        Files.write(repo.root.resolve("src/Bad.txt"), byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        repo.write("src/Filtered.txt", "filtered\n")
        repo.root.resolve(".gitattributes").writeText("src/Filtered.txt filter=mine\n")
        val large = repo.root.resolve("src/Large.txt")
        Files.write(large, ByteArray((8 * 1024 * 1024) + 1) { 'a'.code.toByte() })
        repo.write("src/Fine.txt", "fine\n")
        repo.commitAll("files")

        withService(repo) { service ->
            assertTrue(service.baseText(repo.root.resolve("src/Binary.dat")).isUnavailable("Binary"))
            assertTrue(service.baseText(repo.root.resolve("src/Bad.txt")).isUnavailable("UTF-8"))
            assertTrue(service.baseText(repo.root.resolve("src/Filtered.txt")).isUnavailable("Filtered"))
            assertTrue(service.baseText(large).isUnavailable("8 MB"))
            assertEquals(BaseText.Text("fine\n"), service.baseText(repo.root.resolve("src/Fine.txt")))
        }
    }

    @Test
    fun `a path outside the repository and a missing git are unavailable`() {
        val repo = repoWithCommit()

        withService(repo) { service ->
            assertEquals(
                BaseText.Unavailable("Outside the repository"),
                service.baseText(temp.resolve("outside.txt")),
            )
            service.executor = MissingGitExecutor
            assertEquals(BaseText.Unavailable("Git is not installed"), service.baseText(repo.root.resolve("src/Main.kt")))
            assertEquals("Git is not installed", service.commit(listOf(repo.root.resolve("src/Main.kt")), "m").message)
            assertEquals("Git is not installed", (service.pushPlan() as PushPlan.Refused).message)
        }
    }

    @Test
    fun `changed files follows the first parent and treats a root commit as all added`() {
        val repo = repoWithCommit()
        val first = repo.git("rev-parse", "HEAD").trim()
        repo.git("mv", "src/old.txt", "src/new.txt")
        repo.commitAll("rename")
        val second = repo.git("rev-parse", "HEAD").trim()

        withService(repo) { service ->
            val root = service.changedFiles(first)
            assertEquals(3, root.size)
            assertTrue(root.all { it.kind == ChangeKind.ADDED })

            val rename = service.changedFiles(second).single()
            assertEquals(ChangeKind.RENAMED, rename.kind)
            assertEquals(repo.root.resolve("src/new.txt"), rename.path)
            assertEquals(repo.root.resolve("src/old.txt"), rename.originalPath)
        }
    }

    @Test
    fun `log parses newest first and is empty before the first commit`() {
        val repo = repoWithCommit()
        repo.write("src/Main.kt", "fun main() { println() }\n")
        repo.commitAll("Second commit")

        withService(repo) { service ->
            val entries = service.log()
            assertEquals(2, entries.size)
            assertEquals("Second commit", entries[0].subject)
            assertEquals("Initial commit", entries[1].subject)
            assertEquals("Test Author", entries[0].author)
            assertTrue(entries[0].shortId.isNotEmpty())
            assertEquals(entries[0].shortId, entries[0].id.take(entries[0].shortId.length))
            assertEquals(1, service.log(1).size)
        }
    }

    @Test
    fun `ten refresh calls inside the debounce window run one status`() {
        val repo = repoWithCommit()
        withService(repo) { service ->
            val executor = CountingExecutor(repo)
            service.executor = executor

            repeat(10) { service.refresh() }

            assertTrue(eventually { executor.statusCalls.get() == 1 }, "no status call")
            Thread.sleep(700)
            assertEquals(1, executor.statusCalls.get())
        }
    }

    @Test
    fun `a refresh requested while one runs causes exactly one more`() {
        val repo = repoWithCommit()
        withService(repo) { service ->
            val executor = CountingExecutor(repo)
            executor.blockStatus = CountDownLatch(1)
            service.executor = executor

            service.refresh()
            assertTrue(executor.statusEntered.await(5, TimeUnit.SECONDS), "the first status never started")
            repeat(10) { service.refresh() }
            executor.blockStatus!!.countDown()

            assertTrue(eventually { executor.statusCalls.get() == 2 }, "expected one more status")
            Thread.sleep(700)
            assertEquals(2, executor.statusCalls.get())
        }
    }

    private fun BaseText.isUnavailable(fragment: String): Boolean =
        this is BaseText.Unavailable && reason.contains(fragment, ignoreCase = true)

    private class CountingExecutor(private val repo: TestGitRepo) : GitExecutor {
        val statusCalls = AtomicInteger()
        val statusEntered = CountDownLatch(1)
        val commands = CopyOnWriteArrayList<List<String>>()

        @Volatile
        var blockStatus: CountDownLatch? = null

        override fun run(command: GitCommand): GitResult {
            commands += command.args
            return when (command.args.firstOrNull()) {
                "rev-parse" -> GitResult(
                    0,
                    "${repo.root}\n${repo.root.resolve(".git")}\n".toByteArray(),
                    "",
                    timedOut = false,
                )
                "status" -> {
                    statusCalls.incrementAndGet()
                    statusEntered.countDown()
                    blockStatus?.await(10, TimeUnit.SECONDS)
                    GitResult(
                        0,
                        "# branch.oid abc\u0000# branch.head main\u0000# branch.ab +0 -0\u0000".toByteArray(),
                        "",
                        timedOut = false,
                    )
                }
                else -> GitResult(0, ByteArray(0), "", timedOut = false)
            }
        }
    }
}
