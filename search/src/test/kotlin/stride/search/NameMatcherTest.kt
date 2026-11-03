package stride.search

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NameMatcherTest {
    private fun score(query: String, name: String, path: String = name): Int? =
        NameMatcher.match(query, name, path)

    @Test
    fun `matches camel humps`() {
        assertNotNull(score("FEM", "FileEditorManager.kt", "core/FileEditorManager.kt"))
        assertNotNull(score("fiEdMa", "FileEditorManager.kt", "core/FileEditorManager.kt"))
    }

    @Test
    fun `matches case-insensitive substrings`() {
        assertNotNull(score("EDITOR", "FileEditorManager.kt"))
        assertNotNull(score("itor", "FileEditorManager.kt"))
    }

    @Test
    fun `matches word starts separated by underscore dash and dot`() {
        assertNotNull(score("fbn", "foo-bar-name.kt"))
        assertNotNull(score("fbn", "foo_bar_name.kt"))
        assertNotNull(score("fbn", "foo.bar.name.kt"))
    }

    @Test
    fun `matches path segments in order`() {
        val path = "core/src/main/kotlin/stride/core/extensions/Extensions.kt"
        assertNotNull(score("core/ext", "Extensions.kt", path))
        assertNull(score("core/ext", "Extensions.kt", "core/src/main/kotlin/stride/App.kt"))
        assertNull(score("ext/core", "Extensions.kt", path))
    }

    @Test
    fun `ranks exact over prefix over word starts over substring over path`() {
        val exact = score("App", "App.kt")!!
        val prefix = score("App", "Application.kt")!!
        val wordStart = score("App", "MyApp.kt")!!
        val substring = score("App", "Myapp.kt")!!
        val path = score("core/App", "App.kt", "core/App.kt")!!

        assertTrue(exact > prefix, "exact $exact should beat prefix $prefix")
        assertTrue(prefix > wordStart, "prefix $prefix should beat word start $wordStart")
        assertTrue(wordStart > substring, "word start $wordStart should beat substring $substring")
        assertTrue(substring > path, "substring $substring should beat path only $path")
    }

    @Test
    fun `prefers shorter names within a tier`() {
        val shorter = score("Ap", "App.kt")!!
        val longer = score("Ap", "Application.kt")!!
        assertTrue(shorter > longer, "shorter $shorter should beat longer $longer")
    }

    @Test
    fun `blank query matches everything`() {
        assertNotNull(score("", "Anything.kt"))
        assertNotNull(score("   ", "Anything.kt"))
    }

    @Test
    fun `returns no match when characters or path segments are missing`() {
        assertNull(score("zzz", "File.kt"))
        assertNull(score("FEM", "abc.kt"))
        assertNull(score("nope/x", "App.kt", "nope/App.kt"))
    }
}
