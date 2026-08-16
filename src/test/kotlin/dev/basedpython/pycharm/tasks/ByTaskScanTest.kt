package dev.basedpython.pycharm.tasks

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which files a scan reads, and what a project with several of them looks like. */
class ByTaskScanTest {

    private val preCommit = """
        repos:
          - repo: local
            hooks:
              - id: check
    """.trimIndent()

    private val lefthook = """
        pre-commit:
          commands:
            lint:
              run: ruff check
    """.trimIndent()

    private val pyproject = """
        [tool.pyprojectx.aliases]
        test = "pytest"
    """.trimIndent()

    @Test
    fun `each known name is parsed by the tool that owns it`() {
        val files = ByTaskScan.scan { name ->
            when (name) {
                ".pre-commit-config.yaml" -> preCommit
                "lefthook.yml" -> lefthook
                "pyproject.toml" -> pyproject
                else -> null
            }
        }

        assertEquals(
            listOf(".pre-commit-config.yaml", "lefthook.yml", "pyproject.toml"),
            files.map { it.name },
        )
        assertEquals(
            listOf(ByTaskRunner.PRE_COMMIT, ByTaskRunner.LEFTHOOK, ByTaskRunner.PYPROJECTX),
            files.map { it.runner },
        )
    }

    @Test
    fun `the variants each tool accepts are read too`() {
        val files = ByTaskScan.scan { name ->
            when (name) {
                ".pre-commit-config.yml" -> preCommit
                ".lefthook.yaml" -> lefthook
                "lefthook-local.yml" -> lefthook
                else -> null
            }
        }

        assertEquals(listOf(".pre-commit-config.yml", ".lefthook.yaml", "lefthook-local.yml"), files.map { it.name })
    }

    @Test
    fun `a project with none of them scans to nothing`() {
        assertTrue(ByTaskScan.scan { null }.isEmpty())
        // A pyproject.toml that is not a pyprojectx one is the common case, and is not a row.
        assertTrue(ByTaskScan.scan { if (it == "pyproject.toml") "[project]\nname = \"x\"\n" else null }.isEmpty())
    }

    @Test
    fun `only the names a scan reads count as a configuration change`() {
        assertTrue(ByTaskScan.isConfigFile(".pre-commit-config.yaml"))
        assertTrue(ByTaskScan.isConfigFile("pyproject.toml"))
        assertFalse(ByTaskScan.isConfigFile("lefthook.toml"))
        assertFalse(ByTaskScan.isConfigFile("README.md"))
    }
}
