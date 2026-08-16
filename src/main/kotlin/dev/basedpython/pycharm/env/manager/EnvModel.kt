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
 * Where a dependency is declared — which is also what has to be named to add or remove one.
 *
 * A project's requirements are not one list. There is the main list every install gets, optional
 * extras a consumer opts into, and named groups (`dev` chief among them) that are a development
 * concern and never ship. The three are declared in different places and are added and removed with
 * different flags, so "remove httpx" is not answerable without knowing which of them it came from —
 * which is the concrete reason the tree below is grouped rather than flat.
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
)

/** The dependencies declared under one [target], and everything they pull in. */
data class EnvDependencyGroup(
    val target: EnvDependencyTarget,
    /** The declared requirements themselves; their transitive dependencies are their children. */
    val roots: List<EnvDependencyNode>,
) {
    /** Every distinct package under this target, declared or transitive. For the group's count. */
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
