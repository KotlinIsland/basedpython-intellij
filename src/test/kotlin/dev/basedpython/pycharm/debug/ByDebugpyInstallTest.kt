package dev.basedpython.pycharm.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Choosing how to install `debugpy` after a debug session reports it missing.
 *
 * The interpreter is not a guess: it is the `sys.executable` the debuggee itself reported, which is
 * the one `by run` picked and therefore the only one that counts.
 */
class ByDebugpyInstallTest {

    private val uv = Paths.get("/usr/local/bin/uv")
    private val project = Paths.get("/work/app")
    private val python = "/work/app/.venv/bin/python3"

    @Test
    fun `a uv project installs through uv, in the project directory`() {
        val install = ByDebugpyInstall.choose(uv, project, isUvProject = true, python = python)
        assertEquals(ByDebugpyInstall.WithUv(uv, project), install)
        assertEquals(listOf("/usr/local/bin/uv", "add", "--dev", "debugpy"), install!!.arguments)
        assertEquals(project, install.workingDir)
    }

    /**
     * In a uv project a bare `pip install` lands in an environment uv rebuilds from the lock file,
     * so the package would silently vanish again — hence uv wins even though we know the exact
     * interpreter.
     */
    @Test
    fun `uv wins over pip when the project is uv-managed`() {
        val install = ByDebugpyInstall.choose(uv, project, isUvProject = true, python = python)
        assertEquals(ByDebugpyInstall.WithUv::class.java, install!!.javaClass)
    }

    @Test
    fun `without a uv project it is pip into the reporting interpreter`() {
        val install = ByDebugpyInstall.choose(uv, project, isUvProject = false, python = python)
        assertEquals(ByDebugpyInstall.WithPip(Paths.get(python)), install)
        assertEquals(listOf(python, "-m", "pip", "install", "debugpy"), install!!.arguments)
        assertNull(install.workingDir)
    }

    /** uv absent from the machine is not a reason to skip the offer. */
    @Test
    fun `no uv on PATH still offers pip`() {
        val install = ByDebugpyInstall.choose(null, project, isUvProject = true, python = python)
        assertEquals(ByDebugpyInstall.WithPip(Paths.get(python)), install)
    }

    /**
     * The "never reported a port" failure has no interpreter to name — the notification still shows,
     * it just cannot offer a one-click fix.
     */
    @Test
    fun `no interpreter means no offer rather than a wrong one`() {
        assertNull(ByDebugpyInstall.choose(null, project, isUvProject = false, python = null))
        assertNull(ByDebugpyInstall.choose(null, project, isUvProject = false, python = "   "))
    }

    @Test
    fun `the command is shown the way it would be typed`() {
        assertEquals(
            "$python -m pip install debugpy",
            ByDebugpyInstall.choose(null, null, isUvProject = false, python = python)!!.describe(),
        )
    }
}
