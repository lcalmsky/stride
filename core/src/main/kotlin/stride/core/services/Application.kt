package stride.core.services

import stride.core.extensions.ExtensionArea
import stride.core.extensions.ExtensionPointDescriptor
import stride.core.plugins.LoadResult
import stride.core.plugins.PluginManager
import java.nio.file.Path

object Application {
    const val APPLICATION_SERVICE_EP = "stride.applicationService"
    const val PROJECT_SERVICE_EP = "stride.projectService"
    const val CORE_PLUGIN_ID = "stride.core"

    private val lock = Any()

    @Volatile
    private var area: ExtensionArea = createArea()

    @Volatile
    private var managerRef: ComponentManager = ComponentManager(null, Service.Level.APP)

    val extensionArea: ExtensionArea get() = area

    val manager: ComponentManager get() = managerRef

    fun init(classLoader: ClassLoader = defaultClassLoader()): LoadResult {
        synchronized(lock) {
            area = createArea()
            managerRef = ComponentManager(null, Service.Level.APP)
            return PluginManager(classLoader).loadAll(area)
        }
    }

    fun resetForTests() {
        synchronized(lock) {
            area = createArea()
            managerRef = ComponentManager(null, Service.Level.APP)
        }
    }

    private fun createArea(): ExtensionArea = ExtensionArea().apply {
        registerExtensionPoint(
            ExtensionPointDescriptor(APPLICATION_SERVICE_EP, null, ServiceDescriptor::class.java.name, CORE_PLUGIN_ID)
        )
        registerExtensionPoint(
            ExtensionPointDescriptor(PROJECT_SERVICE_EP, null, ServiceDescriptor::class.java.name, CORE_PLUGIN_ID)
        )
    }

    private fun defaultClassLoader(): ClassLoader =
        Thread.currentThread().contextClassLoader ?: Application::class.java.classLoader
}

class Project(val root: Path) : ComponentManager(Application.manager, Service.Level.PROJECT)
