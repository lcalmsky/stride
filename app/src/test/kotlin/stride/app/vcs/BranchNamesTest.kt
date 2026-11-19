package stride.app.vcs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BranchNamesTest {

    @Test
    fun `an empty name asks for one`() {
        assertEquals("Enter a branch name", BranchNames.problem("", emptyList()))
    }

    @Test
    fun `ordinary names have no problem`() {
        val existing = listOf("develop", "release/1.0")
        for (name in listOf("main", "feature/login", "fix-1.2", "a.b", "head", "team/up", "release/2.0")) {
            assertNull(BranchNames.problem(name, existing), name)
        }
    }

    @Test
    fun `the characters git forbids are invalid`() {
        for (name in listOf("a b", "a\tb", "a\u0001b", "a~b", "a^b", "a:b", "a?b", "a*b", "a[b", "a\\b")) {
            assertEquals("$name is not a valid branch name", BranchNames.problem(name, emptyList()), name)
        }
    }

    @Test
    fun `the shapes git forbids are invalid`() {
        for (name in listOf("a..b", "a//b", "a@{b", "/a", "a/", "a.", ".a", "a/.b", "a.lock", "a.lock/b")) {
            assertEquals("$name is not a valid branch name", BranchNames.problem(name, emptyList()), name)
        }
    }

    @Test
    fun `git's reserved names are invalid`() {
        for (name in listOf("@", "HEAD")) {
            assertEquals("$name is not a valid branch name", BranchNames.problem(name, emptyList()), name)
        }
    }

    @Test
    fun `a name that starts with a dash is invalid`() {
        assertEquals("-x is not a valid branch name", BranchNames.problem("-x", emptyList()))
    }

    @Test
    fun `an existing name is refused`() {
        assertEquals("A branch named main already exists", BranchNames.problem("main", listOf("main", "topic")))
    }

    @Test
    fun `a name that is a folder of an existing branch conflicts both ways`() {
        assertEquals("a/b conflicts with the branch a", BranchNames.problem("a/b", listOf("a")))
        assertEquals("a conflicts with the branch a/b", BranchNames.problem("a", listOf("a/b")))
    }

    @Test
    fun `a sibling under the same folder has no conflict`() {
        assertNull(BranchNames.problem("a/c", listOf("a/b")))
    }

    @Test
    fun `an exact name is reported as existing before a folder conflict`() {
        assertEquals("A branch named a already exists", BranchNames.problem("a", listOf("a", "a/b")))
    }
}
