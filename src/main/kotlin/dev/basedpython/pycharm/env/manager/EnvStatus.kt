package dev.basedpython.pycharm.env.manager

import java.nio.file.Path

/**
 * Everything known about a project's environment at one moment.
 *
 * Assembled by [EnvService] and read by everything that displays or acts on the environment. A
 * single immutable value rather than a bag of fields on the service, so the tool window, the banner
 * and an action all render the same instant — the alternative is a view that shows a package list
 * from before a sync next to a drift verdict from after it.
 */
data class EnvStatus(
    /** The project root, or null for a project with no base path (the default project). */
    val projectRoot: Path?,
    /** The backend that claims this project, or null when none does. */
    val backend: EnvBackend?,
    /** Where the backend's tool was found, or null when it is not installed. */
    val toolPath: Path?,
    /** Where the environment goes, whether or not it is there yet. Null when there is no backend. */
    val environmentRoot: Path?,
    /** The environment, when it exists on disk. */
    val environment: ManagedEnvironment?,
    val drift: EnvDrift,
    /**
     * What is installed in the environment, flat.
     *
     * Ground truth about this machine, and therefore what [dependencies] is checked against: the
     * tree describes what the project *resolves to*, and the difference between the two is exactly
     * what drift looks like when you point at it.
     */
    val packages: List<EnvPackage>,
    /**
     * What the project declares, grouped by where it is declared, with transitive dependencies
     * beneath each.
     *
     * Empty when the backend cannot produce one, or when there is nothing resolved to produce it
     * from — a project with no lock file. The view falls back to listing [packages] flat, which is
     * still the truthful answer to "what is in this environment".
     */
    val dependencies: List<EnvDependencyGroup> = emptyList(),
    /**
     * Why the last refresh could not finish, when it could not.
     *
     * Carried so the view can say what went wrong instead of rendering the empty state, which would
     * claim there is no environment on the strength of a probe that failed.
     */
    val error: String? = null,
) {

    /** The single question the UI leads with: what, if anything, needs doing. */
    val health: EnvHealth
        get() = when {
            backend == null -> EnvHealth.UNMANAGED
            toolPath == null -> EnvHealth.TOOL_MISSING
            environment == null -> EnvHealth.NO_ENVIRONMENT
            drift == EnvDrift.OUT_OF_SYNC -> EnvHealth.OUT_OF_SYNC
            else -> EnvHealth.READY
        }

    companion object {
        /** Before anything has looked. Distinct from a project that genuinely has no backend. */
        fun unknown(projectRoot: Path?): EnvStatus = EnvStatus(
            projectRoot = projectRoot,
            backend = null,
            toolPath = null,
            environmentRoot = null,
            environment = null,
            drift = EnvDrift.UNKNOWN,
            packages = emptyList(),
        )
    }
}

/**
 * What the user is being asked to do about the environment, if anything.
 *
 * Ordered from "nothing here to manage" to "ready", which is also the order in which each state
 * becomes reachable: a project has to be claimed before its tool can be missing, and the tool has to
 * exist before an environment can.
 */
enum class EnvHealth {
    /** No backend claims this project — it is not a Python project this plugin can manage. */
    UNMANAGED,

    /** The backend's tool is not installed. One click away when the backend can install itself. */
    TOOL_MISSING,

    /** The project is managed and the tool is here, but the environment has not been created. */
    NO_ENVIRONMENT,

    /** The environment exists but does not match what the project declares. */
    OUT_OF_SYNC,

    /** Nothing to do. */
    READY,
    ;

    /** True when this state is one the user can fix from the tool window in a single action. */
    val isActionable: Boolean
        get() = this == TOOL_MISSING || this == NO_ENVIRONMENT || this == OUT_OF_SYNC
}
