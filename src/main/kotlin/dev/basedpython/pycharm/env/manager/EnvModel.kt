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
