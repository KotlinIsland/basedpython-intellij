package dev.basedpython.pycharm.env.manager

import java.nio.file.Path

/**
 * What an environment manager tells us about a project, in terms no manager owns.
 *
 * Every type here is deliberately backend-agnostic. uv is the only backend today, but the split
 * exists so that adding conda or pixi is writing one [EnvBackend] and nothing else: the service, the
 * tool window, the actions and the drift banner all read these types and never a uv-shaped one.
 */

/** A package installed in an environment. */
data class EnvPackage(
    val name: String,
    val version: String,
    /**
     * Where an editable install points, when it is one.
     *
     * Worth carrying because it is the answer to the most confusing thing a package list can show:
     * the project itself listed among its own dependencies. Rendered as a hint on the row.
     */
    val editableLocation: String? = null,
) {
    /** True for the project's own package, installed into its environment as an editable. */
    val isEditable: Boolean get() = editableLocation != null
}

/**
 * A Python interpreter an environment could be built on — installed here, or downloadable.
 *
 * [path] is null exactly when this is a download candidate rather than something already on the
 * machine; the picker uses that to say which choices cost a download.
 */
data class PythonCandidate(
    /** The backend's own identifier for this interpreter, e.g. `cpython-3.12.8-macos-aarch64-none`. */
    val key: String,
    val version: String,
    val implementation: String,
    val path: Path?,
) {
    val isInstalled: Boolean get() = path != null

    /** `3.12` — what a request for this interpreter is normally written as. */
    val featureVersion: String
        get() = version.split('.').take(2).joinToString(".")
}

/**
 * Which of a manifest's lists a dependency is declared in.
 *
 * A project's requirements are not one list. There is the main list every install gets, optional
 * extras a consumer opts into, and named groups (`dev` chief among them) that are a development
 * concern and never ship. The three are declared in different places and are added and removed with
 * different flags, so "remove httpx" is not answerable without knowing which of them it came from —
 * which is the concrete reason the tree below is grouped rather than flat.
 *
 * This names the list *within one manifest*, and a workspace has more than one of those — so it is
 * half of an address, not a whole one. [EnvDependencyList] is the whole one.
 */
sealed interface EnvDependencyTarget {

    /** How this target is shown in the UI. */
    val label: String

    /** The main dependency list — `[project.dependencies]`, installed by everything. */
    data object Main : EnvDependencyTarget {
        override val label: String = "dependencies"
    }

    /**
     * A named dependency group — `[dependency-groups]`.
     *
     * `dev` is one of these rather than a case of its own. Tools spell it as a shorthand flag, but
     * it is an ordinary group, and giving it its own branch here would mean every operation had two
     * paths that must not drift apart.
     */
    data class Group(val name: String) : EnvDependencyTarget {
        override val label: String get() = name
    }

    /** An optional extra — `[project.optional-dependencies]`, opted into by a consumer. */
    data class Extra(val name: String) : EnvDependencyTarget {
        override val label: String get() = name
    }

    companion object {
        /** The group tools treat as the default development one. */
        val DEV: Group = Group("dev")
    }
}

/**
 * One package in the dependency tree, with whatever depends on it beneath.
 *
 * [version] is the *resolved* version — what the lock file settles on — which is not necessarily
 * what is installed. The view cross-references the installed list to say when the two differ; that
 * comparison is deliberately not baked in here, so this stays a description of the project's
 * declared graph rather than a description of one machine.
 */
data class EnvDependencyNode(
    val name: String,
    val version: String,
    val children: List<EnvDependencyNode> = emptyList(),
    /**
     * True when this package's dependencies are shown under an earlier occurrence instead of here.
     *
     * A dependency graph is a graph, not a tree: `certifi` is under half of what a project pulls in.
     * Expanding it everywhere turns a readable tree into thousands of rows, so it is expanded once
     * and marked afterwards — the convention every dependency tree uses, including the one the tool
     * prints itself.
     */
    val expandedElsewhere: Boolean = false,
    val source: EnvSource = EnvSource.Index,
    /**
     * The extra this row is required with — `x` for `copy[x]` — or null for the package alone.
     *
     * Kept apart from [name] because the name is what every command and lookup is keyed on:
     * `uv remove copy`, not `copy[x]`, and the installed list knows `copy`.
     */
    val extra: String? = null,
)

/**
 * Where a package's code comes from.
 *
 * Carried because two rows that read `foo 0.1.0` can mean very different things to the person
 * looking at them: one is a download from an index, and the other is a directory in this repository
 * whose edits may or may not reach the environment. The second is the one that surprises people, so
 * it is the one the tree marks.
 */
sealed interface EnvSource {

    /** A package index — PyPI or a mirror. The ordinary case, and drawn as nothing at all. */
    data object Index : EnvSource

    /** One of this workspace's own modules, installed from its own directory. */
    data object Member : EnvSource

    /**
     * A project on this machine that is not a module of the workspace: a path dependency.
     *
     * [editable] is the half that matters. An editable install runs the code in [path]; one that is
     * not is a built copy, and edits to [path] do not reach the environment until it is reinstalled.
     */
    data class Local(val path: Path, val editable: Boolean) : EnvSource

