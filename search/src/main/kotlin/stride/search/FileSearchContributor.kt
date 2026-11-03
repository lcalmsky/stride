package stride.search

import stride.core.services.Project
import stride.core.services.service
import stride.core.vfs.VFile

/** Creates a [FileSearchContributor] backed by the project's [FileNameIndex]. */
class FileSearchContributorFactory : SearchEverywhereContributorFactory<VFile> {
    override fun createContributor(project: Project): SearchEverywhereContributor<VFile> =
        FileSearchContributor(project.service<FileNameIndex>())
}

/**
 * Search Everywhere contributor backed by [FileNameIndex]. Returns files ranked by
 * [NameMatcher], at most [MAX_RESULTS] even when a caller asks for more.
 *
 * The contributor is UI-free: [process] performs no action and returns `false`, so the
 * caller opens the selected [VFile] itself.
 */
class FileSearchContributor(private val index: FileNameIndex) : SearchEverywhereContributor<VFile> {
    override val id: String get() = ID

    override val displayName: String get() = "Files"

    override val sortWeight: Int get() = 100

    override fun fetch(query: String, limit: Int): List<FoundItem<VFile>> {
        if (limit <= 0) return emptyList()
        return index.snapshot()
            .mapNotNull { indexed ->
                val score = NameMatcher.match(query, indexed.name, indexed.relativePath) ?: return@mapNotNull null
                FoundItem(indexed.file, score, indexed.name, indexed.relativePath)
            }
            .sortedWith(
                compareByDescending<FoundItem<VFile>> { it.score }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.presentationText }
                    .thenBy { it.presentationText }
            )
            .take(minOf(limit, MAX_RESULTS))
    }

    override fun process(item: VFile): Boolean = false

    companion object {
        const val ID: String = "stride.files"
        const val MAX_RESULTS: Int = 50
    }
}
