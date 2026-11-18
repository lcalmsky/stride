package stride.app.vcs

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GitBinaryTest {

    private val homebrew = Path.of("/opt/homebrew/bin/git")
    private val local = Path.of("/usr/local/bin/git")
    private val apple = Path.of("/usr/bin/git")

    @Test
    fun `the shell lookup wins over the fixed locations`() {
        val shell = Path.of("/custom/bin/git")
        val resolved = GitBinary.resolve(
            shellLookup = { shell },
            isExecutable = { true },
            xcodeSelectOk = { true },
        )

        assertEquals(shell, resolved)
    }

    @Test
    fun `the fixed order is homebrew, then usr local, then usr bin`() {
        assertEquals(
            homebrew,
            GitBinary.resolve({ null }, { it == homebrew || it == local || it == apple }, { true }),
        )
        assertEquals(
            local,
            GitBinary.resolve({ null }, { it == local || it == apple }, { true }),
        )
        assertEquals(
            apple,
            GitBinary.resolve({ null }, { it == apple }, { true }),
        )
    }

    @Test
    fun `usr bin git needs the command line tools from any source`() {
        assertNull(GitBinary.resolve({ null }, { it == apple }, { false }))
        assertNull(GitBinary.resolve({ apple }, { it == apple }, { false }))
        assertEquals(apple, GitBinary.resolve({ apple }, { it == apple }, { true }))
    }

    @Test
    fun `a shell lookup that is not executable is skipped`() {
        val shell = Path.of("/custom/bin/git")
        assertEquals(
            local,
            GitBinary.resolve({ shell }, { it == local }, { false }),
        )
    }

    @Test
    fun `no candidate means no git`() {
        assertNull(GitBinary.resolve({ null }, { false }, { true }))
    }
}
