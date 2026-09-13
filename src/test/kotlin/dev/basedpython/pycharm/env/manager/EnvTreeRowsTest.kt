package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.basedpython.pycharm.env.modules.ModuleLayout
import dev.basedpython.pycharm.env.modules.ProjectModule
import java.nio.file.Path

/**
 * What the environment tree contains, and what a selection in it means.
 *
 * These decisions have consequences beyond appearance: [EnvTreeRows.removable] decides which
 * `uv remove` runs against which dependency list, and getting it wrong edits the wrong part of the
 * user's `pyproject.toml`. They are checked here rather than through the panel because none of them
 * needs a Swing component to be true.
 */
class EnvTreeRowsTest {

    private fun pkg(name: String, version: String = "1.0", vararg children: EnvDependencyNode) =
        EnvDependencyNode(name, version, children.toList())

    private fun list(target: EnvDependencyTarget, module: String? = null) =
        EnvDependencyList(target, module)

    private val main = EnvDependencyGroup(
        list(EnvDependencyTarget.Main),
        listOf(pkg("requests", "2.34.2", pkg("idna", "3.18"), pkg("urllib3", "2.7.0"))),
    )
    private val dev = EnvDependencyGroup(
        list(EnvDependencyTarget.DEV),
        listOf(pkg("pytest", "9.1.1", pkg("pluggy", "1.6.0"))),
    )
    private val cli = EnvDependencyGroup(
        list(EnvDependencyTarget.Extra("cli")),
        listOf(pkg("click", "8.4.2")),
    )

    /** The same two lists, in a workspace member's manifest rather than the root's. */
    private val memberMain = EnvDependencyGroup(
        list(EnvDependencyTarget.Main, "submodule"),
        listOf(pkg("foo", "0.1.0")),
    )
    private val memberDev = EnvDependencyGroup(
        list(EnvDependencyTarget.DEV, "submodule"),
        listOf(pkg("pytest", "9.1.1")),
    )

    /** A path dependency's one list, which nothing can edit from the workspace. */
    private val fooMain = EnvDependencyGroup(
        list(EnvDependencyTarget.Main, "foo"),
        listOf(pkg("pydantic", "2.13.5")),
        writable = false,
    )
    private val foo = EnvProject(
        name = "foo",
        version = "0.1.0",
        path = Path.of("/p/folder/foo"),
        role = EnvProjectRole.LOCAL,
        groups = listOf(fooMain),
        editable = false,
    )

    /**
     * The graph a backend would report for [dependencies]: one project per module named on them, the
     * root's first, plus any [extra] projects after.
     */
    private fun graph(dependencies: List<EnvDependencyGroup>, extra: List<EnvProject> = emptyList()) =
        EnvDependencyGraph(
            dependencies.groupBy { it.module }.map { (module, lists) ->
                EnvProject(
                    name = module ?: "p",
                    version = "0.1.0",
                    path = if (module == null) Path.of("/p") else Path.of("/p", module),
                    role = if (module == null) EnvProjectRole.ROOT else EnvProjectRole.MEMBER,
                    groups = lists,
                )
            } + extra,
        )

    private fun status(
        dependencies: List<EnvDependencyGroup> = listOf(main, dev, cli),
        packages: List<EnvPackage> = emptyList(),
        modules: ModuleLayout? = null,
        graph: EnvDependencyGraph = graph(dependencies),
    ) = EnvStatus(
        projectRoot = Path.of("/p"),
        backend = UvBackend,
        toolPath = Path.of("/usr/bin/uv"),
        environmentRoot = Path.of("/p/.venv"),
        environment = ManagedEnvironment("uv", Path.of("/p/.venv"), Path.of("/p/.venv/bin/python"), "3.12"),
        drift = EnvDrift.IN_SYNC,
        packages = packages,
        graph = graph,
        modules = modules,
    )

    private fun module(name: String, isRoot: Boolean) = ProjectModule(
        name = name,
        root = Path.of("/p").resolve(if (isRoot) "" else name),
        relativePath = if (isRoot) "" else name,
        version = null,
        description = null,
        requiresPython = null,
        dependencies = emptyList(),
        packaged = !isRoot,
        isRoot = isRoot,
        memberEntry = if (isRoot) null else name,
    )

