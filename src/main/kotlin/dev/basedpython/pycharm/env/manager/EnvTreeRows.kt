package dev.basedpython.pycharm.env.manager

import dev.basedpython.pycharm.env.modules.UvWorkspace
import java.nio.file.Path

/**
 * What the environment window's tree contains, as data.
 *
 * Separated from [EnvPanel] for the reason the rest of this plugin separates its pure parts: the
 * decisions here — which rows exist, which are removable, which group a new dependency joins — have
 * consequences (a wrong one runs `uv remove` against the wrong list), and none of them needs a Swing
 * component to be checked. The panel is left doing what only it can: turning these into tree nodes
 * and painting them.
 */

/** One row of the environment tree. */
internal sealed interface EnvRow {

    /**
     * What identifies this row across a refresh, for keeping it expanded; null for a row that is
     * never restored.
     */
    val expansionKey: Any? get() = null

    /**
     * A project heading its own lists — the workspace root, a member, or a path dependency.
     *
     * [location] is where it lives, relative to the project root (`submodule`, `folder/foo`,
     * `../tool`), or null for the root itself, which is where everything else is relative to.
     *
     * [excluded] is a contradiction in the manifest worth pointing at: the directory is matched by
     * `[tool.uv.workspace] members` *and* by `exclude`. uv lets `exclude` win, so the project is a
     * path dependency and not a member — which is exactly why it is not where its author expected.
     */
    data class Project(
        val project: EnvProject,
        val location: String? = null,
        val excluded: Boolean = false,
    ) : EnvRow {
        override val expansionKey: Any get() = ProjectKey(project.role, project.name, project.path)
    }

    /** A place requirements are declared: the main list, an extra, a named group. */
    data class Group(val group: EnvDependencyGroup) : EnvRow {
        override val expansionKey: Any get() = group.list
    }

    /**
     * A package.
     *
     * [declared] is the distinction the window is built around: a declared requirement is one this
     * project asked for and can remove, while a transitive one is here because something else asked
     * for it and is not the backend's to remove.
     */
    data class Package(val node: EnvDependencyNode, val declared: Boolean) : EnvRow

    /**
     * A row of the flat fallback.
     *
     * Deliberately not a [Package] with `declared = false`: the fallback is used when there is no
     * resolved graph, so nothing is known about whether these were declared. Giving them their own
     * type is what stops the removal rule from having to guess.
     */
    data class Flat(val pkg: EnvPackage) : EnvRow
}

/** A project's identity for [EnvRow.expansionKey] — everything but the parts a sync can change. */
private data class ProjectKey(val role: EnvProjectRole, val name: String?, val path: Path?)

/** A row and whatever hangs beneath it. */
internal data class EnvRowNode(
    val row: EnvRow,
    val children: List<EnvRowNode> = emptyList(),
)

internal object EnvTreeRows {

    /**
     * The tree for [status].
     *
     * Projects at the top once there is more than one of them, each holding its own lists; the lists
     * themselves at the top when there is only the one project, where a heading naming it would be
     * a level of indentation that says nothing the window's title does not.
     *
     * Projects rather than qualified lists because the lists of a workspace are not one set: two
     * headings reading `dependencies`, told apart only by a grey suffix, is a tree whose top level
     * has to be read twice to be read once. A project row also has somewhere to say what the project
     * *is* — a member, the root, a path dependency — which the lists under it cannot.
     *
     * Falls back to listing installed packages flat when there is no grouped graph — a backend with
     * no tree concept, or a project with no lock file yet. That is not an error state and must not
     * render as one: "here is what is installed" stays a truthful and useful answer, and an empty
     * window would read as "nothing is installed" to someone looking at a full `.venv`.
     */
    fun build(status: EnvStatus): List<EnvRowNode> {
        if (isFlat(status)) return status.packages.map { EnvRowNode(EnvRow.Flat(it)) }
        if (!showsProjects(status)) return status.dependencies.map(::groupNode)
        return status.graph.projects.map { project ->
            EnvRowNode(
                EnvRow.Project(project, location(status.projectRoot, project), excluded(status, project)),
                project.groups.map(::groupNode),
            )
        }
    }

