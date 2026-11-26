package stride.app.run

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.actions.ActionGroups
import stride.app.actions.MenuRow
import stride.app.actions.resolveMenuRows
import stride.app.editor.TextFileEditor
import stride.app.keys.ActionId
import stride.app.keys.KeyContext
import stride.app.keys.MacKeymap
import stride.app.keys.dispatchAction
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile

class ContextRunTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var state: AppState
    private lateinit var root: Path
    private lateinit var session: ProjectSession

    @BeforeEach
    fun setUp() {
        state = AppState()
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
    }

    @Test
    fun `a test file with one class runs that class`() {
        openBuild(
            "app/src/test/kotlin/sample/orders/OrderServiceTest.kt" to singleTestFile(),
        )

        val run = requireNotNull(treeRun("app/src/test/kotlin/sample/orders/OrderServiceTest.kt"))

        assertEquals("OrderServiceTest", run.name)
        val configuration = requireNotNull(run.configuration())
        assertEquals(RunKind.TEST, configuration.kind)
        assertEquals(listOf(":app:test", "--tests", "sample.orders.OrderServiceTest"), configuration.args)
    }

    @Test
    fun `a test file with two classes runs both and takes the file name`() {
        openBuild(
            "app/src/test/kotlin/sample/orders/OrderServiceTest.kt" to twoClassTestFile(),
        )

        val run = requireNotNull(treeRun("app/src/test/kotlin/sample/orders/OrderServiceTest.kt"))

        assertEquals("Tests in 'OrderServiceTest.kt'", run.name)
        assertEquals(
            listOf(
                ":app:test",
                "--tests",
                "sample.orders.OrderServiceTest",
                "--tests",
                "sample.orders.InvoiceServiceTest",
            ),
            requireNotNull(run.configuration()).args,
        )
    }

    @Test
    fun `the project and source set directories run the whole test task`() {
        openBuild(
            "app/src/test/kotlin/sample/orders/OrderServiceTest.kt" to singleTestFile(),
        )

        for (path in listOf("app", "app/src", "app/src/test", "app/src/test/kotlin")) {
            val run = requireNotNull(treeRun(path)) { path }
            assertEquals("Tests in '${Path.of(path).fileName}'", run.name, path)
            assertEquals(listOf(":app:test"), requireNotNull(run.configuration()).args, path)
        }
    }

    @Test
    fun `a directory under the test source set runs the classes its package lines declare`() {
        openBuild(
            "app/src/test/kotlin/sample/billing/InvoiceTest.kt" to
                "package sample.orders\n\nclass InvoiceTest {\n    @Test\n    fun sendsInvoice() {\n    }\n}\n",
            "app/src/test/kotlin/sample/orders/OrderServiceTest.kt" to singleTestFile(),
        )

        val run = requireNotNull(treeRun("app/src/test/kotlin/sample"))

        assertEquals("Tests in 'sample'", run.name)
        assertEquals(
            listOf(
                ":app:test",
                "--tests",
                "sample.orders.InvoiceTest",
                "--tests",
                "sample.orders.OrderServiceTest",
            ),
            requireNotNull(run.configuration()).args,
        )
    }

    @Test
    fun `main sources, testless directories and a root without src-test run nothing`() {
        openBuild(
            "app/src/test/kotlin/sample/orders/OrderServiceTest.kt" to singleTestFile(),
            "app/src/main/kotlin/api/Orders.kt" to "package api\n",
        )
        Files.createDirectories(root.resolve("app/src/test/kotlin/sample/empty"))

        assertNull(treeRun("app/src/main/kotlin/api/Orders.kt"))
        assertNull(treeRun("app/src/main/kotlin"))
        assertNull(treeRun("app/src/test/kotlin/sample/empty"))
        assertNull(treeRun(""))

        session.tree.select(VFile(root.resolve("app/src/main/kotlin")))
        state.focus = KeyContext.PROJECT_TREE
        val rows = resolveMenuRows(MacKeymap.keymap, state, ActionGroups.projectViewPopupMenu)
        assertFalse(rows.any { it is MenuRow.Action && it.action == ActionId.RUN_CONTEXT })
        assertFalse(rows.first() is MenuRow.Separator)
        assertFalse(rows.last() is MenuRow.Separator)
        for (index in 1 until rows.size) {
            assertFalse(rows[index] is MenuRow.Separator && rows[index - 1] is MenuRow.Separator)
        }

        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }
        assertTrue(dispatchAction(ActionId.RUN_CONTEXT, state) { null })
        assertEquals("Nothing to run here", state.statusMessage)
        assertNull(manager.lastRun)
    }

    @Test
    fun `the editor keeps the caret test and the file main`() {
        val file = "app/src/test/kotlin/sample/orders/OrderServiceTest.kt"
        openBuild(
            file to singleTestFile(),
            "app/src/main/kotlin/sample/App.kt" to "package sample\n\nfun main() {\n}\n",
        )
        val text = singleTestFile()

        val testEditor = session.editors.openFile(VFile(root.resolve(file))) as TextFileEditor
        testEditor.updateValue(TextFieldValue(text, TextRange(text.indexOf("fun placesOrder"))))
        state.focus = KeyContext.EDITOR
        assertEquals(
            listOf(":app:test", "--tests", "sample.orders.OrderServiceTest.placesOrder"),
            requireNotNull(editorRunConfiguration(state)).args,
        )

        testEditor.updateValue(TextFieldValue(text, TextRange(text.indexOf("class OrderServiceTest"))))
        assertEquals(
            listOf(":app:test", "--tests", "sample.orders.OrderServiceTest"),
            requireNotNull(editorRunConfiguration(state)).args,
        )

        val mainText = "package sample\n\nfun main() {\n}\n"
        val mainEditor = session.editors.openFile(VFile(root.resolve("app/src/main/kotlin/sample/App.kt"))) as TextFileEditor
        mainEditor.updateValue(TextFieldValue(mainText, TextRange(mainText.indexOf("fun main"))))
        val main = requireNotNull(editorRunConfiguration(state))
        assertEquals(RunKind.APPLICATION, main.kind)
        assertEquals(":app:strideRunMain", main.args.first())
    }

    @Test
    fun `the run row shows the resolver name and starts the same configuration`() {
        openBuild(
            "app/src/test/kotlin/sample/orders/OrderServiceTest.kt" to singleTestFile(),
            "app/src/test/kotlin/sample/orders/InvoiceTest.kt" to
                "package sample.orders\n\nclass InvoiceTest {\n    @Test\n    fun sendsInvoice() {\n    }\n}\n",
        )
        session.tree.select(VFile(root.resolve("app/src/test/kotlin/sample/orders")))
        state.focus = KeyContext.PROJECT_TREE

        val run = requireNotNull(contextRun(state))
        val rows = resolveMenuRows(MacKeymap.keymap, state, ActionGroups.projectViewPopupMenu)
        val row = rows.filterIsInstance<MenuRow.Action>().first { it.action == ActionId.RUN_CONTEXT }
        assertEquals("Run '${run.name}'", row.text)
        assertTrue(row.enabled)

        val expected = requireNotNull(run.configuration())
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }
        assertTrue(dispatchAction(ActionId.RUN_CONTEXT, state) { null })
        assertEquals(expected.identity, requireNotNull(manager.lastRun).identity)
    }

    @Test
    fun `the same directory run twice stays one recent entry`() {
        openBuild(
            "app/src/test/kotlin/sample/orders/OrderServiceTest.kt" to singleTestFile(),
        )
        session.tree.select(VFile(root.resolve("app/src/test")))
        state.focus = KeyContext.PROJECT_TREE
        val manager = session.project.service<RunManager>()
        manager.starter = ProcessStarter { StartedProcess(FakeProcess(), "fake") }

        assertTrue(dispatchAction(ActionId.RUN_CONTEXT, state) { null })
        assertTrue(dispatchAction(ActionId.RUN_CONTEXT, state) { null })

        assertEquals(1, manager.recent.size)
        assertEquals("Tests in 'test'", manager.recent.single().name)
    }

    @Test
    fun `the test tree row uses the tree configuration's gradle path`() {
        val current = RunConfiguration(
            name = "build",
            workingDir = temp,
            args = listOf(":app:test", "--tests", "sample.orders.OrderServiceTest"),
            kind = RunKind.TEST,
        )

        val classRun = requireNotNull(testTreeConfiguration(current, "sample.orders.OrderServiceTest", null))
        assertEquals("sample.orders.OrderServiceTest", classRun.name)
        assertEquals(
            listOf(":app:test", "--tests", "sample.orders.OrderServiceTest"),
            classRun.args,
        )

        val methodRun = requireNotNull(
            testTreeConfiguration(current, "sample.orders.OrderServiceTest", "placesOrder"),
        )
        assertEquals("sample.orders.OrderServiceTest.placesOrder", methodRun.name)
        assertEquals(
            listOf(":app:test", "--tests", "sample.orders.OrderServiceTest.placesOrder"),
            methodRun.args,
        )

        val rootRun = requireNotNull(
            testTreeConfiguration(
                current.copy(args = listOf(":test", "--tests", "x.Y")),
                "sample.orders.OrderServiceTest",
                null,
            ),
        )
        assertEquals(listOf(":test", "--tests", "sample.orders.OrderServiceTest"), rootRun.args)

        assertNull(
            testTreeConfiguration(current.copy(args = listOf("classes", "testClasses")), "x.Y", null),
        )
    }

    private fun openBuild(vararg files: Pair<String, String>) {
        root = temp.resolve("build-project")
        Files.createDirectories(root)
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\ninclude(\":app\")\n")
        Files.writeString(root.resolve("build.gradle.kts"), "")
        writeAll(files)
        state.openFolder(root)
        session = requireNotNull(state.session)
    }

    private fun writeAll(files: Array<out Pair<String, String>>) {
        Files.createDirectories(root.resolve("app"))
        Files.writeString(root.resolve("app/build.gradle.kts"), "")
        for ((path, text) in files) {
            val file = root.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
    }

    private fun treeRun(path: String): ContextRun? {
        session.tree.select(VFile(if (path.isEmpty()) root else root.resolve(path)))
        state.focus = KeyContext.PROJECT_TREE
        return contextRun(state)
    }

    private fun singleTestFile(): String =
        "package sample.orders\n\nclass OrderServiceTest {\n    @Test\n    fun placesOrder() {\n    }\n}\n"

    private fun twoClassTestFile(): String =
        "package sample.orders\n\n" +
            "class OrderServiceTest {\n    @Test\n    fun placesOrder() {\n    }\n}\n\n" +
            "class InvoiceServiceTest {\n    @Test\n    fun sendsInvoice() {\n    }\n}\n"

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(ContextRunTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
