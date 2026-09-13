package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Turning uv's dependency graph into the grouped tree the window shows.
 *
 * The main fixture is verbatim `uv tree --all-groups --frozen --format json` from uv 0.12.3, on a
 * project built to have one of everything: a main dependency with transitive dependencies, an
 * optional extra, and two named groups. It is trimmed only of the wheel and hash blocks, which this
 * parser never reads and which are 90% of the bytes.
 */
class UvTreeTest {

    private val real: String = fixture("/env/uv-tree.json")

    /**
     * The same command on a two-module workspace, from uv 0.12.7: a root project with a main list, a
     * `dev` group and a dependency on its own member, and a member with a main list, a `cli` extra
     * and a `dev` group of its own. Paths shortened to `/w`; wheel and hash blocks removed.
     */
    private val workspace: String = fixture("/env/uv-tree-workspace.json")

    /**
     * A *virtual* workspace root — a manifest holding only `[tool.uv.workspace]` and its own
     * `[dependency-groups]`, with one member. Two things about it are unlike every other fixture
     * here: `members` has one entry that is *not* the root, and the root's own `dev` group arrives
     * with a `kind` and no `name`, because a virtual root has no distribution name to carry.
     */
    private val virtualWorkspace: String = fixture("/env/uv-tree-virtual-workspace.json")

    /**
     * One of every source, from uv 0.12.13: a root `app` depending on a workspace member `lib` and on
     * `tool`, an editable path dependency *outside* the root; `lib` depends on `copy`, a path
     * dependency that a `members` glob over `vendored` covers and `exclude` takes back out; and `copy`
     * depends on `gitdep`, from a git repository. Paths shortened to `/w`; nothing else changed —
     * including `/w/../tool`, which uv writes unnormalised.
     */
    private val sources: String = fixture("/env/uv-tree-sources.json")

    /**
     * A project depending on `requests[socks]`, from uv 0.12.13. The requirement is its own entry,
     * `requests[socks]`, whose `kind` names the extra and whose dependencies are `pysocks` and
     * `requests` — the package's own requirements hang off `requests`. Paths shortened to `/p`.
     */
    private val indexExtra: String = fixture("/env/uv-tree-extra.json")

    /**
     * [sources] with `lib` requiring `copy[x]`, `app` requiring plain `copy`, and `copy` declaring a
     * requirement of its own (`tool`) and an extra `x` (`gitdep`), from uv 0.12.13.
     */
    private val pathExtra: String = fixture("/env/uv-tree-path-extra.json")

    private fun fixture(path: String): String =
        checkNotNull(javaClass.getResourceAsStream(path)) {
            "the uv tree fixture $path is missing from the test classpath"
        }.use { it.readBytes().decodeToString() }

    /** Every list, in display order — what the tree showed before it showed projects. */
    private fun parse(json: String): List<EnvDependencyGroup> = UvTree.parse(json).groups

    private fun groups() = parse(real)

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

    // ---- workspaces --------------------------------------------------------

    /**
     * The bug this fixture exists for. uv emits a root per list per *module*, so a two-module
     * workspace has two roots of kind `package` and two `dev` groups. Read by kind alone they
     * collapse into headings that are indistinguishable on screen and — worse — equal to each other,
     * which is what sends a member's removal to the root's manifest.
     */
    @Test
    fun `each module's lists are its own`() {
        assertEquals(
            listOf(
                EnvDependencyList(EnvDependencyTarget.Main, "treedemo"),
                EnvDependencyList(EnvDependencyTarget.Group("dev"), "treedemo"),
                EnvDependencyList(EnvDependencyTarget.Main, "sub"),
                EnvDependencyList(EnvDependencyTarget.Extra("cli"), "sub"),
                EnvDependencyList(EnvDependencyTarget.Group("dev"), "sub"),
            ),
            parse(workspace).map { it.list },
        )
    }

    /** Two lists that read the same on screen are two different lists to act on. */
    @Test
    fun `the two dev groups are not equal`() {
        val devs = parse(workspace).filter { it.target == EnvDependencyTarget.DEV }
        assertEquals(2, devs.size)
        assertNotEquals(devs[0].list, devs[1].list)
        assertEquals(listOf("pytest"), names(devs[0].roots))
        assertEquals(listOf("iniconfig"), names(devs[1].roots))
    }

