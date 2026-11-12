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

    /** Registers the distributed theme notification; the callback runs on the AppKit main thread. */
    fun start(onChange: () -> Unit): Boolean =
        available && runCatching { nativeStartObserver(Runnable(onChange)) }.getOrDefault(false)

    fun stop() {
        if (available) runCatching { nativeStopObserver() }
    }

    private external fun nativeAppearance(): Int

    private external fun nativeStartObserver(callback: Runnable): Boolean

    private external fun nativeStopObserver()
}

/**
 * Feeds the effective macOS appearance into [AppearanceState]. The native library observes the
 * system notification, so a change re-themes the window live. Without the library the same value
 * comes from `defaults read -g AppleInterfaceStyle`, polled on a background thread every two
 * seconds; the UI thread never runs that command.
 */
object SystemAppearance {
    private val log: Logger = Logger.getLogger("stride.app")
    @Volatile
    private var started = false

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
        Thread(
            {
                while (!Thread.currentThread().isInterrupted) {
                    val next = readDefaults()
                    if (next != null && AppearanceState.appearance != next) AppearanceState.set(next)
                    runCatching { Thread.sleep(POLL_MILLIS) }
                }
            },
            "stride-appearance",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun readDefaults(): Appearance? {
        if (!System.getProperty("os.name").lowercase().contains("mac")) return Appearance.LIGHT
        return runCatching {
            val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (output.contains("Dark", ignoreCase = true)) Appearance.DARK else Appearance.LIGHT
        }.getOrNull()
    }

    private const val POLL_MILLIS = 2_000L
}
