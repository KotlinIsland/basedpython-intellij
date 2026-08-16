package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Turning uv's dependency graph into the grouped tree the window shows.
 *
 * The main fixture is verbatim `uv tree --all-groups --frozen --format json` from uv 0.12.3, on a
 * project built to have one of everything: a main dependency with transitive dependencies, an
 * optional extra, and two named groups. It is trimmed only of the wheel and hash blocks, which this
 * parser never reads and which are 90% of the bytes.
 */
class UvTreeTest {

    private val real: String = checkNotNull(javaClass.getResourceAsStream("/env/uv-tree.json")) {
        "the uv tree fixture is missing from the test classpath"
    }.use { it.readBytes().decodeToString() }

    private fun groups() = UvTree.parse(real)

    private fun group(target: EnvDependencyTarget) =
        groups().first { it.target == target }

    private fun names(nodes: List<EnvDependencyNode>) = nodes.map { it.name }

    @Test
    fun `every place a requirement is declared becomes a group`() {
        assertEquals(
            listOf(
                EnvDependencyTarget.Main,
                EnvDependencyTarget.Extra("cli"),
                EnvDependencyTarget.Group("dev"),
                EnvDependencyTarget.Group("docs"),
            ),
            groups().map { it.target },
        )
    }

    /** Main first, then extras, then groups — with `dev` ahead of the rest of them. */
    @Test
    fun `groups are ordered by what a person opens the window for`() {
        val labels = groups().map { it.target.label }
        assertEquals("dependencies", labels.first())
        assertTrue(labels.indexOf("dev") < labels.indexOf("docs"), labels.toString())
        assertTrue(labels.indexOf("cli") < labels.indexOf("dev"), labels.toString())
    }

    @Test
    fun `a group's roots are the requirements it declares, not everything they pull in`() {
        assertEquals(listOf("requests"), names(group(EnvDependencyTarget.Main).roots))
        assertEquals(listOf("pytest"), names(group(EnvDependencyTarget.Group("dev")).roots))
        assertEquals(listOf("markdown"), names(group(EnvDependencyTarget.Group("docs")).roots))
        assertEquals(listOf("click"), names(group(EnvDependencyTarget.Extra("cli")).roots))
    }

    @Test
    fun `transitive dependencies hang under the requirement that pulled them in, sorted`() {
        val requests = group(EnvDependencyTarget.Main).roots.single()
        assertEquals("2.34.2", requests.version)
        assertEquals(listOf("certifi", "charset-normalizer", "idna", "urllib3"), names(requests.children))
        assertTrue(requests.children.all { it.children.isEmpty() })
    }

    /**
     * The edge that would otherwise nest the whole project under each of its own extras: an extra's
     * synthetic node depends on the base package as well as on the extra's own requirements.
     */
    @Test
    fun `an extra does not contain the project it is an extra of`() {
        val cli = group(EnvDependencyTarget.Extra("cli"))
        assertEquals(listOf("click"), names(cli.roots))
        assertFalse(names(cli.roots).contains("treedemo"))
    }

    @Test
    fun `the count is every distinct package under the group`() {
        // requests + its four.
        assertEquals(5, group(EnvDependencyTarget.Main).packageCount())
        assertEquals(1, group(EnvDependencyTarget.Extra("cli")).packageCount())
    }

    @Test
    fun `pytest's own dependencies come through`() {
        val pytest = group(EnvDependencyTarget.Group("dev")).roots.single()
        assertTrue(names(pytest.children).contains("iniconfig"), names(pytest.children).toString())
        assertTrue(names(pytest.children).contains("pluggy"), names(pytest.children).toString())
    }

    // ---- structural cases, on synthetic graphs -----------------------------

    /**
     * Two requirements sharing a dependency. It is expanded under the first and marked under the
     * second — expanding everywhere is what turns a readable tree into thousands of rows.
     */
    @Test
    fun `a shared dependency is expanded once per group and marked afterwards`() {
        val json = """
            {"roots":[{"id":"root"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"package",
                       "dependencies":[{"id":"a"},{"id":"b"}]},
               "a":{"name":"a","version":"1","kind":"package","dependencies":[{"id":"shared"}]},
               "b":{"name":"b","version":"1","kind":"package","dependencies":[{"id":"shared"}]},
               "shared":{"name":"shared","version":"1","kind":"package","dependencies":[{"id":"leaf"}]},
               "leaf":{"name":"leaf","version":"1","kind":"package","dependencies":[]}}}
        """.trimIndent()

        val roots = UvTree.parse(json).single().roots
        val underA = roots.first { it.name == "a" }.children.single()
        val underB = roots.first { it.name == "b" }.children.single()

        assertEquals("shared", underA.name)
        assertEquals(listOf("leaf"), names(underA.children))
        assertFalse(underA.expandedElsewhere)

        assertEquals("shared", underB.name)
        assertTrue(underB.children.isEmpty())
        assertTrue(underB.expandedElsewhere, "the second occurrence points at the first")
    }

