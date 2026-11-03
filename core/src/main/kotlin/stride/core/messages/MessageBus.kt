package stride.core.messages

import stride.core.Disposable
import stride.core.Disposer
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.reflect.KClass

class Topic<L : Any>(val displayName: String, val listenerClass: KClass<L>)

interface MessageBus : Disposable {
    fun connect(parentDisposable: Disposable): MessageBusConnection

    fun <L : Any> syncPublisher(topic: Topic<L>): L
}

interface MessageBusConnection : Disposable {
    fun <L : Any> subscribe(topic: Topic<L>, handler: L)
}

class MessageBusImpl : MessageBus {
    private val LOG = Logger.getLogger("stride.core.messages")

    private class Subscription(val owner: MessageBusConnection, val handler: Any)

    private val lock = Any()
    private val subscriptions = HashMap<Topic<*>, MutableList<Subscription>>()
    private val publishers = HashMap<Topic<*>, Any>()
    private var disposed = false

    private inner class ConnectionImpl : MessageBusConnection {
        private var active = true

        override fun <L : Any> subscribe(topic: Topic<L>, handler: L) {
            synchronized(lock) {
                if (!active || disposed) throw IllegalStateException("Message bus connection is disposed")
                subscriptions.getOrPut(topic) { ArrayList() }.add(Subscription(this, handler))
            }
        }

        override fun dispose() {
            synchronized(lock) {
                if (!active) return
                active = false
                for (list in subscriptions.values) list.removeAll { it.owner === this }
                subscriptions.entries.removeAll { it.value.isEmpty() }
            }
        }
    }

    override fun connect(parentDisposable: Disposable): MessageBusConnection {
        val connection = ConnectionImpl()
        synchronized(lock) {
            if (disposed) throw IllegalStateException("Message bus is disposed")
        }
        Disposer.register(parentDisposable, connection)
        return connection
    }

    @Suppress("UNCHECKED_CAST")
    override fun <L : Any> syncPublisher(topic: Topic<L>): L {
        synchronized(lock) {
            publishers[topic]?.let { return it as L }
            val proxy = Proxy.newProxyInstance(
                topic.listenerClass.java.classLoader ?: MessageBusImpl::class.java.classLoader,
                arrayOf(topic.listenerClass.java)
            ) { _, method, arguments -> dispatch(topic, method, arguments) }
            publishers[topic] = proxy
            return proxy as L
        }
    }

    private fun dispatch(topic: Topic<*>, method: Method, arguments: Array<out Any?>?): Any? {
        when {
            method.name == "toString" && method.parameterCount == 0 ->
                return "MessageBusPublisher(${topic.displayName})"
            method.name == "hashCode" && method.parameterCount == 0 ->
                return System.identityHashCode(topic)
            method.name == "equals" && method.parameterCount == 1 ->
                return arguments?.get(0) === this
        }
        val handlers = synchronized(lock) { subscriptions[topic]?.map { it.handler } ?: emptyList() }
        val callArguments = arguments ?: emptyArray<Any?>()
        for (handler in handlers) {
            try {
                method.invoke(handler, *callArguments)
            } catch (e: InvocationTargetException) {
                LOG.log(Level.WARNING, "Subscriber failed for ${topic.displayName}", e.targetException)
            } catch (t: Throwable) {
                LOG.log(Level.WARNING, "Subscriber failed for ${topic.displayName}", t)
            }
        }
        return defaultValue(method.returnType)
    }

    private fun defaultValue(type: Class<*>): Any? = when {
        type == java.lang.Void.TYPE -> null
        type == java.lang.Boolean.TYPE -> false
        type == java.lang.Byte.TYPE -> 0.toByte()
        type == java.lang.Short.TYPE -> 0.toShort()
        type == java.lang.Integer.TYPE -> 0
        type == java.lang.Long.TYPE -> 0L
        type == java.lang.Float.TYPE -> 0f
        type == java.lang.Double.TYPE -> 0.0
        type == java.lang.Character.TYPE -> '\u0000'
        else -> null
    }

    override fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            subscriptions.clear()
            publishers.clear()
        }
    }
}
