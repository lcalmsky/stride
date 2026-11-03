package stride.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.chooseFolder
import stride.app.ui.loadWindowIcon
import stride.core.services.Application
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger

fun main(args: Array<String>) {
    val result = Application.init()
    val log = Logger.getLogger("stride.app")
    log.info("LoadResult enabled=${result.enabled.map { it.id }}")
    log.info("LoadResult disabled=${result.disabled}")
    val initialPath = args.firstOrNull()

    application {
        val state = remember { AppState() }
        val router = remember { KeyRouter(KeyRouter.isMacOs()) }
        LaunchedEffect(Unit) {
            if (initialPath != null) {
                runCatching { state.openFolder(Paths.get(initialPath)) }
                    .onFailure { log.log(Level.WARNING, "Failed to open $initialPath", it) }
            }
        }
        val windowState = rememberWindowState(
            width = 1280.dp,
            height = 820.dp,
            position = WindowPosition(Alignment.Center),
        )
        val windowIcon = remember { loadWindowIcon() }
        Window(
            onCloseRequest = {
                state.dispose()
                exitApplication()
            },
            title = state.session?.let { "strIDE — ${it.displayName()}" } ?: "strIDE",
            icon = windowIcon,
            state = windowState,
            onPreviewKeyEvent = { event ->
                handleWindowKeyEvent(event, router, state, ::chooseFolder)
            },
        ) {
            App(state, onOpenFolder = { chooseFolder()?.let(state::openFolder) })
        }
    }
}
