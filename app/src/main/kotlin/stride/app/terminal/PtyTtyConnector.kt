package stride.app.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.Path

/** A [TtyConnector] over a pty4j process. */
class PtyTtyConnector(
    private val process: PtyProcess,
    private val charset: Charset = Charsets.UTF_8,
) : TtyConnector {
    private val reader = InputStreamReader(process.inputStream, charset)

    override fun read(buf: CharArray, offset: Int, length: Int): Int = reader.read(buf, offset, length)

    override fun write(bytes: ByteArray) {
        val output = process.outputStream
        output.write(bytes)
        output.flush()
    }

    override fun write(string: String) = write(string.toByteArray(charset))

    override fun isConnected(): Boolean = process.isAlive

    override fun resize(termSize: TermSize) {
        runCatching { process.setWinSize(WinSize(termSize.columns, termSize.rows)) }
    }

    override fun waitFor(): Int = process.waitFor()

    override fun ready(): Boolean = reader.ready()

    override fun getName(): String = "stride-terminal"

    override fun close() {
        runCatching { reader.close() }
        runCatching { process.outputStream.close() }
        runCatching { process.destroyForcibly() }
    }
}

object PtyShell {
    /** Starts `$SHELL` (or `/bin/zsh`) with `-l` in [workingDir] with `TERM=xterm-256color`. */
    fun start(
        workingDir: Path,
        columns: Int,
        rows: Int,
        env: Map<String, String> = System.getenv(),
    ): PtyProcess {
        val shell = env["SHELL"]?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        val environment = HashMap(env).apply { put("TERM", "xterm-256color") }
        return PtyProcessBuilder()
            .setCommand(arrayOf(shell, "-l"))
            .setEnvironment(environment)
            .setDirectory(workingDir.toString())
            .setInitialColumns(columns)
            .setInitialRows(rows)
            .setRedirectErrorStream(true)
            .start()
    }
}
