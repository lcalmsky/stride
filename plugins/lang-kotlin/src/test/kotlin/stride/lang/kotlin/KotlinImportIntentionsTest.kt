package stride.lang.kotlin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import stride.lang.IntentionAction
import stride.lang.IntentionContext
import stride.lang.SymbolLookup
import stride.lang.TextEdit

class KotlinImportIntentionsTest {
    private val provider = KotlinImportIntentions()

    private fun symbols(vararg entries: Pair<String, List<String>>): SymbolLookup {
        val map = entries.toMap()
        return SymbolLookup { name -> map[name].orEmpty() }
    }

    private val clock = symbols("Clock" to listOf("demo.time.Clock"))

    private fun collect(text: String, caret: Int, lookup: SymbolLookup = clock): List<IntentionAction> =
        provider.collect(IntentionContext(text, caret, "Main.kt", lookup))

    private fun caretAfter(text: String, needle: String): Int = text.indexOf(needle) + needle.length

    private fun caretAfterLast(text: String, needle: String): Int = text.lastIndexOf(needle) + needle.length

    private fun apply(text: String, edits: List<TextEdit>): String {
        var result = text
        for (edit in edits.sortedByDescending { it.start }) {
            result = result.substring(0, edit.start) + edit.replacement + result.substring(edit.end)
        }
        return result
    }

    @Test
    fun `imports are inserted in sorted order`() {
        val text = "package demo\n\nimport demo.a.Alpha\nimport demo.c.Charlie\n\nfun main() {\n    Beta()\n}\n"
        val actions = collect(text, caretAfter(text, "Beta"), symbols("Beta" to listOf("demo.b.Beta")))

        assertEquals(listOf("Import 'demo.b.Beta'"), actions.map { it.text })
        assertEquals(
            "package demo\n\nimport demo.a.Alpha\nimport demo.b.Beta\nimport demo.c.Charlie\n\nfun main() {\n    Beta()\n}\n",
            apply(text, actions.single().edits),
        )
    }

    @Test
    fun `an import goes after the package line when the file has none`() {
        val text = "package demo\n\nfun main() {\n    Clock()\n}\n"
        val actions = collect(text, caretAfter(text, "Clock"))

        assertEquals(listOf("Import 'demo.time.Clock'"), actions.map { it.text })
        assertEquals(
            "package demo\n\nimport demo.time.Clock\n\nfun main() {\n    Clock()\n}\n",
            apply(text, actions.single().edits),
        )
    }

    @Test
    fun `a file without a package gets the import at the top`() {
        val text = "fun main() {\n    Clock()\n}\n"
        val actions = collect(text, caretAfter(text, "Clock"))

        assertEquals(
            "import demo.time.Clock\n\nfun main() {\n    Clock()\n}\n",
            apply(text, actions.single().edits),
        )
    }

    @Test
    fun `a same package declaration is not offered`() {
        val text = "package demo\n\nfun main() {\n    Clock()\n}\n"
        val actions = collect(text, caretAfter(text, "Clock"), symbols("Clock" to listOf("demo.Clock")))

        assertTrue(actions.isEmpty())
    }

    @Test
    fun `an already imported name is not offered`() {
        val text = "package demo\n\nimport demo.time.Clock\n\nfun main() {\n    Clock()\n}\n"
        assertTrue(collect(text, caretAfterLast(text, "Clock")).isEmpty())
    }

    @Test
    fun `an alias that binds the name is not offered`() {
        val text = "package demo\n\nimport demo.time.C as Clock\n\nfun main() {\n    Clock()\n}\n"
        assertTrue(collect(text, caretAfterLast(text, "Clock")).isEmpty())
    }

    @Test
    fun `a star import covers its package`() {
        val text = "package demo\n\nimport demo.time.*\n\nfun main() {\n    Clock()\n}\n"
        assertTrue(collect(text, caretAfterLast(text, "Clock")).isEmpty())
    }

    @Test
    fun `a qualified access is not offered`() {
        val text = "package demo\n\nfun main() {\n    thing.Clock()\n}\n"
        assertTrue(collect(text, caretAfter(text, "thing.Clock")).isEmpty())

        val safe = "package demo\n\nfun main() {\n    thing?.Clock()\n}\n"
        assertTrue(collect(safe, caretAfter(safe, "thing?.Clock")).isEmpty())
    }

    @Test
    fun `a name declared in the file is not offered`() {
        val text = "package demo\n\nclass Clock\n\nfun main() {\n    Clock()\n}\n"
        assertTrue(collect(text, caretAfterLast(text, "Clock")).isEmpty())
    }

    @Test
    fun `a caret in a string or a comment is not offered`() {
        val string = "package demo\n\nfun main() {\n    println(\"Clock\")\n}\n"
        assertTrue(collect(string, caretAfter(string, "\"Clock") - 1).isEmpty())

        val comment = "package demo\n\n// Clock\nfun main() {}\n"
        assertTrue(collect(comment, caretAfter(comment, "// Clock")).isEmpty())
    }

    @Test
    fun `a template identifier is code`() {
        val text = "package demo\n\nfun main() {\n    println(\"\${Clock.now()}\")\n}\n"
        val actions = collect(text, caretAfter(text, "\${Clock"))

        assertEquals(listOf("Import 'demo.time.Clock'"), actions.map { it.text })
    }

