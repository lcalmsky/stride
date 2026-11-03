package stride.app.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import com.jediterm.core.typeahead.TerminalTypeAheadManager
import com.jediterm.core.typeahead.TypeAheadTerminalModel
import com.jediterm.core.util.TermSize
import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.RequestOrigin
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TerminalExecutorServiceManager
import com.jediterm.terminal.TerminalStarter
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalModelListener
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.pty4j.PtyProcess
import stride.core.Disposable
import stride.core.services.Project
import stride.core.services.Service
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One project's terminal: the jediterm model, its PTY connector and the emulator thread.
 * Owned by the project, so closing the project disposes the terminal with it.
 */
@Service(Service.Level.PROJECT)
class TerminalSession(private val project: Project) : Disposable {
    val styleState = StyleState()
    val buffer = TerminalTextBuffer(COLUMNS, ROWS, styleState, MAX_HISTORY_LINES)
    val terminal = JediTerminal(SessionDisplay(), buffer, styleState)

    /** Terminal size in cells. Starts at 80×24 and follows the view on resize. */
    var columns: Int = COLUMNS
    var rows: Int = ROWS

    /** Bumped on every model change; views read it to repaint the buffer. */
    var revision by mutableIntStateOf(0)
        private set

    private var started = false
    private var disposed = false
    private var connector: TtyConnector? = null
    private var process: PtyProcess? = null
    private var starter: TerminalStarter? = null
    private var emulatorThread: Thread? = null

    private val modelListener = TerminalModelListener { if (!disposed) revision++ }

    /** The cursor column, 0-based. jediterm's `getCursorX()` is 1-based, so this subtracts one. */
    val cursorX: Int get() = terminal.cursorX - 1

    /** The cursor row, 1-based, as jediterm reports it. */
    val cursorY: Int get() = terminal.cursorY

    val isStarted: Boolean get() = started

    init {
        buffer.addModelListener(modelListener)
    }

    /** Starts [connector]. Does nothing when a connector was already started. */
    fun start(connector: TtyConnector) {
        if (started || disposed) return
        started = true
        this.connector = connector
        val executors = DaemonExecutors(threadName())
        val starter = TerminalStarter(
            terminal,
            connector,
            TtyBasedArrayDataStream(connector),
            TerminalTypeAheadManager(DisabledTypeAheadModel),
            executors,
        )
        this.starter = starter
        emulatorThread = Thread({ starter.start() }, threadName()).apply {
            isDaemon = true
            start()
        }
    }

    /** Starts the login shell in the project root. Does nothing when already started. */
    fun startDefault() {
        if (started || disposed) return
        val process = runCatching { PtyShell.start(project.root, columns, rows) }.getOrNull() ?: return
        this.process = process
        start(PtyTtyConnector(process))
    }

    /** Sends user typing or a paste to the shell. */
    fun write(text: String) {
        if (!started || disposed) return
        starter?.sendString(text, true)
    }

    /**
     * Sends one key press to the shell. Special keys become xterm sequences; printable
     * characters are written as is. Returns `true` when the key was sent.
     */
    fun sendKey(
        key: Key,
        meta: Boolean,
        ctrl: Boolean,
        alt: Boolean,
        shift: Boolean,
        char: Char?,
    ): Boolean {
        if (!started || disposed) return false
        val control = when {
            key == Key.C && ctrl -> "\u0003"
            key == Key.D && ctrl -> "\u0004"
            key == Key.Z && ctrl -> "\u001a"
            key == Key.L && ctrl -> "\u000c"
            else -> null
        }
        val sequence = control ?: when (key) {
            Key.Enter, Key.NumPadEnter -> "\r"
            Key.Backspace -> "\u007f"
            Key.Tab -> if (shift) "\u001b[Z" else "\t"
            Key.Escape -> "\u001b"
            Key.DirectionUp -> "\u001b[A"
            Key.DirectionDown -> "\u001b[B"
            Key.DirectionRight -> "\u001b[C"
            Key.DirectionLeft -> "\u001b[D"
            Key.Delete -> "\u001b[3~"
            Key.Insert -> "\u001b[2~"
            Key.MoveHome -> "\u001b[H"
            Key.MoveEnd -> "\u001b[F"
            Key.PageUp -> "\u001b[5~"
            Key.PageDown -> "\u001b[6~"
            else -> null
        }
        if (sequence != null) {
            write(sequence)
            return true
        }
        if (meta || ctrl || alt) return false
        val character = char?.takeUnless { it.isISOControl() } ?: return false
        write(character.toString())
        return true
    }

