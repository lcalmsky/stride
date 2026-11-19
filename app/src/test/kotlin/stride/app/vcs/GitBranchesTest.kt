package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitBranchesTest {

    @TempDir
    lateinit var temp: Path

    /** A repository with `origin` and a second repository that pushes branches into that remote. */
    private class Fixture(val repo: TestGitRepo, val bare: Path, val publisher: TestGitRepo) {

        fun head(): String = repo.git("rev-parse", "HEAD").trim()

        fun branchId(name: String): String? =
            repo.git("rev-parse", "--verify", "-q", "refs/heads/$name", check = false).trim().ifEmpty { null }

        /** Creates [branch] in the publisher on top of the remote's main and pushes it. */
        fun publish(branch: String, file: String = "src/Remote-${branch.replace('/', '-')}.kt"): String {
            publisher.git("switch", "-q", "-C", branch, "origin/main")
            publisher.write(file, "$branch\n")
            val id = publisher.commitAll("Remote $branch")
            publisher.git("push", "-q", "-u", "origin", branch)
            repo.git("fetch", "-q", "origin")
            return id
        }

        /** Creates local [branch] from main with one commit and goes back to main. */
        fun localBranch(name: String, file: String = "src/Pending.kt"): String {
            repo.git("switch", "-q", "-c", name, "main")
            repo.write(file, "$name\n")
            val id = repo.commitAll("Commit $name")
            repo.git("switch", "-q", "main")
            return id
        }
    }

    private fun fixture(): Fixture {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "base\n")
        repo.commitAll("Initial commit")
        val bare = repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        val publisher = TestGitRepo.create(temp, "web-dashboard")
        publisher.git("remote", "add", "origin", bare.toString())
        publisher.git("fetch", "-q", "origin")
        publisher.git("checkout", "-q", "-B", "main", "origin/main")
        return Fixture(repo, bare, publisher)
    }

    private fun readyList(fixture: Fixture): BranchList.Ready {
        val list = withService(fixture.repo) { service ->
            service.refreshNow()
            service.branches()
        }
        return assertInstanceOf(BranchList.Ready::class.java, list)
    }

    @Test
    fun `the list separates local and remote branches and sorts them`() {
        val fixture = fixture()
        fixture.publish("api-server")
        fixture.publish("feature/login")
        fixture.repo.git("branch", "zeta")
        fixture.repo.git("branch", "alpha")

        val list = readyList(fixture)

        assertEquals("main", list.current)
        assertEquals(fixture.head(), list.head)
        assertEquals(listOf("main", "alpha", "zeta"), list.local.map { it.name })
        assertTrue(list.local.first().current)
        assertFalse(list.local.drop(1).any { it.current })
        assertEquals(listOf("origin/api-server", "origin/feature/login", "origin/main"), list.remote.map { it.display })
        assertEquals("origin", list.remote.first().remote)
        assertEquals("api-server", list.remote.first().name)
        assertEquals("refs/remotes/origin/api-server", list.remote.first().ref)
        assertFalse(list.remote.any { it.name == "HEAD" })
    }

    @Test
    fun `a branch reports its upstream ahead behind and a gone upstream`() {
        val fixture = fixture()
        fixture.repo.write("src/Second.kt", "second\n")
        fixture.repo.commitAll("Second commit")
        fixture.repo.git("branch", "topic")
        fixture.repo.git("config", "branch.topic.remote", "origin")
        fixture.repo.git("config", "branch.topic.merge", "refs/heads/topic")

        val list = readyList(fixture)

        val main = list.local.first { it.name == "main" }
        assertEquals("origin/main", main.upstream)
        assertEquals(1, main.ahead)
        assertEquals(0, main.behind)
        assertFalse(main.upstreamGone)
        val topic = list.local.first { it.name == "topic" }
        assertEquals("origin/topic", topic.upstream)
        assertTrue(topic.upstreamGone)
    }

    @Test
    fun `a branch another worktree has checked out carries its path`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")
        val worktree = temp.toRealPath().resolve("api-worktree")
        fixture.repo.git("worktree", "add", "-q", worktree.toString(), "topic")

        val list = readyList(fixture)

        assertEquals(worktree, list.local.first { it.name == "topic" }.worktree)
        assertNull(list.local.first { it.name == "main" }.worktree)
    }

    @Test
    fun `a remote name containing a slash is one remote`() {
        val fixture = fixture()
        val other = temp.resolve("team-up.git")
        val init = TestGitRepo.run(
            listOf("git", "init", "-q", "--bare", "-b", "main", other.toString()),
            temp,
            fixture.repo.environment,
        )
        assertEquals(0, init.exitCode, init.stderr)
        fixture.repo.git("remote", "add", "team/up", other.toString())
        fixture.repo.git("push", "-q", "team/up", "main")
        fixture.repo.git("fetch", "-q", "team/up")

        val list = readyList(fixture)

        val row = list.remote.first { it.remote == "team/up" && it.name == "main" }
        assertEquals("team/up/main", row.display)
        assertFalse(list.remote.any { it.remote == "team" })
    }

    @Test
    fun `outside a repository the list is refused`() {
        val repo = TestGitRepo.create(temp)
        val plain = Files.createDirectories(temp.resolve("plain"))

        val list = withService(repo, root = plain) { it.branches() }

        assertEquals(BranchList.Refused("Not a Git repository: $plain"), list)
    }

    @Test
    fun `a local checkout switches and logs the command`() {
        val fixture = fixture()
        fixture.localBranch("topic")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.checkout(BranchTarget.Local("topic"))
            result to service.console.filter { it.write }.map { it.command }
        }

        assertTrue(result.ok, result.message)
        assertEquals("Checked out topic", result.message)
        assertEquals("refs/heads/topic", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertTrue(writes.contains("git switch --no-guess topic"), writes.toString())
    }

    @Test
    fun `a checkout that would overwrite local changes is stopped`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "topic")
        fixture.repo.write("src/Main.kt", "topic\n")
        fixture.repo.commitAll("Topic change")
        fixture.repo.git("switch", "-q", "main")
        fixture.repo.write("src/Main.kt", "dirty\n")
        val head = fixture.head()

        val result = withService(fixture.repo) { it.checkout(BranchTarget.Local("topic")) }

        assertFalse(result.ok)
        assertEquals("Checkout stopped: local changes to 1 files would be overwritten: commit them first", result.message)
        assertEquals(head, fixture.head())
        assertEquals("dirty\n", Files.readString(fixture.repo.root.resolve("src/Main.kt")))
    }

    @Test
    fun `a checkout that would overwrite an untracked file is stopped`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "topic")
        fixture.repo.write("src/New.kt", "new\n")
        fixture.repo.commitAll("Topic adds a file")
        fixture.repo.git("switch", "-q", "main")
        fixture.repo.write("src/New.kt", "mine\n")
        val head = fixture.head()

        val result = withService(fixture.repo) { it.checkout(BranchTarget.Local("topic")) }

        assertFalse(result.ok)
        assertEquals("Checkout stopped: 1 untracked files would be overwritten: move them first", result.message)
        assertEquals(head, fixture.head())
        assertEquals("mine\n", Files.readString(fixture.repo.root.resolve("src/New.kt")))
    }

    @Test
    fun `a checkout carries a change git can keep`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "topic")
        fixture.repo.write("src/Topic.kt", "topic\n")
        fixture.repo.commitAll("Topic adds a file")
        fixture.repo.git("switch", "-q", "main")
        fixture.repo.write("src/Main.kt", "carried\n")

        val result = withService(fixture.repo) { it.checkout(BranchTarget.Local("topic")) }

        assertTrue(result.ok, result.message)
        assertEquals("refs/heads/topic", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals("carried\n", Files.readString(fixture.repo.root.resolve("src/Main.kt")))
    }

    @Test
    fun `a checkout during a merge is refused before git runs`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")
        fixture.publish("remote-change", file = "src/Main.kt")
        fixture.publisher.git("push", "-q", "origin", "remote-change:main")
        fixture.repo.write("src/Main.kt", "local\n")
        fixture.repo.commitAll("Local change")
        fixture.repo.git("fetch", "-q", "origin")
        fixture.repo.git("merge", "origin/main", check = false)

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.checkout(BranchTarget.Local("topic"))
            result to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals("A merge is in progress: finish it in the terminal (⌥F12)", result.message)
        assertFalse(writes.any { it.startsWith("git switch") }, writes.toString())
    }

    @Test
    fun `a checkout from a detached HEAD with commits on no branch is refused`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")
        fixture.repo.git("checkout", "-q", "--detach")
        fixture.repo.write("src/Orphan.kt", "orphan\n")
        val orphan = fixture.repo.commitAll("Orphan work")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.checkout(BranchTarget.Local("topic"))
            result to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals("HEAD has 1 commit on no branch: create a branch from it first (New Branch)", result.message)
        assertEquals(orphan, fixture.head())
        assertTrue(writes.isEmpty(), writes.toString())
    }

    @Test
    fun `a checkout from a detached HEAD with no orphan commits switches`() {
        val fixture = fixture()
        fixture.localBranch("topic")
        fixture.repo.git("checkout", "-q", "--detach")

        val result = withService(fixture.repo) { it.checkout(BranchTarget.Local("topic")) }

        assertTrue(result.ok, result.message)
        assertEquals("Checked out topic", result.message)
    }

    @Test
    fun `a remote checkout creates a tracking branch`() {
        val fixture = fixture()
        val remoteId = fixture.publish("feature/login")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.checkout(BranchTarget.Remote("origin", "feature/login"))
            result to service.console.filter { it.write }.map { it.command }
        }

        assertTrue(result.ok, result.message)
        assertEquals("Checked out feature/login, tracking origin/feature/login", result.message)
        assertEquals("refs/heads/feature/login", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals(remoteId, fixture.branchId("feature/login"))
        assertEquals("origin", fixture.repo.git("config", "--get", "branch.feature/login.remote").trim())
        assertEquals("refs/heads/feature/login", fixture.repo.git("config", "--get", "branch.feature/login.merge").trim())
        assertTrue(
            writes.contains("git switch -c feature/login --track refs/remotes/origin/feature/login"),
            writes.toString(),
        )
    }

    @Test
    fun `a remote checkout of a branch already tracking it creates nothing`() {
        val fixture = fixture()
        fixture.publish("feature/login")
        fixture.repo.git("switch", "-q", "-c", "feature/login", "--track", "origin/feature/login")
        fixture.repo.git("switch", "-q", "main")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.checkout(BranchTarget.Remote("origin", "feature/login"))
            result to service.console.filter { it.write }.map { it.command }
        }

        assertTrue(result.ok, result.message)
        assertEquals("Checked out feature/login", result.message)
        assertTrue(writes.contains("git switch --no-guess feature/login"), writes.toString())
        assertFalse(writes.any { it.contains(" -c ") }, writes.toString())
    }

    @Test
    fun `a remote checkout refuses a local branch that tracks something else`() {
        val fixture = fixture()
        fixture.publish("feature/login")
        fixture.repo.git("branch", "feature/login", "main")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.checkout(BranchTarget.Remote("origin", "feature/login"))
            result to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals(
            "A local branch feature/login already exists and does not track origin/feature/login",
            result.message,
        )
        assertEquals("refs/heads/main", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertTrue(writes.isEmpty(), writes.toString())
    }

    @Test
    fun `a local branch deleted after the list is refused and the remote of the same name is not used`() {
        val fixture = fixture()
        fixture.publish("api-server")
        fixture.repo.git("branch", "api-server", "main")
        fixture.repo.git("branch", "-D", "api-server")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.checkout(BranchTarget.Local("api-server"))
            result to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals("Branch api-server no longer exists", result.message)
        assertNull(fixture.branchId("api-server"))
        assertTrue(writes.isEmpty(), writes.toString())
    }

    @Test
    fun `a post-checkout hook that fails still reports the switch`() {
        val fixture = fixture()
        fixture.localBranch("topic")
        val hook = fixture.repo.root.resolve(".git/hooks/post-checkout")
        Files.createDirectories(hook.parent)
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        hook.toFile().setExecutable(true)

        val result = withService(fixture.repo) { it.checkout(BranchTarget.Local("topic")) }

        assertTrue(result.ok, result.message)
        assertEquals(
            "Checked out topic; git reported an error after switching (see the Console)",
            result.message,
        )
        assertEquals("refs/heads/topic", fixture.repo.git("symbolic-ref", "HEAD").trim())
    }

    @Test
    fun `a checkout during another write reports busy`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)

        withService(fixture.repo) { service ->
            service.executor = GatedFetchExecutor(CliGitExecutor(TEST_GIT, fixture.repo.environment), entered, gate)
            coroutineScope {
                val running = launch(Dispatchers.IO) { service.fetch() }
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS), "the fetch did not start")
                    assertTrue(eventually { service.busy == "Fetching…" }, "the fetch did not report busy")
                    val result = service.checkout(BranchTarget.Local("topic"))
                    assertEquals("Git is busy: Fetching…", result.message)
                } finally {
                    gate.countDown()
                    running.join()
                }
            }
        }
    }

    @Test
    fun `a new branch from HEAD is created and checked out`() {
        val fixture = fixture()
        val base = fixture.head()

        val result = withService(fixture.repo) { it.createBranch("fix-login", null, checkout = true) }

        assertTrue(result.ok, result.message)
        assertEquals("Created and checked out fix-login", result.message)
        assertEquals("refs/heads/fix-login", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals(base, fixture.head())
    }

    @Test
    fun `a new branch from a remote ref starts there without an upstream`() {
        val fixture = fixture()
        val remoteId = fixture.publish("feature/login")

        val result = withService(fixture.repo) {
            it.createBranch("fix-login", BranchTarget.Remote("origin", "feature/login"), checkout = false)
        }

        assertTrue(result.ok, result.message)
        assertEquals("Created fix-login from origin/feature/login", result.message)
        assertEquals("refs/heads/main", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals(remoteId, fixture.branchId("fix-login"))
        assertEquals("", fixture.repo.git("config", "--get", "branch.fix-login.remote", check = false).trim())
    }

    @Test
    fun `an invalid expanded or existing name is refused before git runs`() {
        val fixture = fixture()

        val (results, writes) = withService(fixture.repo) { service ->
            val results = listOf(
                service.createBranch("a b", null, checkout = true),
                service.createBranch("@{-1}", null, checkout = true),
                service.createBranch("main", null, checkout = true),
            )
            results to service.console.filter { it.write }.map { it.command }
        }

        assertEquals("a b is not a valid branch name", results[0].message)
        assertEquals("@{-1} is not a valid branch name", results[1].message)
        assertEquals("A branch named main already exists", results[2].message)
        assertTrue(writes.isEmpty(), writes.toString())
    }

    @Test
    fun `a new branch before the first commit is refused`() {
        val repo = TestGitRepo.create(temp)

        val result = withService(repo) { it.createBranch("first", null, checkout = true) }

        assertEquals("Nothing to branch from: the branch has no commits yet", result.message)
        assertNull(repo.git("rev-parse", "--verify", "-q", "refs/heads/first", check = false).trim().ifEmpty { null })
    }

    @Test
    fun `a new branch checked out from another start stops on local changes`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "topic")
        fixture.repo.write("src/Main.kt", "topic\n")
        fixture.repo.commitAll("Topic change")
        fixture.repo.git("switch", "-q", "main")
        fixture.repo.write("src/Main.kt", "dirty\n")
        val head = fixture.head()

        val result = withService(fixture.repo) {
            it.createBranch("fix-login", BranchTarget.Local("topic"), checkout = true)
        }

        assertFalse(result.ok)
        assertEquals("Checkout stopped: local changes to 1 files would be overwritten: commit them first", result.message)
        assertNull(fixture.branchId("fix-login"))
        assertEquals(head, fixture.head())
        assertEquals("dirty\n", Files.readString(fixture.repo.root.resolve("src/Main.kt")))
    }

    @Test
    fun `a new branch checked out from another start moves the working tree to it`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "topic")
        fixture.repo.write("src/Topic.kt", "topic\n")
        val topicId = fixture.repo.commitAll("Topic adds a file")
        fixture.repo.git("switch", "-q", "main")

        val result = withService(fixture.repo) {
            it.createBranch("fix-login", BranchTarget.Local("topic"), checkout = true)
        }

        assertTrue(result.ok, result.message)
        assertEquals("Created and checked out fix-login", result.message)
        assertEquals("refs/heads/fix-login", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals(topicId, fixture.head())
        assertEquals("topic\n", Files.readString(fixture.repo.root.resolve("src/Topic.kt")))
        assertEquals("", fixture.repo.git("config", "--get", "branch.fix-login.remote", check = false).trim())
    }

    @Test
    fun `a failing post-checkout hook still reports a new branch checked out from HEAD`() {
        val fixture = fixture()
        val hook = fixture.repo.root.resolve(".git/hooks/post-checkout")
        Files.createDirectories(hook.parent)
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        hook.toFile().setExecutable(true)

        val result = withService(fixture.repo) { it.createBranch("fix-login", null, checkout = true) }

        assertTrue(result.ok, result.message)
        assertEquals(
            "Created and checked out fix-login; git reported an error after switching (see the Console)",
            result.message,
        )
        assertEquals("refs/heads/fix-login", fixture.repo.git("symbolic-ref", "HEAD").trim())
    }

    @Test
    fun `a failing post-checkout hook still reports a new branch checked out from another start`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "topic")
        fixture.repo.write("src/Topic.kt", "topic\n")
        val topicId = fixture.repo.commitAll("Topic adds a file")
        fixture.repo.git("switch", "-q", "main")
        val hook = fixture.repo.root.resolve(".git/hooks/post-checkout")
        Files.createDirectories(hook.parent)
        Files.writeString(hook, "#!/bin/sh\nexit 1\n")
        hook.toFile().setExecutable(true)

        val result = withService(fixture.repo) {
            it.createBranch("fix-login", BranchTarget.Local("topic"), checkout = true)
        }

        assertTrue(result.ok, result.message)
        assertEquals(
            "Created and checked out fix-login; git reported an error after switching (see the Console)",
            result.message,
        )
        assertEquals("refs/heads/fix-login", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals(topicId, fixture.head())
    }

    @Test
    fun `a new branch checked out from another start refuses a detached HEAD's orphan commits`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")
        fixture.repo.git("checkout", "-q", "--detach")
        fixture.repo.write("src/Orphan.kt", "orphan\n")
        val orphan = fixture.repo.commitAll("Orphan work")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.createBranch("fix-login", BranchTarget.Local("topic"), checkout = true)
            result to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals("HEAD has 1 commit on no branch: create a branch from it first (New Branch)", result.message)
        assertNull(fixture.branchId("fix-login"))
        assertEquals(orphan, fixture.head())
        assertTrue(writes.isEmpty(), writes.toString())
    }

    @Test
    fun `a new branch checked out from a detached HEAD keeps its commits`() {
        val fixture = fixture()
        fixture.repo.git("checkout", "-q", "--detach")
        fixture.repo.write("src/Orphan.kt", "orphan\n")
        val orphan = fixture.repo.commitAll("Orphan work")

        val result = withService(fixture.repo) { it.createBranch("fix-login", null, checkout = true) }

        assertTrue(result.ok, result.message)
        assertEquals("Created and checked out fix-login", result.message)
        assertEquals("refs/heads/fix-login", fixture.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals(orphan, fixture.head())
    }

    @Test
    fun `a rename keeps the upstream and says where it still pushes`() {
        val fixture = fixture()
        fixture.publish("feature")
        fixture.repo.git("switch", "-q", "-c", "feature", "--track", "origin/feature")
        fixture.repo.git("switch", "-q", "main")

        val result = withService(fixture.repo) { it.renameBranch("feature", "feature-2") }

        assertTrue(result.ok, result.message)
        assertEquals("Renamed feature to feature-2; it still pushes to origin/feature", result.message)
        assertEquals("origin", fixture.repo.git("config", "--get", "branch.feature-2.remote").trim())
        assertEquals("refs/heads/feature", fixture.repo.git("config", "--get", "branch.feature-2.merge").trim())
        assertNull(fixture.branchId("feature"))
    }

    @Test
    fun `renaming the current branch moves HEAD's name`() {
        val fixture = fixture()

        val (result, snapshot) = withService(fixture.repo) { service ->
            val result = service.renameBranch("main", "trunk")
            result to service.refreshNow()
        }

        assertTrue(result.ok, result.message)
        assertEquals("Renamed main to trunk; it still pushes to origin/main", result.message)
        assertEquals("trunk", snapshot.branch?.branch)
    }

    @Test
    fun `a rename onto an existing name is refused and never reaches -M`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")

        val (result, writes) = withService(fixture.repo) { service ->
            val result = service.renameBranch("main", "topic")
            result to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals("A branch named topic already exists", result.message)
        assertTrue(writes.isEmpty(), writes.toString())
        assertFalse(writes.any { it.startsWith("git branch -M") }, writes.toString())
        assertEquals("refs/heads/main", fixture.repo.git("symbolic-ref", "HEAD").trim())
    }

    @Test
    fun `a branch merged into HEAD is deleted at once`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")
        val id = fixture.branchId("topic")!!

        val (plan, result) = withService(fixture.repo) { service ->
            val plan = service.deletePlan("topic")
            plan to service.deleteBranch("topic", (plan as DeletePlan.Merged).id, force = false)
        }

        assertEquals(DeletePlan.Merged("topic", id), plan)
        assertTrue(result.ok, result.message)
        assertEquals("Deleted branch topic (was ${id.take(7)})", result.message)
        assertNull(fixture.branchId("topic"))
    }

    @Test
    fun `a branch merged into HEAD but not its upstream lists the commits outside the upstream`() {
        val fixture = fixture()
        fixture.publish("feature")
        fixture.repo.git("switch", "-q", "-c", "feature", "--track", "origin/feature")
        fixture.repo.write("src/Feature.kt", "feature\n")
        val local = fixture.repo.commitAll("Local feature")
        fixture.repo.git("switch", "-q", "main")
        fixture.repo.git("merge", "-q", "--ff-only", "feature")

        val plan = assertInstanceOf(
            DeletePlan.Unmerged::class.java,
            withService(fixture.repo) { it.deletePlan("feature") },
        )

        assertEquals("feature", plan.name)
        assertEquals(local, plan.id)
        assertEquals("origin/feature", plan.reference)
        assertEquals(listOf("Local feature"), plan.commits.map { it.subject })
        assertEquals(0, plan.moreCommits)
    }

    @Test
    fun `a gone upstream makes HEAD the reference`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "topic")
        fixture.repo.write("src/Topic.kt", "topic\n")
        fixture.repo.commitAll("Topic work")
        fixture.repo.git("switch", "-q", "main")
        fixture.repo.git("config", "branch.topic.remote", "origin")
        fixture.repo.git("config", "branch.topic.merge", "refs/heads/topic")

        val plan = assertInstanceOf(
            DeletePlan.Unmerged::class.java,
            withService(fixture.repo) { it.deletePlan("topic") },
        )

        assertEquals("HEAD", plan.reference)
        assertEquals(listOf("Topic work"), plan.commits.map { it.subject })
    }

    @Test
    fun `a branch merged into a local upstream is judged against it as git does`() {
        val fixture = fixture()
        val base = fixture.head()
        fixture.repo.git("switch", "-q", "-c", "b")
        fixture.repo.write("src/B.kt", "b\n")
        val id = fixture.repo.commitAll("Work on b")
        fixture.repo.git("switch", "-q", "main")
        fixture.repo.git("merge", "-q", "--ff-only", "b")
        fixture.repo.git("branch", "other", base)
        fixture.repo.git("switch", "-q", "other")
        fixture.repo.git("branch", "--set-upstream-to=main", "b")
        fixture.repo.git("branch", "raw", "b")
        fixture.repo.git("branch", "--set-upstream-to=main", "raw")
        val raw = TestGitRepo.run(
            listOf("git", "-C", fixture.repo.root.toString(), "branch", "-d", "raw"),
            fixture.repo.root,
            fixture.repo.environment,
        )

        assertEquals(0, raw.exitCode, raw.stderr)
        assertEquals("main", readyList(fixture).local.first { it.name == "b" }.upstream)
        val plan = withService(fixture.repo) { it.deletePlan("b") }
        assertEquals(DeletePlan.Merged("b", id), plan)
        val result = withService(fixture.repo) { service ->
            service.deleteBranch("b", (plan as DeletePlan.Merged).id, force = false)
        }
        assertTrue(result.ok, result.message)
        assertNull(fixture.branchId("b"))
    }

    @Test
    fun `the delete page's force deletes an unmerged branch`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "wip")
        fixture.repo.write("src/Wip.kt", "wip\n")
        val id = fixture.repo.commitAll("Wip work")
        fixture.repo.git("switch", "-q", "main")

        val result = withService(fixture.repo) { service ->
            val plan = assertInstanceOf(DeletePlan.Unmerged::class.java, service.deletePlan("wip"))
            service.deleteBranch("wip", plan.id, force = true)
        }

        assertTrue(result.ok, result.message)
        assertEquals("Deleted branch wip (was ${id.take(7)})", result.message)
        assertNull(fixture.branchId("wip"))
    }

    @Test
    fun `a branch that moved after the plan is not force-deleted`() {
        val fixture = fixture()
        fixture.repo.git("switch", "-q", "-c", "wip")
        fixture.repo.write("src/Wip.kt", "wip\n")
        fixture.repo.commitAll("Wip work")
        fixture.repo.git("switch", "-q", "main")

        val (result, stillThere) = withService(fixture.repo) { service ->
            val plan = assertInstanceOf(DeletePlan.Unmerged::class.java, service.deletePlan("wip"))
            fixture.repo.git("switch", "-q", "wip")
            fixture.repo.write("src/More.kt", "more\n")
            fixture.repo.commitAll("More work")
            fixture.repo.git("switch", "-q", "main")
            service.deleteBranch("wip", plan.id, force = true) to (fixture.branchId("wip") != null)
        }

        assertFalse(result.ok)
        assertEquals("Branch wip changed since you chose to delete it: delete it again", result.message)
        assertTrue(stillThere)
    }

    @Test
    fun `the current branch cannot be deleted`() {
        val fixture = fixture()
        val id = fixture.branchId("main")!!

        val result = withService(fixture.repo) { it.deleteBranch("main", id, force = false) }

        assertFalse(result.ok)
        assertEquals("Cannot delete the current branch main: check out another branch first", result.message)
        assertEquals(id, fixture.branchId("main"))
    }

    @Test
    fun `a branch another worktree has checked out cannot be deleted`() {
        val fixture = fixture()
        fixture.repo.git("branch", "topic")
        val id = fixture.branchId("topic")!!
        val worktree = temp.toRealPath().resolve("delete-worktree")
        fixture.repo.git("worktree", "add", "-q", worktree.toString(), "topic")

        val result = withService(fixture.repo) { it.deleteBranch("topic", id, force = false) }

        assertFalse(result.ok)
        assertEquals("topic is checked out in another worktree: $worktree", result.message)
        assertEquals(id, fixture.branchId("topic"))
    }

    @Test
    fun `a branch write bumps refsVersion`() {
        val fixture = fixture()

        val (before, after) = withService(fixture.repo) { service ->
            val before = service.refsVersion
            service.createBranch("fix-login", null, checkout = false)
            before to service.refsVersion
        }

        assertEquals(before + 1, after)
    }

    /** Holds the first `git fetch` until [gate] opens, so a write can be observed while it runs. */
    private class GatedFetchExecutor(
        private val delegate: GitExecutor,
        private val entered: CountDownLatch,
        private val gate: CountDownLatch,
    ) : GitExecutor {
        override fun run(command: GitCommand): GitResult {
            if (command.args.firstOrNull() == "fetch") {
                entered.countDown()
                gate.await()
            }
            return delegate.run(command)
        }
    }
}
