package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun `registers the strideRunMain task lazily for every project`() {
        assertTrue(script.contains("tasks.register('strideRunMain', JavaExec)"))
        assertTrue(script.contains("gradleProperty('stride.mainClass')"))
        assertTrue(script.contains("gradleProperty('stride.sourceSet')"))
        assertTrue(script.contains("runtimeClasspath"))
        assertTrue(script.contains("standardInput = System.in"))
        assertTrue(script.contains("workingDir = project.projectDir"))
    }

    @Test
    fun `a project without the java plugin fails with a clear message`() {
        assertTrue(script.contains("def projectPath = project.path"))
        assertTrue(script.contains("def javaApplied = false"))
        assertTrue(script.contains("withPlugin('java') { javaApplied = true }"))
        assertTrue(
            script.contains(
                "stride: project \${projectPath} does not apply the java plugin; cannot run ",
            ),
        )
        assertTrue(script.contains("throw new GradleException"))
        val register = script.indexOf("tasks.register('strideRunMain', JavaExec)")
        val pluginBlock = script.indexOf("sourceSets.getByName(sourceSetName).runtimeClasspath")
        assertTrue(register in 0 until pluginBlock, "the task must be registered outside the java plugin callback")
    }

    @Test
    fun `the run action captures no project reference for the configuration cache`() {
        val action = doFirstAction()
        assertTrue(action.contains("javaApplied"), action)
        assertTrue(action.contains("\${projectPath}"), action)
        assertFalse(action.contains("project."), "a Project in the action breaks the configuration cache: $action")
    }

    /** The body of the strideRunMain `doFirst` action, indentation included. */
    private fun doFirstAction(): String {
        val start = script.indexOf("doFirst {")
        check(start >= 0) { "no doFirst action in the init script" }
        val end = script.indexOf("\n        }", start)
        check(end > start) { "unterminated doFirst action" }
        return script.substring(start, end)
    }

    companion object {
        private val PREFIX = Regex("""##stride\[(\w+) (\w+) """)
        private val PLACEHOLDER = Regex("""\$\{[^}]*\}""")
    }
}
