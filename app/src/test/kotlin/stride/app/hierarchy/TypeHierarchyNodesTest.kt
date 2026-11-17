package stride.app.hierarchy

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.vfs.VFile
import stride.search.Declaration
import stride.search.DeclarationParser
import stride.search.TypeGraph

class TypeHierarchyNodesTest {
    @TempDir
    lateinit var temp: Path

    private var projects = 0

    @Test
    fun `the root names the class with its path and opens at its name`() {
        val fixture = fixture(mapOf("Circle.kt" to classText("class Circle : Shape"), "Shape.kt" to shape()))

        val tree = TypeHierarchyNodes.tree(fixture.graph, fixture.find("Circle"), fixture.textOf)

        assertEquals("Type Hierarchy of Circle", tree.title)
        assertEquals("Types outside project sources are not expanded", tree.note)
        val root = tree.roots.single()
        assertEquals("Circle", root.text)
        assertEquals("Circle.kt:3", root.detail)
        assertEquals("Circle.kt", root.iconName)
        assertTrue(root.accent)
        assertTrue(root.expanded)
        assertEquals(fixture.textOf(fixture.pathOf("Circle.kt"))!!.indexOf("class Circle") + 6, root.location?.offset)
    }

    @Test
    fun `subtypes are direct and expand to further subtypes`() {
        val fixture = fixture(
            mapOf(
                "Shape.kt" to shape(),
                "Circle.kt" to classText("class Circle : Shape"),
                "Polygon.kt" to classText("abstract class Polygon : Shape"),
                "Solid.kt" to classText("interface Solid : Shape"),
                "Square.kt" to classText("class Square : Polygon()"),
            ),
        )

        val root = TypeHierarchyNodes.tree(fixture.graph, fixture.find("Shape"), fixture.textOf).roots.single()
        val bundles = load(root)
        assertEquals(listOf("Supertypes", "Subtypes"), bundles.map { it.text })
        assertTrue(bundles.all { it.expanded })

        assertEquals(emptyList<HierarchyNode>(), load(bundles[0]))
        val subtypes = load(bundles[1])
        assertEquals(listOf("Circle", "Polygon", "Solid"), subtypes.map { it.text })

        val polygon = subtypes.first { it.text == "Polygon" }
        assertEquals(listOf("Square"), load(load(polygon)[1]).map { it.text })
    }

    @Test
    fun `a supertype outside the project is a leaf with its written name`() {
        val fixture = fixture(
            mapOf(
                "Shape.kt" to shape(),
                "Ring.kt" to classText("class Ring : Shape, Serializable"),
            ),
        )

        val root = TypeHierarchyNodes.tree(fixture.graph, fixture.find("Ring"), fixture.textOf).roots.single()
        val supertypes = load(load(root)[0])

        assertEquals(listOf("Shape", "Serializable"), supertypes.map { it.text })
        assertEquals("Shape.kt:3", supertypes[0].detail)
        assertEquals("outside project sources", supertypes[1].detail)
        assertNull(supertypes[1].children)
    }

    @Test
    fun `a class already on the path is a recursive leaf`() {
        val fixture = fixture(mapOf("Loop.kt" to classText("class Loop : Loop")))

        val root = TypeHierarchyNodes.tree(fixture.graph, fixture.find("Loop"), fixture.textOf).roots.single()
        val supertypes = load(load(root)[0])

        assertEquals(listOf("Loop (recursive)"), supertypes.map { it.text })
        assertNull(supertypes.single().children)
        assertFalse(supertypes.single().expanded)
    }

    @Test
    fun `each level keeps 200 children and ends with a more leaf`() {
        val files = LinkedHashMap<String, String>()
        files["Shape.kt"] = shape()
        repeat(205) { files["T$it.kt"] = classText("class T$it : Shape") }
        val fixture = fixture(files)

        val root = TypeHierarchyNodes.tree(fixture.graph, fixture.find("Shape"), fixture.textOf).roots.single()
        val subtypes = load(load(root)[1])

        assertEquals(201, subtypes.size)
        assertEquals("… 5 more", subtypes.last().text)
    }

    private fun load(node: HierarchyNode): List<HierarchyNode> =
        runBlocking { node.children?.invoke().orEmpty() }

    private fun shape(): String =
        """
        package demo

        interface Shape {
            fun area(): Double
        }
        """.trimIndent() + "\n"

    private fun classText(header: String): String = "package demo\n\n$header\n"

    private inner class Fixture(files: Map<String, String>) {
        val root: Path
        val declarations: List<Declaration>
        val graph: TypeGraph
        val textOf: (Path) -> String?

        init {
            root = temp.resolve("type-project-${projects++}")
            Files.createDirectories(root)
            files.forEach { (relative, text) -> Files.writeString(root.resolve(relative), text) }
            declarations = files.flatMap { (relative, text) ->
                val file = VFile(root.resolve(relative))
                val packageName = DeclarationParser.packageOf(text)
                DeclarationParser.parse(relative.substringAfterLast('/'), text).map {
                    Declaration(
                        name = it.name,
                        kind = it.kind,
                        file = file,
                        relativePath = relative,
                        line = it.line,
                        container = it.container,
                        packageName = packageName,
                        isPrivate = it.isPrivate,
                        column = it.column,
                        supertypes = it.supertypes,
                        isInterface = it.isInterface,
                        isOverride = it.isOverride,
                    )
                }
            }
            graph = TypeGraph(declarations) { emptyList() }
            textOf = { path -> runCatching { Files.readString(path) }.getOrNull() }
        }

        fun find(name: String): Declaration = declarations.first { it.name == name }

        fun pathOf(relative: String): Path = root.resolve(relative)
    }

    private fun fixture(files: Map<String, String>): Fixture = Fixture(files)
}
