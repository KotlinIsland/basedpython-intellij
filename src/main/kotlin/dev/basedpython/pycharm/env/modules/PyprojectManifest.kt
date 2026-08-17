package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import dev.basedpython.pycharm.env.manager.EnvRequirements
import dev.basedpython.pycharm.tasks.ByToml

/**
 * The parts of a `pyproject.toml` the structure view needs, read out of its text.
 *
 * A pure function of the file's content, on purpose and for the same reason
 * [dev.basedpython.pycharm.tasks.ByToml] itself is: everything decided here — whether a directory is
 * a module, which module depends on which, which `members` entry names one — has a consequence
 * (removing the wrong entry, reporting no dependents right before breaking one), and none of it
 * needs a project, a VFS or a running IDE to be checked.
 *
 * ### Why not the TOML plugin's PSI
 *
 * Same trade [ByToml] documents. The scan reads one manifest per module on a background thread; PSI
 * would mean a read action per file and a parse of the whole document to answer six questions about
 * four tables. What is lost is TOML this never sees in practice — dotted keys and arrays of tables —
 * and what is gained is that every case below is a string in a test.
 */
internal data class PyprojectManifest(
    val name: String?,
    val version: String?,
    val description: String?,
    val requiresPython: String?,
    /**
     * Every requirement declared anywhere in the manifest, each with the list it is declared in.
     *
     * The main list, the optional extras and the dependency groups, read as one sequence — a
     * sibling module can be named in any of them, and which one decides what a removal has to say.
     * Direct references (a URL, a path) contribute nothing, because [EnvRequirements.packageName]
     * cannot name them and a guess would be worse than the omission.
     */
    val dependencies: List<ModuleDependency>,
    /** `[tool.uv.workspace] members`, exactly as written. */
    val workspaceMembers: List<String>,
    /** `[tool.uv.workspace] exclude`, exactly as written. */
    val workspaceExclude: List<String>,
    /** True when the manifest declares a `[build-system]` — the manifest of something installable. */
    val hasBuildSystem: Boolean,
) {

    /** True when this manifest describes a project at all, rather than only tool configuration. */
    val isProject: Boolean get() = name != null

    companion object {

        /** Reads [text]; every field is absent rather than defaulted when the file does not say. */
        fun parse(text: String): PyprojectManifest {
            val sections = ByToml.parse(text)
            val project = ByToml.table(sections, "project")
            val workspace = ByToml.table(sections, "tool", "uv", "workspace")

            fun value(key: String): String? =
                project.firstOrNull { it.key == key }?.string()?.takeIf { it.isNotBlank() }

            return PyprojectManifest(
                name = value("name"),
                version = value("version"),
                description = value("description"),
                requiresPython = value("requires-python"),
                dependencies = declaredRequirements(sections),
                workspaceMembers = workspace.firstOrNull { it.key == "members" }?.strings().orEmpty(),
                workspaceExclude = workspace.firstOrNull { it.key == "exclude" }?.strings().orEmpty(),
                hasBuildSystem = ByToml.hasTable(sections, "build-system"),
            )
        }

        /**
         * Every requirement the manifest declares, from all three kinds of list.
         *
         * `[project] dependencies` is the main one; in `[project.optional-dependencies]` and
         * `[dependency-groups]` each *key* is the name of an extra or a group and its value is that
         * list's requirements — which is why those two are read as tables rather than as arrays.
         * De-duplicated on name *and* list, so a sibling declared in both the main list and `dev`
         * stays two entries: removing it needs two commands.
         */
        private fun declaredRequirements(
            sections: List<dev.basedpython.pycharm.tasks.ByTomlSection>,
        ): List<ModuleDependency> {
            val raw = buildList {
                ByToml.table(sections, "project")
                    .filter { it.key == "dependencies" }
                    .forEach { entry -> entry.strings().forEach { add(it to EnvDependencyTarget.Main) } }
                ByToml.table(sections, "project", "optional-dependencies")
                    .forEach { entry -> entry.strings().forEach { add(it to EnvDependencyTarget.Extra(entry.key)) } }
                ByToml.table(sections, "dependency-groups")
                    .forEach { entry -> entry.strings().forEach { add(it to EnvDependencyTarget.Group(entry.key)) } }
            }
            return raw
                .mapNotNull { (requirement, target) ->
                    EnvRequirements.packageName(requirement)?.let { ModuleDependency(it, target) }
                }
                .distinctBy { it.key to it.target }
        }
    }
}
