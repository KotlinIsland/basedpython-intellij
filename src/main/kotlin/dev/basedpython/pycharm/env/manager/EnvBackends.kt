package dev.basedpython.pycharm.env.manager

import java.nio.file.Path

/**
 * Every environment manager the plugin knows how to drive, in the order they are consulted.
 *
 * ### Adding conda or pixi
 *
 * Write the [EnvBackend] and add it to [ALL]. That is the whole change — the service, the tool
 * window, the actions and the drift banner are all written against the interface.
 *
 * Order is the one thing to think about, and it is only consulted when a project has markers for
 * more than one manager, which is common in practice: a `pixi.toml` project also has a
 * `pyproject.toml`, and a conda project often has both. The rule is **most specific first** — a
 * backend whose marker is unambiguous (`pixi.toml`, `environment.yml`) goes ahead of uv, whose
 * `pyproject.toml` marker is shared with every other Python tool in existence. uv is last for that
 * reason, not because it is least preferred.
 */
object EnvBackends {

    val ALL: List<EnvBackend> = listOf(UvBackend)

    /** The backend with [id], or null. Used to read a persisted [ManagedEnvironment.backendId]. */
    fun byId(id: String?): EnvBackend? = ALL.firstOrNull { it.id == id }

    /** The first backend that claims [projectRoot], or null when none does. */
    fun detect(projectRoot: Path): EnvBackend? = ALL.firstOrNull { it.claims(projectRoot) }

    /**
     * Every file name any backend treats as a project marker.
     *
     * What the tool window's availability check and the file watcher key on, so a project that grows
     * its first `pyproject.toml` gets the window without a restart.
     */
    val ALL_MARKERS: Set<String> = ALL.flatMapTo(LinkedHashSet()) { it.projectMarkers }
}
