package stride.search

/** Tabs of the Search Everywhere popup, in the order they appear in the tab row. */
enum class SearchTab(val title: String) {
    ALL("All"),
    CLASSES("Classes"),
    FILES("Files"),
    SYMBOLS("Symbols"),
    ACTIONS("Actions");

    fun next(): SearchTab = entries[(ordinal + 1) % entries.size]

    fun previous(): SearchTab = entries[(ordinal - 1 + entries.size) % entries.size]
}
