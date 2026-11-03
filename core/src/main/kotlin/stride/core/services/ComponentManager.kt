package stride.core.services

import stride.core.Disposable
import stride.core.Disposer
import stride.core.internal.Reflection
import stride.core.messages.MessageBus
import stride.core.messages.MessageBusImpl
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.reflect.KClass

open class ComponentManager(val parent: ComponentManager?, val level: Service.Level) : Disposable {
    private val LOG = Logger.getLogger("stride.core.services")

    private val lock = Any()
    private val services = HashMap<String, Any>()
    private val constructing = HashSet<String>()
    private var disposed = false

    val messageBus: MessageBus = MessageBusImpl()

    fun <T : Any> getService(type: KClass<T>): T {
        val key = type.qualifiedName ?: type.java.name
        synchronized(lock) {
            if (disposed) throw IllegalStateException("ComponentManager is disposed")
            services[key]?.let { return type.java.cast(it) }
            if (!constructing.add(key)) throw IllegalStateException("Cyclic service initialization: $key")
            val created = try {
                createService(type, key)
            } finally {
                constructing.remove(key)
            }
            if (created != null) {
                services[key] = created
                return type.java.cast(created)
            }
        }
        val parentManager = parent ?: throw IllegalStateException("Service not found: $key")
        return parentManager.getService(type)
    }

    private fun createService(type: KClass<*>, key: String): Any? {
        findDescriptor(key)?.let { descriptor ->
            val implementation = Reflection.loadClass(descriptor.serviceImplementation, ComponentManager::class.java.classLoader)
            return instantiateService(implementation)
        }
        if (isLightService(type)) return instantiateService(type.java)
        return null
    }

    private fun findDescriptor(key: String): ServiceDescriptor? {
        val extensionPoint = if (level == Service.Level.PROJECT) {
            Application.PROJECT_SERVICE_EP
        } else {
            Application.APPLICATION_SERVICE_EP
        }
        return Application.extensionArea.getExtensionList<ServiceDescriptor>(extensionPoint)
            .firstOrNull { (it.serviceInterface ?: it.serviceImplementation) == key }
    }

    private fun isLightService(type: KClass<*>): Boolean {
        val annotation = type.java.getAnnotation(Service::class.java) ?: return false
        if (!Modifier.isFinal(type.java.modifiers)) return false
        return annotation.value.contains(level)
    }

    private fun instantiateService(cls: Class<*>): Any {
        try {
            val singleton = cls.getField("INSTANCE").get(null)
            if (singleton != null && cls.isInstance(singleton)) return singleton
        } catch (_: NoSuchFieldException) {
        }

        val constructor = cls.declaredConstructors.firstOrNull { it.parameterCount == 0 }
            ?: cls.declaredConstructors.firstOrNull { candidate ->
                candidate.parameterCount == 1 && candidate.parameterTypes[0].isInstance(this)
            }
            ?: throw IllegalStateException(
                "Service ${cls.name} must have a no-arg constructor or a single ComponentManager/Project parameter"
            )
        constructor.isAccessible = true
        val arguments: Array<Any?> = if (constructor.parameterCount == 0) emptyArray() else arrayOf(this)
        try {
            return constructor.newInstance(*arguments)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    override fun dispose() {
        val toDispose: List<Any>
        synchronized(lock) {
            if (disposed) return
            disposed = true
            toDispose = services.values.toList()
            services.clear()
        }
        messageBus.dispose()
        for (service in toDispose) {
            if (service is Disposable) {
                try {
                    Disposer.dispose(service)
                } catch (t: Throwable) {
                    LOG.log(Level.WARNING, "Failed to dispose service ${service::class.java.name}", t)
                }
            }
        }
    }
}

inline fun <reified T : Any> ComponentManager.service(): T = getService(T::class)
