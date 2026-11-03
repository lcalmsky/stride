# stride core public API

This document is the contract for `:core`. Signatures below are the public Kotlin API of
`core/src/main/kotlin`, exactly as implemented. Other modules must depend on these
signatures and on the plugin descriptor format described at the end.

## Module layout

```
:core                  stride.core.*          (this document)
:lang                  empty, depends on :core
:search                empty, depends on :core, :lang
:plugins:lang-kotlin   empty, depends on :core, :lang
:plugins:lang-json     empty, depends on :core, :lang
:app                   Compose Desktop, depends on all of the above
```

`rootProject.name = "stride"`. All modules use JVM toolchain 21.

## `stride.core`

```kotlin
interface Disposable {
    fun dispose()
}

object Disposer {
    fun register(parent: Disposable, child: Disposable)
    fun dispose(d: Disposable)
    fun newDisposable(name: String = "?"): Disposable
    fun isDisposed(d: Disposable): Boolean
}
```

- `register` links a child to a parent. When the parent is disposed, its children are
  disposed first, in reverse registration order.
- `dispose` is idempotent. Disposing through `Disposer` guarantees at most one call to a
  child's `dispose()`.
- Registering a child on an already disposed parent disposes the child immediately.

## `stride.core.extensions`

```kotlin
class ExtensionPointName<T : Any>(val name: String) {
    fun extensionList(area: ExtensionArea = Application.extensionArea): List<T>
    fun findFirst(area: ExtensionArea = Application.extensionArea, predicate: (T) -> Boolean): T?
}

class BeanExtensionPointName<B : ExtensionBean>(val name: String) {
    fun beans(area: ExtensionArea = Application.extensionArea): List<B>
    fun forKey(key: String, area: ExtensionArea = Application.extensionArea): List<B>
}

abstract class ExtensionBean {
    lateinit var attributes: Map<String, String>
    lateinit var pluginId: String
    open val key: String? get() = null
    fun <T : Any> instance(type: KClass<T>): T
}

class ExtensionArea {
    fun registerExtensionPoint(desc: ExtensionPointDescriptor)
    fun registerExtension(epName: String, element: ExtensionElement)
    fun hasExtensionPoint(name: String): Boolean
    fun <T : Any> getExtensionList(name: String): List<T>
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
```

- `registerExtensionPoint` throws `IllegalStateException` when the qualified name is
  already registered.
- `registerExtension` for an unknown extension point logs a warning and is a no-op.
- `getExtensionList` returns an empty list for an unregistered name.
- Instances are created lazily on the first `getExtensionList` call and cached. Creation
  runs under a per-extension-point lock.
- Re-entering an extension point that is being initialized throws
  `IllegalStateException("Cyclic extension initialization: <qualifiedName>")`.
- A class that cannot be loaded or instantiated is logged through
  `java.util.logging` and skipped; it does not fail the list.
- Registration order is preserved, with `order="first"` elements moved to the front and
  `order="last"` elements moved to the back (each group keeps its registration order).
- Interface extension points instantiate the class named by the extension element's
  `implementation` attribute.
- Bean extension points instantiate `ExtensionPointDescriptor.beanClass`, then set
  `attributes` and `pluginId` on the bean. `ExtensionBean.instance(type)` lazily
  instantiates `attributes["implementationClass"]` (Kotlin `object` or no-arg
  constructor) and caches it per bean.
- `ExtensionBean.key` is the bean's key for `forKey`; subclasses override it and usually
  read one of their attributes.

## `stride.core.plugins`

```kotlin
data class PluginDescriptor(
    val id: String,
    val name: String,
    val depends: List<String>,
    val extensionPoints: List<ExtensionPointDescriptor>,
    val extensions: List<Pair<String, ExtensionElement>>,
    val source: String
)

object PluginDescriptorParser {
    fun parse(xml: InputStream, source: String): PluginDescriptor
}

class PluginManager(private val classLoader: ClassLoader) {
    fun loadAll(area: ExtensionArea): LoadResult
}

data class LoadResult(
    val enabled: List<PluginDescriptor>,
    val disabled: Map<String, String>
)
```