    // ---- structure ---------------------------------------------------------

    @Test
    fun `the top level is where requirements are declared`() {
        val rows = EnvTreeRows.build(status())
        assertEquals(
            listOf("dependencies", "dev", "cli"),
            rows.map { (it.row as EnvRow.Group).group.target.label },
        )
        assertFalse(EnvTreeRows.isFlat(status()))
    }

    /** The two levels the user acts on differently have to be distinguishable. */
    @Test
    fun `a group's own requirements are declared and everything under them is not`() {
        val requests = EnvTreeRows.build(status()).first().children.single()
        assertEquals("requests", (requests.row as EnvRow.Package).node.name)
        assertTrue(requests.row.declared)

        assertEquals(listOf("idna", "urllib3"), requests.children.map { (it.row as EnvRow.Package).node.name })
        assertTrue(requests.children.all { !(it.row as EnvRow.Package).declared })
    }

    /**
     * With no resolved graph — a backend with no tree, or a project with no lock — listing what is
     * installed is still true and useful. An empty window would read as "nothing is installed" to
     * someone looking at a full `.venv`.
     */
    @Test
    fun `with no graph it falls back to the flat installed list`() {
        val installed = listOf(EnvPackage("attrs", "24.2.0"), EnvPackage("httpx", "0.27.0"))
        val rows = EnvTreeRows.build(status(dependencies = emptyList(), packages = installed))

        assertTrue(EnvTreeRows.isFlat(status(dependencies = emptyList())))
        assertEquals(listOf("attrs", "httpx"), rows.map { (it.row as EnvRow.Flat).pkg.name })
        assertTrue(rows.all { it.children.isEmpty() })
    }

    @Test
    fun `a project with nothing at all produces no rows`() {
        assertTrue(EnvTreeRows.build(status(dependencies = emptyList())).isEmpty())
    }

    // ---- projects ----------------------------------------------------------

    private fun projectRows(status: EnvStatus) = EnvTreeRows.build(status).map { it.row as EnvRow.Project }

    /**
     * What the window looked like before it had projects: two headings reading `dependencies`, told
     * apart by a grey suffix. With a project per manifest, each heading is under the one it is from.
     */
    @Test
    fun `a workspace is headed by its projects, each holding its own lists`() {
        val rows = EnvTreeRows.build(status(dependencies = listOf(main, dev, memberMain, memberDev)))
        assertEquals(listOf("p", "submodule"), rows.map { (it.row as EnvRow.Project).project.name })
        assertEquals(
            listOf(listOf(main.list, dev.list), listOf(memberMain.list, memberDev.list)),
            rows.map { project -> project.children.map { (it.row as EnvRow.Group).group.list } },
        )
        assertTrue(EnvTreeRows.showsProjects(status(dependencies = listOf(main, memberMain))))
    }

    /** One project is the whole window, so a heading naming it would only push everything right. */
    @Test
    fun `a single project is not given a heading`() {
        assertFalse(EnvTreeRows.showsProjects(status()))
        assertTrue(EnvTreeRows.build(status()).all { it.row is EnvRow.Group })
    }

    /** The case the user reported: `folder/foo` showed up only as something `submodule` pulled in. */
    @Test
    fun `a path dependency is a project of its own`() {
        val rows = projectRows(status(graph = graph(listOf(main), extra = listOf(foo))))
        assertEquals(listOf(EnvProjectRole.ROOT, EnvProjectRole.LOCAL), rows.map { it.project.role })
        assertEquals("folder/foo", rows[1].location)
        assertEquals(null, rows[0].location)
    }

    @Test
    fun `a project outside the root is placed relative to it`() {
        val tool = foo.copy(name = "tool", path = Path.of("/tool"))
        assertEquals("../tool", EnvTreeRows.location(Path.of("/p"), tool))
    }

    private fun layout(members: List<String>, exclude: List<String>) = ModuleLayout(
        root = module("p", isRoot = true),
        members = emptyList(),
        memberPatterns = members,
        excludePatterns = exclude,
    )

