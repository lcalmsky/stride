package stride.core.extensions

import stride.core.internal.Reflection
import stride.core.services.Application
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.reflect.KClass

class ExtensionPointName<T : Any>(val name: String) {
    fun extensionList(area: ExtensionArea = Application.extensionArea): List<T> = area.getExtensionList(name)

    fun findFirst(area: ExtensionArea = Application.extensionArea, predicate: (T) -> Boolean): T? =
        extensionList(area).firstOrNull(predicate)
}

class BeanExtensionPointName<B : ExtensionBean>(val name: String) {
    fun beans(area: ExtensionArea = Application.extensionArea): List<B> = area.getExtensionList(name)

    fun forKey(key: String, area: ExtensionArea = Application.extensionArea): List<B> =
        beans(area).filter { it.key == key }
}

abstract class ExtensionBean {
    lateinit var attributes: Map<String, String>
    lateinit var pluginId: String

    open val key: String? get() = null

    private val instances = HashMap<String, Any>()

    fun <T : Any> instance(type: KClass<T>): T {
        val className = attributes["implementationClass"]
            ?: throw IllegalStateException("${this::class.qualifiedName} has no implementationClass attribute")
        synchronized(instances) {
            val instance = instances[className]
                ?: Reflection.instantiate(Reflection.loadClass(className, ExtensionBean::class.java.classLoader))
                    .also { instances[className] = it }
            if (!type.java.isInstance(instance)) {
                throw IllegalStateException("${instance::class.java.name} is not a ${type.java.name}")
            }
            @Suppress("UNCHECKED_CAST")
            return instance as T
        }
    }
}

class ExtensionArea {
    private val LOG = Logger.getLogger("stride.core.extensions")

    private class PointState(val descriptor: ExtensionPointDescriptor) {
        val extensions = ArrayList<ExtensionElement>()
        var cached: List<Any>? = null
        var initializing = false
    }

    private class CyclicInitializationException(name: String) :
        IllegalStateException("Cyclic extension initialization: $name")

    private val lock = Any()
    private val points = LinkedHashMap<String, PointState>()

    fun registerExtensionPoint(desc: ExtensionPointDescriptor) {
        synchronized(lock) {
            if (points.containsKey(desc.qualifiedName)) {
                throw IllegalStateException("Extension point already registered: ${desc.qualifiedName}")
            }
            points[desc.qualifiedName] = PointState(desc)
        }
    }

    fun registerExtension(epName: String, element: ExtensionElement) {
        val point = synchronized(lock) { points[epName] }
        if (point == null) {
            LOG.warning("Extension for unknown extension point $epName from plugin ${element.pluginId} skipped")
            return
        }
        synchronized(point) {
            if (point.cached != null) {
                LOG.warning("Extension registered to already initialized extension point $epName")
            }
            point.extensions.add(element)
        }
    }

    fun hasExtensionPoint(name: String): Boolean = synchronized(lock) { points.containsKey(name) }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getExtensionList(name: String): List<T> {
        val point = synchronized(lock) { points[name] } ?: return emptyList()
        point.cached?.let { return it as List<T> }
        synchronized(point) {
            point.cached?.let { return it as List<T> }
            if (point.initializing) throw CyclicInitializationException(name)
            point.initializing = true
            try {
                val instances = ArrayList<Any>()
                for (element in ordered(point.extensions)) {
                    createInstance(point.descriptor, element)?.let { instances.add(it) }
                }
                val result: List<Any> = instances
                point.cached = result
                return result as List<T>
            } finally {
                point.initializing = false
            }
        }
    }

    private fun ordered(extensions: List<ExtensionElement>): List<ExtensionElement> {
        val first = ArrayList<ExtensionElement>()
        val normal = ArrayList<ExtensionElement>()
        val last = ArrayList<ExtensionElement>()
        for (extension in extensions) {
            when (extension.order) {
                "first" -> first.add(extension)
                "last" -> last.add(extension)
                else -> normal.add(extension)
            }
        }
        return first + normal + last
    }

    private fun createInstance(descriptor: ExtensionPointDescriptor, element: ExtensionElement): Any? {
        return try {
            val beanClass = descriptor.beanClass
            if (beanClass != null) {
                val bean = Reflection.instantiate(Reflection.loadClass(beanClass, ExtensionBean::class.java.classLoader))
                if (bean !is ExtensionBean) {
                    throw IllegalStateException("$beanClass is not an ExtensionBean")
                }
                bean.attributes = element.attributes
                bean.pluginId = element.pluginId
                bean
            } else {
                val implementation = element.attributes["implementation"]
                    ?: throw IllegalStateException("Extension has no implementation attribute")
                Reflection.instantiate(Reflection.loadClass(implementation))
            }
        } catch (e: CyclicInitializationException) {
            throw e
        } catch (t: Throwable) {
            LOG.log(
                Level.WARNING,
                "Failed to instantiate extension for ${descriptor.qualifiedName} from plugin ${element.pluginId}",
                t
            )
            null
        }
    }
}

data class ExtensionPointDescriptor(
    val qualifiedName: String,
    val interfaceClass: String?,
    val beanClass: String?,
    val pluginId: String
)

data class ExtensionElement(
    val pluginId: String,
    val attributes: Map<String, String>,
    val order: String?
)
