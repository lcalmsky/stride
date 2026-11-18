package stride.app.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitStatusParserTest {

    @Test
    fun `branch headers give the head, branch, upstream and counts`() {
        val parsed = GitStatusParser.parse(
            header("a".repeat(40), "main", "origin/main", 2, 3),
        )

        assertEquals("a".repeat(40), parsed.head)
        assertEquals("main", parsed.branch)
        assertEquals("origin/main", parsed.upstream)
        assertEquals(2, parsed.ahead)
        assertEquals(3, parsed.behind)
        assertEquals(emptyList<GitStatusParser.Record>(), parsed.records)
    }

    @Test
    fun `an initial head and a detached head are null`() {
        val initial = GitStatusParser.parse("# branch.oid (initial)\u0000# branch.head main\u0000")
        assertNull(initial.head)
        assertEquals("main", initial.branch)
        assertNull(initial.upstream)

        val detached = GitStatusParser.parse("# branch.oid abc\u0000# branch.head (detached)\u0000")
        assertEquals("abc", detached.head)
        assertNull(detached.branch)
    }

    @Test
    fun `ordinary records map their two letter code`() {
        val parsed = GitStatusParser.parse(
            header("abc", "main", null, 0, 0) +
                record("1", ".M", "worktree.txt") +
                record("1", "M.", "staged.txt") +
                record("1", "A.", "added.txt") +
                record("1", ".D", "deleted.txt") +
                record("1", "MM", "both.txt") +
                record("1", "T.", "type.txt"),
        )

        assertEquals(
            listOf(
                ChangeKind.MODIFIED to "worktree.txt",
                ChangeKind.MODIFIED to "staged.txt",
                ChangeKind.ADDED to "added.txt",
                ChangeKind.DELETED to "deleted.txt",
                ChangeKind.MODIFIED to "both.txt",
                ChangeKind.MODIFIED to "type.txt",
            ),
            parsed.records.map { it.kind to it.path },
        )
    }

    @Test
    fun `a rename keeps the original path and a copy is renamed too`() {
        val renamed = GitStatusParser.parse(
            "2 R. N... 100644 100644 100644 aaa bbb R100 new/path.txt\u0000old/path.txt\u0000",
        )
        assertEquals(ChangeKind.RENAMED, renamed.records.single().kind)
        assertEquals("new/path.txt", renamed.records.single().path)
        assertEquals("old/path.txt", renamed.records.single().originalPath)

        val copied = GitStatusParser.parse(
            "2 C. N... 100644 100644 100644 aaa bbb C75 copy.txt\u0000source.txt\u0000",
        )
        assertEquals(ChangeKind.RENAMED, copied.records.single().kind)
        assertEquals("source.txt", copied.records.single().originalPath)
    }

    @Test
    fun `an unmerged record is conflicted and an ignored record is skipped`() {
        val parsed = GitStatusParser.parse(
            record("u", "UU", "conflicted.txt") + "! ignored.txt\u0000",
        )

        assertEquals(listOf(ChangeKind.CONFLICTED to "conflicted.txt"), parsed.records.map { it.kind to it.path })
    }

    @Test
    fun `an untracked path keeps its spaces and subfolders`() {
        val parsed = GitStatusParser.parse("? dir/with space/note.txt\u0000")
        assertEquals("dir/with space/note.txt", parsed.records.single().path)
        assertEquals(ChangeKind.UNTRACKED, parsed.records.single().kind)
    }

    private fun header(head: String, branch: String, upstream: String?, ahead: Int, behind: Int): String = buildString {
        append("# branch.oid ").append(head).append('\u0000')
        append("# branch.head ").append(branch).append('\u0000')
        if (upstream != null) append("# branch.upstream ").append(upstream).append('\u0000')
        append("# branch.ab +").append(ahead).append(" -").append(behind).append('\u0000')
    }

    private fun record(type: String, xy: String, path: String): String {
        val fields = when (type) {
            "1" -> "1 $xy N... 100644 100644 100644 aaa bbb $path"
            "u" -> "u $xy N... 100644 100644 100644 100644 aaa bbb ccc $path"
            else -> "$type $path"
        }
        return fields + "\u0000"
    }
}
