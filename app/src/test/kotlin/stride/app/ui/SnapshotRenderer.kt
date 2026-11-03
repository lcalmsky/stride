package stride.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import stride.app.AppState
import java.nio.file.Files
import java.nio.file.Path

/**
 * Renders the real [App] through [ImageComposeScene] and writes the PNG to
 * `app/build/ui-snapshots`. Software-only, so no window or display is required.
 */
object SnapshotRenderer {
    fun snapshotDir(): Path = projectRoot().resolve("app").resolve("build").resolve("ui-snapshots")

    fun render(
        output: Path,
        state: AppState,
        initialQuery: String = "",
        renderMillis: Long = 800,
    ) {
        Files.createDirectories(output.parent)
        var scene: ImageComposeScene? = null
        try {
            // 2560×1640 px at density 2 is the real 1280×820 dp default window.
            scene = ImageComposeScene(
                width = 2560,
                height = 1640,
                density = Density(2f),
            ) {
                CompositionLocalProvider(
                    LocalWordmarkCaretBlink provides false,
                    LocalAnimationsEnabled provides false,
                ) {
                    App(state, onOpenFolder = {}, searchInitialQuery = initialQuery)
                }
            }
            val start = System.nanoTime()
            var nanos = 0L
            while ((System.nanoTime() - start) / 1_000_000 < renderMillis) {
                scene.render(nanos)
                nanos += 16_000_000L
                Thread.sleep(16)
            }
            val image = scene.render(nanos)
            try {
                val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) {
                    "PNG encoding failed for ${output.fileName}"
                }
                Files.write(output, data.bytes)
            } finally {
                image.close()
            }
        } finally {
            scene?.close()
        }
        check(Files.exists(output) && Files.size(output) > 0) { "${output.fileName} was not written" }
    }

    /**
     * Warms [scene] up for [warmupFrames] frames, then measures [measuredFrames] single frames and
     * returns the median (p50) in milliseconds. Each frame advances by 16 ms.
     */
    fun renderFrameMillis(
        scene: ImageComposeScene,
        warmupFrames: Int = 10,
        measuredFrames: Int = 20,
        startNanos: Long = 0L,
    ): Double {
        var nanos = startNanos
        repeat(warmupFrames) {
            scene.render(nanos).close()
            nanos += 16_000_000L
        }
        val samples = DoubleArray(measuredFrames)
        for (index in samples.indices) {
            val begin = System.nanoTime()
            scene.render(nanos).close()
            samples[index] = (System.nanoTime() - begin) / 1_000_000.0
            nanos += 16_000_000L
        }
        samples.sort()
        val middle = measuredFrames / 2
        return if (measuredFrames % 2 == 1) {
            samples[middle]
        } else {
            (samples[middle - 1] + samples[middle]) / 2.0
        }
    }

    private fun projectRoot(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null && !Files.exists(directory.resolve("settings.gradle.kts"))) {
            directory = directory.parent
        }
        return requireNotNull(directory) { "project root not found from ${Path.of("").toAbsolutePath()}" }
    }
}
