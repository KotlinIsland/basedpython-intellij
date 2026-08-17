package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import java.nio.file.Path

/**
 * A project's internal structure, in terms no environment manager owns.
 *
 * The thing being described is what uv calls a *workspace member*: a directory with a
 * `pyproject.toml` of its own, listed in the root project's `[tool.uv.workspace] members`, sharing
 * one lock file and one environment with its siblings. This plugin calls it a **module**, because
 * that is the word the IDE and the person using it already have for "a part of this project that is
 * built and depended on separately".
 *
 * These types are deliberately backend-agnostic in the same way [dev.basedpython.pycharm.env.manager.EnvPackage]
 * is: uv is the only manager with a workspace concept today, and the UI is written against this
 * rather than against uv's spelling of it, so a second one is an [dev.basedpython.pycharm.env.manager.EnvBackend]
 * implementation and nothing else.
 */

/**
 * What a module's sources look like on disk — which is the one thing that cannot be changed
 * afterwards without moving files, and therefore the one question a new module has to answer.
 *
 * The four are uv's own, and each maps to a flag combination `uv init` understands. What separates
 * them is whether the module is *built* (and so installable, and so nameable as a dependency by its
 * siblings) and whether it has a source layout at all.
 */
enum class ModuleKind {

    /**
     * A library: `src/<name>/__init__.py`, a build backend, importable by its siblings.
     *
     * The default, because a module that no sibling can import is not doing the job a workspace
     * member exists to do.
     */
    LIBRARY,

    /** An application: a `main.py` to run, and nothing built or installed. */
    APPLICATION,

    /** An application that is also built — a `src/` layout plus a console script entry point. */
    PACKAGED_APPLICATION,

    /** Only a `pyproject.toml`. For a module whose sources are going to be moved in by hand. */
    BARE,
}

/**
 * One module: the root project itself, or one of its workspace members.
 *
 * [dependencies] holds every requirement the manifest declares, each paired with the list it is
 * declared in. Both halves are load-bearing: the name answers the question the structure view asks —
 * "who would break if this module went away" — and the list is what a removal has to be told, since
 * `uv remove` takes a sibling out of the main list unless it is told the group, and would report
 * success having removed nothing.
 */
data class ProjectModule(
    /** The distribution name, as `[project] name` spells it. */
    val name: String,
    /** Where the module's own `pyproject.toml` lives. */
    val root: Path,
    /**
     * The path uv names this module by, relative to the workspace root, with `/` separators.
     *
     * Empty for the root module. This is the string that goes into `members`, and the one that has
     * to be taken back out when the module is removed.
     */
    val relativePath: String,
    val version: String?,
    val description: String?,
    val requiresPython: String?,
    val dependencies: List<ModuleDependency>,
    /**
     * True when the module declares a build system, and so is something that gets built.
     *
     * The nearest thing to reading back the [ModuleKind] it was created with. uv writes no record of
     * which flag scaffolded a directory, so the kind is not recoverable; what *is* recoverable is
     * the distinction that matters afterwards — a module with a build backend can be installed and
     * imported by its siblings, and one without is a program that is only ever run.
     */
    val packaged: Boolean,
    /** True for the workspace root — the project the environment and the lock file belong to. */
    val isRoot: Boolean,
    /**
     * The `members` entry that names this module exactly, or null when a glob is what covers it.
     *
     * The difference decides what removing the module has to do to the root manifest: an exact entry
     * is left dangling and has to be taken out, while a glob — a star under `packages`, say —
     * describes a shape and stops matching by itself the moment the directory is gone. Removing the
     * glob instead would silently unlist every other module it covers.
     */
    val memberEntry: String?,
) {
    /** True when this module can be named as a dependency by another: built, and not the root. */
    val isImportable: Boolean get() = !isRoot && packaged

    /** How the module is identified in commands and in comparisons — PEP 503's normal form. */
    val key: String get() = ModuleNames.normalize(name)

    /**
     * The lists this module declares [name] in — empty when it does not depend on it at all.
     *
     * A list rather than a single target because a project may legitimately declare the same
     * requirement twice, in the main list and in a group, and removing it from one leaves the other.
     */
    fun dependsOn(name: String): List<EnvDependencyTarget> {
        val key = ModuleNames.normalize(name)
        return dependencies.filter { it.key == key }.map { it.target }
    }
}

