package stride.app.vcs

/**
 * The name page's live line: git's `check-ref-format --branch` rules in process. The service
 * checks the same name again with git inside the write lock, which stays the judge.
 */
object BranchNames {

    /** The problem [name] has among [existing] local branch names, or `null` when there is none. */
    fun problem(name: String, existing: Collection<String>): String? {
        if (name.isEmpty()) return "Enter a branch name"
        if (!isValid(name)) return "$name is not a valid branch name"
        if (name in existing) return "A branch named $name already exists"
        val other = existing.firstOrNull { it.startsWith("$name/") || name.startsWith("$it/") }
        return if (other == null) null else "$name conflicts with the branch $other"
    }

    private fun isValid(name: String): Boolean {
        if (name == "@" || name == "HEAD") return false
        if (name.startsWith("-") || name.startsWith("/") || name.endsWith("/") || name.endsWith(".")) return false
        if (name.contains("..") || name.contains("//") || name.contains("@{")) return false
        if (name.any { it == ' ' || it.code < 0x20 || it.code == 0x7f || it in FORBIDDEN }) return false
        return name.split('/').all { it.isNotEmpty() && !it.startsWith(".") && !it.endsWith(".lock") }
    }

    private const val FORBIDDEN = "~^:?*[\\"
}
