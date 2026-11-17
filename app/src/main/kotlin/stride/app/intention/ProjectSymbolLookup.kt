package stride.app.intention

import stride.lang.SymbolLookup
import stride.search.Declaration
import stride.search.DeclarationIndex

/** [SymbolLookup] over the project's [DeclarationIndex]; it never waits for the index scan. */
class ProjectSymbolLookup(private val index: DeclarationIndex) : SymbolLookup {
    private var source: List<Declaration>? = null
    private var byName: Map<String, List<String>> = emptyMap()

    override fun qualifiedNames(simpleName: String): List<String> = names()[simpleName].orEmpty()

    @Synchronized
    private fun names(): Map<String, List<String>> {
        val snapshot = index.snapshot()
        if (snapshot !== source) {
            source = snapshot
            byName = snapshot
                .filter { it.container == null && !it.packageName.isNullOrEmpty() && !it.isPrivate }
                .groupBy { it.name }
                .mapValues { (_, declarations) ->
                    declarations.mapNotNull { declaration ->
                        declaration.packageName?.let { "${it}.${declaration.name}" }
                    }.distinct().sorted()
                }
        }
        return byName
    }
}