    /**
     * `members` naming a directory and `exclude` naming it too is legal, and `exclude` wins — so the
     * project the author listed as a member is installed as a path dependency, and the one place that
     * can say why is the row that shows it.
     */
    @Test
    fun `a path dependency that members and exclude both name is marked excluded`() {
        val both = status(
            graph = graph(listOf(main), extra = listOf(foo)),
            modules = layout(members = listOf("submodule", "folder/foo"), exclude = listOf("folder/foo")),
        )
        assertTrue(projectRows(both)[1].excluded)

        // Globs, the way uv reads them.
        val glob = status(
            graph = graph(listOf(main), extra = listOf(foo)),
            modules = layout(members = listOf("folder/*"), exclude = listOf("folder/foo")),
        )
        assertTrue(projectRows(glob)[1].excluded)
    }

    /** Excluded and never listed is an ordinary path dependency, and nothing to point at. */
    @Test
    fun `a path dependency that members does not name is not marked`() {
        val onlyExcluded = status(
            graph = graph(listOf(main), extra = listOf(foo)),
            modules = layout(members = listOf("submodule"), exclude = listOf("folder/foo")),
        )
        assertFalse(projectRows(onlyExcluded)[1].excluded)

        val noLayout = status(graph = graph(listOf(main), extra = listOf(foo)))
        assertFalse(projectRows(noLayout)[1].excluded)
    }

    /**
     * Expansion is restored by key across a refresh, so two lists that read the same have to key
     * differently — and a project has to keep its key when a sync bumps its version.
     */
    @Test
    fun `expansion keys tell same-named lists apart and survive a version change`() {
        assertFalse(EnvRow.Group(dev).expansionKey == EnvRow.Group(memberDev).expansionKey)
        assertEquals(
            EnvRow.Project(foo).expansionKey,
            EnvRow.Project(foo.copy(version = "0.2.0")).expansionKey,
        )
    }

    /**
     * A row is found by its own name. By `toString` — the default — a heading's text held every
     * package under it, so searching for a package landed on each of its ancestors first.
     */
    @Test
    fun `speed search matches a row by its own name only`() {
        assertEquals("requests", EnvTreeRows.searchText(EnvRow.Package(main.roots.single(), declared = true)))
        assertEquals("dependencies", EnvTreeRows.searchText(EnvRow.Group(main)))
        assertEquals("foo", EnvTreeRows.searchText(EnvRow.Project(foo)))
        val virtualRoot = EnvProject(null, null, Path.of("/w/workspace"), EnvProjectRole.ROOT, emptyList())
        assertEquals("workspace", EnvTreeRows.searchText(EnvRow.Project(virtualRoot)))
    }

    // ---- what a selection means --------------------------------------------

    private fun selected(group: EnvDependencyGroup, name: String, declared: Boolean) =
        EnvTreeRows.Selected(EnvRow.Package(pkg(name), declared), group)

    @Test
    fun `add targets the group the selection sits in`() {
        assertEquals(
            list(EnvDependencyTarget.DEV),
            EnvTreeRows.listForAdd(listOf(EnvTreeRows.Selected(EnvRow.Group(dev), dev))),
        )
        // Selecting a package, not the heading, still means that package's group.
        assertEquals(
            list(EnvDependencyTarget.Extra("cli")),
            EnvTreeRows.listForAdd(listOf(selected(cli, "click", declared = true))),
        )
    }

    /** Otherwise *Add* under a member's heading proposes writing into the root's manifest. */
    @Test
    fun `add carries the module the selection sits in`() {
        assertEquals(
            list(EnvDependencyTarget.DEV, "submodule"),
            EnvTreeRows.listForAdd(listOf(selected(memberDev, "pytest", declared = true))),
        )
    }

    @Test
    fun `add falls back to the main list when nothing useful is selected`() {
        assertEquals(list(EnvDependencyTarget.Main), EnvTreeRows.listForAdd(emptyList()))
        assertEquals(
            list(EnvDependencyTarget.Main),
            EnvTreeRows.listForAdd(listOf(EnvTreeRows.Selected(EnvRow.Flat(EnvPackage("attrs", "1")), null))),
        )
    }

    @Test
    fun `a declared requirement is removable, from the list it is declared in`() {
        assertEquals(
            mapOf(list(EnvDependencyTarget.DEV) to listOf("pytest")),
            EnvTreeRows.removable(listOf(selected(dev, "pytest", declared = true))),
        )
    }

