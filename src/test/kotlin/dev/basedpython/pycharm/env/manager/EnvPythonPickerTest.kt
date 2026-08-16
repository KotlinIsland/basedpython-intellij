package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** What the interpreter picker offers, given what the backend reported. */
class EnvPythonPickerTest {

    private fun candidate(version: String, installed: Boolean, implementation: String = "cpython") =
        PythonCandidate(
            key = "$implementation-$version-macos-aarch64-none",
            version = version,
            implementation = implementation,
            path = if (installed) Path.of("/py/$version/bin/python") else null,
        )

    private fun status(currentVersion: String? = null) = EnvStatus(
        projectRoot = Path.of("/p"),
        backend = UvBackend,
        toolPath = Path.of("/usr/bin/uv"),
        environmentRoot = Path.of("/p/.venv"),
        environment = currentVersion?.let {
            ManagedEnvironment("uv", Path.of("/p/.venv"), Path.of("/p/.venv/bin/python"), it)
        },
        drift = EnvDrift.IN_SYNC,
        packages = emptyList(),
    )

    /**
     * The backend reading the project's own `requires-python` is the right answer whenever the
     * project states one, so it is the first thing offered and the only entry with no request.
     */
    @Test
    fun `the project's own requirement is offered first and pins nothing`() {
        val entries = EnvPythonPicker.entries(listOf(candidate("3.12.8", installed = true)), status())

        assertNull(entries.first().request)
        assertFalse(entries.first().needsInstall)
    }

    @Test
    fun `versions collapse to their feature version, newest first`() {
        val entries = EnvPythonPicker.entries(
            listOf(
                candidate("3.9.18", installed = true),
                candidate("3.12.8", installed = true),
                candidate("3.12.1", installed = true),
                candidate("3.10.13", installed = true),
            ),
            status(),
        )

        assertEquals(listOf("3.12", "3.10", "3.9"), entries.drop(1).map { it.request })
    }

    /** `3.9` sorts below `3.10`, which a string comparison gets backwards. */
    @Test
    fun `version order is numeric, not lexicographic`() {
        val entries = EnvPythonPicker.entries(
            listOf(candidate("3.9.0", installed = true), candidate("3.10.0", installed = true)),
            status(),
        )
        assertEquals(listOf("3.10", "3.9"), entries.drop(1).map { it.request })
    }

    @Test
    fun `installed versions come before downloadable ones, which say so`() {
        val entries = EnvPythonPicker.entries(
            listOf(
                candidate("3.14.0", installed = false),
                candidate("3.12.8", installed = true),
            ),
            status(),
        )

        val requests = entries.drop(1)
        assertEquals(listOf("3.12", "3.14"), requests.map { it.request })
        assertFalse(requests[0].needsInstall)
        assertTrue(requests[1].needsInstall)
        assertTrue(requests[1].label.contains("download", ignoreCase = true))
    }

    /**
     * uv lists a version both ways when a newer patch is downloadable for an already-installed
     * feature version. Offering "3.12 (will be downloaded)" beside a 3.12 that is right there is
     * the picker telling the user to download something they have.
     */
    @Test
    fun `a feature version already installed is never offered as a download`() {
        val entries = EnvPythonPicker.entries(
            listOf(
                candidate("3.12.8", installed = true),
                candidate("3.12.11", installed = false),
            ),
            status(),
        )

        assertEquals(listOf("3.12"), entries.drop(1).map { it.request })
        assertFalse(entries.drop(1).single().needsInstall)
    }

    /** `3.15.0rc1` collapses to a `3.15` that is not generally available. */
    @Test
    fun `pre-releases are not offered`() {
        val entries = EnvPythonPicker.entries(
            listOf(candidate("3.15.0rc1", installed = true), candidate("3.13.1", installed = true)),
            status(),
        )
        assertEquals(listOf("3.13"), entries.drop(1).map { it.request })
    }

    @Test
    fun `only CPython is offered`() {
        val entries = EnvPythonPicker.entries(
            listOf(
                candidate("3.11.0", installed = true, implementation = "pypy"),
                candidate("3.12.8", installed = true),
            ),
            status(),
        )
        assertEquals(listOf("3.12"), entries.drop(1).map { it.request })
    }

    @Test
    fun `the version the environment is already on is marked`() {
        val entries = EnvPythonPicker.entries(
            listOf(candidate("3.12.8", installed = true), candidate("3.13.1", installed = true)),
            status(currentVersion = "3.12"),
        )

        val current = entries.first { it.request == "3.12" }
        assertTrue(current.label.contains("current"), current.label)
        assertFalse(entries.first { it.request == "3.13" }.label.contains("current"))
    }

    /** A full patch version in `pyvenv.cfg` still has to match the feature version in the list. */
    @Test
    fun `the current marker matches on the feature version`() {
        val entries = EnvPythonPicker.entries(
            listOf(candidate("3.12.8", installed = true)),
            status(currentVersion = "3.12.8"),
        )
        assertTrue(entries.first { it.request == "3.12" }.label.contains("current"))
    }

    /** With nothing reported, the project's own requirement is still a usable answer. */
    @Test
    fun `an empty list still offers the project's requirement`() {
        val entries = EnvPythonPicker.entries(emptyList(), status())
        assertEquals(1, entries.size)
        assertNull(entries.single().request)
    }
}
