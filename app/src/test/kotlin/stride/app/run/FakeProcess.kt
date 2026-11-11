package stride.app.run

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * A child process fed by bytes. With [blocking], its input stream never reaches EOF until
 * [destroy] closes the pipe, so a run can stay running in tests.
 */
class FakeProcess(
    output: ByteArray = ByteArray(0),
    private val exitCode: Int = 0,
    blocking: Boolean = false,
    private val failStdin: Boolean = false,
) : Process() {
    @Volatile
    var destroyed = false
        private set

    private val written = ByteArrayOutputStream()

    private val failingOutput = object : OutputStream() {
        override fun write(b: Int) {
            throw java.io.IOException("stdin closed")
        }
    }

    /** Everything written to the child's stdin, decoded as UTF-8. */
    val stdin: String get() = written.toString(Charsets.UTF_8.name())

    private val writer: PipedOutputStream? = if (blocking) PipedOutputStream() else null

    private val input: InputStream = if (blocking) {
        PipedInputStream(requireNotNull(writer))
    } else {
        ByteArrayInputStream(output)
    }

    override fun getOutputStream(): OutputStream = if (failStdin) failingOutput else written

    override fun getInputStream(): InputStream = input

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exitCode

    override fun exitValue(): Int = exitCode

    override fun destroy() {
        destroyed = true
        runCatching { writer?.close() }
        runCatching { input.close() }
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}
