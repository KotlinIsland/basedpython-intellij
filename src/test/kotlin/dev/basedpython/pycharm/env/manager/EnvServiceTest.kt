package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
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

    /**
     * A view inside a modal dialog is told about changes while that dialog is up; the tool window is
     * not.
     *
     * *Settings | Modules* lives in the modal Settings dialog, and every change used to be delivered
     * non-modally — after Settings closed — so the table showed a module created from it only once
     * the page that created it was gone.
     */
    @Test
    fun `a listener that asks for any modality is told while a dialog is open, the default is not`() {
        val service = EnvService.getInstance(project)
        val parent = Disposer.newDisposable("modality test")
        val dialog = Any()
        try {
            var toolWindow = 0
            var settingsPage = 0
            service.addListener(parent) { toolWindow++ }
            service.addListener(parent, ModalityState.any()) { settingsPage++ }

            LaterInvocator.enterModal(dialog)
            try {
                service.clearProgress()
                PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                assertTrue(settingsPage > 0, "the page inside the dialog is told now")
                assertEquals(0, toolWindow, "the tool window waits for the dialog to close")
            } finally {
                LaterInvocator.leaveModal(dialog)
            }
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            assertTrue(toolWindow > 0, "and is told once it has")
        } finally {
            Disposer.dispose(parent)
        }
    }
}
