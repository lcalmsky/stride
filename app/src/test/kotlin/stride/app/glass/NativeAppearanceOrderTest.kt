package stride.app.glass

import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The menu-bar regression: querying the appearance before AWT used to create a plain
 * NSApplication, after which AWT could not install NSApplicationAWT and the app lost its menu
 * bar and delegate. The probe runs in a fresh JVM because this test JVM has already touched AWT.
 */
class NativeAppearanceOrderTest {
    @Test
    fun `an appearance query before AWT leaves NSApplicationAWT installed`() {
        assumeTrue(NativeGlass.available, "native glass library unavailable: ${NativeGlass.failure}")
        val classpath = System.getProperty("stride.test.classpath")
        assumeTrue(classpath != null, "the test classpath is not exposed to the test JVM")
        val command = mutableListOf(
            Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.awt.headless=false",
            "-cp",
            requireNotNull(classpath),
        )
        System.getProperty("stride.glass.library")?.let { command += "-Dstride.glass.library=$it" }
        command += "stride.app.glass.AppearanceOrderProbeKt"
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        System.err.println("appearance-order-probe: ${output.trim()}")
        assertTrue(finished, "the probe JVM did not finish within 60 s")
        assertEquals(0, process.exitValue(), "the probe failed:\n$output")
        assertEquals(
            "(nil)",
            beforeClass(output),
            "the appearance query created an application before AWT:\n$output",
        )
        assertEquals(
            "NSApplicationAWT",
            afterClass(output),
            "AWT did not install its application subclass:\n$output",
        )
    }

    private fun beforeClass(output: String): String? =
        Regex("beforeClass=(\\S+)").find(output)?.groupValues?.get(1)

    private fun afterClass(output: String): String? =
        Regex("afterClass=(\\S+)").find(output)?.groupValues?.get(1)
}
