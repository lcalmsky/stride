package stride.core.services

import stride.core.extensions.ExtensionBean

class ServiceDescriptor : ExtensionBean() {
    val serviceInterface: String? get() = attributes["serviceInterface"]

    val serviceImplementation: String
        get() = attributes["serviceImplementation"]
            ?: throw IllegalStateException("Service descriptor from plugin $pluginId has no serviceImplementation attribute")
}
