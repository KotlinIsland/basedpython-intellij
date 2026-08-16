package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which files an operation may rewrite — the list that decides what gets flushed to disk before a
 * command runs and re-read into the IDE afterwards.
 *
 * Worth pinning because getting it wrong is invisible in every way that matters until a user hits
 * it: the command still succeeds, the tool window still updates, and only the editor is left showing
 * a `pyproject.toml` that no longer exists on disk.
 */
class EnvManagedFilesTest {

    /** `uv add`, `uv remove` and `uv lock` write both of these, and none goes through the IDE. */
    @Test
    fun `uv declares the manifests it rewrites`() {
        assertEquals(listOf("pyproject.toml", "uv.lock"), UvBackend.managedFiles)
    }

    /** The one a user is likely to have open leads, since its staleness is the visible one. */
    @Test
    fun `the manifest a user reads comes first`() {
        assertEquals("pyproject.toml", UvBackend.managedFiles.first())
    }

    @Test
    fun `every backend names the files it writes`() {
        for (backend in EnvBackends.ALL) {
            assertTrue(
                backend.managedFiles.isNotEmpty(),
                "${backend.id} declares no managed files, so nothing it writes would be re-read",
            )
            assertTrue(
                backend.managedFiles.none { it.isBlank() || it.contains('/') || it.contains('\\') },
                "${backend.id} managed files are plain names at the project root: ${backend.managedFiles}",
            )
        }
    }

    /**
     * The two lists answer different questions and are allowed to differ.
     *
     * For uv they happen to name the same files; for conda they would not — `environment.yml`
     * identifies the project and is never written back to. This test exists to state that the
     * overlap is a coincidence rather than an invariant, so the next backend is not written on the
     * assumption that one list can stand in for the other.
     */
    @Test
    fun `recognising a project and writing to it are separate lists`() {
        assertEquals(
            setOf("uv.lock", "pyproject.toml"),
            UvBackend.projectMarkers.toSet(),
        )
        assertEquals(
            UvBackend.projectMarkers.toSet(),
            UvBackend.managedFiles.toSet(),
            "for uv the two coincide — see the KDoc on EnvBackend.managedFiles for why they still differ",
        )
    }
}
