package stride.app.vcs

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import stride.core.services.Project
import stride.core.services.service

/** The git binary these tests run; [TestGitRepo] already needs one to create its repositories. */
internal val TEST_GIT: Path = requireNotNull(GitBinary.resolve()) { "the vcs tests need a git binary" }

/** Runs [block] against a project rooted at [root] with a [GitService] cut off from the user's git. */
internal fun <T> withService(
    repo: TestGitRepo,
    root: Path = repo.root,
    block: suspend (GitService) -> T,
): T {
    val project = Project(root)
    try {
        val service = project.service<GitService>()
        service.executor = CliGitExecutor(TEST_GIT, repo.environment)
        return runBlocking { block(service) }
    } finally {
        project.dispose()
    }
}

/** Polls [condition] until it holds or [timeoutMillis] passes; returns whether it held. */
internal fun eventually(timeoutMillis: Long = 5_000, condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
    while (System.nanoTime() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}
