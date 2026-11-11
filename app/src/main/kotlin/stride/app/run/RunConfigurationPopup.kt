package stride.app.run

import androidx.compose.runtime.Composable
import java.nio.file.Path

/** The ⌃⌥R chooser: the selected file's entries, then the recent runs. */
@Composable
fun RunConfigurationPopup(
    configurations: List<RunConfiguration>,
    onChoose: (RunConfiguration) -> Unit,
    onClose: () -> Unit,
    projectRoot: Path,
) {
    ChoicePopup(
        labels = configurations.map { it.name },
        title = "Run Configuration",
        secondaryLabels = secondaryLabels(configurations, projectRoot),
        onChoose = { index -> configurations.getOrNull(index)?.let(onChoose) },
        onClose = onClose,
    )
}

/**
 * The kind label, plus the working directory when two configurations share a name (for
 * example `MainKt` from two modules), so the chooser rows stay distinguishable.
 */
internal fun secondaryLabels(configurations: List<RunConfiguration>, projectRoot: Path): List<String> {
    val nameCounts = configurations.groupingBy { it.name }.eachCount()
    return configurations.map { configuration ->
        val kind = kindLabel(configuration)
        if (nameCounts.getValue(configuration.name) > 1) {
            "$kind · ${relativeDirectory(configuration.workingDir, projectRoot)}"
        } else {
            kind
        }
    }
}

private fun relativeDirectory(directory: Path, projectRoot: Path): String {
    val root = projectRoot.toAbsolutePath().normalize()
    val normalized = directory.toAbsolutePath().normalize()
    val relative = runCatching { root.relativize(normalized).toString().replace('\\', '/') }
        .getOrDefault(normalized.toString())
    return relative.ifEmpty { "." }
}

private fun kindLabel(configuration: RunConfiguration): String = when (configuration.kind) {
    RunKind.TEST -> "Test"
    RunKind.TASK -> "Task"
    RunKind.APPLICATION -> "Application"
}