    /**
     * The root project's lists lead, and each module's lists stay together. Interleaving them would
     * put the root's `dev` between the member's `dependencies` and the member's `dev`.
     */
    @Test
    fun `the root module's lists come first and each module's stay together`() {
        assertEquals(
            listOf("treedemo", "treedemo", "sub", "sub", "sub"),
            parse(workspace).map { it.module },
        )
    }

    /**
     * A module that depends on a sibling depends on something that is itself a root. Dropping every
     * edge that lands on a root — which is one way to remove an extra's edge back to its own base
     * package — takes the sibling out of the list that declares it.
     */
    @Test
    fun `a dependency on a workspace member is still a dependency`() {
        val main = parse(workspace).first { it.module == "treedemo" && it.target == EnvDependencyTarget.Main }
        assertEquals(listOf("requests", "sub"), names(main.roots))
    }

    /** The one edge that does have to go, in a workspace as much as anywhere. */
    @Test
    fun `a member's extra does not contain the member`() {
        val cli = parse(workspace).first { it.target == EnvDependencyTarget.Extra("cli") }
        assertEquals(listOf("click"), names(cli.roots))
    }

    /**
     * uv lists a single-package project as the workspace's one member. Naming its module would put a
     * qualifier on every heading of a project with nothing to qualify against, and would start
     * passing `--package` to commands that have always managed without it.
     */
    @Test
    fun `a project that is not a workspace has no module on its lists`() {
        assertTrue(groups().all { it.module == null }, groups().map { it.module }.toString())
    }

    /**
     * Counting members is not the same question as "is this a workspace".
     *
     * A single-package project lists itself as the one member; a virtual root with one member lists
     * the *member*, and the root is not in the list at all. Reading the count alone called the
     * second one unmanaged and dropped the module from its lists — which drops `--package` from
     * every command the window then issues, and on uv 0.12.10 that is `uv remove idna` answering
     * "could not be found in `project.dependencies`" and `uv add httpx` answering "Project is
     * missing a `[project]` table".
     */
    @Test
    fun `a workspace whose single member is not the root still has modules`() {
        val groups = parse(virtualWorkspace)
        assertEquals(
            listOf(
                // The workspace root's own list leads, as the root project's does elsewhere.
                EnvDependencyList(EnvDependencyTarget.Group("dev"), null),
                EnvDependencyList(EnvDependencyTarget.Main, "alpha"),
                EnvDependencyList(EnvDependencyTarget.Group("dev"), "alpha"),
            ),
            groups.map { it.list },
        )
    }

    /**
     * The virtual root's own `dev` is a list you can add to and remove from — `uv add --group dev`
     * and `uv remove --group dev` at the workspace root both work, verified on uv 0.12.10 — so it
     * has to be in the tree. It was not: uv gives that node a `kind` and no `name`, and requiring a
     * name dropped it, so the group was not shown empty, it was not shown.
     */
    @Test
    fun `a virtual root's own groups are not dropped for having no name`() {
        val rootDev = parse(virtualWorkspace).single { it.module == null }
        assertEquals(EnvDependencyTarget.DEV, rootDev.target)
        assertEquals(listOf("packaging"), names(rootDev.roots))
    }

    // ---- projects and sources ----------------------------------------------

    private fun projects(json: String) = UvTree.parse(json).projects

    @Test
    fun `a single-package project is one project, the root`() {
        val only = projects(real).single()
        assertEquals(EnvProjectRole.ROOT, only.role)
        assertEquals(groups(), only.groups)
    }

    @Test
    fun `a workspace's projects are its root and then its members`() {
        assertEquals(
            listOf("treedemo" to EnvProjectRole.ROOT, "sub" to EnvProjectRole.MEMBER),
            projects(workspace).map { it.name to it.role },
        )
        assertEquals(listOf("0.1.0", "0.1.0"), projects(workspace).map { it.version })
    }

    /** A virtual root has no name, and its lists are still its own — not orphans, not the member's. */
    @Test
    fun `a virtual root is a nameless root project holding its own lists`() {
        val (rootProject, alpha) = projects(virtualWorkspace)
        assertEquals(null, rootProject.name)
        assertEquals(Path.of("/w"), rootProject.path)
        assertEquals(listOf(EnvDependencyTarget.DEV), rootProject.groups.map { it.target })
        assertEquals("alpha", alpha.name)
        assertEquals(listOf(EnvDependencyTarget.Main, EnvDependencyTarget.DEV), alpha.groups.map { it.target })
    }

