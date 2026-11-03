package stride.app.tree

import stride.core.vfs.VFile

/** Extension point name for [TreeStructureProvider] implementations. */
const val TREE_STRUCTURE_PROVIDER_EP: String = "stride.treeStructureProvider"

/** Rewrites the children a project tree shows for [parent]. */
interface TreeStructureProvider {
    fun modify(parent: VFile, children: List<VFile>): List<VFile>

    companion object {
        const val EP_NAME: String = TREE_STRUCTURE_PROVIDER_EP
    }
}
