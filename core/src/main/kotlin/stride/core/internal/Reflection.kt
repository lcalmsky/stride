package stride.core.internal

import java.lang.reflect.InvocationTargetException

internal object Reflection {
    fun loadClass(name: String, fallback: ClassLoader? = null): Class<*> {
        val loader = Thread.currentThread().contextClassLoader
            ?: fallback
            ?: Reflection::class.java.classLoader
        return Class.forName(name, true, loader)
    }

    fun instantiate(cls: Class<*>): Any {
        try {
            val singleton = cls.getField("INSTANCE").get(null)
            if (singleton != null) return singleton
        } catch (_: NoSuchFieldException) {
        }
        val constructor = cls.getDeclaredConstructor()
        constructor.isAccessible = true
        try {
            return constructor.newInstance()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
