package stride.app.navigation

import stride.search.IndexedFile

/** Maps a source file to its test (or a test file back to its subject) by file name. */
object TestNavigation {
    private val EXTENSIONS = listOf(".kt", ".java")

    fun baseName(fileName: String): String? =
        EXTENSIONS.firstOrNull { fileName.endsWith(it) }?.let { fileName.dropLast(it.length) }

    fun isTestName(base: String): Boolean =
        base.endsWith("Test") || base.endsWith("Tests") || base.startsWith("Test")

    /** Counterpart base names: `Foo` → `FooTest`, `FooTests`, `TestFoo`; a test → its subject. */
    fun counterpartNames(base: String): List<String> {
        if (!isTestName(base)) return listOf("${base}Test", "${base}Tests", "Test$base")
        return listOfNotNull(
            base.removeSuffix("Tests").takeIf { it != base && it.isNotEmpty() },
            base.removeSuffix("Test").takeIf { it != base && it.isNotEmpty() },
            base.removePrefix("Test").takeIf { it != base && it.isNotEmpty() },
        ).distinct()
    }

    /**
     * Files whose name is a counterpart of [fileName]; files in the same directory come first,
     * then the relative path and the name.
     */
    fun candidates(fileName: String, currentRelativePath: String?, files: List<IndexedFile>): List<IndexedFile> {
        val base = baseName(fileName) ?: return emptyList()
        val extension = EXTENSIONS.firstOrNull { fileName.endsWith(it) } ?: return emptyList()
        val targets = counterpartNames(base).map { it + extension }.toSet()
        val currentDirectory = currentRelativePath?.substringBeforeLast('/', "")
        return files
            .filter { it.name in targets }
            .sortedWith(
                compareBy<IndexedFile> { it.relativePath.substringBeforeLast('/', "") != currentDirectory }
                    .thenBy { it.relativePath },
            )
    }
}
