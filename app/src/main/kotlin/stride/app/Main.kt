package stride.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Paths
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.WindowConstants
import stride.app.glass.GlassRuntime
import stride.app.glass.SystemAppearance
import stride.app.keys.KeyRouter
import stride.app.keys.handleWindowKeyEvent
import stride.app.ui.App
import stride.app.ui.chooseFolder
import stride.core.services.Application

fun main(args: Array<String>) {
    val result = Application.init()
    val log = Logger.getLogger("stride.app")
    log.info("LoadResult enabled=${result.enabled.map { it.id }}")
    log.info("LoadResult disabled=${result.disabled}")
    val initialPath = args.firstOrNull()
    // Follow the system appearance: the native observer notifies on change, so no restart is
    // needed; the palette swap is Compose state read by every surface.
    SystemAppearance.start()

    application {
        val state = remember { AppState() }
        val router = remember { KeyRouter(KeyRouter.isMacOs()) }
        LaunchedEffect(Unit) {
            if (initialPath != null) {
                runCatching { state.openFolder(Paths.get(initialPath)) }
                    .onFailure { log.log(Level.WARNING, "Failed to open $initialPath", it) }
            }
        }
        val close = {
            state.dispose()
            exitApplication()
        }
        Window(
            visible = true,
            onPreviewKeyEvent = { event ->
                handleWindowKeyEvent(event, router, state, ::chooseFolder)
            },
            onKeyEvent = { false },
            create = {
                ComposeWindow().apply {
                    // Read when the peer is created: the content view fills the window and the
                    // title strip becomes transparent with the title hidden. The native traffic
                    // lights stay, centred in the top capsule row.
                    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                    setSize(1280, 820)
                    setLocationRelativeTo(null)
                    val window = this
                    addWindowListener(
                        object : WindowAdapter() {
                            override fun windowClosing(event: WindowEvent) = close()
                        },
                    )
                    addComponentListener(
                        object : ComponentAdapter() {
                            override fun componentShown(event: ComponentEvent) = GlassRuntime.attach(window)
                            override fun componentHidden(event: ComponentEvent) = GlassRuntime.detach()
                        },
                    )
                }
            },
            dispose = { it.dispose() },
            update = { window ->
                // Runs before the first show: the Compose Skia layer must be transparent from
                // the first frame or the native glass below it never shows.
                GlassRuntime.prepare(window)
                // The title is hidden in the title bar but stays for Mission Control and the
                // Window menu.
                window.title = state.session?.let { "strIDE — ${it.displayName()}" } ?: "strIDE"
            },
        ) {
            App(
                state,
                onOpenFolder = { chooseFolder()?.let(state::openFolder) },
                chooseFolder = ::chooseFolder,
            )
        }
    }
}