    /**
     * Dependency cycles exist in the wild. The dedupe is what terminates the walk; this is the test
     * that says so, because "it cannot recurse forever" is not obvious from reading it.
     */
    @Test
    fun `a dependency cycle terminates`() {
        val json = """
            {"roots":[{"id":"root"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"package","dependencies":[{"id":"a"}]},
               "a":{"name":"a","version":"1","kind":"package","dependencies":[{"id":"b"}]},
               "b":{"name":"b","version":"1","kind":"package","dependencies":[{"id":"a"}]}}}
        """.trimIndent()

        val a = UvTree.parse(json).single().roots.single()
        assertEquals("a", a.name)
        val b = a.children.single()
        assertEquals("b", b.name)
        assertEquals("a", b.children.single().name)
        assertTrue(b.children.single().expandedElsewhere)
        assertTrue(b.children.single().children.isEmpty())
    }

    /** A group whose requirements all failed to resolve would be an empty heading. */
    @Test
    fun `a group with nothing in it is not shown`() {
        val json = """
            {"roots":[{"id":"root"},{"id":"empty"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"package","dependencies":[{"id":"a"}]},
               "empty":{"name":"proj","version":"1","kind":{"group":"docs"},"dependencies":[]},
               "a":{"name":"a","version":"1","kind":"package","dependencies":[]}}}
        """.trimIndent()

        assertEquals(listOf(EnvDependencyTarget.Main), UvTree.parse(json).map { it.target })
    }

    /** The container node carries no requirements and must not become a heading. */
    @Test
    fun `the workspace node is not a group`() {
        val json = """
            {"roots":[{"id":"ws"},{"id":"root"}],
             "resolution":{
               "ws":{"kind":"workspace","dependencies":[]},
               "root":{"name":"proj","version":"1","kind":"package","dependencies":[{"id":"a"}]},
               "a":{"name":"a","version":"1","kind":"package","dependencies":[]}}}
        """.trimIndent()

        assertEquals(listOf(EnvDependencyTarget.Main), UvTree.parse(json).map { it.target })
    }

    /**
     * uv calls this schema `preview`. A partial tree would silently claim a project has fewer
     * dependencies than it does, so anything unreadable yields nothing at all — which the view shows
     * as the flat installed list rather than as a wrong tree.
     */
    @Test
    fun `unreadable output yields no tree rather than a partial one`() {
        assertTrue(UvTree.parse("").isEmpty())
        assertTrue(UvTree.parse("error: no lockfile found").isEmpty())
        assertTrue(UvTree.parse("{\"resolution\":").isEmpty())
        assertTrue(UvTree.parse("[]").isEmpty())
        assertTrue(UvTree.parse("{}").isEmpty())
        assertTrue(UvTree.parse("""{"roots":[],"resolution":{}}""").isEmpty())
    }

    /** A root naming an id that is not in the resolution map is skipped, not thrown on. */
    @Test
    fun `a dangling reference is skipped`() {
        val json = """
            {"roots":[{"id":"missing"},{"id":"root"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"package",
                       "dependencies":[{"id":"a"},{"id":"gone"}]},
               "a":{"name":"a","version":"1","kind":"package","dependencies":[]}}}
        """.trimIndent()

        val groups = UvTree.parse(json)
        assertEquals(1, groups.size)
        assertEquals(listOf("a"), names(groups.single().roots))
    }

    /** A kind this parser has never seen should cost the label on that row, not the whole tree. */
    @Test
    fun `an unknown kind is treated as an ordinary package`() {
        val json = """
            {"roots":[{"id":"root"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"something-new",
                       "dependencies":[{"id":"a"}]},
               "a":{"name":"a","version":"1","kind":{"unheard-of":"x"},"dependencies":[]}}}
        """.trimIndent()

        val group = UvTree.parse(json).single()
        assertEquals(EnvDependencyTarget.Main, group.target)
        assertEquals(listOf("a"), names(group.roots))
    }

    @Test
    fun `a package with no version still appears`() {
        val json = """
            {"roots":[{"id":"root"}],
             "resolution":{
               "root":{"name":"proj","kind":"package","dependencies":[{"id":"a"}]},
               "a":{"name":"a","kind":"package","dependencies":[]}}}
        """.trimIndent()

        val node = UvTree.parse(json).single().roots.single()
        assertNotNull(node)
        assertEquals("a", node.name)
        assertEquals("", node.version)
    }
}
