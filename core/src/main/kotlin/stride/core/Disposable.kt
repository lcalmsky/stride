package stride.core

import java.util.IdentityHashMap
import java.util.logging.Level
import java.util.logging.Logger

interface Disposable {
    fun dispose()
}

object Disposer {
    private val LOG = Logger.getLogger("stride.core.disposer")

    private class Node(val name: String) {
        val children = ArrayList<Disposable>()
        var disposed = false
    }

    private class Handle(private val name: String) : Disposable {
        override fun dispose() {
            Disposer.dispose(this)
        }

        override fun toString(): String = "Disposable($name)"
    }

    private val lock = Any()
    private val nodes = IdentityHashMap<Disposable, Node>()

    fun register(parent: Disposable, child: Disposable) {
        val disposeImmediately = synchronized(lock) {
            val parentNode = nodeFor(parent)
            val childNode = nodeFor(child)
            if (childNode.disposed) return
            if (parentNode.disposed) {
                true
            } else {
                parentNode.children.add(child)
                false
            }
        }
        if (disposeImmediately) dispose(child)
    }

    fun dispose(d: Disposable) {
        val node = synchronized(lock) {
            val n = nodeFor(d)
            if (n.disposed) return
            n.disposed = true
            n
        }
        val children = synchronized(lock) { node.children.toList().asReversed() }
        for (child in children) dispose(child)
        if (d !is Handle) {
            try {
                d.dispose()
            } catch (t: Throwable) {
                LOG.log(Level.WARNING, "Failed to dispose ${d::class.java.name}", t)
            }
        }
    }

    fun newDisposable(name: String = "?"): Disposable {
        val handle = Handle(name)
        synchronized(lock) { nodeFor(handle) }
        return handle
    }

    fun isDisposed(d: Disposable): Boolean = synchronized(lock) {
        nodes[d]?.disposed ?: false
    }

    private fun nodeFor(d: Disposable): Node = nodes.getOrPut(d) { Node(d::class.java.name) }
}
