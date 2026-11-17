package stride.app.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import stride.core.vfs.VFile
import java.nio.file.Path

class NavigationBarStateTest {
    private val root = Path.of("/project")
    private val file = Path.of("/project/src/main/App.kt")
    private val app = VFile(Path.of("/project/src/main/App.kt"))
    private val util = VFile(Path.of("/project/src/main/Util.kt"))
    private val source = VFile(Path.of("/project/src/main"))

    @Test
    fun `the segments run from the project root to the file with the file selected`() {
        val bar = NavigationBarState.forFile(root, file)

        assertEquals(listOf("project", "src", "main", "App.kt"), bar.segments.map { it.name })
        assertEquals(listOf(true, true, true, false), bar.segments.map { it.isDirectory })
        assertEquals(3, bar.selected)
        assertEquals("App.kt", bar.segment.name)
    }

    @Test
    fun `no open file selects the root segment`() {
        val bar = NavigationBarState.forFile(root, null)

        assertEquals(listOf("project"), bar.segments.map { it.name })
        assertEquals(0, bar.selected)
    }

    @Test
    fun `left and right move between the segments and stop at the ends`() {
        val bar = NavigationBarState.forFile(root, file)

        assertEquals(2, bar.previous().selected)
        assertEquals(3, bar.next().selected)
        assertEquals(0, bar.previous().previous().previous().previous().selected)
        assertEquals(3, bar.next().next().selected)
    }

    @Test
    fun `a folder opens its children and the file opens none`() {
        val folder = NavigationBarState.forFile(root, file).copy(selected = 1)
        val onFile = NavigationBarState.forFile(root, file)

        val opened = folder.openList { listOf(app, util) }

        assertEquals(listOf(app, util), opened.list?.children)
        assertEquals(Path.of("/project/src"), opened.list?.folder)
        assertEquals(onFile, onFile.openList { listOf(app, util) })
    }

    @Test
    fun `the list moves by one and stops at the ends`() {
        val bar = NavigationBarState.forFile(root, file).copy(selected = 1).openList { listOf(app, util) }

        assertEquals(1, bar.moveInList(1).list?.selected)
        assertEquals(0, bar.moveInList(-1).list?.selected)
        assertEquals(1, bar.moveInList(5).list?.selected)
        assertEquals(util, bar.moveInList(1).selectedChild)
    }

    @Test
    fun `a folder in the list becomes the next segment and drops the deeper ones`() {
        val bar = NavigationBarState.forFile(root, file).copy(selected = 1)

        val descended = bar.descend(source) { listOf(app) }

        assertEquals(listOf("project", "src", "main"), descended.segments.map { it.name })
        assertEquals(2, descended.selected)
        assertEquals(Path.of("/project/src/main"), descended.list?.folder)
        assertEquals(listOf(app), descended.list?.children)
    }

    @Test
    fun `escaping the list closes only the list`() {
        val bar = NavigationBarState.forFile(root, file).copy(selected = 1).openList { listOf(app) }

        val closed = bar.closeList()

        assertNull(closed.list)
        assertEquals(bar.segments, closed.segments)
        assertEquals(1, closed.selected)
    }

    @Test
    fun `a file outside the project keeps one segment after the root`() {
        val outside = Path.of("/elsewhere/Other.kt")
        val bar = NavigationBarState.forFile(root, outside)

        assertEquals(listOf("project", "Other.kt"), bar.segments.map { it.name })
        assertEquals(listOf(true, false), bar.segments.map { it.isDirectory })
    }
}
