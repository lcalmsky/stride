package stride.core.plugins

import stride.core.extensions.ExtensionArea
import java.util.logging.Level
import java.util.logging.Logger

data class LoadResult(
    val enabled: List<PluginDescriptor>,
    val disabled: Map<String, String>
)

class PluginManager(private val classLoader: ClassLoader) {
    private val log = Logger.getLogger("stride.core.plugins")

    fun loadAll(area: ExtensionArea): LoadResult {
        val descriptors = ArrayList<PluginDescriptor>()
        val disabled = LinkedHashMap<String, String>()
        val ids = HashSet<String>()

        val resources = classLoader.getResources("META-INF/stride-plugin.xml").toList()
        for (url in resources) {
            val descriptor = try {
                url.openStream().use { PluginDescriptorParser.parse(it, url.toString()) }
            } catch (t: Throwable) {
                disabled[url.toString()] = "Malformed descriptor: ${t.message ?: t::class.java.simpleName}"
                log.log(Level.WARNING, "Failed to parse plugin descriptor $url", t)
                continue
            }
            if (!ids.add(descriptor.id)) {
                disabled[descriptor.id] = "Duplicate plugin id"
                continue
            }
            descriptors.add(descriptor)
        }

        val enabled = LinkedHashMap<String, PluginDescriptor>()
        descriptors.forEach { enabled[it.id] = it }
        disableWithMissingDependencies(enabled, disabled)

        val cycleMembers = findCycleMembers(enabled)
        for (id in cycleMembers) {
            enabled.remove(id)
            disabled[id] = "Dependency cycle"
        }
        disableWithMissingDependencies(enabled, disabled)

        val ordered = topologicalOrder(enabled)

        for (descriptor in ordered) {
            for (extensionPoint in descriptor.extensionPoints) {
                try {
                    area.registerExtensionPoint(extensionPoint)
                } catch (e: IllegalStateException) {
                    log.warning("Failed to register extension point ${extensionPoint.qualifiedName} from ${descriptor.id}: ${e.message}")
                }
            }
        }
        for (descriptor in ordered) {
            for ((extensionPointName, element) in descriptor.extensions) {
                if (!area.hasExtensionPoint(extensionPointName)) {
                    log.warning("Extension point $extensionPointName is not registered; extension from ${descriptor.id} skipped")
                    continue
                }
                area.registerExtension(extensionPointName, element)
            }
        }

        return LoadResult(ordered, disabled)
    }

    private fun disableWithMissingDependencies(
        enabled: MutableMap<String, PluginDescriptor>,
        disabled: MutableMap<String, String>
    ) {
        var changed = true
        while (changed) {
            changed = false
            val iterator = enabled.values.iterator()
            while (iterator.hasNext()) {
                val descriptor = iterator.next()
                val missing = descriptor.depends.firstOrNull { dep ->
                    dep != CORE_PLUGIN_ID && !enabled.containsKey(dep)
                }
                if (missing != null) {
                    iterator.remove()
                    disabled[descriptor.id] = "Missing dependency: $missing"
                    changed = true
                }
            }
        }
    }

    private fun findCycleMembers(enabled: Map<String, PluginDescriptor>): Set<String> {
        val colors = HashMap<String, Int>()
        val stack = ArrayList<String>()
        val cycleMembers = LinkedHashSet<String>()

        fun visit(id: String) {
            colors[id] = GRAY
            stack.add(id)
            val descriptor = enabled[id]
            if (descriptor != null) {
                for (dependency in descriptor.depends) {
                    if (!enabled.containsKey(dependency)) continue
                    when (colors[dependency]) {
                        null -> visit(dependency)
                        GRAY -> {
                            val start = stack.indexOf(dependency)
                            if (start >= 0) cycleMembers.addAll(stack.subList(start, stack.size))
                        }
                    }
                }
            }
            stack.removeAt(stack.lastIndex)
            colors[id] = BLACK
        }

        for (id in enabled.keys) {
            if (colors[id] == null) visit(id)
        }
        return cycleMembers
    }

    private fun topologicalOrder(enabled: Map<String, PluginDescriptor>): List<PluginDescriptor> {
        val visited = HashSet<String>()
        val result = ArrayList<PluginDescriptor>()

        fun visit(id: String) {
            if (!visited.add(id)) return
            val descriptor = enabled[id] ?: return
            for (dependency in descriptor.depends) {
                if (enabled.containsKey(dependency)) visit(dependency)
            }
            result.add(descriptor)
        }

        for (id in enabled.keys) visit(id)
        return result
    }

    private companion object {
        const val CORE_PLUGIN_ID = "stride.core"
        const val GRAY = 1
        const val BLACK = 2
    }
}
