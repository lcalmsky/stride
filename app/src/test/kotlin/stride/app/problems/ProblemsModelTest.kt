package stride.app.problems

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionElement
import stride.core.extensions.ExtensionPointDescriptor
import stride.core.services.Project
import stride.core.services.service
import stride.lang.FileTypeBean
import stride.lang.FileTypeRegistry
import stride.lang.Language
import stride.lang.LanguageFileType
import stride.lang.Problem
import stride.lang.ProblemContext
import stride.lang.ProblemProvider
import stride.lang.ProblemProviderBean
import stride.lang.ProblemSeverity

class ProblemsModelTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var area: ExtensionArea

    @BeforeEach
    fun setUp() {
        area = ExtensionArea()
        area.registerExtensionPoint(
            ExtensionPointDescriptor(FileTypeRegistry.FILE_TYPE_EP, null, FileTypeBean::class.java.name, "test"),
        )
        area.registerExtensionPoint(
            ExtensionPointDescriptor(ProblemProvider.EP_NAME, null, ProblemProviderBean::class.java.name, "test"),
        )
        area.registerExtension(
            FileTypeRegistry.FILE_TYPE_EP,
            ExtensionElement(
                "test",
                mapOf(
                    "extension" to "lisp",
                    "language" to "lisp",
                    "implementationClass" to LispFileType::class.java.name,
                ),
                null,
            ),
        )
        area.registerExtension(
            ProblemProvider.EP_NAME,
            ExtensionElement(
                "test",
                mapOf("language" to "lisp", "implementationClass" to LispProvider::class.java.name),
                null,
            ),
        )
    }

    private fun file(name: String, text: String): Path {
        val path = temp.resolve(name)
        Files.writeString(path, text)
        return path.toAbsolutePath().normalize()
    }

    private fun withProject(block: (Project, ProblemsModel) -> Unit) {
        val project = Project(temp)
        try {
            block(project, project.service())
        } finally {
            project.dispose()
        }
    }

    @Test
    fun `publishing groups the messages per path and bumps the version once`() = withProject { _, model ->
        val first = file("First.kt", "first\n")
        val second = file("Second.kt", "second\n")

        model.publishBuild(
            listOf(
                CompilerMessage(first, 0, 1, ProblemSeverity.ERROR, "one"),
                CompilerMessage(first, 0, 2, ProblemSeverity.WARNING, "two"),
                CompilerMessage(second, 0, 0, ProblemSeverity.ERROR, "three"),
            ),
        )

        assertEquals(1, model.buildVersion)
        val byFile = model.projectErrors { false }.associate { it.path.fileName.toString() to it.errors }
        assertEquals(listOf("one"), byFile.getValue("First.kt").map { it.message })
        assertEquals(listOf("three"), byFile.getValue("Second.kt").map { it.message })
    }

    @Test
    fun `compute merges the editor source and the remapped build messages`() = withProject { _, model ->
        val path = file("Sample.lisp", "first\nsecond()\n")
        model.publishBuild(listOf(CompilerMessage(path, 1, 0, ProblemSeverity.ERROR, "second is not defined")))

        val problems = model.compute(path, "Sample.lisp", "first\nsecond()\n", area)

        assertEquals(2, problems.size)
        val editor = problems.first()
        assertEquals(ProblemSource.EDITOR, editor.source)
        assertEquals(0, editor.start)
        assertEquals(5, editor.end)
        assertEquals(0, editor.line)
        assertEquals(0, editor.column)
        val build = problems.last()
        assertEquals(ProblemSource.BUILD, build.source)
        assertEquals(6, build.start)
        assertEquals(12, build.end)
        assertEquals(1, build.line)
        assertEquals(0, build.column)
    }

    @Test
    fun `a javac message without a column covers the first non-blank run`() = withProject { _, model ->
        val text = "  indented line  \nrest\n"
        val path = file("Plain.java", text)
        model.publishBuild(listOf(CompilerMessage(path, 0, null, ProblemSeverity.ERROR, "bad line")))

        val problem = model.compute(path, "Plain.java", text, area).single()

        assertEquals(2, problem.start)
        assertEquals(15, problem.end)
        assertEquals(0, problem.line)
        assertEquals(2, problem.column)
    }

    @Test
    fun `the same start severity and message is kept once`() = withProject { _, model ->
        val text = "first\n"
        val path = file("Same.lisp", text)
        model.publishBuild(listOf(CompilerMessage(path, 0, 0, ProblemSeverity.ERROR, "closing bracket missing")))

        val problems = model.compute(path, "Same.lisp", text, area)

        assertEquals(1, problems.size)
        assertEquals(ProblemSource.EDITOR, problems.single().source)
    }

    @Test
    fun `a message on a duplicated line is hidden`() = withProject { _, model ->
        val base = "x\nb\nb\nz\n"
        val path = file("Dup.kt", base)
        model.publishBuild(listOf(CompilerMessage(path, 1, 0, ProblemSeverity.ERROR, "on b")))

        val moved = model.compute(path, "Dup.kt", "y\nb\nb\nw\n", area)

        assertTrue(moved.isEmpty())
    }

    @Test
    fun `an unreadable build file hides the message from the editor but not from Project Errors`() = withProject { _, model ->
        val missing = temp.resolve("Missing.kt").toAbsolutePath().normalize()
        model.publishBuild(listOf(CompilerMessage(missing, 2, 0, ProblemSeverity.ERROR, "gone")))

        assertTrue(model.compute(missing, "Missing.kt", "", area).isEmpty())
        assertEquals(2, model.projectErrors { false }.single().errors.single().line)
    }

    @Test
    fun `project errors prefer an open file's recorded errors`() = withProject { _, model ->
        val open = file("Open.kt", "open\n")
        val closed = file("Closed.kt", "closed\n")
        model.publishBuild(
            listOf(
                CompilerMessage(open, 0, 0, ProblemSeverity.ERROR, "open error"),
                CompilerMessage(closed, 0, 0, ProblemSeverity.ERROR, "closed error"),
            ),
        )

        val byFile = model.projectErrors { false }.associate { it.path.fileName.toString() to it.errors.first().message }
        assertEquals(mapOf("Open.kt" to "open error", "Closed.kt" to "closed error"), byFile)

        model.record(open, listOf(FileProblem(ProblemSeverity.WARNING, "only a warning", ProblemSource.EDITOR, 0, 0, 0, 1)))
        assertEquals(listOf("closed error"), model.projectErrors { it == open }.map { it.errors.first().message })

        val recorded = FileProblem(ProblemSeverity.ERROR, "recorded error", ProblemSource.EDITOR, 0, 3, 3, 7)
        model.record(open, listOf(recorded))
        val openErrors = model.projectErrors { it == open }.first { it.path == open }
        assertEquals(listOf(recorded), openErrors.errors)
    }

    @Test
    fun `publishing replaces the previous build and clears the records`() = withProject { _, model ->
        val path = file("Cleared.kt", "text\n")
        model.publishBuild(listOf(CompilerMessage(path, 0, 0, ProblemSeverity.ERROR, "old")))
        model.record(path, listOf(FileProblem(ProblemSeverity.ERROR, "record", ProblemSource.EDITOR, 0, 0, 0, 1)))

        model.publishBuild(listOf(CompilerMessage(path, 0, 0, ProblemSeverity.WARNING, "new")))

        assertEquals(2, model.buildVersion)
        assertTrue(model.projectErrors { true }.isEmpty())
        val computed = model.compute(path, "Cleared.kt", "text\n", area)
        assertEquals(listOf("new"), computed.map { it.message })
    }

    private class LispFileType : LanguageFileType(Language("lisp"))

    private class LispProvider : ProblemProvider {
        override fun collect(context: ProblemContext): List<Problem> {
            val message = if (context.text.startsWith("first")) "closing bracket missing" else "unclosed"
            return listOf(Problem(0, minOf(5, context.text.length), ProblemSeverity.ERROR, message))
        }
    }
}