    /**
     * The rule this exists for. A transitive dependency is installed because something else requires
     * it; `uv remove urllib3` on a project that never declared it fails naming a requirement that is
     * not there, so the button must not be offered.
     */
    @Test
    fun `a transitive dependency is not removable`() {
        assertTrue(EnvTreeRows.removable(listOf(selected(main, "urllib3", declared = false))).isEmpty())
    }

    /** Group headings and the flat fallback are not things to remove. */
    @Test
    fun `only packages are removable`() {
        assertTrue(EnvTreeRows.removable(listOf(EnvTreeRows.Selected(EnvRow.Group(dev), dev))).isEmpty())
        assertTrue(
            EnvTreeRows.removable(
                listOf(EnvTreeRows.Selected(EnvRow.Flat(EnvPackage("attrs", "1")), null)),
            ).isEmpty(),
        )
    }

    /**
     * A selection spanning lists is several commands, because that is what it is: no single
     * `uv remove` edits both the main list and a group.
     */
    @Test
    fun `a selection across lists is grouped by the list to remove from`() {
        val removable = EnvTreeRows.removable(
            listOf(
                selected(main, "requests", declared = true),
                selected(dev, "pytest", declared = true),
                selected(cli, "click", declared = true),
            ),
        )

        assertEquals(
            mapOf(
                list(EnvDependencyTarget.Main) to listOf("requests"),
                list(EnvDependencyTarget.DEV) to listOf("pytest"),
                list(EnvDependencyTarget.Extra("cli")) to listOf("click"),
            ),
            removable,
        )
    }

    /**
     * The bug this addressing exists for. Two modules of a workspace each have a `dependencies` and
     * a `dev`; keyed on the target alone they collapse into one entry, and the single `uv remove`
     * that entry produces carries no `--package` — so it edits the root's manifest and reports
     * success having left the member's alone.
     */
    @Test
    fun `two modules' lists of the same name are separate removals`() {
        val removable = EnvTreeRows.removable(
            listOf(
                selected(main, "requests", declared = true),
                selected(memberMain, "foo", declared = true),
                selected(dev, "pytest", declared = true),
                selected(memberDev, "pytest", declared = true),
            ),
        )

        assertEquals(
            mapOf(
                list(EnvDependencyTarget.Main) to listOf("requests"),
                list(EnvDependencyTarget.Main, "submodule") to listOf("foo"),
                list(EnvDependencyTarget.DEV) to listOf("pytest"),
                list(EnvDependencyTarget.DEV, "submodule") to listOf("pytest"),
            ),
            removable,
        )
    }

    /** A package can appear twice in one group — declared, and as something else's dependency. */
    @Test
    fun `the same requirement selected twice is named once`() {
        val removable = EnvTreeRows.removable(
            listOf(
                selected(main, "requests", declared = true),
                selected(main, "requests", declared = true),
            ),
        )
        assertEquals(mapOf(list(EnvDependencyTarget.Main) to listOf("requests")), removable)
    }

    /**
     * `uv remove --package foo` on a path dependency answers "The workspace does not have a member
     * foo" (uv 0.12.13), so its requirements are shown and never offered for removal.
     */
    @Test
    fun `a path dependency's requirements are not removable`() {
        assertTrue(EnvTreeRows.removable(listOf(selected(fooMain, "pydantic", declared = true))).isEmpty())
    }

    /** Nor is a path dependency's list somewhere Add can write — not selected, and not offered. */
    @Test
    fun `add never targets a path dependency`() {
        assertEquals(
            list(EnvDependencyTarget.Main),
            EnvTreeRows.listForAdd(listOf(selected(fooMain, "pydantic", declared = true))),
        )
        val withFoo = status(graph = graph(listOf(main, memberMain), extra = listOf(foo)))
        assertFalse(fooMain.list in EnvTreeRows.listsToOffer(withFoo))
        assertEquals(listOf("submodule"), EnvTreeRows.modulesToOffer(withFoo))
    }

