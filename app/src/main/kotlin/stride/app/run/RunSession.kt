package stride.app.run

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

enum class TestStatus { RUNNING, PASSED, FAILED, SKIPPED }

class TestMethodNode(val className: String, val name: String) {
    var status: TestStatus by mutableStateOf(TestStatus.RUNNING)
    var message: String? by mutableStateOf(null)
    var stackTrace: String? by mutableStateOf(null)
    val output: SnapshotStateList<String> = mutableStateListOf()
}

class TestClassNode(val className: String) {
    val methods: SnapshotStateList<TestMethodNode> = mutableStateListOf()

    val status: TestStatus
        get() = when {
            methods.any { it.status == TestStatus.FAILED } -> TestStatus.FAILED
            methods.any { it.status == TestStatus.RUNNING } -> TestStatus.RUNNING
            methods.isNotEmpty() && methods.all { it.status == TestStatus.SKIPPED } -> TestStatus.SKIPPED
            else -> TestStatus.PASSED
        }
}

class TestTree {
    val classes: SnapshotStateList<TestClassNode> = mutableStateListOf()

    val passed: Int get() = methods().count { it.status == TestStatus.PASSED }
    val failed: Int get() = methods().count { it.status == TestStatus.FAILED }
    val skipped: Int get() = methods().count { it.status == TestStatus.SKIPPED }

    fun apply(event: TestEvent) {
        val classNode = classes.firstOrNull { it.className == event.className }
            ?: TestClassNode(event.className).also { classes.add(it) }
        val method = classNode.methods.firstOrNull { it.name == event.methodName }
            ?: TestMethodNode(event.className, event.methodName).also { classNode.methods.add(it) }
        when (event) {
            is TestEvent.Start -> method.status = TestStatus.RUNNING
            is TestEvent.Pass -> {
                method.status = TestStatus.PASSED
                method.message = null
                method.stackTrace = null
            }
            is TestEvent.Fail -> {
                method.status = TestStatus.FAILED
                method.message = event.message
                method.stackTrace = event.stackTrace
            }
            is TestEvent.Skip -> method.status = TestStatus.SKIPPED
            is TestEvent.Out -> method.output.add(event.text)
        }
    }

    private fun methods(): List<TestMethodNode> = classes.flatMap { it.methods }
}

/**
 * One run: the child process, the console it writes to, and the test tree its stdout fills.
 * The reader thread is the only writer of [exitCode] and [tree].
 */
class RunSession(
    val configuration: RunConfiguration,
    private val process: Process,
    private val displayCommand: String,
) {
    val consoleLines: SnapshotStateList<String> = mutableStateListOf(displayCommand)
    val tree = TestTree()

    var exitCode: Int? by mutableStateOf(null)
        private set

    /** A line the writer thread could not deliver; the console input puts it back. */
    var undeliveredInput: String? by mutableStateOf(null)
        private set

    val isRunning: Boolean get() = exitCode == null

    private val pendingInput = ArrayBlockingQueue<String>(MAX_PENDING_INPUT)
    private var writer: Thread? = null

    /** Set once a write to the child's stdin failed; the writer thread has stopped by then. */
    @Volatile
    private var stdinClosed = false

    /** Adds [line], dropping the oldest lines once the console exceeds [MAX_CONSOLE_LINES]. */
    private fun appendConsole(line: String) {
        consoleLines.add(line)
        if (consoleLines.size > MAX_CONSOLE_LINES) {
            val drop = (consoleLines.size - MAX_CONSOLE_LINES).coerceAtLeast(CONSOLE_TRIM_BATCH)
            repeat(drop) { consoleLines.removeAt(0) }
        }
    }

    fun start() {
        Thread({ read() }, "stride-run-reader").apply {
            isDaemon = true
            start()
        }
        writer = Thread({ writeInputs() }, "stride-run-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun read() {
        val code = runCatching {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    when (val parsed = TestEventParser.parse(line)) {
                        is ParsedLine.Event -> tree.apply(parsed.event)
                        is ParsedLine.Output -> appendConsole(parsed.text)
                    }
                    line = reader.readLine()
                }
            }
            process.waitFor()
        }.getOrElse {
            runCatching { process.exitValue() }.getOrDefault(-1)
        }
        exitCode = code
    }

    /**
     * Queues [line] and a newline for the child's stdin. Returns `false` when the process has
     * ended, its stdin is closed, or the bounded queue is full; the caller keeps the line.
     */
    fun writeInput(line: String): Boolean {
        if (!isRunning || stdinClosed) return false
        return pendingInput.offer(line)
    }

    /** Clears [undeliveredInput] after the console input has put the line back. */
    fun clearUndeliveredInput() {
        undeliveredInput = null
    }

    private fun writeInputs() {
        while (isRunning) {
            val line = try {
                pendingInput.poll(INPUT_POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            } catch (_: InterruptedException) {
                return
            }
            val written = runCatching {
                process.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }.isSuccess
            if (!written) {
                stdinClosed = true
                undeliveredInput = line
                return
            }
            appendConsole(line)
        }
    }

    fun stop() {
        writer?.interrupt()
        runCatching {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
    }

    companion object {
        const val MAX_PENDING_INPUT = 64
        const val MAX_CONSOLE_LINES = 10_000
        private const val CONSOLE_TRIM_BATCH = 1_000
        private const val INPUT_POLL_MILLIS = 100L
    }
}
