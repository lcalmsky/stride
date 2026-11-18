package stride.app.vcs

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GitUpdateTest {

    @TempDir
    lateinit var temp: Path

    private class Upstream(val repo: TestGitRepo, val bare: Path)

    /** A repository on branch `main` with `origin` as its upstream. */
    private fun upstream(): Upstream {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "base\n")
        repo.commitAll("Initial commit")
        val bare = repo.bareRemote()
        repo.git("push", "-u", "origin", "main")
        return Upstream(repo, bare)
    }

    private fun cloneOf(up: Upstream): Path {
        val clone = Files.createTempDirectory(temp, "web-dashboard")
        val result = TestGitRepo.run(
            listOf("git", "clone", up.bare.toString(), clone.toString()),
            temp,
            up.repo.environment,
        )
        assertEquals(0, result.exitCode, result.stderr)
        return clone
    }

    private fun gitAt(up: Upstream, dir: Path, vararg args: String): String {
        val result = TestGitRepo.run(listOf("git", "-C", dir.toString()) + args, dir, up.repo.environment)
        assertEquals(0, result.exitCode, result.stderr)
        return result.stdout
    }

    /** Commits [text] as [file] in [clone] and pushes it; returns the clone's new HEAD. */
    private fun commitIn(up: Upstream, clone: Path, file: String, text: String, message: String): String {
        val target = clone.resolve(file)
        target.parent.createDirectories()
        target.writeText(text)
        gitAt(up, clone, "add", "-A")
        gitAt(up, clone, "commit", "-q", "-m", message)
        gitAt(up, clone, "push")
        return gitAt(up, clone, "rev-parse", "HEAD").trim()
    }

    /** The local branch and the upstream both gained a commit on a different file. */
    private fun diverged(): Upstream {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/Remote.kt", "remote\n", "Remote commit")
        up.repo.write("src/Local.kt", "local\n")
        up.repo.commitAll("Local commit")
        return up
    }

    /** Both sides changed `src/Main.kt`, so a merge or rebase conflicts on it. */
    private fun conflicted(): Upstream {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/Main.kt", "remote\n", "Remote change")
        up.repo.write("src/Main.kt", "local\n")
        up.repo.commitAll("Local change")
        return up
    }

    @Test
    fun `a branch that is only behind fast-forwards to the upstream`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/One.kt", "one\n", "Remote one")
        val remoteHead = commitIn(up, clone, "src/Two.kt", "two\n", "Remote two")

        val (plan, result) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            ready to service.update(ready, UpdateStrategy.FAST_FORWARD)
        }

        assertEquals(listOf(UpdateStrategy.FAST_FORWARD), plan.strategies)
        assertEquals(UpdateStrategy.FAST_FORWARD, plan.defaultStrategy)
        assertEquals(0, plan.ahead)
        assertEquals(2, plan.incoming.size)
        assertEquals(listOf("Remote two", "Remote one"), plan.incoming.map { it.subject })
        assertEquals(0, plan.moreIncoming)
        assertTrue(result.ok, result.message)
        assertEquals("Updated main: 2 commits from origin/main (fast-forward)", result.message)
        assertEquals(remoteHead, up.repo.git("rev-parse", "HEAD").trim())
        assertEquals("one\n", up.repo.git("show", "HEAD:src/One.kt"))
    }

    @Test
    fun `nothing incoming is already up to date`() {
        val up = upstream()

        val plan = withService(up.repo) { it.updatePlan() }

        assertEquals(UpdatePlan.Refused("Already up to date"), plan)
    }

    @Test
    fun `a diverged branch offers merge and rebase and defaults from git config`() {
        val up = diverged()

        assertEquals(listOf(UpdateStrategy.MERGE, UpdateStrategy.REBASE), planOf(up).strategies)
        assertEquals(UpdateStrategy.MERGE, planOf(up).defaultStrategy)

        up.repo.git("config", "pull.rebase", "true")
        assertEquals(UpdateStrategy.REBASE, planOf(up).defaultStrategy)

        up.repo.git("config", "branch.main.rebase", "false")
        assertEquals(UpdateStrategy.MERGE, planOf(up).defaultStrategy)
    }

    private fun planOf(up: Upstream): UpdatePlan.Ready =
        withService(up.repo) { assertInstanceOf(UpdatePlan.Ready::class.java, it.updatePlan()) }

    private fun remoteHead(up: Upstream): String = gitAt(up, up.bare, "rev-parse", "main").trim()

    @Test
    fun `a merge keeps both histories with one merge commit`() {
        val up = diverged()
        val localHead = up.repo.git("rev-parse", "HEAD").trim()
        val remoteHead = remoteHead(up)

        val result = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.MERGE)
        }

        assertTrue(result.ok, result.message)
        assertEquals("Updated main: 1 commits from origin/main (merge)", result.message)
        val parents = up.repo.git("rev-list", "--parents", "-n", "1", "HEAD").trim().split(' ')
        assertEquals(3, parents.size)
        assertEquals(setOf(localHead, remoteHead), parents.drop(1).toSet())
    }

    @Test
    fun `a rebase puts the local commit on top of the upstream`() {
        val up = diverged()
        val localHead = up.repo.git("rev-parse", "HEAD").trim()
        val remoteHead = remoteHead(up)

        val result = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.REBASE)
        }

        assertTrue(result.ok, result.message)
        assertEquals("Updated main: 1 commits from origin/main (rebase)", result.message)
        val parents = up.repo.git("rev-list", "--parents", "-n", "1", "HEAD").trim().split(' ')
        assertEquals(2, parents.size)
        assertEquals(remoteHead, up.repo.git("rev-parse", "HEAD^").trim())
        assertEquals("Local commit", up.repo.git("log", "-1", "--format=%s").trim())
        assertTrue(up.repo.git("rev-parse", "HEAD").trim() != localHead)
    }

    @Test
    fun `a merge conflict is undone and the files come back`() {
        val up = conflicted()
        val head = up.repo.git("rev-parse", "HEAD").trim()
        val before = Files.readString(up.repo.root.resolve("src/Main.kt"))

        val (result, operation) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.MERGE) to service.operationInProgress()
        }

        assertFalse(result.ok)
        assertEquals(
            "Update had conflicts in 1 files and was undone: update from the terminal (⌥F12) to resolve them",
            result.message,
        )
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertFalse(Files.exists(up.repo.root.resolve(".git/MERGE_HEAD")))
        assertEquals(before, Files.readString(up.repo.root.resolve("src/Main.kt")))
        assertNull(operation)
        assertTrue(up.repo.git("status", "--porcelain").isBlank())
    }

    @Test
    fun `a rebase conflict is undone and the branch comes back`() {
        val up = conflicted()
        val head = up.repo.git("rev-parse", "HEAD").trim()
        val before = Files.readString(up.repo.root.resolve("src/Main.kt"))

        val result = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.REBASE)
        }

        assertFalse(result.ok)
        assertEquals(
            "Update had conflicts in 1 files and was undone: update from the terminal (⌥F12) to resolve them",
            result.message,
        )
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertEquals("refs/heads/main", up.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals(before, Files.readString(up.repo.root.resolve("src/Main.kt")))
        assertFalse(Files.exists(up.repo.root.resolve(".git/rebase-merge")))
        assertNull(withService(up.repo) { it.operationInProgress() })
    }

    @Test
    fun `a fast-forward that would overwrite local changes is stopped`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/Main.kt", "remote\n", "Remote change")
        up.repo.write("src/Main.kt", "local edit\n")
        val head = up.repo.git("rev-parse", "HEAD").trim()

        val (plan, result) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            ready to service.update(ready, UpdateStrategy.FAST_FORWARD)
        }

        assertEquals(listOf(UpdateStrategy.FAST_FORWARD), plan.strategies)
        assertFalse(result.ok)
        assertEquals("Update stopped: local changes to 1 files would be overwritten: commit them first", result.message)
        assertEquals("local edit\n", Files.readString(up.repo.root.resolve("src/Main.kt")))
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
    }

    @Test
    fun `a fast-forward over unrelated local changes keeps them`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/Remote.kt", "remote\n", "Remote change")
        up.repo.write("src/Main.kt", "local edit\n")

        val result = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.FAST_FORWARD)
        }

        assertTrue(result.ok, result.message)
        assertEquals("local edit\n", Files.readString(up.repo.root.resolve("src/Main.kt")))
        assertEquals("remote\n", Files.readString(up.repo.root.resolve("src/Remote.kt")))
    }

    @Test
    fun `a fast-forward that would overwrite an untracked file is stopped`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/New.kt", "remote\n", "Add file")
        up.repo.write("src/New.kt", "mine\n")
        val head = up.repo.git("rev-parse", "HEAD").trim()

        val result = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.FAST_FORWARD)
        }

        assertFalse(result.ok)
        assertEquals("Update stopped: 1 untracked files would be overwritten: move them first", result.message)
        assertEquals("mine\n", Files.readString(up.repo.root.resolve("src/New.kt")))
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
    }

    @Test
    fun `a diverged branch with changes needs a clean tree`() {
        val up = diverged()
        up.repo.write("src/Main.kt", "dirty\n")

        assertEquals(
            UpdatePlan.Refused("Update needs a clean working tree to merge or rebase: commit your 1 changed files first"),
            withService(up.repo) { it.updatePlan() },
        )

        up.repo.git("checkout", "--", "src/Main.kt")
        assertEquals(
            UpdatePlan.Refused("Update needs a clean working tree to merge or rebase: commit your 2 changed files first"),
            withService(up.repo) { it.updatePlan(unsavedTrackedEditors = 2) },
        )

        up.repo.write("notes.txt", "note\n")
        assertInstanceOf(UpdatePlan.Ready::class.java, withService(up.repo) { it.updatePlan() })
    }

    @Test
    fun `a merge after the plan refuses a tree that became dirty`() {
        val up = diverged()
        val head = up.repo.git("rev-parse", "HEAD").trim()

        val (result, writes) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            up.repo.write("src/Local.kt", "edited\n")
            service.update(ready, UpdateStrategy.MERGE) to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals(
            "Update needs a clean working tree to merge or rebase: commit your 1 changed files first",
            result.message,
        )
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertEquals("edited\n", Files.readString(up.repo.root.resolve("src/Local.kt")))
        assertFalse(writes.any { it.startsWith("git merge") }, writes.toString())
    }

    @Test
    fun `a rebase after the plan refuses a tree that became dirty`() {
        val up = diverged()
        val head = up.repo.git("rev-parse", "HEAD").trim()

        val (result, writes) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            up.repo.write("src/Local.kt", "edited\n")
            service.update(ready, UpdateStrategy.REBASE) to service.console.filter { it.write }.map { it.command }
        }

        assertFalse(result.ok)
        assertEquals(
            "Update needs a clean working tree to merge or rebase: commit your 1 changed files first",
            result.message,
        )
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertEquals("edited\n", Files.readString(up.repo.root.resolve("src/Local.kt")))
        assertFalse(writes.any { it.startsWith("git rebase") }, writes.toString())
    }

    @Test
    fun `a rebase that fails part-way is undone`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "src/Main.kt", "remote\n", "Remote change")
        up.repo.write("src/One.kt", "one\n")
        up.repo.commitAll("Local one")
        up.repo.write("src/Main.kt", "local\n")
        up.repo.commitAll("Local two")
        val head = up.repo.git("rev-parse", "HEAD").trim()

        val (result, operation) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.REBASE) to service.operationInProgress()
        }

        assertFalse(result.ok)
        assertEquals(
            "Update had conflicts in 1 files and was undone: update from the terminal (⌥F12) to resolve them",
            result.message,
        )
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertEquals("refs/heads/main", up.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals("local\n", Files.readString(up.repo.root.resolve("src/Main.kt")))
        assertFalse(Files.exists(up.repo.root.resolve(".git/rebase-merge")))
        assertNull(operation)
    }

    @Test
    fun `a rebase refused over an untracked file leaves the branch and the file alone`() {
        val up = upstream()
        val clone = cloneOf(up)
        commitIn(up, clone, "new.txt", "remote\n", "Remote adds new")
        up.repo.write("src/Local.kt", "local\n")
        up.repo.commitAll("Local commit")
        up.repo.write("new.txt", "mine\n")
        val head = up.repo.git("rev-parse", "HEAD").trim()

        val (result, operation) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.REBASE) to service.operationInProgress()
        }

        assertFalse(result.ok)
        assertEquals("Update stopped: 1 untracked files would be overwritten: move them first", result.message)
        assertNull(operation)
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertEquals("refs/heads/main", up.repo.git("symbolic-ref", "HEAD").trim())
        assertEquals("mine\n", Files.readString(up.repo.root.resolve("new.txt")))
        assertFalse(Files.exists(up.repo.root.resolve(".git/rebase-merge")))
    }

    @Test
    fun `autoStash in the repository does not leave a stash`() {
        val up = upstream()
        up.repo.git("config", "merge.autoStash", "true")
        val clone = cloneOf(up)
        commitIn(up, clone, "src/Remote.kt", "remote\n", "Remote change")
        up.repo.write("src/Main.kt", "local edit\n")

        val result = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.FAST_FORWARD)
        }

        assertTrue(result.ok, result.message)
        assertEquals("", up.repo.git("stash", "list"))
        assertEquals("local edit\n", Files.readString(up.repo.root.resolve("src/Main.kt")))
    }

    @Test
    fun `a detached head cannot be updated`() {
        val up = upstream()
        up.repo.git("checkout", "--detach")

        assertEquals(
            UpdatePlan.Refused("Update needs a branch: HEAD is detached"),
            withService(up.repo) { it.updatePlan() },
        )
    }

    @Test
    fun `a branch without an upstream cannot be updated`() {
        val repo = TestGitRepo.create(temp)
        repo.write("src/Main.kt", "base\n")
        repo.commitAll("Initial commit")
        repo.bareRemote()

        assertEquals(
            UpdatePlan.Refused("No upstream: set one in the terminal"),
            withService(repo) { it.updatePlan() },
        )
    }

    @Test
    fun `a missing upstream remote-tracking ref is refused after the fetch`() {
        val up = upstream()
        gitAt(up, up.bare, "config", "receive.denyDeleteCurrent", "ignore")
        up.repo.git("push", "origin", "--delete", "main")
        up.repo.git("update-ref", "-d", "refs/remotes/origin/main", check = false)

        assertEquals(
            UpdatePlan.Refused("The upstream origin/main does not exist: set one in the terminal"),
            withService(up.repo) { it.updatePlan() },
        )
    }

    @Test
    fun `an update and a commit are refused while a merge is in progress`() {
        val up = conflicted()
        up.repo.git("fetch", "origin")
        up.repo.git("merge", "origin/main", check = false)
        assertTrue(Files.exists(up.repo.root.resolve(".git/MERGE_HEAD")))

        val (plan, commit) = withService(up.repo) { service ->
            service.updatePlan() to service.commit(listOf(up.repo.root.resolve("src/Main.kt")), "Message")
        }

        assertEquals(UpdatePlan.Refused("A merge is in progress: finish it in the terminal (⌥F12)"), plan)
        assertEquals("A merge is in progress: finish it in the terminal (⌥F12)", commit.message)
    }

    @Test
    fun `an update is refused while a rebase is in progress`() {
        val up = conflicted()
        up.repo.git("fetch", "origin")
        up.repo.git("rebase", "origin/main", check = false)
        assertTrue(Files.exists(up.repo.root.resolve(".git/rebase-merge")))

        val plan = withService(up.repo) { it.updatePlan() }

        assertEquals(UpdatePlan.Refused("A rebase is in progress: finish it in the terminal (⌥F12)"), plan)
    }

    @Test
    fun `a failing pre-merge-commit hook is undone`() {
        val up = diverged()
        val hook = up.repo.root.resolve(".git/hooks/pre-merge-commit")
        Files.createDirectories(hook.parent)
        hook.writeText("#!/bin/sh\necho 'hook refused the merge' >&2\nexit 1\n")
        hook.toFile().setExecutable(true)
        val head = up.repo.git("rev-parse", "HEAD").trim()

        val (result, operation) = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.MERGE) to service.operationInProgress()
        }

        assertFalse(result.ok)
        assertEquals("Update failed: hook refused the merge (undone)", result.message)
        assertEquals(head, up.repo.git("rev-parse", "HEAD").trim())
        assertFalse(Files.exists(up.repo.root.resolve(".git/MERGE_HEAD")))
        assertNull(operation)
    }

    @Test
    fun `fetch merge and abort are write entries in the console`() {
        val up = conflicted()

        val writes = withService(up.repo) { service ->
            val ready = assertInstanceOf(UpdatePlan.Ready::class.java, service.updatePlan())
            service.update(ready, UpdateStrategy.MERGE)
            service.console.filter { it.write }.map { it.command }
        }

        assertTrue(writes.any { it.startsWith("git fetch ") }, writes.toString())
        assertTrue(writes.any { it.startsWith("git merge --no-edit") }, writes.toString())
        assertTrue(writes.any { it == "git merge --abort" }, writes.toString())
    }
}
