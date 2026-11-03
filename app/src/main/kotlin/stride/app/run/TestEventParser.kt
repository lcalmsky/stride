package stride.app.run

/** One event the init script prints for the run's test tree. */
sealed class TestEvent {
    abstract val className: String
    abstract val methodName: String

    data class Start(
        override val className: String,
        override val methodName: String,
    ) : TestEvent()

    data class Pass(
        override val className: String,
        override val methodName: String,
        val duration: Long,
    ) : TestEvent()

    data class Fail(
        override val className: String,
        override val methodName: String,
        val duration: Long,
        val message: String,
        val stackTrace: String,
    ) : TestEvent()

    data class Skip(
        override val className: String,
        override val methodName: String,
    ) : TestEvent()

    data class Out(
        override val className: String,
        override val methodName: String,
        val stream: String,
        val text: String,
    ) : TestEvent()
}

sealed class ParsedLine {
    data class Event(val event: TestEvent) : ParsedLine()

    data class Output(val text: String) : ParsedLine()
}

/**
 * Reads the `##stride[test …]` stdout protocol. Anything else, including a line with a
 * broken prefix match, comes back as [ParsedLine.Output] with its text unchanged.
 */
object TestEventParser {
    private const val PREFIX = "##stride[test "

    fun parse(line: String): ParsedLine {
        if (!line.startsWith(PREFIX) || !line.endsWith("]")) return ParsedLine.Output(line)
        val body = line.substring(PREFIX.length, line.length - 1)
        val kindEnd = body.indexOf(' ')
        if (kindEnd <= 0) return ParsedLine.Output(line)
        val kind = body.substring(0, kindEnd)
        val attributes = parseAttributes(body.substring(kindEnd)) ?: return ParsedLine.Output(line)
        val className = attributes["class"] ?: return ParsedLine.Output(line)
        val methodName = attributes["name"] ?: return ParsedLine.Output(line)
        val event = when (kind) {
            "start" -> TestEvent.Start(className, methodName)
            "pass" -> TestEvent.Pass(
                className,
                methodName,
                attributes["duration"]?.toLongOrNull() ?: return ParsedLine.Output(line),
            )
            "fail" -> TestEvent.Fail(
                className,
                methodName,
                attributes["duration"]?.toLongOrNull() ?: return ParsedLine.Output(line),
                attributes["message"] ?: return ParsedLine.Output(line),
                attributes["stack"] ?: return ParsedLine.Output(line),
            )
            "skip" -> TestEvent.Skip(className, methodName)
            "out" -> TestEvent.Out(
                className,
                methodName,
                attributes["stream"] ?: return ParsedLine.Output(line),
                attributes["text"] ?: return ParsedLine.Output(line),
            )
            else -> return ParsedLine.Output(line)
        }
        return ParsedLine.Event(event)
    }

    private fun parseAttributes(input: String): Map<String, String>? {
        val attributes = LinkedHashMap<String, String>()
        var index = 0
        while (index < input.length) {
            if (input[index] != ' ') return null
            index++
            val keyStart = index
            while (index < input.length && (input[index].isLetterOrDigit() || input[index] == '_')) index++
            if (index == keyStart) return null
            val key = input.substring(keyStart, index)
            if (index >= input.length || input[index] != '=') return null
            index++
            if (index >= input.length || input[index] != '\'') return null
            index++
            val value = StringBuilder()
            var closed = false
            while (index < input.length) {
                val character = input[index]
                if (character == '|') {
                    if (index + 1 >= input.length) return null
                    val decoded = when (input[index + 1]) {
                        '|' -> '|'
                        '\'' -> '\''
                        'n' -> '\n'
                        'r' -> '\r'
                        '[' -> '['
                        ']' -> ']'
                        else -> return null
                    }
                    value.append(decoded)
                    index += 2
                } else if (character == '\'') {
                    closed = true
                    index++
                    break
                } else {
                    value.append(character)
                    index++
                }
            }
            if (!closed) return null
            attributes[key] = value.toString()
        }
        return attributes
    }
}
