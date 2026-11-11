package stride.app.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class RunConfigurationPopupTest {
    private val root = Path.of("/project")

    private fun configuration(name: String, workingDir: String, kind: RunKind = RunKind.APPLICATION) =
        RunConfiguration(
            name = name,
            workingDir = Path.of(workingDir),
            args = if (kind == RunKind.APPLICATION) listOf(":run") else emptyList(),
            kind = kind,
        )

    @Test
    fun `the secondary text is the kind when names are distinct`() {
        val labels = secondaryLabels(
            listOf(
                configuration("MainKt", "/project"),
                configuration("DemoTest", "/project", RunKind.TEST),
            ),
            root,
        )

        assertEquals(listOf("Application", "Test"), labels)
    }

    @Test
    fun `duplicate names add the working directory`() {
        val labels = secondaryLabels(
            listOf(
                configuration("MainKt", "/project/a"),
                configuration("MainKt", "/project/b"),
            ),
            root,
        )

        assertEquals(listOf("Application · a", "Application · b"), labels)
    }

    @Test
    fun `the project root itself is shown as a dot`() {
        val labels = secondaryLabels(
            listOf(
                configuration("MainKt", "/project"),
                configuration("MainKt", "/project/b"),
            ),
            root,
        )

        assertEquals(listOf("Application · .", "Application · b"), labels)
    }
}
