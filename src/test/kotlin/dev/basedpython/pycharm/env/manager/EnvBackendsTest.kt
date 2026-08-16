package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The registry a future conda or pixi backend is added to, and how a project picks one. */
class EnvBackendsTest {

    @Test
    fun `a project with no manifest is claimed by nobody`(@TempDir dir: Path) {
        assertNull(EnvBackends.detect(dir))
    }

    @Test
    fun `a uv project is claimed by uv`(@TempDir dir: Path) {
        Files.writeString(dir.resolve("uv.lock"), "version = 1\n")
        assertSame(UvBackend, EnvBackends.detect(dir))
    }

    @Test
    fun `a backend can be found by the id it persists under`() {
        assertSame(UvBackend, EnvBackends.byId("uv"))
        assertNull(EnvBackends.byId("conda"))
        assertNull(EnvBackends.byId(null))
    }

    /** Ids reach settings and [ManagedEnvironment], so a duplicate would make one unaddressable. */
    @Test
    fun `every backend has a distinct id`() {
        val ids = EnvBackends.ALL.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.none { it.isBlank() })
    }

    /** What the tool window's availability check and the file watcher key on. */
    @Test
    fun `the marker set is the union of every backend's`() {
        assertEquals(EnvBackends.ALL.flatMap { it.projectMarkers }.toSet(), EnvBackends.ALL_MARKERS)
        assertTrue("pyproject.toml" in EnvBackends.ALL_MARKERS)
        assertTrue("uv.lock" in EnvBackends.ALL_MARKERS)
    }
}