    /**
     * What speed search matches [row] by: the name the row leads with, and nothing else.
     *
     * Not the row's `toString`, which is what a tree searches by when it is not told otherwise. For
     * these data classes that is the whole subtree — `Group(group=EnvDependencyGroup(…, roots=[…` with
     * every package beneath it spelled out — so typing `pydantic` matched each row *above* pydantic
     * before it reached pydantic. Not the version or the chips either: `path` should find a package
     * called `path`, not every row that happens to wear the chip.
     */
    fun searchText(row: EnvRow): String = when (row) {
        is EnvRow.Project -> row.project.name ?: row.project.path?.fileName?.toString().orEmpty()
        is EnvRow.Group -> row.group.target.label
        is EnvRow.Package -> row.node.name
        is EnvRow.Flat -> row.pkg.name
    }

    /** True when [build] produced the flat fallback rather than the grouped tree. */
    fun isFlat(status: EnvStatus): Boolean = status.dependencies.isEmpty()

    /** True when [build] heads the tree with projects rather than with lists. */
    fun showsProjects(status: EnvStatus): Boolean = !isFlat(status) && status.graph.projects.size > 1

    private fun groupNode(group: EnvDependencyGroup): EnvRowNode = EnvRowNode(
        EnvRow.Group(group),
        group.roots.map { node(it, declared = true) },
    )

    private fun node(dependency: EnvDependencyNode, declared: Boolean): EnvRowNode = EnvRowNode(
        EnvRow.Package(dependency, declared),
        dependency.children.map { node(it, declared = false) },
    )

    /**
     * Where [project] is, relative to [projectRoot] and `/`-separated; null for the root project.
     *
     * Relative even when it is outside the root — `../tool` says "a sibling of this repository"
     * more plainly than an absolute path does — and absolute only when no relative path exists at
     * all, as between two Windows drives.
     */
    fun location(projectRoot: Path?, project: EnvProject): String? {
        if (project.role == EnvProjectRole.ROOT) return null
        val path = project.path ?: return null
        val relative = projectRoot?.let { root ->
            runCatching { root.normalize().relativize(path).joinToString("/") { it.toString() } }.getOrNull()
        }
        return relative?.takeIf { it.isNotEmpty() } ?: path.toString()
    }

    /**
     * True when [project] is a path dependency whose directory `members` names and `exclude` also
     * names — see [EnvRow.Project.excluded].
     *
     * Read against the manifest's own patterns with [UvWorkspace.matches]. uv's member listing
     * cannot answer this one — an excluded directory is simply absent from it, the same as one no
     * pattern ever named.
     */
    fun excluded(status: EnvStatus, project: EnvProject): Boolean {
        if (project.role != EnvProjectRole.LOCAL) return false
        val layout = status.modules ?: return false
        val relative = location(status.projectRoot, project) ?: return false
        if (relative.startsWith("..") || Path.of(relative).isAbsolute) return false
        return layout.memberPatterns.any { UvWorkspace.matches(relative, it) } &&
            layout.excludePatterns.any { UvWorkspace.matches(relative, it) }
    }

    /**
     * How many distinct packages under [group] are installed at a version other than the one the
     * lock resolves — keyed as [installed] is, by lowercased name.
     *
     * What a collapsed list says about drift. The banner says the environment is out of sync and the
     * rows say which packages are, but only once they are on screen; without this, finding the one
     * package the banner means is expanding every list until it turns up.
     *
     * A package that is not installed at all is not counted: that is ordinary for an extra or a
     * group that is not synced by default, and counting it would mark those lists permanently.
     */
    fun differing(group: EnvDependencyGroup, installed: Map<String, EnvPackage>): Int {
        val seen = HashSet<String>()
        var count = 0
        fun walk(nodes: List<EnvDependencyNode>) {
            for (node in nodes) {
                if (!seen.add(node.name)) continue
                val here = installed[node.name.lowercase()]
                if (here != null && here.version.isNotEmpty() && here.version != node.version) count++
                walk(node.children)
            }
        }
        walk(group.roots)
        return count
    }

