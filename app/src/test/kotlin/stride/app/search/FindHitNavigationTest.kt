package stride.app.search

import androidx.compose.ui.text.TextRange
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.app.AppState
import stride.app.editor.TextFileEditor
import stride.core.services.Application
import stride.core.vfs.VFile

class FindHitNavigationTest {
    @TempDir
    lateinit var temp: Path

    private lateinit var state: AppState

    @BeforeEach
    fun setUp() {
        state = AppState()
    }

    @AfterEach
    fun tearDown() {
        state.dispose()
    }

    @Test
    fun `a hit whose text still matches selects the range and records one history entry`() {
        val root = Files.createDirectories(temp.resolve("project"))
        Files.writeString(root.resolve("A.kt"), "alpha\nbeta alpha\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val hit = hit(root.resolve("A.kt"), line = 2, start = 11, end = 16, matchText = "alpha")

        openFindHit(state, session, hit)

        val editor = session.editors.selected as TextFileEditor
        assertEquals(TextRange(11, 16), editor.value.selection)
        assertEquals(1, session.history.size)
    }

    @Test
    fun `a hit whose text changed goes to its line instead`() {
        val root = Files.createDirectories(temp.resolve("project"))
        Files.writeString(root.resolve("A.kt"), "alpha\nbeta alpha\n")
        state.openFolder(root)
        val session = requireNotNull(state.session)
        val hit = hit(root.resolve("A.kt"), line = 2, start = 0, end = 5, matchText = "omega")

        openFindHit(state, session, hit)

        val editor = session.editors.selected as TextFileEditor
        assertEquals(1, editor.value.text.take(editor.value.selection.start).count { it == '\n' })
        assertEquals(TextRange(6), editor.value.selection)
        assertEquals(1, session.history.size)
    }

    private fun hit(path: Path, line: Int, start: Int, end: Int, matchText: String) = FindInPathHit(
        file = VFile(path),
        relativePath = "A.kt",
        line = line,
        column = start + 1,
        lineText = "beta alpha",
        start = start,
        end = end,
        matchText = matchText,
    )

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(FindHitNavigationTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
