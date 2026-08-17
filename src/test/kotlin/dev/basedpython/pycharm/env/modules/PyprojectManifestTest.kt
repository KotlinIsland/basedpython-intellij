package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a `pyproject.toml` says, as the structure view reads it.
 *
 * The fixtures are uv's own output. Both manifests below were written by `uv init` (0.12.5) and are
 * pasted rather than minimised, because the point of this test is that the *real* file parses — a
 * hand-trimmed one would drop exactly the multi-line arrays and inline tables that are worth
 * checking.
 */
class PyprojectManifestTest {

    private val workspaceRoot = """
        [project]
        name = "root"
        version = "0.1.0"
        requires-python = ">=3.14"
        dependencies = [
            "alpha",
            "httpx>=0.27",
        ]

        [tool.uv.workspace]
        members = [
            "packages/alpha",
            "packages/beta",
        ]
        exclude = ["packages/scratch"]

        [tool.uv.sources]
        alpha = { workspace = true }
    """.trimIndent()

    private val member = """
        [project]
        name = "alpha"
        version = "0.2.1"
        description = "Add your description here"
        readme = "README.md"
        authors = [
            { name = "A Person", email = "person@example.com" }
        ]
        requires-python = ">=3.14"
        dependencies = ["beta"]

        [project.optional-dependencies]
        cli = ["typer"]

        [dependency-groups]
        dev = ["pytest>=8", "beta"]

        [build-system]
        requires = ["uv_build>=0.12.5,<0.13.0"]
        build-backend = "uv_build"
    """.trimIndent()

    @Test
    fun `reads the project's own metadata`() {
        val manifest = PyprojectManifest.parse(member)
        assertEquals("alpha", manifest.name)
        assertEquals("0.2.1", manifest.version)
        assertEquals("Add your description here", manifest.description)
        assertEquals(">=3.14", manifest.requiresPython)
        assertTrue(manifest.isProject)
    }

    @Test
    fun `a build system is what makes a module installable by its siblings`() {
        assertTrue(PyprojectManifest.parse(member).hasBuildSystem)
        assertFalse(PyprojectManifest.parse(workspaceRoot).hasBuildSystem)
    }

    @Test
    fun `member globs and exclusions are read exactly as written`() {
        val manifest = PyprojectManifest.parse(workspaceRoot)
        assertEquals(listOf("packages/alpha", "packages/beta"), manifest.workspaceMembers)
        assertEquals(listOf("packages/scratch"), manifest.workspaceExclude)
    }

    /**
     * The distinction the removal path depends on: a sibling named in `dev` is not removed by a
     * command that only names the main list.
     */
    @Test
    fun `every dependency carries the list it is declared in`() {
        val dependencies = PyprojectManifest.parse(member).dependencies
        assertEquals(
            listOf(
                ModuleDependency("beta", EnvDependencyTarget.Main),
                ModuleDependency("typer", EnvDependencyTarget.Extra("cli")),
                ModuleDependency("pytest", EnvDependencyTarget.Group("dev")),
                ModuleDependency("beta", EnvDependencyTarget.Group("dev")),
            ),
            dependencies,
        )
    }

    /** Version specifiers are not part of the name, and comparing them would find no dependents. */
    @Test
    fun `a requirement is reduced to its distribution name`() {
        val dependencies = PyprojectManifest.parse(workspaceRoot).dependencies
        assertEquals(listOf("alpha", "httpx"), dependencies.map { it.name })
    }

    @Test
    fun `a file with no project table is configuration rather than a module`() {
        val manifest = PyprojectManifest.parse(
            """
            [tool.ruff]
            line-length = 100
            """.trimIndent(),
        )
        assertNull(manifest.name)
        assertFalse(manifest.isProject)
        assertTrue(manifest.dependencies.isEmpty())
    }

    /** Nothing here may throw on a file that is being typed into; every field is simply absent. */
    @Test
    fun `an unfinished manifest parses to nothing rather than failing`() {
        val manifest = PyprojectManifest.parse("[project\nname = ")
        assertNull(manifest.version)
        assertTrue(manifest.workspaceMembers.isEmpty())
    }
}
