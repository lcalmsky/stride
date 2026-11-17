package stride.search

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.core.vfs.VFile
import java.nio.file.Paths

class TypeGraphPerformanceTest {
    @Test
    fun `a large project builds and answers callees within budget`() {
        val declarations = largeProject()
        val imports = starImports()

        val buildStart = System.nanoTime()
        val graph = TypeGraph(declarations) { file -> imports[file.path.toString()].orEmpty() }
        val buildMillis = (System.nanoTime() - buildStart) / 1_000_000

        val context = NameContext(rootFile("src/pkg0/File0.kt"), "pkg0", emptyList(), listOf("Class0"))
        val qualified = CallSite("a0", 0, 1, receiver = "?")
        val unqualified = CallSite("a0", 0, 1)
        val queryStart = System.nanoTime()
        repeat(QUERY_COUNT) { index ->
            graph.resolveCallee(if (index % 2 == 0) qualified else unqualified, context)
        }
        val queryMillis = (System.nanoTime() - queryStart) / 1_000_000

        assertTrue(buildMillis < BUILD_BUDGET_MILLIS, "TypeGraph construction took ${buildMillis}ms")
        assertTrue(queryMillis < QUERY_BUDGET_MILLIS, "$QUERY_COUNT resolveCallee calls took ${queryMillis}ms")
    }

    private fun largeProject(): List<Declaration> {
        val declarations = ArrayList<Declaration>(CLASS_COUNT + CLASS_COUNT * MEMBERS_PER_CLASS)
        for (index in 0 until CLASS_COUNT) {
            val packageName = "pkg${index / CLASSES_PER_PACKAGE}"
            val path = "src/pkg${index / CLASSES_PER_PACKAGE}/File${index / CLASSES_PER_FILE}.kt"
            val file = rootFile(path)
            declarations.add(
                Declaration(
                    "Class$index",
                    DeclarationKind.CLASS,
                    file,
                    path,
                    0,
                    packageName = packageName,
                    supertypes = supertypesOf(index),
                )
            )
            for (member in 0 until MEMBERS_PER_CLASS) {
                declarations.add(
                    Declaration(
                        "m${member}_$index",
                        DeclarationKind.SYMBOL,
                        file,
                        path,
                        member + 1,
                        container = "Class$index",
                        packageName = packageName,
                    )
                )
            }
        }
        return declarations
    }

    private fun supertypesOf(index: Int): List<String> =
        if (index == 0) {
            listOf("kotlin.Any")
        } else if (index % 3 == 0) {
            listOf("Class${index / 2}", "Class${index / 2 + 1}")
        } else {
            listOf("Class${index / 2}")
        }

    private fun starImports(): Map<String, List<SourceImport>> = buildMap {
        for (fileIndex in 0 until FILE_COUNT) {
            if (fileIndex % 4 != 0) continue
            val packageIndex = fileIndex / FILES_PER_PACKAGE
            put(
                "src/pkg$packageIndex/File$fileIndex.kt",
                listOf(SourceImport("pkg${(packageIndex + 1) % PACKAGE_COUNT}", isStar = true)),
            )
        }
    }

    private fun rootFile(path: String): VFile = VFile(Paths.get("/project").resolve(path))

    private companion object {
        const val CLASS_COUNT = 20_000
        const val FILE_COUNT = 2_000
        const val PACKAGE_COUNT = 200
        const val CLASSES_PER_FILE = CLASS_COUNT / FILE_COUNT
        const val FILES_PER_PACKAGE = FILE_COUNT / PACKAGE_COUNT
        const val CLASSES_PER_PACKAGE = CLASS_COUNT / PACKAGE_COUNT
        const val MEMBERS_PER_CLASS = 3
        const val QUERY_COUNT = 10_000
        const val BUILD_BUDGET_MILLIS = 1_500L
        const val QUERY_BUDGET_MILLIS = 1_000L
    }
}
