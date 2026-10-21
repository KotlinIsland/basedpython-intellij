package dev.basedpython.pycharm.run.target

import com.intellij.execution.Platform
import dev.basedpython.pycharm.run.ByRunOptions
import dev.basedpython.pycharm.run.byTargetEnvironment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * The translation between the project here and its copy or mount on a run target, and the
 * environment `by` is given there.
 *
 * The target's paths are the target's: a Linux container's from any IDE, written with `/` and joined
 * with `:` whatever this machine uses.
 */
class ByTargetPathsTest {

    private val local = Path.of("/work/proj")
    private val paths = ByTargetPaths(local, "/opt/project/", Platform.UNIX)

    @Test
    fun `a project path maps under the target root`() {
        assertEquals("/opt/project", paths.toTarget(local))
        assertEquals("/opt/project/pkg/main.by", paths.toTarget(local.resolve("pkg/main.by")))
        assertEquals("/opt/project/pkg", paths.toTarget(local.resolve("pkg/sub/..")))
    }

    @Test
    fun `a path outside the project has no counterpart on the target`() {
        assertNull(paths.toTarget(Path.of("/work/other")))
        assertNull(paths.toTarget(Path.of("/work/proj-sibling/main.by")))
    }

    @Test
    fun `what the target prints maps back into the project`() {
        assertEquals(local, paths.toLocal("/opt/project"))
        assertEquals(local, paths.toLocal("/opt/project/"))
        assertEquals(local.resolve("pkg/main.by"), paths.toLocal("/opt/project/pkg/main.by"))
    }

    @Test
    fun `a target path outside the project is not a project file`() {
        assertNull(paths.toLocal("/usr/local/lib/python3.13/runpy.py"))
        assertNull(paths.toLocal("/opt/project-old/main.by"))
        assertNull(paths.toLocal("/opt/project/../../etc/passwd"))
        assertNull(paths.toLocal("pkg/main.by"))
    }

    @Test
    fun `a windows target is written in its own separators`() {
        val windows = ByTargetPaths(local, "C:\\proj", Platform.WINDOWS)
        assertEquals("C:\\proj\\pkg\\main.by", windows.toTarget(local.resolve("pkg/main.by")))
        assertEquals(local.resolve("pkg/main.by"), windows.toLocal("C:\\proj\\pkg\\main.by"))
    }

    @Test
    fun `a target rooted at the filesystem root still maps`() {
        val root = ByTargetPaths(local, "/", Platform.UNIX)
        assertEquals("/", root.toTarget(local))
        assertEquals("/pkg/main.by", root.toTarget(local.resolve("pkg/main.by")))
        assertEquals(local.resolve("pkg/main.by"), root.toLocal("/pkg/main.by"))
    }

    @Test
    fun `the target environment puts the target working directory on PYTHONPATH in the target's syntax`() {
        val options = ByRunOptions().apply { envVars = linkedMapOf("PYTHONPATH" to "/opt/libs", "A" to "1") }

        val env = byTargetEnvironment(options, "/opt/project", startsProgram = true, separator = ':')

        assertEquals("/opt/project:/opt/libs", env["PYTHONPATH"])
        assertEquals("1", env["A"])
        assertEquals("1", env["PYTHONUNBUFFERED"])
    }

    @Test
    fun `nothing of this machine's environment reaches the target`() {
        val env = byTargetEnvironment(ByRunOptions(), "/opt/project", startsProgram = false, separator = ':')

        assertEquals(mapOf("PYTHONUNBUFFERED" to "1"), env)
    }

    @Test
    fun `a user's PYTHONUNBUFFERED still wins`() {
        val options = ByRunOptions().apply { envVars = linkedMapOf("PYTHONUNBUFFERED" to "") }

        assertEquals("", byTargetEnvironment(options, "/p", startsProgram = true, separator = ':')["PYTHONUNBUFFERED"])
    }
}
