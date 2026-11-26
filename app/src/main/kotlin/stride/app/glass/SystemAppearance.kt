package stride.app.glass

import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import stride.app.ui.theme.Appearance
import stride.app.ui.theme.AppearanceState

/** JNI bridge to the appearance query and observer in `native/glass/stride_glass.mm`. */
internal object NativeAppearance {
    val available: Boolean get() = NativeGlass.available

    fun current(): Appearance? = when (runCatching { nativeAppearance() }.getOrDefault(0)) {
        1 -> Appearance.LIGHT
        2 -> Appearance.DARK
        else -> null
    }

    /** The class of the installed application object; empty before AWT installs it. */
    fun appClassName(): String =
        if (!available) "" else runCatching { nativeAppClassName() }.getOrDefault("")

    /**
     * `NSApp.appearance`'s name, `"(nil)"` when unset, or empty before AWT installs the app or
     * when the AppKit thread does not answer within one second.
     */
    fun appAppearanceName(): String =
        if (!available) "" else runCatching { nativeAppAppearanceName() }.getOrDefault("")

    /** Registers the distributed theme notification; the callback runs on the AppKit main thread. */
    fun start(onChange: () -> Unit): Boolean =
        available && runCatching { nativeStartObserver(Runnable(onChange)) }.getOrDefault(false)

    fun stop() {
        if (available) runCatching { nativeStopObserver() }
    }

    private external fun nativeAppearance(): Int

    private external fun nativeAppClassName(): String

    private external fun nativeAppAppearanceName(): String

    private external fun nativeStartObserver(callback: Runnable): Boolean

    private external fun nativeStopObserver()
}

/**
 * Feeds the effective macOS appearance into [AppearanceState]. The native library observes the
 * system notification, so a change re-themes the window live. Without the library the same value
 * comes from `defaults read -g AppleInterfaceStyle`, polled on a background thread every two
 * seconds; the UI thread never runs that command. Start it only after AWT has installed its
 * application object, otherwise AppKit would create a plain `NSApplication` first.
 */
object SystemAppearance {
    private val log: Logger = Logger.getLogger("stride.app")
    @Volatile
    private var started = false
    @Volatile
    private var poller: Thread? = null

    /**
     * Lets AWT leave NSApp's appearance unset so it follows the system. Without it JDK 21 pins
     * NSApp to Aqua, and the native glass would stay light while the palette follows dark mode.
     * Must run before AWT initialises; an explicitly set [APPEARANCE_PROPERTY] is left alone.
     */
    fun prepareProcess() {
        if (System.getProperty(APPEARANCE_PROPERTY) == null) {
            System.setProperty(APPEARANCE_PROPERTY, "system")
        }
        // M3: AquaMenuBarUI reads this when the bar's UI is created, so it must be set first.
        if (System.getProperty(SCREEN_MENU_PROPERTY) == null) {
            System.setProperty(SCREEN_MENU_PROPERTY, "true")
        }
    }

    internal const val APPEARANCE_PROPERTY = "apple.awt.application.appearance"
    internal const val SCREEN_MENU_PROPERTY = "apple.laf.useScreenMenuBar"

    fun start() {
        if (started) return
        started = true
        if (NativeAppearance.available) {
            NativeAppearance.current()?.let(AppearanceState::set)
            val observing = NativeAppearance.start {
                NativeAppearance.current()?.let(AppearanceState::set)
            }
            if (observing) return
            log.info("appearance: native observer unavailable, polling defaults")
        }
        val thread = Thread({ poll() }, "stride-appearance").apply {
            isDaemon = true
            start()
        }
        poller = thread
    }

    /** Stops the observer and the polling thread; [start] may run again afterwards. */
    fun stop() {
        started = false
        NativeAppearance.stop()
        val thread = poller
        poller = null
        thread?.interrupt()
    }

    private fun poll() {
        while (true) {
            val next = readDefaults()
            if (next != null && AppearanceState.appearance != next) AppearanceState.set(next)
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }

    private fun readDefaults(): Appearance? {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return Appearance.LIGHT
        return runCatching {
            val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val reader = Thread(
                {
                    runCatching {
                        process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                    }
                },
                "stride-appearance-read",
            ).apply {
                isDaemon = true
                start()
            }
            // Wait for the process first: reading the stream up front would block forever when
            // `defaults` hangs, so the timeout would never be reached.
            val finished = process.waitFor(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                runCatching { process.inputStream.close() }
                reader.interrupt()
                return@runCatching null
            }
            reader.join(READER_JOIN_MILLIS)
            if (output.contains("Dark", ignoreCase = true)) Appearance.DARK else Appearance.LIGHT
        }.getOrNull()
    }

    private const val POLL_MILLIS = 2_000L
    private const val READ_TIMEOUT_SECONDS = 2L
    private const val READER_JOIN_MILLIS = 200L
}