    /**
     * The lists *Add* can write into — every list the backend can edit.
     *
     * A path dependency's list is shown and never offered: see [EnvDependencyGroup.writable].
     */
    fun listsToOffer(status: EnvStatus): List<EnvDependencyList> =
        status.dependencies.filter { it.writable }.map { it.list }

    /**
     * The modules a new dependency can be written into — empty when the project has one manifest.
     *
     * From the tree when there is one, so the dialog offers exactly the modules the window is
     * showing and can never name one the resolved graph does not have — and only the ones whose
     * lists are [EnvDependencyGroup.writable], so a path dependency is never offered as a module
     * that `--package` could name.
     *
     * From the module layout when there is not, which is the case that matters. A workspace with no
     * lock file yet has no tree — `uv tree --frozen` exits non-zero and the window falls back to the
     * flat installed list — and with no modules offered, *Add* wrote to the root manifest with no
     * `--package`: on a workspace whose root is virtual that is an outright error, and on any other
     * it silently declares the dependency on the wrong project. The layout is read on the same
     * refresh and *is* populated in this state, which is why [EnvService] goes out of its way to
     * read it before its early return.
     */
    fun modulesToOffer(status: EnvStatus): List<String> {
        val fromTree = status.dependencies.filter { it.writable }.mapNotNull { it.module }.distinct()
        if (fromTree.isNotEmpty()) return fromTree
        val layout = status.modules ?: return emptyList()
        // No members is not a workspace: one manifest, and nothing to choose between.
        return if (layout.members.isEmpty()) emptyList() else layout.all.map { it.name }
    }

    /**
     * The list a new dependency should join, given what is selected.
     *
     * The first selected row's group wins, and the project's own main list is the answer when
     * nothing useful is selected. Selecting `dev` and pressing *Add* adding to `dev` is what makes
     * the grouping worth having — otherwise the tree is a picture and the operations ignore it. A
     * selected project heading means that project's main list, which is where a dependency of a
     * project goes when nobody said otherwise.
     *
     * The module travels with it, so *Add* under a member's heading offers that member's manifest
     * rather than silently proposing the root's. A list that cannot be written to is passed over:
     * proposing a path dependency's list would be proposing a command that fails.
     */
    fun listForAdd(selection: List<Selected>): EnvDependencyList =
        selection.firstNotNullOfOrNull { selected ->
            selected.group?.takeIf { it.writable }?.list
                ?: selected.project?.groups?.firstOrNull { it.writable && it.target == EnvDependencyTarget.Main }?.list
        } ?: EnvDependencyList(EnvDependencyTarget.Main)

    /**
     * The selected requirements that can be removed, grouped by the list to remove them from.
     *
     * Only declared ones survive. Removing a transitive dependency is not an operation any of these
     * backends has — it is installed because something else requires it, and the command fails
     * naming a requirement the project never declared — so offering it would be offering a button
     * that cannot work. Rows from the flat fallback are excluded for the same reason: nothing there
     * says whether a package was declared, and so are rows of a list the backend cannot edit.
     *
     * Grouped rather than flattened because a selection can span lists, and removing `pytest` from
     * `dev` and `httpx` from the main list is two edits that no single command expresses. Keyed by
     * the whole [EnvDependencyList] rather than by the target alone, because in a workspace two
     * modules' `dev` groups are different lists in different files — keying on the target merges
     * them into one command that edits one manifest and claims to have edited both.
     */
    fun removable(selection: List<Selected>): Map<EnvDependencyList, List<String>> {
        val byList = LinkedHashMap<EnvDependencyList, MutableList<String>>()
        for (selected in selection) {
            val row = selected.row as? EnvRow.Package ?: continue
            if (!row.declared) continue
            val group = selected.group?.takeIf { it.writable } ?: continue
            val names = byList.getOrPut(group.list) { mutableListOf() }
            // A tree can legitimately show the same requirement twice — as the declared row and as
            // something else's transitive dependency — and naming it twice on one command line is
            // at best noise in the confirmation.
            if (row.node.name !in names) names.add(row.node.name)
        }
        return byList
    }

    /** A selected row, together with the group heading and the project heading it sits under. */
    data class Selected(val row: EnvRow, val group: EnvDependencyGroup?, val project: EnvProject? = null)
}
