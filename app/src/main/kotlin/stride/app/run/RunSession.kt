package stride.app.run

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

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

    val isRunning: Boolean get() = exitCode == null

    fun start() {
        Thread({ read() }, "stride-run-reader").apply {
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
                        is ParsedLine.Output -> consoleLines.add(parsed.text)
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

    fun stop() {
        runCatching {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
        }
        process.destroyForcibly()
    }
}
