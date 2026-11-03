package stride.app.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import java.nio.charset.Charset

/** A [TtyConnector] fed by a string; records what the terminal writes back. */
class FakeTtyConnector(initial: String = "") : TtyConnector {
    val written = StringBuilder()
    val resizes = mutableListOf<Pair<Int, Int>>()

    @Volatile
    var closed = false
        private set

    private val lock = Object()
    private val pending = StringBuilder(initial)
    private var offset = 0

    fun feed(text: String) {
        synchronized(lock) {
            pending.append(text)
            lock.notifyAll()
        }
    }

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        synchronized(lock) {
            while (this.offset >= pending.length) {
                if (closed) return -1
                lock.wait()
            }
            val count = minOf(length, pending.length - this.offset)
            pending.getChars(this.offset, this.offset + count, buf, offset)
            this.offset += count
            return count
        }
    }

    override fun write(bytes: ByteArray) {
        written.append(String(bytes, Charset.forName("UTF-8")))
    }

    override fun write(string: String) {
        written.append(string)
    }

    override fun isConnected(): Boolean = !closed

    override fun resize(termSize: TermSize) {
        resizes += termSize.columns to termSize.rows
    }

    override fun waitFor(): Int = 0

    override fun ready(): Boolean = true

    override fun getName(): String = "fake"

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }
}
