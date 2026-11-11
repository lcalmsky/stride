package stride.search

import stride.core.vfs.VFile

enum class DeclarationKind { CLASS, SYMBOL }

/** A declaration found by [DeclarationIndex]; [line] is 0-based. */
data class Declaration(
    val name: String,
    val kind: DeclarationKind,
    val file: VFile,
    val relativePath: String,
    val line: Int,
    val container: String? = null,
)
