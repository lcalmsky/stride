package stride.app.vcs

/**
 * Parses `git status --porcelain=v2 -z --branch --untracked-files=all`. Paths are relative to the
 * repository root, as git prints them even when the command runs from a subdirectory.
 *
 * Index/worktree codes map to [ChangeKind] as follows, in this order: an unmerged record is
 * [ChangeKind.CONFLICTED]; `R` or `C` on either side is [ChangeKind.RENAMED] (git has no copy
 * kind, so a copy keeps its original path); `A` on either side is [ChangeKind.ADDED]; `D` on
 * either side is [ChangeKind.DELETED]; anything else, including `M` and `T`, is
 * [ChangeKind.MODIFIED]. `?` is [ChangeKind.UNTRACKED].
 */
internal object GitStatusParser {

    data class Record(val kind: ChangeKind, val path: String, val originalPath: String? = null)

    data class Parsed(
        val head: String?,
        val branch: String?,
        val upstream: String?,
        val ahead: Int,
        val behind: Int,
        val records: List<Record>,
    )

    fun parse(output: String): Parsed {
        var head: String? = null
        var branch: String? = null
        var upstream: String? = null
        var ahead = 0
        var behind = 0
        val records = ArrayList<Record>()
        val tokens = output.split('\u0000')
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            when {
                token.isEmpty() -> Unit
                token.startsWith(HEADER_OID) -> head = token.removePrefix(HEADER_OID).takeUnless { it == INITIAL }
                token.startsWith(HEADER_HEAD) -> branch = token.removePrefix(HEADER_HEAD).takeUnless { it == DETACHED }
                token.startsWith(HEADER_UPSTREAM) -> upstream = token.removePrefix(HEADER_UPSTREAM)
                token.startsWith(HEADER_AB) -> {
                    val parts = token.removePrefix(HEADER_AB).trim().split(' ')
                    ahead = parts.firstOrNull { it.startsWith('+') }?.drop(1)?.toIntOrNull() ?: 0
                    behind = parts.firstOrNull { it.startsWith('-') }?.drop(1)?.toIntOrNull() ?: 0
                }
                token.startsWith("1 ") -> {
                    val fields = token.split(' ', limit = 9)
                    if (fields.size == 9) records += Record(kindOf(fields[1]), fields[8])
                }
                token.startsWith("2 ") -> {
                    val fields = token.split(' ', limit = 10)
                    val original = tokens.getOrNull(index + 1)
                    if (fields.size == 10) {
                        records += Record(kindOf(fields[1]), fields[9], original)
                        index++
                    }
                }
                token.startsWith("u ") -> {
                    val fields = token.split(' ', limit = 11)
                    if (fields.size == 11) records += Record(ChangeKind.CONFLICTED, fields[10])
                }
                token.startsWith("? ") -> records += Record(ChangeKind.UNTRACKED, token.removePrefix("? "))
                token.startsWith("! ") -> Unit
            }
            index++
        }
        return Parsed(head, branch, upstream, ahead, behind, records)
    }

    private fun kindOf(xy: String): ChangeKind {
        val index = xy.getOrNull(0) ?: ' '
        val worktree = xy.getOrNull(1) ?: ' '
        return when {
            index == 'R' || worktree == 'R' || index == 'C' || worktree == 'C' -> ChangeKind.RENAMED
            index == 'A' || worktree == 'A' -> ChangeKind.ADDED
            index == 'D' || worktree == 'D' -> ChangeKind.DELETED
            else -> ChangeKind.MODIFIED
        }
    }

    private const val HEADER_OID = "# branch.oid "
    private const val HEADER_HEAD = "# branch.head "
    private const val HEADER_UPSTREAM = "# branch.upstream "
    private const val HEADER_AB = "# branch.ab "
    private const val INITIAL = "(initial)"
    private const val DETACHED = "(detached)"
}
