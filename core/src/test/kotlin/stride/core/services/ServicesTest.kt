package stride.core.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import stride.core.extensions.ExtensionElement
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

@Service
class AppLightService {
    init {
        constructed.incrementAndGet()
    }

    companion object {
        val constructed = AtomicInteger()
    }
}

@Service(Service.Level.PROJECT)
class ProjectLightService(val project: Project)

@Service(Service.Level.PROJECT)
class ProjectOnlyService

@Service
open class OpenService

@Service
class ManagerAwareService(private val manager: ComponentManager) {
    fun manager(): ComponentManager = manager
}

interface GreetingService {
    fun greet(): String
}

class GreetingServiceImpl : GreetingService {
    override fun greet(): String = "hello"
}

class ServicesTest {
    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun reset() {
        Application.resetForTests()
        AppLightService.constructed.set(0)
    }

    @Test
    fun `light service is a lazy singleton per manager`() {
        val first = Application.manager.getService(AppLightService::class)
        val second = Application.manager.getService(AppLightService::class)

        assertSame(first, second)
        assertEquals(1, AppLightService.constructed.get())
    }

    @Test
    fun `project level light service is not available from the application manager`() {
        assertThrows(IllegalStateException::class.java) {
            Application.manager.getService(ProjectOnlyService::class)
        }
    }

    @Test
    fun `project manager creates project service with the project argument`() {
        val project = Project(tempDir)

        val service = project.getService(ProjectLightService::class)

        assertSame(project, service.project)
    }

    @Test
    fun `service constructor can receive the component manager`() {
        val service = Application.manager.getService(ManagerAwareService::class)

        assertSame(Application.manager, service.manager())
    }

    @Test
    fun `descriptor service is resolved by interface key`() {
        Application.extensionArea.registerExtension(
            Application.APPLICATION_SERVICE_EP,
            ExtensionElement(
                "test.services",
                mapOf(
                    "serviceInterface" to GreetingService::class.java.name,
                    "serviceImplementation" to GreetingServiceImpl::class.java.name
                ),
                null
            )
        )

        val service = Application.manager.getService(GreetingService::class)

        assertTrue(service is GreetingServiceImpl)
        assertEquals("hello", service.greet())
    }

    @Test
    fun `open class with Service annotation is not a light service`() {
        assertThrows(IllegalStateException::class.java) {
            Application.manager.getService(OpenService::class)
        }
    }
}