    /** Resizes the terminal model and the PTY. */
    fun resize(columns: Int, rows: Int) {
        if (disposed) return
        val nextColumns = columns.coerceAtLeast(1)
        val nextRows = rows.coerceAtLeast(1)
        if (nextColumns == this.columns && nextRows == this.rows) return
        this.columns = nextColumns
        this.rows = nextRows
        val size = TermSize(nextColumns, nextRows)
        terminal.resize(size, RequestOrigin.User)
        connector?.resize(size)
    }

    /** Visible screen lines, top to bottom, without trailing padding. */
    fun lines(): List<String> = buffer.readLines(0, buffer.height)

    /** Scrollback lines, oldest first. */
    fun historyLines(): List<String> {
        val count = buffer.historyLinesCount
        return buffer.readLines(-count, count)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        buffer.removeModelListener(modelListener)
        runCatching { starter?.close() }
        runCatching { connector?.close() }
        process?.let { pty ->
            runCatching { pty.destroyForcibly() }
            runCatching { pty.waitFor(1, TimeUnit.SECONDS) }
        }
        emulatorThread?.interrupt()
        emulatorThread = null
        starter = null
        connector = null
        process = null
    }

    private fun threadName(): String =
        "stride-terminal-${project.root.fileName?.toString() ?: "project"}"

    private fun TerminalTextBuffer.readLines(from: Int, count: Int): List<String> {
        lock()
        try {
            return (from until from + count).map { getLine(it).text }
        } finally {
            unlock()
        }
    }

    private companion object {
        const val COLUMNS = 80
        const val ROWS = 24
        const val MAX_HISTORY_LINES = 1_000
    }
}

private class SessionDisplay : TerminalDisplay {
    private var title = ""

    override fun setCursor(x: Int, y: Int) = Unit

    override fun setCursorShape(shape: CursorShape?) = Unit

    override fun beep() = Unit

    override fun scrollArea(scrollRegionTop: Int, scrollRegionBottom: Int, dy: Int) = Unit

    override fun setCursorVisible(visible: Boolean) = Unit

    override fun useAlternateScreenBuffer(use: Boolean) = Unit

    override fun getWindowTitle(): String = title

    override fun setWindowTitle(name: String) {
        title = name
    }

    override fun getSelection(): TerminalSelection? = null

    override fun terminalMouseModeSet(mode: MouseMode) = Unit

    override fun setMouseFormat(format: MouseFormat) = Unit

    override fun ambiguousCharsAreDoubleWidth(): Boolean = false
}

private class DaemonExecutors(name: String) : TerminalExecutorServiceManager {
    private val scheduled: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "$name-emulator").apply { isDaemon = true }
        }

    private val unbounded: ExecutorService =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "$name-writer").apply { isDaemon = true }
        }

    override fun getSingleThreadScheduledExecutor(): ScheduledExecutorService = scheduled

    override fun getUnboundedExecutorService(): ExecutorService = unbounded

    override fun shutdownWhenAllExecuted() {
        scheduled.shutdownNow()
        unbounded.shutdownNow()
    }
}

private object DisabledTypeAheadModel : TypeAheadTerminalModel {
    override fun insertCharacter(character: Char, index: Int) = Unit

    override fun removeCharacters(count: Int, index: Int) = Unit

    override fun moveCursor(index: Int) = Unit

    override fun forceRedraw() = Unit

    override fun clearPredictions() = Unit

    override fun lock() = Unit

    override fun unlock() = Unit

    override fun isUsingAlternateBuffer(): Boolean = false

    override fun getCurrentLineWithCursor(): TypeAheadTerminalModel.LineWithCursorX =
        TypeAheadTerminalModel.LineWithCursorX(StringBuffer(), 0)

    override fun getTerminalWidth(): Int = 0

    override fun isTypeAheadEnabled(): Boolean = false

    override fun getLatencyThreshold(): Long = 0

    override fun getShellType(): TypeAheadTerminalModel.ShellType =
        TypeAheadTerminalModel.ShellType.Unknown
}