    /** A git repository, as the lock records it — usually with the commit after a `#`. */
    data class Git(val url: String) : EnvSource

    /** A built archive — a wheel or an sdist — named directly, by URL or by file path. */
    data class Archive(val location: String) : EnvSource
}

/**
 * A project whose sources are here, heading the lists it declares.
 *
 * The workspace root and its members, which are what a workspace *is*, and also every local
 * [EnvSource.Local] project the environment installs from a directory — which is what a person
 * pointing at `folder/foo` in their repository expects to find as a project of its own, rather than
 * only as something another project happens to pull in.
 */
data class EnvProject(
    /** `[project] name`, or null for a virtual workspace root, which has none. */
    val name: String?,
    val version: String?,
    /** The project's directory, or null when the backend did not say. */
    val path: Path?,
    val role: EnvProjectRole,
    /** Its lists, in the order they are shown. */
    val groups: List<EnvDependencyGroup>,
    /** False only for a [EnvProjectRole.LOCAL] project installed as a copy. See [EnvSource.Local]. */
    val editable: Boolean = true,
)

/** What a project is to the workspace, which decides what can be done to its lists. */
enum class EnvProjectRole {
    /** The workspace root — the project the lock file and the environment belong to. */
    ROOT,

    /** A workspace member: its manifest is edited with `--package`. */
    MEMBER,

    /**
     * A path dependency. Resolved and installed with the workspace but not part of it, so no
     * command edits its manifest from here — its lists are read-only. See [EnvDependencyGroup.writable].
     */
    LOCAL,
}

/** Every project's lists, in the order they are shown. */
data class EnvDependencyGraph(val projects: List<EnvProject>) {

    /** Every list of every project, flattened in display order. */
    val groups: List<EnvDependencyGroup> get() = projects.flatMap { it.groups }

    companion object {
        val EMPTY: EnvDependencyGraph = EnvDependencyGraph(emptyList())
    }
}

/**
 * One dependency list, addressed completely: which module declares it, and which of that module's
 * lists it is.
 *
 * The pair, rather than [EnvDependencyTarget] alone, is what an add or a remove has to be told. A
 * workspace has a manifest per module and every one of them may declare a `dependencies` and a
 * `dev`, so the target on its own names several lists at once — and acting on the wrong one is not
 * a visible failure: `uv remove` without `--package` edits the *root* manifest and reports success
 * having removed the sibling's requirement from nowhere. Modelling the module as part of the
 * address is what stops that being expressible.
 *
 * See [EnvOp.Add.module] for what [module] becomes on the command line.
 */
data class EnvDependencyList(
    val target: EnvDependencyTarget,
    /**
     * The module whose manifest declares this list, or null for the project's own.
     *
     * Null for every list of a project that is not a workspace, so a single-package project
     * produces exactly the commands it produced before modules were modelled at all.
     */
    val module: String? = null,
)

/** The dependencies declared under one [list], and everything they pull in. */
data class EnvDependencyGroup(
    val list: EnvDependencyList,
    /** The declared requirements themselves; their transitive dependencies are their children. */
    val roots: List<EnvDependencyNode>,
    /**
     * True when the backend can add to and remove from this list.
     *
     * False for a [EnvProjectRole.LOCAL] project's: `uv add --package foo` and `uv remove --package
     * foo` both answer "The workspace does not have a member foo" (uv 0.12.13), so offering *Add* or
     * *Remove* there is offering a button that fails.
     */
    val writable: Boolean = true,
) {
    val target: EnvDependencyTarget get() = list.target

    /** The module this list belongs to — see [EnvDependencyList.module]. */
    val module: String? get() = list.module

    /** Every distinct package under this list, declared or transitive. For the group's count. */
    fun packageCount(): Int {
        val seen = HashSet<String>()
        fun walk(nodes: List<EnvDependencyNode>) {
            for (node in nodes) {
                seen += node.name
                walk(node.children)
            }
        }
        walk(roots)
        return seen.size
    }
}

/** An environment that exists on disk. */
data class ManagedEnvironment(
    /** [EnvBackend.id] of whichever backend produced this. */
    val backendId: String,
    /** The environment root — the directory holding `pyvenv.cfg` for a venv-shaped backend. */
    val root: Path,
    /** The interpreter inside it. */
    val python: Path,
    /** Its Python version as the environment itself records it, or null when that could not be read. */
    val pythonVersion: String?,
)

/**
 * Whether the environment matches what the project declares.
 *
 * The distinction that matters is [UNKNOWN] versus [IN_SYNC]: a backend that cannot answer cheaply
 * must not be allowed to report "fine", or the UI would tell the user everything is in order on the
 * strength of never having asked.
 */
enum class EnvDrift {
    /** The environment matches the project's declared and locked dependencies. */
    IN_SYNC,

    /** Syncing would change something — a dependency added, removed, or at the wrong version. */
    OUT_OF_SYNC,

    /** Not established: no environment, no backend, or the probe could not be run. */
    UNKNOWN,
}
