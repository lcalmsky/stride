package stride.app.tree

import stride.core.vfs.VFile

/** Hides build and tooling directories, and a `.git` entry in any form, from a project view. */
class HiddenDirectoriesTreeStructureProvider : TreeStructureProvider {
    override fun modify(parent: VFile, children: List<VFile>): List<VFile> =
        children.filterNot { it.name in HIDDEN_ANY_TYPE || (it.isDirectory && it.name in HIDDEN_DIRECTORIES) }

    companion object {
        val HIDDEN_DIRECTORIES: Set<String> =
            setOf("build", ".gradle", "node_modules", ".kotlin", ".idea")

        // `.git` is a directory in a normal checkout but a gitdir pointer file in worktrees and submodules.
        val HIDDEN_ANY_TYPE: Set<String> = setOf(".git")
    }
}