    /** A project heading selected means that project's main list — the root's own, or a member's. */
    @Test
    fun `add under a project heading targets that project's main list`() {
        val member = graph(listOf(main, memberMain, memberDev)).projects[1]
        assertEquals(
            list(EnvDependencyTarget.Main, "submodule"),
            EnvTreeRows.listForAdd(listOf(EnvTreeRows.Selected(EnvRow.Project(member), null, member))),
        )
        assertEquals(
            list(EnvDependencyTarget.Main),
            EnvTreeRows.listForAdd(listOf(EnvTreeRows.Selected(EnvRow.Project(foo), null, foo))),
        )
    }

    @Test
    fun `a selection with nothing removable in it removes nothing`() {
        assertTrue(EnvTreeRows.removable(emptyList()).isEmpty())
        assertTrue(
            EnvTreeRows.removable(
                listOf(
                    selected(main, "idna", declared = false),
                    EnvTreeRows.Selected(EnvRow.Group(main), main),
                ),
            ).isEmpty(),
        )
    }

    // ---- counts ------------------------------------------------------------

    // ---- which modules Add can write into ----------------------------------

    @Test
    fun `the modules offered are the ones the tree is showing`() {
        val workspace = status(dependencies = listOf(main, memberMain, memberDev))
        assertEquals(listOf("submodule"), EnvTreeRows.modulesToOffer(workspace))
    }

    @Test
    fun `a project with one manifest offers no module`() {
        assertTrue(EnvTreeRows.modulesToOffer(status()).isEmpty())
        val single = ModuleLayout(root = module("p", isRoot = true), members = emptyList())
        assertTrue(EnvTreeRows.modulesToOffer(status(modules = single)).isEmpty())
    }

    /**
     * A workspace with no lock file has no tree — `uv tree --frozen` exits non-zero and the window
     * falls back to the flat installed list — and *Add* with no modules offered writes to the root
     * manifest with no `--package`: an outright error on a workspace whose root is virtual, and a
     * dependency silently declared on the wrong project on any other. The layout is read on the same
     * refresh and is populated in exactly this state.
     */
    @Test
    fun `an unlocked workspace still offers its modules`() {
        val layout = ModuleLayout(
            root = module("p", isRoot = true),
            members = listOf(module("submodule", isRoot = false)),
        )
        assertEquals(
            listOf("p", "submodule"),
            EnvTreeRows.modulesToOffer(status(dependencies = emptyList(), modules = layout)),
        )
    }

    /** A virtual workspace root is not a module of its own, and must not be offered as one. */
    @Test
    fun `a virtual root contributes no module to the fallback`() {
        val layout = ModuleLayout(root = null, members = listOf(module("alpha", isRoot = false)))
        assertEquals(
            listOf("alpha"),
            EnvTreeRows.modulesToOffer(status(dependencies = emptyList(), modules = layout)),
        )
    }

    @Test
    fun `a group counts every distinct package under it`() {
        assertEquals(3, main.packageCount())
        assertEquals(2, dev.packageCount())
        assertEquals(1, cli.packageCount())
    }

    /**
     * What a collapsed list says about drift: the packages under it installed at another version,
     * each once however often it appears, and not the ones that are simply not installed — which is
     * ordinary for a group that is not synced by default.
     */
    @Test
    fun `a list counts the packages under it that are installed at another version`() {
        val group = EnvDependencyGroup(
            list(EnvDependencyTarget.Main),
            listOf(
                pkg("a", "1", pkg("shared", "2.0")),
                pkg("b", "1", pkg("shared", "2.0")),
                pkg("missing", "1"),
            ),
        )
        val installed = mapOf(
            "a" to EnvPackage("a", "1"),
            "b" to EnvPackage("b", "0.9"),
            "shared" to EnvPackage("shared", "1.0"),
        )
        assertEquals(2, EnvTreeRows.differing(group, installed))
        assertEquals(0, EnvTreeRows.differing(group, emptyMap()))
    }

    /** A shared dependency appearing under two requirements is one package, not two. */
    @Test
    fun `the count does not double up a shared dependency`() {
        val group = EnvDependencyGroup(
            list(EnvDependencyTarget.Main),
            listOf(
                pkg("a", "1", pkg("shared", "1")),
                pkg("b", "1", pkg("shared", "1")),
            ),
        )
        assertEquals(3, group.packageCount())
    }
}
