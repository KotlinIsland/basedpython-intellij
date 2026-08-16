package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Reading a venv's own record of what it is.
 *
 * The fixtures are real: one written by uv 0.12.3, one by CPython's own `venv` module. They disagree
 * about which key carries the version, which is the whole reason this parser exists.
 */
class PyvenvCfgTest {

    /** Verbatim from a `uv venv --python 3.12`. */
    private val uvWritten = """
        home = /Users/x/.local/share/uv/python/cpython-3.12-macos-aarch64-none/bin
        implementation = CPython
        uv = 0.12.3
        version_info = 3.12
        include-system-site-packages = false
        prompt = envdemo
    """.trimIndent()

    /** Verbatim from a `python3 -m venv .venv`, which writes the full patch version under `version`. */
    private val stdlibWritten = """
        home = /usr/local/bin
        include-system-site-packages = false
        version = 3.11.7
        executable = /usr/local/bin/python3.11
        command = /usr/local/bin/python3 -m venv /p/.venv
    """.trimIndent()

    @Test
    fun `a uv-written config reports its version, home and prompt`() {
        val info = PyvenvCfg.parse(uvWritten)

        assertEquals("3.12", info.version)
        assertEquals("envdemo", info.prompt)
        assertEquals("/Users/x/.local/share/uv/python/cpython-3.12-macos-aarch64-none/bin", info.home)
        assertEquals("uv 0.12.3", info.createdBy)
    }

    @Test
    fun `a stdlib-written config reports the version under its own key`() {
        val info = PyvenvCfg.parse(stdlibWritten)

        assertEquals("3.11.7", info.version)
        assertEquals("3.11", info.featureVersion)
        assertNull(info.prompt)
        assertNull(info.createdBy)
    }

    /** `version_info` is CPython's own and carries the patch level; `version` may be abbreviated. */
    @Test
    fun `version_info wins when a config carries both`() {
        val info = PyvenvCfg.parse("version = 3.12\nversion_info = 3.12.8\n")
        assertEquals("3.12.8", info.version)
        assertEquals("3.12", info.featureVersion)
    }

    @Test
    fun `whitespace and key case do not matter`() {
        val info = PyvenvCfg.parse("   VERSION_INFO   =   3.13.0   \n")
        assertEquals("3.13.0", info.version)
    }

    /**
     * A value can legitimately contain an `=` — `command` does, and so does any path with one in it —
     * so the split is on the first separator, not on every one.
     */
    @Test
    fun `only the first equals separates the key from the value`() {
        val info = PyvenvCfg.parse("home = /opt/py=3/bin\n")
        assertEquals("/opt/py=3/bin", info.home)
    }

    @Test
    fun `an unreadable or empty config yields nothing rather than throwing`() {
        val info = PyvenvCfg.parse("")
        assertNull(info.version)
        assertNull(info.featureVersion)
        assertNull(info.home)

        val junk = PyvenvCfg.parse("not a config\n= no key\nkey =\n")
        assertNull(junk.version)
    }

    /** A version with no minor part is not a feature version, and must not be reported as one. */
    @Test
    fun `a single-component version has no feature version`() {
        assertNull(PyvenvCfg.parse("version = 3\n").featureVersion)
    }
}
