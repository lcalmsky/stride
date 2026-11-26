package stride.app

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.services.Application

class RecentProjectsTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `the list round-trips through an injected node`() {
        val node = Preferences.userRoot().node("stride-test-" + UUID.randomUUID())
        try {
            val store = RecentProjects(node)
            val first = temp.resolve("first")
            val second = temp.resolve("second")
            store.record(first)
            store.record(second)
            store.record(first)

            val expected = listOf(first, second).map { it.toAbsolutePath().normalize() }
            assertEquals(expected, store.entries())
            assertEquals(expected, RecentProjects(node).entries())
        } finally {
            runCatching { node.removeNode() }
        }
    }

    @Test
    fun `a path is normalised, a duplicate is one entry and a newline path is not stored`() {
        val store = RecentProjects(null)
        store.record(temp.resolve("a/../b"))
        store.record(temp.resolve("b"))
        assertEquals(listOf(temp.resolve("b").toAbsolutePath().normalize()), store.entries())

        store.record(temp.resolve("bad\nname"))
        assertEquals(1, store.entries().size)
    }

    @Test
    fun `at most twenty are kept, newest first`() {
        val store = RecentProjects(null)
        for (index in 0 until 25) store.record(temp.resolve("p$index"))

        val entries = store.entries()
        assertEquals(RecentProjects.MAX_ENTRIES, entries.size)
        assertEquals(temp.resolve("p24").toAbsolutePath().normalize(), entries.first())
        assertEquals(temp.resolve("p5").toAbsolutePath().normalize(), entries.last())
    }

    @Test
    fun `the oldest entries go when the stored value would be too long`() {
        val store = RecentProjects(null)
        val directory = "d".repeat(400)
        for (index in 0 until 25) store.record(temp.resolve(directory).resolve("p$index"))

        val entries = store.entries()
        assertTrue(entries.size < RecentProjects.MAX_ENTRIES)
        assertTrue(entries.joinToString("\n") { it.toString() }.length <= Preferences.MAX_VALUE_LENGTH)
        assertEquals(temp.resolve(directory).resolve("p24").toAbsolutePath().normalize(), entries.first())
    }

    @Test
    fun `the test JVM keeps recent projects in memory only`() {
        assertEquals("true", System.getProperty(RecentProjects.DISABLE_DEFAULT_STORE))
        assertNull(RecentProjects.defaultStore())
        val state = AppState()
        try {
            assertNull(state.recentProjects.node)
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `openFolder records the opened root and a failed open records nothing`() {
        val root = Files.createDirectories(temp.resolve("opened"))
        val state = AppState()
        try {
            state.recentProjects = RecentProjects(null)
            state.openFolder(root)
            val expected = listOf(root.toAbsolutePath().normalize())
            assertEquals(expected, state.recentProjects.entries())

            state.openSession = { throw IllegalStateException("open failed") }
            assertThrows(IllegalStateException::class.java) {
                state.openFolder(Files.createDirectories(temp.resolve("failed")))
            }
            assertEquals(expected, state.recentProjects.entries())
        } finally {
            state.dispose()
        }
    }

    @Test
    fun `rows leave out the open folder, name duplicates by their parent and drop a missing folder`() {
        val root = Files.createDirectories(temp.resolve("current"))
        val first = Files.createDirectories(temp.resolve("a/shared"))
        val second = Files.createDirectories(temp.resolve("b/shared"))
        val missing = temp.resolve("gone")
        val state = AppState()
        try {
            state.recentProjects = RecentProjects(null)
            state.openFolder(root)
            state.recentProjects.record(first)
            state.recentProjects.record(second)
            state.recentProjects.record(missing)

            val rows = recentProjectRows(state)
            assertEquals(
                listOf(missing.fileName.toString(), "shared", "shared"),
                rows.map { it.text.substringBefore(" (") },
            )
            assertTrue(rows[1].text.startsWith("shared ("))
            assertTrue(rows[2].text.startsWith("shared ("))
            assertFalse(rows[1].text == rows[2].text)
            assertFalse(rows.any { it.text.startsWith(root.fileName.toString()) })

            val gone = rows.first { it.text.startsWith(missing.fileName.toString()) }
            gone.run()
            assertEquals("Folder not found: $missing", state.statusMessage)
            assertFalse(state.recentProjects.entries().contains(missing.toAbsolutePath().normalize()))

            // Newest first: the second folder's row comes before the first one's.
            rows[1].run()
            assertNull(state.statusMessage)
            assertEquals(second.toAbsolutePath().normalize(), state.session?.project?.root?.toAbsolutePath()?.normalize())
        } finally {
            state.dispose()
        }
    }

    companion object {
        @BeforeAll
        @JvmStatic
        fun loadPlugins() {
            Application.resetForTests()
            Application.init(RecentProjectsTest::class.java.classLoader)
        }

        @AfterAll
        @JvmStatic
        fun resetPlugins() {
            Application.resetForTests()
        }
    }
}