    /**
     * The case that was reported: a directory in the repository that uv installs from, and that the
     * window showed only as a transitive row under whatever depended on it.
     */
    @Test
    fun `every path dependency is a project, after the workspace's own`() {
        assertEquals(
            listOf(
                "app" to EnvProjectRole.ROOT,
                "lib" to EnvProjectRole.MEMBER,
                "copy" to EnvProjectRole.LOCAL,
                "tool" to EnvProjectRole.LOCAL,
            ),
            projects(sources).map { it.name to it.role },
        )
    }

    @Test
    fun `a path dependency's project says whether it is editable, and where it is`() {
        val copy = projects(sources).single { it.name == "copy" }
        val tool = projects(sources).single { it.name == "tool" }
        assertFalse(copy.editable)
        assertTrue(tool.editable)
        assertEquals(Path.of("/w/vendored/copy"), copy.path)
        // uv writes `/w/../tool`; a path with `..` in it is not one to show anyone.
        assertEquals(Path.of("/tool"), tool.path)
    }

    /**
     * Its requirements are its entry's dependencies — and its list is read-only, because
     * `uv add --package copy` answers that the workspace has no such member.
     */
    @Test
    fun `a path dependency's own list is its requirements, and read-only`() {
        val list = projects(sources).single { it.name == "copy" }.groups.single()
        assertEquals(EnvDependencyList(EnvDependencyTarget.Main, "copy"), list.list)
        assertEquals(listOf("gitdep"), names(list.roots))
        assertFalse(list.writable)
        assertTrue(projects(sources).filter { it.role != EnvProjectRole.LOCAL }.flatMap { it.groups }.all { it.writable })
    }

    /** A member and an editable path dependency carry the same `source`; `members` decides. */
    @Test
    fun `each package says where it comes from`() {
        val app = projects(sources).first().groups.single()
        val (lib, tool) = app.roots
        assertEquals(EnvSource.Member, lib.source)
        assertEquals(EnvSource.Local(Path.of("/tool"), editable = true), tool.source)

        val copy = lib.children.single()
        assertEquals(EnvSource.Local(Path.of("/w/vendored/copy"), editable = false), copy.source)
        val gitdep = copy.children.single()
        assertEquals(EnvSource.Git("file:///gitdep#4a23a7800fbc1b2ab3a8a92d4abd659a3fc22b18"), gitdep.source)
    }

    @Test
    fun `an index package is from an index`() {
        val requests = group(EnvDependencyTarget.Main).roots.single()
        assertEquals(EnvSource.Index, requests.source)
        assertEquals(EnvSource.Index, requests.children.first().source)
    }

    /** The sibling a member depends on is a member, in the list that declares it. */
    @Test
    fun `a dependency on a member is marked as one`() {
        val main = parse(workspace).first { it.module == "treedemo" && it.target == EnvDependencyTarget.Main }
        assertEquals(EnvSource.Member, main.roots.single { it.name == "sub" }.source)
    }

    // ---- requirements with extras ------------------------------------------

    /**
     * The row uv's own tree shows: `requests[socks]` holding requests' requirements and pysocks.
     * Following the extra's edge to its package instead put a `requests` row under
     * `requests[socks]`, and the package's requirements one level further down than the extra's.
     */
    @Test
    fun `a requirement with an extra is one row holding the package's requirements and the extra's`() {
        val requests = parse(indexExtra).single().roots.single()
        assertEquals("requests", requests.name)
        assertEquals("socks", requests.extra)
        assertEquals(
            listOf("certifi", "charset-normalizer", "idna", "pysocks", "urllib3"),
            names(requests.children),
        )
    }

    /** Required as `copy[x]` by one project and as `copy` by another: one project, two lists. */
    @Test
    fun `a path dependency required with an extra lists the extra as its own`() {
        val copy = projects(pathExtra).single { it.name == "copy" }
        assertEquals(
            listOf(EnvDependencyTarget.Main, EnvDependencyTarget.Extra("x")),
            copy.groups.map { it.target },
        )
        assertEquals(listOf(listOf("tool"), listOf("gitdep")), copy.groups.map { names(it.roots) })
        assertTrue(copy.groups.none { it.writable })
        assertEquals(1, projects(pathExtra).count { it.path == Path.of("/w/vendored/copy") })
    }

