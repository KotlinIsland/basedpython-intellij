package dev.basedpython.pycharm.debug

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.process.NopProcessHandler
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * Where a debug start's setup waits between its two hooks, and when its directory goes.
 *
 * Every setup is a directory in the temp tree holding a bootstrap or a wrapper script and what the
 * session wrote. They were deleted only when the IDE exited, and a start that failed between the
 * hooks left its setup on the run configuration for as long as the configuration existed.
 */
@TestFixtures
class ByDebugSetupsTest {

    /** For the application, which a process handler needs; the suite's light project rather than one of its own. */
    @Suppress("unused")
    private val fixture by codeInsightFixture()

    private val setups = ByDebugSetups()

    @AfterEach
    fun dispose() = Disposer.dispose(setups)

    private fun profile(): RunProfile =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RunProfile::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args!![0]
                "hashCode" -> System.identityHashCode(self)
                else -> null
            }
        } as RunProfile

    private fun setup(): ByDebugSetup {
        val dir = Files.createTempDirectory("basedpython-debug-test")
        Files.writeString(dir.resolve("debug-info.json"), "{}")
        return ByDebugSetup(port = 1, bootstrapDir = dir, infoFile = dir.resolve("debug-info.json"))
    }

    @Test
    fun `a setup offered for a start is taken once`() {
        val profile = profile()
        val setup = setup()
        setups.offer(profile, setup)
        assertSame(setup, setups.take(profile))
        assertNull(setups.take(profile), "a second start of the profile must not find the first one's setup")
    }

    /** A start that failed before its descriptor took the setup: the next start replaces it. */
    @Test
    fun `a setup nobody took is deleted when the next start replaces it`() {
        val profile = profile()
        val abandoned = setup()
        setups.offer(profile, abandoned)
        setups.offer(profile, setup())
        assertFalse(Files.exists(abandoned.bootstrapDir))
    }

    @Test
    fun `the directory lives until the program ends`() {
        val setup = setup()
        val process = NopProcessHandler().apply { startNotify() }
        setups.releaseWith(setup, process)
        assertTrue(Files.exists(setup.bootstrapDir), "deleted while the program could still read it")

        process.destroyProcess()
        assertTrue(process.waitFor(10_000))
        val deadline = System.currentTimeMillis() + 10_000
        while (Files.exists(setup.bootstrapDir) && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse(Files.exists(setup.bootstrapDir), "the program ended and its directory stayed")
    }

    @Test
    fun `a program that has already ended has its directory deleted at once`() {
        val setup = setup()
        val process = NopProcessHandler().apply {
            startNotify()
            destroyProcess()
            waitFor(10_000)
        }
        setups.releaseWith(setup, process)
        assertFalse(Files.exists(setup.bootstrapDir))
    }

    /** Unloading the plugin, or closing the project, leaves nothing of a session behind. */
    @Test
    fun `whatever is left when the service goes is deleted`() {
        val waiting = setup()
        val running = setup()
        setups.offer(profile(), waiting)
        setups.releaseWith(running, NopProcessHandler().apply { startNotify() })
        Disposer.dispose(setups)
        assertFalse(Files.exists(waiting.bootstrapDir))
        assertFalse(Files.exists(running.bootstrapDir))
    }
}
