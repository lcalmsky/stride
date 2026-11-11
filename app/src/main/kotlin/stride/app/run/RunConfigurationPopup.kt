package stride.app.run

import androidx.compose.runtime.Composable

/** The ⌃⌥R chooser: the selected file's entries, then the recent runs. */
@Composable
fun RunConfigurationPopup(
    configurations: List<RunConfiguration>,
    onChoose: (RunConfiguration) -> Unit,
    onClose: () -> Unit,
) {
    ChoicePopup(
        labels = configurations.map { it.name },
        title = "Run Configuration",
        secondaryLabels = configurations.map(::kindLabel),
        onChoose = { index -> configurations.getOrNull(index)?.let(onChoose) },
        onClose = onClose,
    )
}

private fun kindLabel(configuration: RunConfiguration): String = when (configuration.kind) {
    RunKind.TEST -> "Test"
    RunKind.TASK -> "Task"
    RunKind.APPLICATION -> "Application"
}