    @Test
    fun `an extra's row keeps its package's source`() {
        val lib = projects(pathExtra).single { it.name == "lib" }
        val copy = lib.groups.single().roots.single()
        assertEquals("x", copy.extra)
        assertEquals(EnvSource.Local(Path.of("/w/vendored/copy"), editable = false), copy.source)
        assertEquals(listOf("gitdep", "tool"), names(copy.children))
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

        val roots = parse(json).single().roots
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

        val a = parse(json).single().roots.single()
        assertEquals("a", a.name)
        val b = a.children.single()
        assertEquals("b", b.name)
        assertEquals("a", b.children.single().name)
        assertTrue(b.children.single().expandedElsewhere)
        assertTrue(b.children.single().children.isEmpty())
    }

    /**
     * The main list is where dependencies go by default, so a project with none has an *empty* main
     * list rather than no main list. Hiding it leaves a project whose only dependencies are dev ones
     * looking like it has no main list at all — and nothing to select before pressing Add.
     */
    @Test
    fun `the main list is shown even when it is empty`() {
        val json = """
            {"roots":[{"id":"root"},{"id":"dev"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"package","dependencies":[]},
               "dev":{"name":"proj","version":"1","kind":{"group":"dev"},
                      "dependencies":[{"id":"pytest"}]},
               "pytest":{"name":"pytest","version":"9","kind":"package","dependencies":[]}}}
        """.trimIndent()

        val groups = parse(json)
        assertEquals(
            listOf(EnvDependencyTarget.Main, EnvDependencyTarget.Group("dev")),
            groups.map { it.target },
        )
        assertTrue(groups.first().roots.isEmpty())
        assertEquals(0, groups.first().packageCount())
    }

    /**
     * `dev` is where development dependencies go, so removing the last one should leave a heading to
     * add to rather than making the group vanish and need conjuring back by name.
     */
    @Test
    fun `the dev group is shown even when it is empty`() {
        val json = """
            {"roots":[{"id":"root"},{"id":"dev"},{"id":"docs"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"package","dependencies":[{"id":"a"}]},
               "dev":{"name":"proj","version":"1","kind":{"group":"dev"},"dependencies":[]},
               "docs":{"name":"proj","version":"1","kind":{"group":"docs"},"dependencies":[]},
               "a":{"name":"a","version":"1","kind":"package","dependencies":[]}}}
        """.trimIndent()

        val groups = parse(json)
        assertEquals(
            listOf(EnvDependencyTarget.Main, EnvDependencyTarget.Group("dev")),
            groups.map { it.target },
            "dev survives being empty; docs does not",
        )
        assertTrue(groups.last().roots.isEmpty())
    }

    /** A group whose requirements all failed to resolve would be an empty heading. */
    @Test
    fun `an empty group other than the main list is not shown`() {
        val json = """
            {"roots":[{"id":"root"},{"id":"empty"}],
             "resolution":{
               "root":{"name":"proj","version":"1","kind":"package","dependencies":[{"id":"a"}]},
               "empty":{"name":"proj","version":"1","kind":{"group":"docs"},"dependencies":[]},
               "a":{"name":"a","version":"1","kind":"package","dependencies":[]}}}
        """.trimIndent()

        assertEquals(listOf(EnvDependencyTarget.Main), parse(json).map { it.target })
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

        assertEquals(listOf(EnvDependencyTarget.Main), parse(json).map { it.target })
    }

    /**
     * uv calls this schema `preview`. A partial tree would silently claim a project has fewer
     * dependencies than it does, so anything unreadable yields nothing at all — which the view shows
     * as the flat installed list rather than as a wrong tree.
     */
    @Test
    fun `unreadable output yields no tree rather than a partial one`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("error: no lockfile found").isEmpty())
        assertTrue(parse("{\"resolution\":").isEmpty())
        assertTrue(parse("[]").isEmpty())
        assertTrue(parse("{}").isEmpty())
        assertTrue(parse("""{"roots":[],"resolution":{}}""").isEmpty())
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

        val groups = parse(json)
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

        val group = parse(json).single()
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

        val node = parse(json).single().roots.single()
        assertNotNull(node)
        assertEquals("a", node.name)
        assertEquals("", node.version)
    }
}
