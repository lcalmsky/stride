package stride.app.navigation

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.ProjectSession
import stride.app.editor.TextFileEditor
import stride.app.hierarchy.HierarchyRow
import stride.app.keys.KeyContext
import stride.core.services.Application
import stride.core.services.service
import stride.core.vfs.VFile
import stride.search.DeclarationIndex
import stride.search.FileNameIndex

class HierarchyActionsTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var root: Path
    private lateinit var state: AppState
    private lateinit var session: ProjectSession

    @BeforeEach
    fun setUp() {
        Application.resetForTests()
        Application.init(HierarchyActionsTest::class.java.classLoader)
        root = temp.resolve("hierarchy-project")
        writeFixture(root)
        state = AppState()
        state.openFolder(root)
        session = requireNotNull(state.session)
        assertTrue(session.project.service<DeclarationIndex>().awaitReady(5_000), "declaration index did not finish")
        assertTrue(session.project.service<FileNameIndex>().awaitReady(5_000), "file index did not finish")
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
        Application.resetForTests()
    }

    @Test
    fun `the actions need a selected text editor`() {
        val empty = AppState()
        try {
            assertFalse(goToImplementation(empty))
            assertFalse(goToTypeDeclaration(empty))
            assertFalse(goToSuperMethod(empty))
            assertFalse(showTypeHierarchy(empty))
            assertFalse(showCallHierarchy(empty))
        } finally {
            empty.dispose()
        }
    }

    @Test
    fun `an unfinished declaration index reports the indexing message`() {
        installUnfinishedIndex()
        openAt("demo/shapes/Circle.kt") { it.indexOf(": Shape") + 2 }

        assertTrue(goToImplementation(state))
        assertEquals(INDEXING_MESSAGE, state.statusMessage)
        assertTrue(goToTypeDeclaration(state))
        assertEquals(INDEXING_MESSAGE, state.statusMessage)
        assertTrue(goToSuperMethod(state))
        assertEquals(INDEXING_MESSAGE, state.statusMessage)
        assertTrue(showTypeHierarchy(state))
        assertEquals(INDEXING_MESSAGE, state.statusMessage)
        assertTrue(showCallHierarchy(state))
        assertEquals(INDEXING_MESSAGE, state.statusMessage)
    }

    @Test
    fun `actions run from the search popup context`() {
        openAt("demo/shapes/Square.kt") { it.indexOf("Polygon") }
        state.focus = KeyContext.SEARCH_POPUP

        assertTrue(goToImplementation(state))

        assertNull(state.statusMessage)
        assertEquals("Square.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `implementation of a class opens its only implementation`() {
        openAt("demo/shapes/Square.kt") { it.indexOf("Polygon") }

        assertTrue(goToImplementation(state))

        assertNull(state.statusMessage)
        assertNull(state.navigationTargets)
        assertEquals("Square.kt", session.editors.selected?.file?.name)
        assertEquals("Square", identifierAt(selectedText(), "Square.kt", selectedCaret()))
    }

    @Test
    fun `implementation chooser leaves out sub-interfaces and keeps abstract and java classes`() {
        openAt("demo/shapes/Shape.kt") { it.indexOf("interface Shape") + 10 }

        assertTrue(goToImplementation(state))

        val targets = requireNotNull(state.navigationTargets)
        assertEquals("Choose Implementation of Shape (4 found)", targets.title)
        assertEquals(listOf("Circle", "Polygon", "Square", "Triangle"), targets.targets.map { it.name })
    }

    @Test
    fun `implementation of a member lists the overriding members`() {
        openAt("demo/shapes/Shape.kt") { it.indexOf("fun area") + 4 }

        assertTrue(goToImplementation(state))

        val targets = requireNotNull(state.navigationTargets)
        assertEquals("Choose Implementation of area (3 found)", targets.title)
        assertEquals(listOf("Circle", "Square", "Triangle"), targets.targets.map { it.container })
    }

    @Test
    fun `implementation reports the absence in project sources`() {
        openAt("demo/shapes/Square.kt") { it.indexOf("class Square") + 6 }

        assertTrue(goToImplementation(state))

        assertEquals("No implementations of Square found in project sources", state.statusMessage)
        assertNull(state.navigationTargets)
    }

    @Test
    fun `implementation without an identifier says so`() {
        openAt("demo/shapes/Shape.kt") { it.indexOf("}") }

        assertTrue(goToImplementation(state))

        assertEquals("No identifier at the caret", state.statusMessage)
    }

    @Test
    fun `implementation still resolves after unsaved lines move the declaration`() {
        val editor = openAt("demo/shapes/Shape.kt") { it.indexOf("interface Shape") + 10 }
        val shifted = "\n\n\n" + editor.value.text
        editor.updateValue(TextFieldValue(shifted, TextRange(shifted.indexOf("interface Shape") + 10)))

        assertTrue(goToImplementation(state))

        val targets = requireNotNull(state.navigationTargets)
        assertEquals("Choose Implementation of Shape (4 found)", targets.title)
    }

    @Test
    fun `super method opens the overridden member`() {
        val editor = openAt("demo/shapes/Circle.kt") { it.indexOf("override fun area") + 13 }
        val caret = editor.value.selection.start

        assertTrue(goToSuperMethod(state))

        assertNull(state.statusMessage)
        assertEquals("Shape.kt", session.editors.selected?.file?.name)
        val shape = session.editors.selected as TextFileEditor
        assertEquals(shape.value.text.indexOf("area"), shape.value.selection.start)
        assertTrue(caret >= 0)
    }

    @Test
    fun `super method reports a kotlin member without override`() {
        openAt("demo/Main.kt") { it.indexOf("fun makeCircle") + 4 }

        assertTrue(goToSuperMethod(state))

        assertEquals("makeCircle does not override a method", state.statusMessage)
    }

    @Test
    fun `super method reports a super outside project sources`() {
        openAt("demo/shapes/Widget.kt") { it.indexOf("fun toString") + 4 }

        assertTrue(goToSuperMethod(state))

        assertEquals("No super method of toString in project sources", state.statusMessage)
    }

    @Test
    fun `super method of a twice-inherited member opens the chooser`() {
        openAt("demo/shapes/Multi.kt") { it.indexOf("override fun push") + 13 }

        assertTrue(goToSuperMethod(state))

        val targets = requireNotNull(state.navigationTargets)
        assertEquals("Choose Super Method of push", targets.title)
        assertEquals(listOf("Pushable", "Pullable"), targets.targets.map { it.container })
    }

    @Test
    fun `super method on a class name opens its single supertype`() {
        openAt("demo/shapes/Polygon.kt") { text -> text.indexOf("class Polygon") + 6 }

        assertTrue(goToSuperMethod(state))

        assertNull(state.statusMessage)
        assertEquals("Shape.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `super type chooser lists the two interfaces of a class`() {
        openAt("demo/shapes/Multi.kt") { it.indexOf("class Both") + 6 }

        assertTrue(goToSuperMethod(state))

        val targets = requireNotNull(state.navigationTargets)
        assertEquals("Choose Super Class of Both", targets.title)
        assertEquals(listOf("Pushable", "Pullable"), targets.targets.map { it.name })
    }

    @Test
    fun `supertypes outside project sources are reported`() {
        openAt("demo/shapes/Widget.kt") { it.indexOf("class Widget") + 6 }

        assertTrue(goToSuperMethod(state))

        assertEquals("No supertype of Widget in project sources", state.statusMessage)
    }

    @Test
    fun `super method outside a class and function says where to put the caret`() {
        openAt("demo/Main.kt") { it.indexOf("\n\nimport demo.shapes.Circle") + 1 }

        assertTrue(goToSuperMethod(state))

        assertEquals("Place the caret in a class or function", state.statusMessage)
    }

    @Test
    fun `type declaration follows an explicit type`() {
        openAt("demo/Main.kt") { it.indexOf("explicit") + 1 }

        assertTrue(goToTypeDeclaration(state))

        assertNull(state.statusMessage)
        assertEquals("Shape.kt", session.editors.selected?.file?.name)
        val editor = session.editors.selected as TextFileEditor
        assertEquals(editor.value.text.indexOf("interface Shape") + 10, editor.value.selection.start)
    }

    @Test
    fun `type declaration follows a local parameter type`() {
        openAt("demo/Main.kt") { it.indexOf("circle.area") + 1 }

        assertTrue(goToTypeDeclaration(state))

        assertNull(state.statusMessage)
        assertEquals("Circle.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `type declaration follows a constructor call`() {
        openAt("demo/Main.kt") { it.indexOf("constructed") + 1 }

        assertTrue(goToTypeDeclaration(state))

        assertNull(state.statusMessage)
        assertEquals("Circle.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `type declaration reports an initializer it cannot infer`() {
        openAt("demo/Main.kt") { it.indexOf("inferred") + 1 }

        assertTrue(goToTypeDeclaration(state))

        assertEquals(
            "Cannot infer the type of inferred without a compiler; declare it explicitly",
            state.statusMessage,
        )
    }

    @Test
    fun `type declaration reports a type outside project sources as written`() {
        openAt("demo/Main.kt") { it.indexOf("list:") + 1 }

        assertTrue(goToTypeDeclaration(state))

        assertEquals("List is not in project sources", state.statusMessage)
    }

    @Test
    fun `type declaration of a class name is the class itself`() {
        openAt("demo/Main.kt") { it.indexOf("Circle(1.0)") + 1 }

        assertTrue(goToTypeDeclaration(state))

        assertNull(state.statusMessage)
        assertEquals("Circle.kt", session.editors.selected?.file?.name)
    }

    @Test
    fun `type declaration chooser lists duplicate project types`() {
        openAt("demo/Main.kt") { it.indexOf("duplicated") + 1 }

        assertTrue(goToTypeDeclaration(state))

        val targets = requireNotNull(state.navigationTargets)
        assertEquals("Choose Type Declaration of duplicated", targets.title)
        assertEquals(2, targets.targets.size)
        assertTrue(targets.targets.all { it.name == "Duplicate" })
    }

    @Test
    fun `type declaration reports a name with no declaration`() {
        openAt("demo/Main.kt") { it.indexOf("missing") + 1 }

        assertTrue(goToTypeDeclaration(state))

        assertEquals("Cannot find the declaration of missing", state.statusMessage)
    }

    @Test
    fun `type hierarchy opens around a class name`() {
        openAt("demo/shapes/Shape.kt") { it.indexOf("interface Shape") + 10 }

        assertTrue(showTypeHierarchy(state))

        val tree = requireNotNull(state.hierarchyTree)
        assertEquals("Type Hierarchy of Shape", tree.title)
        assertEquals("Types outside project sources are not expanded", tree.note)
        assertEquals("Shape", tree.roots.single().text)
    }

    @Test
    fun `type hierarchy falls back to the innermost class`() {
        openAt("demo/shapes/Circle.kt") { it.indexOf("override fun area") + 5 }

        assertTrue(showTypeHierarchy(state))

        assertEquals("Type Hierarchy of Circle", requireNotNull(state.hierarchyTree).title)
    }

    @Test
    fun `type hierarchy outside a class says where to put the caret`() {
        openAt("demo/Main.kt") { it.indexOf("\n\nimport demo.shapes.Circle") + 1 }

        assertTrue(showTypeHierarchy(state))

        assertEquals("Place the caret in a class", state.statusMessage)
    }

    @Test
    fun `call hierarchy roots on a function declaration and lists its callers`() {
        openAt("demo/Main.kt") { it.indexOf("fun helper") + 4 }

        assertTrue(showCallHierarchy(state))

        val tree = requireNotNull(state.hierarchyTree)
        assertEquals("Call Hierarchy of helper", tree.title)
        assertTrue(requireNotNull(tree.note).contains("; 2 declarations share this name"))

        val rows = awaitRows { rows -> rows.any { it.node?.text == "Service.run" } }
        val texts = rows.mapNotNull { it.node?.text }
        assertTrue("run" in texts)
        assertTrue("Service.run" in texts)
        assertFalse("usesHelper" in texts)
        val run = rows.first { it.node?.text == "run" }.node
        assertTrue(requireNotNull(run?.detail).contains("(2 calls)"))
    }

    @Test
    fun `call hierarchy roots on the call at the caret`() {
        openAt("demo/Main.kt") { it.indexOf("helper()") + 1 }

        assertTrue(showCallHierarchy(state))

        assertEquals("Call Hierarchy of helper", requireNotNull(state.hierarchyTree).title)
    }

    @Test
    fun `call hierarchy falls back to the enclosing function`() {
        openAt("demo/shapes/Circle.kt") { it.indexOf("r * r") }

        assertTrue(showCallHierarchy(state))

        assertEquals("Call Hierarchy of area", requireNotNull(state.hierarchyTree).title)
    }

    @Test
    fun `call hierarchy outside a function says where to put the caret`() {
        openAt("demo/Main.kt") { it.indexOf("\n\nimport demo.shapes.Circle") + 1 }

        assertTrue(showCallHierarchy(state))

        assertEquals("Place the caret in a function", state.statusMessage)
    }

    @Test
    fun `call hierarchy callees open on demand`() {
        openAt("demo/Main.kt") { it.indexOf("fun helper") + 4 }
        assertTrue(showCallHierarchy(state))

        val rows = awaitRows { rows -> rows.any { it.node?.text == "Callers" } }
        val calleesIndex = rows.indexOfFirst { it.node?.text == "Callees" }
        assertTrue(calleesIndex >= 0)
        assertFalse(rows[calleesIndex].expanded)

        state.hierarchyToolWindow.toggle(calleesIndex)
        val loaded = awaitRows { rows -> rows.any { it.node?.text == "Circle.area" } }
        assertTrue(loaded.any { it.node?.text == "Circle.area" })
    }

    @Test
    fun `the chooser state clears when the session closes`() {
        openAt("demo/shapes/Shape.kt") { it.indexOf("interface Shape") + 10 }
        assertTrue(goToImplementation(state))
        assertNotNull(state.navigationTargets)

        state.closeSession()

        assertNull(state.navigationTargets)
    }

    private fun installUnfinishedIndex() {
        val index = session.project.service<DeclarationIndex>()
        val field = DeclarationIndex::class.java.getDeclaredField("ready")
        field.isAccessible = true
        field.set(index, CountDownLatch(1))
        assertFalse(index.isReady)
    }

    private fun selectedText(): String = (session.editors.selected as TextFileEditor).value.text

    private fun selectedCaret(): Int = (session.editors.selected as TextFileEditor).value.selection.start

    private fun openAt(relative: String, find: (String) -> Int): TextFileEditor {
        val editor = session.editors.openFile(VFile(root.resolve(relative))) as TextFileEditor
        val text = editor.value.text
        val offset = find(text)
        check(offset in 0..text.length) { "marker not found in $relative" }
        editor.updateValue(TextFieldValue(text, TextRange(offset)))
        state.focus = KeyContext.EDITOR
        state.statusMessage = null
        return editor
    }

    private fun awaitRows(predicate: (List<HierarchyRow>) -> Boolean): List<HierarchyRow> = runBlocking {
        withTimeout(5_000) {
            var rows = state.hierarchyToolWindow.rows
            while (!predicate(rows)) {
                delay(20)
                rows = state.hierarchyToolWindow.rows
            }
            rows
        }
    }

    private fun writeFixture(root: Path) {
        fun write(relative: String, text: String) {
            val file = root.resolve(relative)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        write(
            "demo/shapes/Shape.kt",
            """
            package demo.shapes

            interface Shape {
                fun area(): Double
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Circle.kt",
            """
            package demo.shapes

            class Circle(val r: Double) : Shape {
                override fun area() = r * r
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Polygon.kt",
            """
            package demo.shapes

            abstract class Polygon : Shape
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Square.kt",
            """
            package demo.shapes

            class Square : Polygon() {
                override fun area() = 1.0
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Solid.kt",
            """
            package demo.shapes

            interface Solid : Shape
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Triangle.java",
            """
            package demo.shapes;

            public class Triangle implements Shape {
                public double area() {
                    return 0;
                }
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Multi.kt",
            """
            package demo.shapes

            interface Pushable {
                fun push()
            }

            interface Pullable {
                fun push()
            }

            class Both : Pushable, Pullable {
                override fun push() {}
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/shapes/Widget.kt",
            """
            package demo.shapes

            class Widget {
                override fun toString(): String = "widget"
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/Main.kt",
            """
            package demo

            import demo.shapes.Circle
            import demo.shapes.Shape

            val explicit: Shape = Circle(1.0)
            val constructed = Circle(2.0)
            val inferred = makeCircle()
            val list: List<String> = emptyList()
            val duplicated: Duplicate = Duplicate()

            fun makeCircle(): Circle = Circle(0.0)

            fun takes(circle: Circle) {
                circle.area()
            }

            fun helper(): Double = Circle(1.0).area()

            fun run() {
                helper()
                helper()
            }

            fun readsInferred() {
                val value = inferred
                println(value)
                println(missing)
            }
            """.trimIndent() + "\n",
        )
        write("demo/DuplicateA.kt", "package demo\n\nclass Duplicate\n")
        write("demo/DuplicateB.kt", "package demo\n\nclass Duplicate\n")
        write(
            "demo/Service.kt",
            """
            package demo

            class Service {
                fun run() {
                    helper()
                }
            }
            """.trimIndent() + "\n",
        )
        write(
            "demo/other/Util.kt",
            """
            package demo.other

            fun helper(): Double = 0.0

            fun usesHelper() {
                helper()
            }
            """.trimIndent() + "\n",
        )
    }
}
