package stride.app.hierarchy

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.vfs.VFile
import stride.search.Declaration
import stride.search.DeclarationParser
import stride.search.IndexedFile
import stride.search.TypeGraph

class CallHierarchyNodesTest {
    @TempDir
    lateinit var temp: Path

    private var projects = 0

    @Test
    fun `callers group by member with the call count and the first call`() {
        val fixture = fixture(
            mapOf(
                "A.kt" to """
                    package demo

                    fun a() {
                        b()
                        b()
                    }

                    fun b() {}
                """.trimIndent() + "\n",
                "B.kt" to """
                    package demo

                    class Service {
                        fun run() {
                            b()
                        }
                    }
                """.trimIndent() + "\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, listOf(fixture.find("b")), fixture.files, fixture.textOf)

        assertEquals("Call Hierarchy of b", tree.title)
        assertEquals("Calls are matched by name and visibility", tree.note)
        val callers = load(load(tree.roots.single())[0])
        assertEquals(listOf("a", "Service.run"), callers.map { it.text })
        assertEquals("A.kt:3 (2 calls)", callers[0].detail)
        assertEquals("B.kt:4", callers[1].detail)
        assertEquals(fixture.textOf(fixture.pathOf("A.kt"))!!.indexOf("b()"), callers[0].location?.offset)
    }

    @Test
    fun `a caller already on the path is a recursive leaf`() {
        val fixture = fixture(
            mapOf(
                "Mutual.kt" to """
                    package demo

                    fun a() {
                        b()
                    }

                    fun b() {
                        a()
                    }
                """.trimIndent() + "\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, listOf(fixture.find("a")), fixture.files, fixture.textOf)
        val b = load(load(tree.roots.single())[0]).single()
        val backToA = load(b).single()

        assertEquals("a (recursive)", backToA.text)
        assertNull(backToA.children)
    }

    @Test
    fun `a call outside every member is a file row`() {
        val fixture = fixture(
            mapOf(
                "Target.kt" to "package demo\n\nfun target() {}\n",
                "Loose.kt" to "package demo\n\ntarget()\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, listOf(fixture.find("target")), fixture.files, fixture.textOf)
        val caller = load(load(tree.roots.single())[0]).single()

        assertEquals("Loose.kt", caller.text)
        assertEquals("Loose.kt:3", caller.detail)
        assertEquals(fixture.textOf(fixture.pathOf("Loose.kt"))!!.indexOf("target()"), caller.location?.offset)
        assertNull(caller.children)
    }

    @Test
    fun `callees open at the declaration and sort by path then offset`() {
        val fixture = fixture(
            mapOf(
                "A.kt" to """
                    package demo

                    fun helper() {
                        lowest()
                    }

                    fun lowest() {}

                    fun run() {
                        helper()
                        lowest()
                    }
                """.trimIndent() + "\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, listOf(fixture.find("run")), fixture.files, fixture.textOf)
        val bundles = load(tree.roots.single())
        assertEquals(listOf("Callers", "Callees"), bundles.map { it.text })
        assertTrue(bundles[0].expanded)
        assertEquals(false, bundles[1].expanded)

        val callees = load(bundles[1])
        assertEquals(listOf("helper", "lowest"), callees.map { it.text })
        val text = fixture.textOf(fixture.pathOf("A.kt"))!!
        assertEquals(text.indexOf("fun helper") + 4, callees[0].location?.offset)
        assertEquals("lowest", load(callees[0]).single().text)
    }

    @Test
    fun `a callee already on the path is a recursive leaf`() {
        val fixture = fixture(
            mapOf(
                "Mutual.kt" to """
                    package demo

                    fun a() {
                        b()
                    }

                    fun b() {
                        a()
                    }
                """.trimIndent() + "\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, listOf(fixture.find("a")), fixture.files, fixture.textOf)
        val b = load(load(tree.roots.single())[1]).single()
        val backToA = load(b).single()

        assertEquals("a (recursive)", backToA.text)
        assertNull(backToA.children)
    }

    @Test
    fun `each level keeps 200 children and ends with a more leaf`() {
        val calls = StringBuilder("package demo\n\nfun target() {}\n")
        repeat(205) { calls.append("\nfun c$it() {\n    target()\n}\n") }
        val fixture = fixture(mapOf("Many.kt" to calls.toString()))

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, listOf(fixture.find("target")), fixture.files, fixture.textOf)
        val callers = load(load(tree.roots.single())[0])

        assertEquals(201, callers.size)
        assertEquals("… 5 more", callers.last().text)
    }

    @Test
    fun `the note counts every declaration sharing the name`() {
        val fixture = fixture(
            mapOf(
                "A.kt" to "package demo.a\n\nfun target() {}\n",
                "B.kt" to "package demo.b\n\nfun target() {}\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, listOf(fixture.find("target")), fixture.files, fixture.textOf)

        assertEquals("Calls are matched by name and visibility; 2 declarations share this name", tree.note)
    }

    @Test
    fun `the note counts a class declared with the function's name`() {
        val fixture = fixture(
            mapOf(
                "A.kt" to "package demo.a\n\nfun target() {}\n",
                "B.kt" to "package demo.b\n\nclass target\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(
            fixture.graph,
            fixture.declarations,
            listOf(fixture.find("target")),
            fixture.files,
            fixture.textOf,
        )

        assertEquals("Calls are matched by name and visibility; 2 declarations share this name", tree.note)
    }

    @Test
    fun `several roots stay collapsed and the title counts them`() {
        val fixture = fixture(
            mapOf(
                "A.kt" to "package demo.a\n\nfun target() {}\n",
                "B.kt" to "package demo.b\n\nfun target() {}\n",
            ),
        )
        val roots = fixture.declarations.filter { it.name == "target" }

        val tree = CallHierarchyNodes.tree(fixture.graph, fixture.declarations, roots, fixture.files, fixture.textOf)

        assertEquals("Call Hierarchy of target (2 declarations)", tree.title)
        assertEquals(listOf("A.kt:3", "B.kt:3"), tree.roots.map { it.detail })
        assertTrue(tree.roots.none { it.expanded })
    }

    @Test
    fun `expanding deeper reuses the file texts`() {
        val fixture = fixture(
            mapOf(
                "A.kt" to """
                    package demo

                    fun a() {
                        b()
                    }

                    fun b() {}
                """.trimIndent() + "\n",
                "B.kt" to """
                    package demo

                    class Service {
                        fun run() {
                            a()
                        }
                    }
                """.trimIndent() + "\n",
            ),
        )

        val tree = CallHierarchyNodes.tree(
            fixture.graph,
            fixture.declarations,
            listOf(fixture.find("a")),
            fixture.files,
            fixture.textOf,
        )
        val callers = load(load(tree.roots.single())[0])
        assertTrue(callers.any { it.text == "Service.run" })
        assertTrue(fixture.reads.values.all { it == 1 }, "a file was read twice: ${fixture.reads}")

        fixture.reads.clear()
        load(callers.first { it.text == "Service.run" })

        assertEquals(emptyMap<Path, Int>(), fixture.reads)
    }

    private fun load(node: HierarchyNode): List<HierarchyNode> =
        runBlocking { node.children?.invoke().orEmpty() }

    private inner class Fixture(files: Map<String, String>) {
        val root: Path
        val declarations: List<Declaration>
        val graph: TypeGraph
        val indexed: List<IndexedFile>
        val reads = ConcurrentHashMap<Path, Int>()
        val textOf: (Path) -> String?

        init {
            root = temp.resolve("call-project-${projects++}")
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
            indexed = files.keys.map { IndexedFile(VFile(root.resolve(it)), it) }
            textOf = { path ->
                reads.merge(path, 1, Int::plus)
                runCatching { Files.readString(path) }.getOrNull()
            }
        }

        val files: List<IndexedFile> get() = indexed

        fun find(name: String): Declaration = declarations.first { it.name == name }

        fun pathOf(relative: String): Path = root.resolve(relative)
    }

    private fun fixture(files: Map<String, String>): Fixture = Fixture(files)
}
