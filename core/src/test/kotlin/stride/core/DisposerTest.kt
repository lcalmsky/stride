package stride.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class DisposerTest {
    private fun recording(log: MutableList<String>, name: String): Disposable = object : Disposable {
        override fun dispose() {
            log.add(name)
        }
    }

    @Test
    fun `children are disposed before parent in reverse registration order`() {
        val log = ArrayList<String>()
        val parent = Disposer.newDisposable("parent")
        val first = recording(log, "first")
        val second = recording(log, "second")
        Disposer.register(parent, first)
        Disposer.register(parent, second)

        Disposer.dispose(parent)

        assertEquals(listOf("second", "first"), log)
        assertTrue(Disposer.isDisposed(parent))
        assertTrue(Disposer.isDisposed(first))
        assertTrue(Disposer.isDisposed(second))
    }

    @Test
    fun `nested children are disposed before their own parent`() {
        val log = ArrayList<String>()
        val parent = Disposer.newDisposable("parent")
        val child = recording(log, "child")
        val grandchild = recording(log, "grandchild")
        Disposer.register(parent, child)
        Disposer.register(child, grandchild)

        Disposer.dispose(parent)

        assertEquals(listOf("grandchild", "child"), log)
    }

    @Test
    fun `dispose is idempotent`() {
        val count = AtomicInteger()
        val disposable = object : Disposable {
            override fun dispose() {
                count.incrementAndGet()
            }
        }

        Disposer.dispose(disposable)
        Disposer.dispose(disposable)

        assertEquals(1, count.get())
        assertTrue(Disposer.isDisposed(disposable))
    }
}
