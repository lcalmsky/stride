package stride.app.generate

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.DeclarationIndex

class ProjectTypeAnswersTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var root: Path
    private lateinit var state: AppState
    private lateinit var session: ProjectSession

    @BeforeEach
    fun setUp() {
        Application.resetForTests()
        Application.init(ProjectTypeAnswersTest::class.java.classLoader)
        root = temp.resolve("generate-project")
        writeFixture(root)
        state = AppState()
        state.openFolder(root)
        session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000), "declaration index did not finish")
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
        Application.resetForTests()
    }

    @Test
    fun `a name in the same package resolves to its declaration text and name offset`() {
        val alpha = path("demo/Alpha.kt").toString()

        val types = requireNotNull(projectTypes(state))
        val found = types.resolve("Outer", alpha, 0)

        assertEquals(1, found.size)
        val source = found.single()
        assertEquals("demo.Outer", source.qualifiedName)
        assertEquals(path("demo/Outer.kt").toString(), source.path)
        assertTrue(source.text.startsWith("package demo"))
        assertEquals("Outer", source.text.substring(source.nameOffset, source.nameOffset + "Outer".length))
    }

    @Test
    fun `an explicit import resolves the imported class`() {
        val beta = path("other/Beta.kt").toString()

        val found = requireNotNull(projectTypes(state)).resolve("Alpha", beta, 0)

        assertEquals(listOf("demo.Alpha"), found.map { it.qualifiedName })
        assertEquals(path("demo/Alpha.kt").toString(), found.single().path)
    }

    @Test
    fun `a dotted name resolves the nested class with its outer names`() {
        val beta = path("other/Beta.kt").toString()

        val found = requireNotNull(projectTypes(state)).resolve("Outer.Inner", beta, 0)

        assertEquals(listOf("demo.Outer.Inner"), found.map { it.qualifiedName })
        val source = found.single()
        assertEquals("Inner", source.text.substring(source.nameOffset, source.nameOffset + "Inner".length))
    }

    @Test
    fun `a nested class resolves from inside its enclosing class`() {
        val holder = path("demo/Holder.kt")
        val text = Files.readString(holder)
        val offset = text.indexOf("fun value") + "fun ".length

        val found = requireNotNull(projectTypes(state)).resolve("Nested", holder.toString(), offset)

        assertEquals(listOf("demo.Holder.Nested"), found.map { it.qualifiedName })
        val source = found.single()
        assertEquals("Nested", source.text.substring(source.nameOffset, source.nameOffset + "Nested".length))
    }

    @Test
    fun `an open editor's unsaved class takes part and its offset matches the editor text`() {
        val alpha = path("demo/Alpha.kt")
        val editor = session.editors.openFile(VFile(alpha)) as TextFileEditor
        val unsaved = "package demo\n\nclass Alpha\n\nclass Fresh\n"
        editor.updateValue(TextFieldValue(unsaved, TextRange(0)))

        val found = requireNotNull(projectTypes(state)).resolve("Fresh", alpha.toString(), 0)

        assertEquals(listOf("demo.Fresh"), found.map { it.qualifiedName })
        val source = found.single()
        assertEquals(unsaved, source.text)
        assertEquals(unsaved.indexOf("Fresh"), source.nameOffset)
    }

    @Test
    fun `a name outside project sources resolves to nothing`() {
        val alpha = path("demo/Alpha.kt").toString()
        val types = requireNotNull(projectTypes(state))

        assertTrue(types.resolve("String", alpha, 0).isEmpty())
        assertTrue(types.resolve("Runnable", alpha, 0).isEmpty())
        assertTrue(types.resolve("Missing", alpha, 0).isEmpty())
    }

    @Test
    fun `a backticked name resolves and its name offset starts at the opening backtick`() {
        val use = path("demo/Use.kt").toString()
        val types = requireNotNull(projectTypes(state))

        val found = types.resolve("`A B`", use, 0)

        assertEquals(listOf("demo.A B"), found.map { it.qualifiedName })
        val source = found.single()
        assertEquals("`A B`", source.text.substring(source.nameOffset, source.nameOffset + "`A B`".length))
        assertEquals(found.map { it.path }, types.resolve("A B", use, 0).map { it.path })
    }

    @Test
    fun `top-level functions are answered for every file, private ones included`() {
        val types = requireNotNull(projectTypes(state))

        assertTrue(types.hasTopLevelFunction("greet"))
        assertTrue(types.hasTopLevelFunction("hidden"))
        assertFalse(types.hasTopLevelFunction("Greeting"))
    }

    @Test
    fun `an unfinished index gives no project types`() {
        val index = session.project.service<DeclarationIndex>()
        val field = DeclarationIndex::class.java.getDeclaredField("ready")
        field.isAccessible = true
        field.set(index, CountDownLatch(1))
        assertFalse(index.isReady)

        assertNull(projectTypes(state))
    }

    private fun path(relative: String): Path = root.resolve(relative)

    private fun writeFixture(root: Path) {
        fun write(relative: String, text: String) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        write("demo/Alpha.kt", "package demo\n\nclass Alpha\n")
        write("demo/Outer.kt", "package demo\n\nclass Outer {\n    class Inner\n}\n")
        write("demo/Holder.kt", "package demo\n\nclass Holder {\n    class Nested\n\n    fun value(): Int = 1\n}\n")
        write("demo/Spaced.kt", "package demo\n\nclass `A B`\n")
        write("demo/Use.kt", "package demo\n\nclass Use : `A B`\n")
        write(
            "other/Beta.kt",
            "package other\n\nimport demo.Alpha\nimport demo.Outer\n\nclass Beta\n",
        )
        write(
            "other/Declarations.kt",
            "package other\n\nfun greet() {}\n\nprivate fun hidden() {}\n\nclass Greeting\n",
        )
    }
}