`loadAll` finds every `META-INF/stride-plugin.xml` through
`classLoader.getResources(...)` and then:

1. parses all descriptors (StAX only). A malformed descriptor disables only that plugin;
   its key in `disabled` is the resource URL, because no id can be read.
2. keeps the first descriptor per id and disables later duplicates with reason
   `Duplicate plugin id`.
3. disables plugins with a dependency that is not `stride.core` and not enabled, with
   reason `Missing dependency: <id>`. Dependency on `stride.core` is always satisfied.
4. runs a DFS topological sort on `depends`; every plugin that is part of a dependency
   cycle is disabled with reason `Dependency cycle`. Dependants of a disabled plugin are
   then disabled with `Missing dependency: <id>`.
5. registers all extension points of enabled plugins (in topological order), then all
   their extensions. Extensions for unknown extension points are logged and skipped.

`enabled` is in topological order (dependencies first). `disabled` maps plugin id (or
resource URL for an unparsable descriptor) to a human-readable reason.

## `stride.core.services`

```kotlin
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Service(vararg val value: Level = [Level.APP]) {
    enum class Level { APP, PROJECT }
}

open class ComponentManager(val parent: ComponentManager?, val level: Service.Level) : Disposable {
    fun <T : Any> getService(type: KClass<T>): T
    val messageBus: MessageBus
}

inline fun <reified T : Any> ComponentManager.service(): T

class ServiceDescriptor : ExtensionBean() {
    val serviceInterface: String?
    val serviceImplementation: String
}

object Application {
    const val APPLICATION_SERVICE_EP = "stride.applicationService"
    const val PROJECT_SERVICE_EP = "stride.projectService"
    const val CORE_PLUGIN_ID = "stride.core"

    val extensionArea: ExtensionArea
    val manager: ComponentManager
    fun init(classLoader: ClassLoader = <context class loader>): LoadResult
    fun resetForTests()
}

class Project(val root: Path) : ComponentManager(Application.manager, Service.Level.PROJECT)
```

Service resolution in `getService(type)`:

1. returned from the manager's cache if already constructed;
2. otherwise a descriptor from the matching extension point is used
   (`stride.applicationService` for `APP`, `stride.projectService` for `PROJECT`). A
   descriptor matches when `(serviceInterface ?: serviceImplementation)` equals the
   qualified name of the requested type;
3. otherwise the requested class is used as a light service when it is `final` and
   annotated `@Service` with a `Level` equal to the manager's level;
4. a `PROJECT` manager that finds nothing delegates to its parent (`Application.manager`);
   an `APP` manager throws `IllegalStateException("Service not found: <qualifiedName>")`.

Service constructors may be no-arg, or take one parameter that accepts the manager
(`ComponentManager`), which lets project services declare a `Project` parameter. Services
are constructed once per manager; construction is guarded against cyclic initialization.
`ComponentManager.dispose()` disposes its message bus and every constructed service that
implements `Disposable`.

`Application.init` replaces the current extension area and application manager, registers
the two service bean extension points (`stride.applicationService`, `stride.projectService`)
with plugin id `stride.core`, then loads plugins from the given class loader.
`Application.resetForTests` restores a clean extension area (including those two core
extension points) and a fresh application manager.

## `stride.core.messages`

```kotlin
class Topic<L : Any>(val displayName: String, val listenerClass: KClass<L>)

interface MessageBus : Disposable {
    fun connect(parentDisposable: Disposable): MessageBusConnection
    fun <L : Any> syncPublisher(topic: Topic<L>): L
}

interface MessageBusConnection : Disposable {
    fun <L : Any> subscribe(topic: Topic<L>, handler: L)
}

class MessageBusImpl : MessageBus
```

- Each `ComponentManager` owns one bus (`messageBus`). There is no parent/child broadcast.
- `connect(parentDisposable)` creates a connection registered with the given
  `Disposable`; disposing the parent (through `Disposer`) disposes the connection and
  removes its subscriptions.
