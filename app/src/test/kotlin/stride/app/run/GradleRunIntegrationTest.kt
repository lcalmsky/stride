package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Project
import stride.core.services.service
import java.nio.file.Files
import java.nio.file.Path

/** Runs one real child Gradle; excluded from the default test run through the `slow` tag. */
@Tag("slow")
class GradleRunIntegrationTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `runs a real Gradle test task and reports its events`() {
        assumeTrue(System.getProperty("stride.runIntegration") == "true", "needs -Dstride.runIntegration=true")
        val root = Path.of(requireNotNull(System.getProperty("stride.repoRoot")) { "stride.repoRoot is not set" })
        val project = Project(root)
        try {
            val manager = project.service<RunManager>()
            val args = GradleCommand.testArgs(":core", "stride.core.DisposerTest")

            var session = manager.run(configuration(root, args))
            var outcome = await(session, TIMEOUT_MILLIS)
            if (outcome != Outcome.DONE) {
                manager.stop()
                val cache = temp.resolve("gradle-project-cache")
                Files.createDirectories(cache)
                session = manager.run(
                    configuration(root, listOf("--project-cache-dir", cache.toString()) + args),
                )
                outcome = await(session, TIMEOUT_MILLIS)
            }

            assertEquals(Outcome.DONE, outcome, "child Gradle did not finish: ${tail(session)}")
            assertEquals(0, session.exitCode, "child Gradle failed: ${tail(session)}")

            val classNode = session.tree.classes.firstOrNull { it.className == "stride.core.DisposerTest" }
            assertNotNull(classNode, "classes: ${session.tree.classes.map { it.className }}")
            requireNotNull(classNode)
            assertTrue(classNode.methods.isNotEmpty(), "no test methods reported")
            assertTrue(
                classNode.methods.all { it.status == TestStatus.PASSED },
                classNode.methods.joinToString { "${it.name}=${it.status}" },
            )
            assertTrue(session.consoleLines.any { it.contains("BUILD SUCCESSFUL") }, tail(session))
        } finally {
            runCatching { project.service<RunManager>().stop() }
            project.dispose()
        }
    }

    @Test
    fun `runs a main through strideRunMain and reports its stdout`() {
        assumeTrue(System.getProperty("stride.runIntegration") == "true", "needs -Dstride.runIntegration=true")
        val root = Path.of(requireNotNull(System.getProperty("stride.repoRoot")) { "stride.repoRoot is not set" })
        val project = Project(root)
        try {
            val manager = project.service<RunManager>()
            val args = RunCommands.gradleMainArgs(":app", "stride.app.run.MainFixture", "test")
            val session = manager.run(
                RunConfiguration(
                    name = "MainFixture",
                    workingDir = root,
                    args = args,
                    kind = RunKind.APPLICATION,
                ),
            )

            val outcome = await(session, TIMEOUT_MILLIS)

            assertEquals(Outcome.DONE, outcome, "child Gradle did not finish: ${tail(session)}")
            assertEquals(0, session.exitCode, "child Gradle failed: ${tail(session)}")
            assertTrue(session.consoleLines.any { it.contains("stride-main-ok") }, tail(session))
            assertTrue(session.tree.classes.isEmpty(), "an application run must not report tests")
        } finally {
            runCatching { project.service<RunManager>().stop() }
            project.dispose()
        }
    }

    @Test
    fun `runs strideRunMain with the configuration cache enabled`() {
        assumeTrue(System.getProperty("stride.runIntegration") == "true", "needs -Dstride.runIntegration=true")
        val repo = Path.of(requireNotNull(System.getProperty("stride.repoRoot")) { "stride.repoRoot is not set" })
        val root = writeConfigurationCacheProject(repo, temp.resolve("cc-project"))
        val project = Project(root)
        try {
            val manager = project.service<RunManager>()
            val args = listOf("classes") +
                RunCommands.gradleMainArgs("", "demo.Main", "main") +
                listOf("--configuration-cache")
            val session = manager.run(
                RunConfiguration(
                    name = "Configuration cache",
                    workingDir = root,
                    args = args,
                    kind = RunKind.APPLICATION,
                ),
            )

            val outcome = await(session, TIMEOUT_MILLIS)

            assertEquals(Outcome.DONE, outcome, "child Gradle did not finish: ${tail(session)}")
            assertEquals(0, session.exitCode, "child Gradle failed: ${tail(session)}")
            assertTrue(session.consoleLines.any { it.contains("cc-main-ok") }, tail(session))
            assertTrue(session.consoleLines.any { it.contains("Configuration cache entry stored") }, tail(session))
            assertFalse(session.consoleLines.any { it.contains("Configuration cache problems") }, tail(session))
        } finally {
            runCatching { project.service<RunManager>().stop() }
            project.dispose()
        }
    }

    /** The launcher comes from the project dir, so the temp project delegates to the repo wrapper. */
    private fun writeConfigurationCacheProject(repo: Path, root: Path): Path {
        Files.createDirectories(root.resolve("src/main/java/demo"))
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'cc-project'\n")
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n")
        Files.writeString(
            root.resolve("src/main/java/demo/Main.java"),
            """
            package demo;

            public class Main {
                public static void main(String[] args) {
                    System.out.println("cc-main-ok");
                }
            }
            """.trimIndent() + "\n",
        )
        val gradlew = root.resolve("gradlew")
        Files.writeString(
            gradlew,
            "#!/bin/sh\nexec \"${repo.resolve("gradlew")}\" -p \"$root\" \"\$@\"\n",
        )
        assertTrue(gradlew.toFile().setExecutable(true), "cannot make the temp wrapper executable")
        return root
    }

    private fun configuration(root: Path, args: List<String>) = RunConfiguration(
        name = "core DisposerTest",
        workingDir = root,
        args = args,
        kind = RunKind.TEST,
    )

    private fun await(session: RunSession, timeoutMillis: Long): Outcome {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (session.isRunning && System.currentTimeMillis() < deadline) {
            if (session.consoleLines.any {
                    it.contains("Timeout waiting to lock") ||
                        it.contains("is currently in use by another Gradle instance")
                }
            ) {
                return Outcome.LOCKED
            }
            Thread.sleep(100)
        }
        return if (session.isRunning) Outcome.TIMEOUT else Outcome.DONE
    }

    private fun tail(session: RunSession): String = session.consoleLines.takeLast(40).joinToString("\n")

    private enum class Outcome { DONE, LOCKED, TIMEOUT }

    private companion object {
        const val TIMEOUT_MILLIS = 180_000L
    }
}
