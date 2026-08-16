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
     * The group whose rows a new dependency should join, given what is selected.
     *
     * The first selected row's group wins, and the main list is the answer when nothing useful is
     * selected. Selecting `dev` and pressing *Add* adding to `dev` is what makes the grouping worth
     * having — otherwise the tree is a picture and the operations ignore it.
     */
    fun targetForAdd(selection: List<Selected>): EnvDependencyTarget =
        selection.firstNotNullOfOrNull { it.group?.target } ?: EnvDependencyTarget.Main

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
     * `dev` and `httpx` from the main list is two edits that no single command expresses.
     */
    fun removable(selection: List<Selected>): Map<EnvDependencyTarget, List<String>> {
        val byTarget = LinkedHashMap<EnvDependencyTarget, MutableList<String>>()
        for (selected in selection) {
            val row = selected.row as? EnvRow.Package ?: continue
            if (!row.declared) continue
            val target = selected.group?.target ?: continue
            val names = byTarget.getOrPut(target) { mutableListOf() }
            // A tree can legitimately show the same requirement twice — as the declared row and as
            // something else's transitive dependency — and naming it twice on one command line is
            // at best noise in the confirmation.
            if (row.node.name !in names) names.add(row.node.name)
        }
        return byTarget
    }

    /** A selected row, together with the group heading it sits under. */
    data class Selected(val row: EnvRow, val group: EnvDependencyGroup?)
}