/**
 * One requirement of a module, and where it is written.
 *
 * [target] is [EnvDependencyTarget] rather than a type of this feature's own so that a dependency
 * read out of a manifest here and one selected in the environment tree there are the same thing —
 * and so that [dev.basedpython.pycharm.env.manager.EnvOp.Remove] can be handed it directly.
 */
data class ModuleDependency(val name: String, val target: EnvDependencyTarget) {
    val key: String get() = ModuleNames.normalize(name)
}

/**
 * The whole structure at one moment: the root project, its members, and the patterns that decide
 * which directories are members at all.
 *
 * [memberPatterns] is carried alongside the modules it resolved to because the two answer different
 * questions. The modules are what exists; the patterns are what *will* exist — a module created
 * under a directory an existing glob covers needs no edit to the root manifest, and one created
 * outside every glob needs one. uv makes that decision itself when it runs `uv init`, and the view
 * needs the same information to explain what it did.
 */
data class ModuleLayout(
    /** The root project, or null when the root `pyproject.toml` declares no `[project]` of its own. */
    val root: ProjectModule?,
    val members: List<ProjectModule>,
    val memberPatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = emptyList(),
) {

    /** The root first, then the members in path order. The order the structure view shows. */
    val all: List<ProjectModule> get() = listOfNotNull(root) + members

    /** True once the project is a workspace — it has members, or patterns that would admit some. */
    val isWorkspace: Boolean get() = members.isNotEmpty() || memberPatterns.isNotEmpty()

    fun byName(name: String): ProjectModule? {
        val key = ModuleNames.normalize(name)
        return all.firstOrNull { it.key == key }
    }

    /**
     * The modules that declare a dependency on [name].
     *
     * What removal and renaming both have to know: these are the manifests that name the module, and
     * leaving them naming something that is gone is how a workspace ends up unresolvable.
     */
    fun dependents(name: String): List<ProjectModule> {
        val key = ModuleNames.normalize(name)
        return all.filter { module -> module.key != key && module.dependsOn(key).isNotEmpty() }
    }

    /** The modules that could depend on [module] — every importable one that is not itself. */
    fun possibleDependents(module: ProjectModule): List<ProjectModule> =
        all.filter { it.key != module.key }

    companion object {
        /** A project with nothing read yet, and nothing to show. */
        val EMPTY: ModuleLayout = ModuleLayout(root = null, members = emptyList())
    }
}

/** Package-name comparison, in the one form everything else agrees on. */
object ModuleNames {

    /**
     * [name] in PEP 503's normal form: lowercase, with runs of `-`, `_` and `.` collapsed to one `-`.
     *
     * Necessary rather than tidy: a module directory called `my_lib` declares itself as `my-lib` or
     * `my_lib` depending on who wrote the manifest, and a dependent naming the other spelling is
     * naming the same distribution. Comparing raw strings therefore reports a module as having no
     * dependents right before removing it breaks one.
     */
    fun normalize(name: String): String {
        val lower = name.trim().lowercase()
        val out = StringBuilder(lower.length)
        var separator = false
        for (ch in lower) {
            if (ch == '-' || ch == '_' || ch == '.') {
                separator = true
                continue
            }
            if (separator && out.isNotEmpty()) out.append('-')
            separator = false
            out.append(ch)
        }
        return out.toString()
    }

    /**
     * True when [name] is a distribution name a manifest can carry.
     *
     * PEP 508: starts and ends with a letter or digit, with letters, digits, `-`, `_` and `.`
     * between. Checked before `uv init` rather than after, because uv's own failure for a bad name
     * arrives as a process exit code in a notification, and this is a field the dialog can mark red.
     */
    fun isValid(name: String): Boolean {
        val text = name.trim()
        if (text.isEmpty()) return false
        if (!text.first().isLetterOrDigit() || !text.last().isLetterOrDigit()) return false
        return text.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }

    /**
     * The import package a distribution called [name] conventionally installs.
     *
     * `my-lib` is imported as `my_lib`: uv's own `--lib` scaffold writes `src/my_lib/__init__.py`,
     * and this is what the structure view shows so that the name in the table can be matched to the
     * directory beside it.
     */
    fun importName(name: String): String = normalize(name).replace('-', '_')
}
