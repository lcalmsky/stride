package stride.app.run

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Writes the bundled init script to a temp file the Gradle process can read. */
object GradleInitScript {
    private const val RESOURCE = "/stride/run/stride-test-events.init.gradle"

    fun materialize(): Path {
        val target = Files.createTempFile("stride-test-events", ".init.gradle")
        try {
            val resource = requireNotNull(GradleInitScript::class.java.getResourceAsStream(RESOURCE)) {
                "missing resource $RESOURCE"
            }
            resource.use { Files.copy(it, target, StandardCopyOption.REPLACE_EXISTING) }
        } catch (t: Throwable) {
            Files.deleteIfExists(target)
            throw t
        }
        target.toFile().deleteOnExit()
        return target
    }
}