    @Test
    fun `at most six candidates are offered`() {
        val candidates = (1..8).map { "p$it.Clock" }
        val text = "package demo\n\nfun main() {\n    Clock()\n}\n"
        val actions = collect(text, caretAfterLast(text, "Clock"), symbols("Clock" to candidates))

        assertEquals((1..6).map { "Import 'p$it.Clock'" }, actions.map { it.text })
    }

    @Test
    fun `a single unused import is removed with its line`() {
        val text = "package demo\n\nimport demo.a.Alpha\nimport demo.b.Beta\n\nfun main() {\n    Beta()\n}\n"
        val actions = collect(text, caretAfter(text, "Alpha"))

        assertEquals(listOf("Remove unused import 'demo.a.Alpha'"), actions.map { it.text })
        assertEquals(
            "package demo\n\nimport demo.b.Beta\n\nfun main() {\n    Beta()\n}\n",
            apply(text, actions.single().edits),
        )
    }

    @Test
    fun `all unused imports are removed and the blank line collapses`() {
        val text = "package demo\n\nimport demo.a.Alpha\nimport demo.b.Beta\n\nfun main() {\n}\n"
        val actions = collect(text, caretAfter(text, "Alpha"))

        assertEquals(
            listOf("Remove unused import 'demo.a.Alpha'", "Remove unused imports"),
            actions.map { it.text },
        )
        assertEquals(
            "package demo\n\nfun main() {\n}\n",
            apply(text, actions[1].edits),
        )
    }

    @Test
    fun `operator convention names are never unused`() {
        val text = "package demo\n\nimport demo.a.getValue\n\nfun main() {\n}\n"
        assertTrue(collect(text, caretAfter(text, "getValue")).isEmpty())
    }

    @Test
    fun `an alias used in code keeps its import`() {
        val text = "package demo\n\nimport demo.a.Alpha as A\n\nfun main() {\n    A()\n}\n"
        assertTrue(collect(text, caretAfter(text, "Alpha")).isEmpty())
    }

    @Test
    fun `a star import is never removed`() {
        val text = "package demo\n\nimport demo.a.*\n\nfun main() {\n}\n"
        assertTrue(collect(text, caretAfter(text, "demo.a.*")).isEmpty())
    }

    @Test
    fun `a template usage keeps its import`() {
        val text = "package demo\n\nimport demo.time.Clock\n\nfun main() {\n    println(\"\${Clock.now()}\")\n}\n"
        assertTrue(collect(text, caretAfter(text, "import demo.time.Clock")).isEmpty())
    }

    @Test
    fun `a simple template usage keeps its import`() {
        val text = "package demo\n\nimport demo.config.VERSION\n\nfun main() {\n    println(\"v\$VERSION\")\n}\n"
        assertTrue(collect(text, caretAfter(text, "import demo.config.VERSION")).isEmpty())
    }

    @Test
    fun `a simple template in a raw string is a usage`() {
        val text = "package demo\n\nimport demo.config.VERSION\n\nval banner = \"\"\"\nv\$VERSION\n\"\"\"\n"
        assertTrue(collect(text, caretAfter(text, "import demo.config.VERSION")).isEmpty())
    }

    @Test
    fun `an escaped dollar is not a template`() {
        val text = "package demo\n\nimport demo.config.VERSION\n\nfun main() {\n    println(\"\\\$VERSION\")\n}\n"
        val actions = collect(text, caretAfter(text, "import demo.config.VERSION"))

        assertEquals(listOf("Remove unused import 'demo.config.VERSION'"), actions.map { it.text })
    }

    @Test
    fun `a soft keyword used as a name keeps its import`() {
        val text = "import demo.store.data\n\nfun main() { println(data.size) }\n"
        assertTrue(collect(text, caretAfter(text, "import demo.store.data")).isEmpty())
    }

    @Test
    fun `the assignment plugin name is never unused`() {
        val text = "package demo\n\nimport demo.a.assign\n\nfun main() {\n}\n"
        assertTrue(collect(text, caretAfter(text, "assign")).isEmpty())
    }

    @Test
    fun `an import inside a raw string is not an import`() {
        val text =
            "package demo\n\nimport demo.a.Alpha\n\nval sample = \"\"\"\nimport zzz.Zed\n\"\"\"\n\n" +
                "fun main() { Alpha(); Clock() }\n"
        val actions = collect(text, caretAfterLast(text, "Clock"))

        assertEquals(listOf("Import 'demo.time.Clock'"), actions.map { it.text })
        assertEquals(
            "package demo\n\nimport demo.a.Alpha\nimport demo.time.Clock\n\nval sample = \"\"\"\n" +
                "import zzz.Zed\n\"\"\"\n\nfun main() { Alpha(); Clock() }\n",
            apply(text, actions.single().edits),
        )
    }

    @Test
    fun `a raw string after the imports does not extend the import block`() {
        val text =
            "package demo\n\nimport demo.a.Alpha\n\nval sample = \"\"\"\nimport zzz.Zed\n\"\"\"\n\nfun main() { Alpha() }\n"

        assertTrue(collect(text, caretAfter(text, "import demo.a.Alpha")).isEmpty())
    }

    @Test
    fun `a caret outside the import block offers no removal`() {
        val text = "package demo\n\nimport demo.a.Alpha\n\nfun main() {\n}\n"
        assertTrue(collect(text, caretAfter(text, "main")).isEmpty())
    }
}
