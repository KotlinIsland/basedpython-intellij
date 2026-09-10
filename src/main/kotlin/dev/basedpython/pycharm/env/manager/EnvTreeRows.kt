package dev.basedpython.pycharm.env.manager

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

    /** A place requirements are declared: the main list, an extra, a named group. */
    data class Group(val group: EnvDependencyGroup) : EnvRow

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

/** A row and whatever hangs beneath it. */
internal data class EnvRowNode(
    val row: EnvRow,
    val children: List<EnvRowNode> = emptyList(),
)

internal object EnvTreeRows {

    /**
     * The tree for [status].
     *
     * Falls back to listing installed packages flat when there is no grouped graph — a backend with
     * no tree concept, or a project with no lock file yet. That is not an error state and must not
     * render as one: "here is what is installed" stays a truthful and useful answer, and an empty
     * window would read as "nothing is installed" to someone looking at a full `.venv`.
     */
    fun build(status: EnvStatus): List<EnvRowNode> {
        if (status.dependencies.isNotEmpty()) {
            return status.dependencies.map { group ->
                EnvRowNode(
                    EnvRow.Group(group),
                    group.roots.map { node(it, declared = true) },
                )
            }
        }
        return status.packages.map { EnvRowNode(EnvRow.Flat(it)) }
    }

    /** True when [build] produced the flat fallback rather than the grouped tree. */
    fun isFlat(status: EnvStatus): Boolean = status.dependencies.isEmpty()

    private fun node(dependency: EnvDependencyNode, declared: Boolean): EnvRowNode = EnvRowNode(
        EnvRow.Package(dependency, declared),
        dependency.children.map { node(it, declared = false) },
    )

    /**
     * The modules a new dependency can be written into — empty when the project has one manifest.
     *
     * From the tree when there is one, so the dialog offers exactly the modules the window is
     * showing and can never name one the resolved graph does not have.
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
        val fromTree = status.dependencies.mapNotNull { it.module }.distinct()
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
     * the grouping worth having — otherwise the tree is a picture and the operations ignore it.
     *
     * The module travels with it, so *Add* under a member's heading offers that member's manifest
     * rather than silently proposing the root's.
     */
    fun listForAdd(selection: List<Selected>): EnvDependencyList =
        selection.firstNotNullOfOrNull { it.group?.list } ?: EnvDependencyList(EnvDependencyTarget.Main)

    /**
     * The selected requirements that can be removed, grouped by the list to remove them from.
     *
     * Only declared ones survive. Removing a transitive dependency is not an operation any of these
     * backends has — it is installed because something else requires it, and the command fails
     * naming a requirement the project never declared — so offering it would be offering a button
     * that cannot work. Rows from the flat fallback are excluded for the same reason: nothing there
     * says whether a package was declared.
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
            val list = selected.group?.list ?: continue
            val names = byList.getOrPut(list) { mutableListOf() }
            // A tree can legitimately show the same requirement twice — as the declared row and as
            // something else's transitive dependency — and naming it twice on one command line is
            // at best noise in the confirmation.
            if (row.node.name !in names) names.add(row.node.name)
        }
        return byList
    }

    /** A selected row, together with the group heading it sits under. */
    data class Selected(val row: EnvRow, val group: EnvDependencyGroup?)
}
