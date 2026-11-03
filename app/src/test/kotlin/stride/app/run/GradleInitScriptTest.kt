package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

class GradleInitScriptTest {
    private val script: String = Files.readString(GradleInitScript.materialize())

    @Test
    fun `every event line uses the parser prefix and the five kinds`() {
        val matches = PREFIX.findAll(script).toList()
        assertTrue(matches.isNotEmpty(), "no event lines in the init script")
        assertTrue(matches.all { it.groupValues[1] == "test" }, "unexpected prefix in ${matches.map { it.value }}")
        assertEquals(setOf("start", "pass", "fail", "skip", "out"), matches.map { it.groupValues[2] }.toSet())
    }

    @Test
    fun `synthesized lines of each kind parse as events`() {
        val templates = script.lines().mapNotNull { line ->
            val start = line.indexOf("##stride[")
            if (start < 0) return@mapNotNull null
            val end = line.lastIndexOf(']')
            if (end < start) return@mapNotNull null
            line.substring(start, end + 1)
        }.map { template ->
            template.replace(PLACEHOLDER) { match ->
                if ("duration" in match.value) "1" else "x"
            }
        }
        assertEquals(5, templates.size, "expected one template per kind")
        for (line in templates) {
            assertTrue(TestEventParser.parse(line) is ParsedLine.Event, "did not parse as an event: $line")
        }
    }

    @Test
    fun `the test task cannot be skipped as up-to-date or served from the build cache`() {
        assertTrue(script.contains("outputs.upToDateWhen { false }"))
        assertTrue(script.contains("outputs.cacheIf { false }"))
    }

    companion object {
        private val PREFIX = Regex("""##stride\[(\w+) (\w+) """)
        private val PLACEHOLDER = Regex("""\$\{[^}]*\}""")
    }
}
