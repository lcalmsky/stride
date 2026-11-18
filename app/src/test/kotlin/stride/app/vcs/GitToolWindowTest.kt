package stride.app.vcs

import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GitToolWindowTest {

    private val now = Instant.parse("2026-06-15T12:00:00Z")

    private fun ago(seconds: Long): String = relativeDate(now.minusSeconds(seconds), now)

    @Test
    fun `a recent commit reads as just now`() {
        assertEquals("just now", ago(0))
        assertEquals("just now", ago(59))
        assertEquals("just now", relativeDate(now.plusSeconds(30), now))
    }

    @Test
    fun `minutes, hours and days are relative`() {
        assertEquals("1 minute ago", ago(60))
        assertEquals("5 minutes ago", ago(5 * 60))
        assertEquals("1 hour ago", ago(3_600))
        assertEquals("3 hours ago", ago(3 * 3_600))
        assertEquals("1 day ago", ago(86_400))
        assertEquals("3 days ago", ago(3L * 86_400))
        assertEquals("29 days ago", ago(29L * 86_400))
    }

    @Test
    fun `an older commit reads as its date`() {
        assertEquals("2026-04-20", ago(56L * 86_400))
        assertEquals("2026-01-01", relativeDate(Instant.parse("2026-01-01T12:00:00Z"), now))
    }

    @Test
    fun `the console prints milliseconds under a second and tenths above`() {
        assertEquals("123 ms", formatDuration(123.milliseconds))
        assertEquals("1.5 s", formatDuration(1_500.milliseconds))
        assertEquals("90.0 s", formatDuration(kotlin.time.Duration.parse("90s")))
    }
}
