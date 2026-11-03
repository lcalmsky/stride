package stride.core.messages

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import stride.core.Disposer

class MessageBusTest {
    private interface Listener {
        fun onEvent(value: String)
    }

    private val topic = Topic("test topic", Listener::class)

    private fun listener(action: (String) -> Unit): Listener = object : Listener {
        override fun onEvent(value: String) {
            action(value)
        }
    }

    @Test
    fun `subscribers are notified in subscription order`() {
        val bus = MessageBusImpl()
        val connection = bus.connect(Disposer.newDisposable())
        val received = ArrayList<String>()
        connection.subscribe(topic, listener { received.add("first:$it") })
        connection.subscribe(topic, listener { received.add("second:$it") })

        bus.syncPublisher(topic).onEvent("x")

        assertEquals(listOf("first:x", "second:x"), received)
    }

    @Test
    fun `disposed connection unsubscribes`() {
        val bus = MessageBusImpl()
        val connection = bus.connect(Disposer.newDisposable())
        var called = 0
        connection.subscribe(topic, listener { called++ })

        connection.dispose()
        bus.syncPublisher(topic).onEvent("x")

        assertEquals(0, called)
    }

    @Test
    fun `exception in one subscriber does not stop the others`() {
        val bus = MessageBusImpl()
        val connection = bus.connect(Disposer.newDisposable())
        val received = ArrayList<String>()
        connection.subscribe(topic, listener { throw IllegalStateException("boom") })
        connection.subscribe(topic, listener { received.add(it) })

        bus.syncPublisher(topic).onEvent("x")

        assertEquals(listOf("x"), received)
    }

    @Test
    fun `connection is disposed together with its parent disposable`() {
        val bus = MessageBusImpl()
        val parent = Disposer.newDisposable("parent")
        val connection = bus.connect(parent)
        var called = 0
        connection.subscribe(topic, listener { called++ })

        Disposer.dispose(parent)
        bus.syncPublisher(topic).onEvent("x")

        assertEquals(0, called)
    }
}