- `syncPublisher(topic)` returns a proxy implementing `topic.listenerClass`. Each call
  synchronously invokes all current subscribers for that topic in subscription order.
- An exception thrown by one subscriber is logged and does not stop the remaining
  subscribers. Publishing a method with a non-`Unit` return type returns the type's
  default value.

## `stride.core.vfs`

```kotlin
class VFile(val path: Path) {
    val name: String
    val extension: String?
    val isDirectory: Boolean
    fun children(): List<VFile>
    fun readText(): String
    fun writeText(s: String)
    fun length(): Long
}

sealed interface VFileEvent {
    val file: VFile
    data class Created(override val file: VFile) : VFileEvent
    data class Deleted(override val file: VFile) : VFileEvent
    data class Changed(override val file: VFile) : VFileEvent
}

interface VfsListener {
    fun after(events: List<VFileEvent>)
}

val VFS_CHANGES: Topic<VfsListener>

@Service(Service.Level.PROJECT)
class VirtualFileManager(project: Project) : Disposable {
    fun start()
    fun findFile(path: Path): VFile?
}
```

- `VFile.name` is the last path element. `VFile.extension` is the text after the last
  dot, or `null` for a dotfile (`.gitignore`) or a name without a dot.
- `children()` returns direct children sorted directories-first, then by
  case-insensitive name; an unreadable or non-directory path yields an empty list.
- `VirtualFileManager` is a project light service; obtain it with
  `project.getService(VirtualFileManager::class)` or `project.service<VirtualFileManager>()`.
- `start()` registers a recursive `WatchService` on `project.root` and starts a daemon
  thread. New subdirectories are registered when they appear. Created, deleted, and
  modified entries are published as one `List<VFileEvent>` per watch-key batch on
  `project.messageBus` via `VFS_CHANGES`.
- A relative path passed to `findFile` is resolved against `project.root`; a missing path
  returns `null`.
- Disposing the manager (directly or through `Project.dispose()`) stops the watcher.

## Plugin descriptor XML

A plugin jar/directory contains `META-INF/stride-plugin.xml`:

```xml
<stride-plugin>
  <id>stride.lang.kotlin</id>
  <name>Kotlin</name>
  <depends>stride.core</depends>            <!-- 0..n -->
  <depends>stride.lang</depends>
  <extensionPoints>
    <extensionPoint name="fileType" beanClass="stride.lang.FileTypeBean"/>
    <extensionPoint qualifiedName="stride.searchEverywhereContributor" interface="stride.search.Contributor"/>
  </extensionPoints>
  <extensions defaultExtensionNs="stride">
    <fileType name="Kotlin" extensions="kt;kts" implementationClass="stride.lang.KotlinFileType" order="first"/>
  </extensions>
</stride-plugin>
```

Rules:

- `<id>` is required. `<name>` defaults to the id when absent. `<depends>` may repeat.
- `<extensionPoints>` declares extension points:
  - `qualifiedName="..."` is used verbatim;
  - `name="x"` becomes `<pluginId>.x`, except plugin id `stride.core`, which uses prefix
    `stride` (so `stride.fileType`);
  - `beanClass="..."` declares a bean extension point, `interface="..."` declares an
    interface extension point.
- `<extensions defaultExtensionNs="stride">` contains extension elements. Each direct
  child element is registered to the extension point
  `<defaultExtensionNs>.<element name>`; `defaultExtensionNs` defaults to `stride`.
- All XML attributes of the extension element (read by local name) become
  `ExtensionElement.attributes`. `order="first"` / `order="last"` controls ordering.
- For an interface extension point the instantiated class is the `implementation`
  attribute. For a bean extension point the bean is `beanClass`; the bean can lazily
  instantiate `implementationClass` with `ExtensionBean.instance(...)`.
- Plugin ids are unique across the class path. `stride.core` is always considered
  present as a dependency; the `app` module ships the `stride.core` descriptor, which
  declares the shared extension points, and that descriptor must not redeclare
  `stride.applicationService` / `stride.projectService` (registered in code by `Application`).
