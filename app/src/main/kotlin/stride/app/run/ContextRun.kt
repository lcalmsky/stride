package stride.app.run

import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.files.fileOperationContext
import stride.app.files.fileOperationTarget
import stride.app.keys.KeyContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * A10: what the focused surface runs. [name] is the text of the `Run '<name>'` row; the filters
 * are built in [configuration] only when the row runs, so a directory's class scan stays out of
 * the menu open.
 */
class ContextRun(val name: String, private val create: () -> RunConfiguration?) {
    fun configuration(): RunConfiguration? = create()
}

/**
 * A10: `RUN_CONTEXT`'s one resolver. The row's visibility and text and the dispatcher all read
 * it: the project tree target under `PROJECT_TREE`, otherwise the selected editor.
 */
fun contextRun(state: AppState): ContextRun? {
    val session = state.session ?: return null
    return when (fileOperationContext(state)) {
        KeyContext.PROJECT_TREE -> treeRun(session, fileOperationTarget(state))
        else -> editorRunConfiguration(state)?.let { configuration ->
            ContextRun(configuration.name) { configuration }
        }
    }
}

/** A10 editor: the test at the caret when it is in a Gradle project, else the file's main. */
fun editorRunConfiguration(state: AppState): RunConfiguration? {
    val session = state.session ?: return null
    val editor = session.editors.selected as? TextFileEditor ?: return null
    caretTestConfiguration(state)?.let { return it }
    val main = MainLocator.locate(editor.value.text, editor.file.name)
    return mainConfiguration(session, editor.file.path, main)
}

/** A10: the tree positions a directory can hold inside its Gradle project. */
internal sealed interface TreeDirectory {
    /** The project directory, `src`, `src/test` or `src/test/<language>`: its whole test task. */
    data object SourceSetRoot : TreeDirectory

    /** A directory under `src/test/<language>/`: the test classes in the files below it. */
    data class Tests(val directory: Path, val language: String) : TreeDirectory
}

private val TEST_LANGUAGES = setOf("kotlin", "java")

/** A10: classifies [directory] inside [projectDir]; it reads no file. */
internal fun treeDirectory(projectDir: Path, directory: Path): TreeDirectory? {
    val relative = runCatching {
        projectDir.toAbsolutePath().normalize().relativize(directory.toAbsolutePath().normalize())
    }.getOrNull() ?: return null
    // The empty path relativize returns for the directory itself iterates one empty name.
    val parts = relative.map { it.toString() }.filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() || parts == listOf("src") || parts == listOf("src", "test") ->
            TreeDirectory.SourceSetRoot
        parts.size == 3 && parts[0] == "src" && parts[1] == "test" && parts[2] in TEST_LANGUAGES ->
            TreeDirectory.SourceSetRoot
        parts.size >= 4 && parts[0] == "src" && parts[1] == "test" && parts[2] in TEST_LANGUAGES ->
            TreeDirectory.Tests(directory.toAbsolutePath().normalize(), parts[2])
        else -> null
    }
}

/**
 * A10: a test tree row's run for [current], the tree's own configuration. Its `<path>:test`
 * argument tells the row's Gradle project; the name is the gutter run's filter text.
 */
internal fun testTreeConfiguration(
    current: RunConfiguration,
    className: String,
    methodName: String?,
): RunConfiguration? {
    val task = current.args.firstOrNull()?.takeIf { it.endsWith(":test") } ?: return null
    val gradlePath = task.removeSuffix(":test")
    val filter = methodName?.let { "$className.$it" } ?: className
    return RunConfiguration(
        name = filter,
        workingDir = current.workingDir,
        args = GradleCommand.testArgs(gradlePath, filter),
        kind = RunKind.TEST,
    )
}

private fun treeRun(session: ProjectSession, target: Path?): ContextRun? {
    if (target == null) return null
    return if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        directoryRun(session, target)
    } else {
        testFileRun(session, target)
    }
}

/** A10: a file in the `test` source set; one `--tests` per test class it declares. */
private fun testFileRun(session: ProjectSession, file: Path): ContextRun? {
    val root = session.project.root
    val projectDir = TestLocator.gradleProjectDir(file, root) ?: return null
    if (RunCommands.sourceSetIn(projectDir, file) != "test") return null
    val classes = testClassesIn(file)
    if (classes.isEmpty()) return null
    val gradlePath = TestLocator.gradleProjectPath(file, root) ?: return null
    val name = if (classes.size == 1) classes.single().substringAfterLast('.') else "Tests in '${file.fileName}'"
    return ContextRun(name) {
        RunConfiguration(name, root, GradleCommand.testArgs(gradlePath, classes), RunKind.TEST)
    }
}

private fun directoryRun(session: ProjectSession, directory: Path): ContextRun? {
    val root = session.project.root
    val projectDir = TestLocator.gradleProjectDirOf(directory, root) ?: return null
    val classification = treeDirectory(projectDir, directory) ?: return null
    if (!Files.isDirectory(projectDir.resolve("src/test"), LinkOption.NOFOLLOW_LINKS)) return null
    val gradlePath = TestLocator.gradleProjectPathOf(projectDir, root)
    val name = "Tests in '${directory.fileName}'"
    return when (classification) {
        TreeDirectory.SourceSetRoot -> ContextRun(name) {
            RunConfiguration(name, root, GradleCommand.testArgs(gradlePath), RunKind.TEST)
        }
        is TreeDirectory.Tests -> {
            if (!hasTestClass(classification.directory, classification.language)) return null
            ContextRun(name) {
                val classes = testClassesUnder(classification.directory, classification.language)
                if (classes.isEmpty()) {
                    null
                } else {
                    RunConfiguration(name, root, GradleCommand.testArgs(gradlePath, classes), RunKind.TEST)
                }
            }
        }
    }
}

private fun testClassesIn(file: Path): List<String> =
    runCatching { Files.readString(file) }.getOrNull()?.let(TestLocator::testClasses).orEmpty()

/** The test files under [directory], by path, so a run's filters do not depend on walk order. */
private fun testFiles(directory: Path, language: String): List<Path> {
    val extension = if (language == "java") "java" else "kt"
    return runCatching {
        Files.walk(directory).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".$extension") }
                .sorted()
                .toList()
        }
    }.getOrElse { emptyList() }
}

/** A10's cheap half: stops at the first file that declares a test class. */
private fun hasTestClass(directory: Path, language: String): Boolean =
    testFiles(directory, language).any { testClassesIn(it).isNotEmpty() }

private fun testClassesUnder(directory: Path, language: String): List<String> =
    testFiles(directory, language).flatMap { testClassesIn(it) }
