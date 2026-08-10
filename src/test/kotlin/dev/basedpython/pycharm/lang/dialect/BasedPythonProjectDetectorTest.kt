package dev.basedpython.pycharm.lang.dialect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Decision logic for [BasedPythonProjectDetector].
 *
 * Everything interesting is in [BasedPythonProjectDetector.classify], which takes a base-directory
 * listing and the head of `pyproject.toml` and answers what kind of project this is — so it can be
 * tested without a project, a fixture, or files on disk.
 *
 * The behaviour these tests pin down is the fix for "don't activate in non-python projects": a bare
 * `pyproject.toml` used to be enough to call a project basedpython, which meant every Python
 * project had its `.py` files re-typed and a `by` server spawned.
 */
class BasedPythonProjectDetectorTest {

    private fun classify(vararg names: String, pyproject: String? = null): ProjectKind =
        BasedPythonProjectDetector.classify(names.toSet(), pyproject)

    // ---- basedpython ----

    @Test
    fun `an api lock marks a basedpython project`() {
        assertEquals(ProjectKind.BASEDPYTHON, classify("api.lock"))
    }

    @Test
    fun `a basedpython toml marks a basedpython project`() {
        assertEquals(ProjectKind.BASEDPYTHON, classify("basedpython.toml"))
    }

    @Test
    fun `a top-level by source marks a basedpython project`() {
        assertEquals(ProjectKind.BASEDPYTHON, classify("main.by"))
        assertEquals(ProjectKind.BASEDPYTHON, classify("stub.byi"))
    }

    @Test
    fun `a pyproject that mentions basedpython marks a basedpython project`() {
        val manifest = """
            [project]
            name = "demo"
            dependencies = ["basedpython"]
        """.trimIndent()
        assertEquals(ProjectKind.BASEDPYTHON, classify("pyproject.toml", pyproject = manifest))
    }

    @Test
    fun `a tool basedpython table marks a basedpython project`() {
        assertEquals(
            ProjectKind.BASEDPYTHON,
            classify("pyproject.toml", pyproject = "[tool.basedpython]\nstrict = true\n"),
        )
    }

    // ---- plain python ----

    @Test
    fun `a bare pyproject is only a python project`() {
        val manifest = """
            [project]
            name = "demo"
            dependencies = ["requests"]
        """.trimIndent()
        assertEquals(ProjectKind.PYTHON, classify("pyproject.toml", pyproject = manifest))
    }

    @Test
    fun `the usual python layout markers are python`() {
        assertEquals(ProjectKind.PYTHON, classify("setup.py"))
        assertEquals(ProjectKind.PYTHON, classify("requirements.txt"))
        assertEquals(ProjectKind.PYTHON, classify("uv.lock"))
        assertEquals(ProjectKind.PYTHON, classify(".venv"))
    }

    @Test
    fun `a py source at the base is a python project`() {
        assertEquals(ProjectKind.PYTHON, classify("script.py"))
        assertEquals(ProjectKind.PYTHON, classify("stub.pyi"))
    }

    // ---- neither ----

    @Test
    fun `a project with nothing python in it is other`() {
        assertEquals(ProjectKind.OTHER, classify("Cargo.toml", "src", "README.md"))
    }

    @Test
    fun `an empty base directory is other`() {
        assertEquals(ProjectKind.OTHER, classify())
    }

    @Test
    fun `unrelated files are not markers`() {
        assertEquals(ProjectKind.OTHER, classify("README.md", "notes.txt", "index.js"))
    }

    // ---- details ----

    @Test
    fun `extensions are matched case-insensitively`() {
        assertEquals(ProjectKind.BASEDPYTHON, classify("MAIN.BY"))
        assertEquals(ProjectKind.PYTHON, classify("SCRIPT.PY"))
    }

    @Test
    fun `a basedpython marker wins over plain python markers`() {
        assertEquals(ProjectKind.BASEDPYTHON, classify("setup.py", "requirements.txt", "main.by"))
    }

    @Test
    fun `a name that merely starts like a marker does not count`() {
        assertEquals(ProjectKind.OTHER, classify("pyproject.toml.bak", "apilock"))
    }
}
