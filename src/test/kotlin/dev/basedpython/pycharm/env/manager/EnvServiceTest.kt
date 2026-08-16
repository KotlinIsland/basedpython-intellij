package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.project.Project
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The service the platform actually hands out, driven against a project on disk.
 *
 * Every other test here calls the pure pieces directly, which says the pieces are right and nothing
 * about whether the platform can build the thing that uses them. A project-level `@Service` taking a
 * [kotlinx.coroutines.CoroutineScope] is instantiated reflectively at first use, so a constructor
 * the platform cannot satisfy is not a compile error — it is an exception the first time a user
 * opens the tool window.
 *
 * Scanning is driven synchronously through the same code the background refresh runs, rather than
 * by starting one and waiting: a test that polls a coroutine is a test that fails on a slow machine.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class EnvServiceTest {

    private val fixture by codeInsightFixture()
    private val project: Project get() = fixture.project

    private fun base(): Path? = project.basePath?.let { Path.of(it) }?.takeIf { Files.isDirectory(it) }

    @Test
    fun `the platform can build the service`() {
        val service = EnvService.getInstance(project)
        assertNotNull(service)
        assertSame(service, EnvService.getInstance(project), "project services are singletons")
    }

    /** Before anything has looked, "not scanned yet" must be distinguishable from "nothing here". */
    @Test
    fun `the initial status claims nothing`() {
        val service = EnvService.getInstance(project)
        assertEquals(EnvDrift.UNKNOWN, service.status.drift)
        assertEquals(emptyList<EnvPackage>(), service.status.packages)
    }

    @Test
    fun `a project with no manifest is unmanaged`() {
        val base = base()
        assumeTrue(base != null, "the fixture project has no directory on disk")
        requireNotNull(base)
        assumeTrue(EnvBackends.ALL_MARKERS.none { Files.exists(base.resolve(it)) }, "fixture already has a manifest")

        assertNull(EnvBackends.detect(base))
        assertEquals(EnvHealth.UNMANAGED, EnvStatus.unknown(base).health)
    }

    /**
     * The state that has to be reachable without a restart: a project that grows its first manifest.
     * Detection is a file check, so writing one is the whole change.
     */
    @Test
    fun `writing a manifest is what makes a project managed`() {
        val base = base()
        assumeTrue(base != null, "the fixture project has no directory on disk")
        requireNotNull(base)

        val manifest = base.resolve("pyproject.toml")
        val existed = Files.exists(manifest)
        try {
            Files.writeString(manifest, "[project]\nname = \"fixture\"\nversion = \"0.1.0\"\n")
            assertSame(UvBackend, EnvBackends.detect(base))
            assertEquals(base.resolve(".venv"), UvBackend.environmentRoot(base))
        } finally {
            if (!existed) Files.deleteIfExists(manifest)
        }
    }
}
