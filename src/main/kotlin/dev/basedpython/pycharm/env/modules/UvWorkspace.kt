package dev.basedpython.pycharm.env.modules

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * A uv workspace, as uv lists it.
 *
 * Which directories are members is uv's decision, and it is taken from uv: `uv workspace list
 * --paths` ([dev.basedpython.pycharm.env.manager.EnvOp.ListModules]) is the member list uv itself
 * resolves every other command against. This used to be a glob walk of the plugin's own, and it
 * disagreed with uv in exactly the cases nobody would think to check — a star under `packages` admits
 * `packages/build` and `packages/.hidden` to uv, and the walk pruned both — which left modules uv
 * would build, lock and install invisible in the structure page.
 *
 * What is read here is what uv's listing does not carry: each module's own manifest — its name,
 * version and dependencies — and the root's `members` and `exclude` entries, which removing a module
 * has to edit.
 */
internal object UvWorkspace {

    /**
     * The structure at [projectRoot], given the directories uv listed as its members, or null when
     * there is no project there at all.
     *
     * Null and empty are different answers and both are reachable: null is "no `pyproject.toml`", so
     * there is nothing to show and nothing to create a module in, while a layout with a root and no
     * members is an ordinary single-package project — which is exactly the project the *New module*
     * button turns into a workspace.
     *
     * uv prints canonical paths, and the project root the IDE was handed need not be one — a
     * checkout under a symlinked directory, or `/tmp` on macOS. Each listed directory is therefore
     * placed relative to the root's real path and resolved back against [projectRoot], so every
     * module lives under the same spelling of the project the rest of the IDE uses.
     */
    fun read(projectRoot: Path, listed: List<Path>): ModuleLayout? {
        val rootManifest = manifestAt(projectRoot) ?: return null
        val patterns = rootManifest.workspaceMembers
        val realRoot = runCatching { projectRoot.toRealPath() }.getOrDefault(projectRoot)

        val members = listed
            .map { directory -> underProject(projectRoot, realRoot, directory) }
            .distinct()
            .filter { it != projectRoot }
            .mapNotNull { directory ->
                val manifest = manifestAt(directory) ?: return@mapNotNull null
                if (!manifest.isProject) return@mapNotNull null
                module(projectRoot, directory, manifest, patterns)
            }
            .sortedBy { it.relativePath }

        return ModuleLayout(
            root = rootManifest.takeIf { it.isProject }
                ?.let { module(projectRoot, projectRoot, it, patterns) },
            members = members,
            memberPatterns = patterns,
            excludePatterns = rootManifest.workspaceExclude,
        )
    }

    /** [directory], as uv printed it, re-expressed under [projectRoot] when it lies inside it. */
    private fun underProject(projectRoot: Path, realRoot: Path, directory: Path): Path {
        val real = runCatching { directory.toRealPath() }.getOrDefault(directory)
        if (!real.startsWith(realRoot)) return directory
        return projectRoot.resolve(realRoot.relativize(real).toString()).normalize()
    }

    /** The manifest in [directory], or null when it has none or it could not be read. */
    private fun manifestAt(directory: Path): PyprojectManifest? {
        val file = directory.resolve(MANIFEST)
        if (!Files.isRegularFile(file)) return null
        val text = runCatching { Files.readString(file) }.getOrNull() ?: return null
        return runCatching { PyprojectManifest.parse(text) }.getOrNull()
    }

    private fun module(
        projectRoot: Path,
        directory: Path,
        manifest: PyprojectManifest,
        patterns: List<String>,
    ): ProjectModule {
        val relative = relativePath(projectRoot, directory)
        return ProjectModule(
            // A member whose manifest omits `[project] name` cannot be a member at all — uv refuses
            // to load the workspace — but the root can legitimately be a bare configuration file, and
            // falling back to the directory name keeps that project showing something truthful.
            name = manifest.name ?: directory.fileName?.toString().orEmpty(),
            root = directory,
            relativePath = relative,
            version = manifest.version,
            description = manifest.description,
            requiresPython = manifest.requiresPython,
            dependencies = manifest.dependencies,
            packaged = manifest.hasBuildSystem,
            isRoot = directory == projectRoot,
            memberEntry = patterns.firstOrNull { isLiteral(it) && normalizePattern(it) == relative },
        )
    }

    /**
     * Glob matching, on `/`-separated relative paths.
     *
     * Not how members are found — uv answers that, see [read]. This is for the one question uv's
     * listing cannot answer, whether an `exclude` entry names a directory `members` also names. The
     * platform's own matcher: `*` stays inside a directory level and `**` crosses them. Paths are
     * rebuilt from the relative string rather than passed through as filesystem paths so that a
     * pattern written with `/` — the only separator uv accepts — matches on Windows too.
     */
    fun matches(relativePath: String, pattern: String): Boolean {
        val normalized = normalizePattern(pattern)
        if (normalized.isEmpty()) return false
        return runCatching {
            FileSystems.getDefault().getPathMatcher("glob:$normalized").matches(Path.of(relativePath))
        }.getOrDefault(false)
    }

    /** [directory] relative to [projectRoot], `/`-separated; empty when they are the same directory. */
    fun relativePath(projectRoot: Path, directory: Path): String = runCatching {
        projectRoot.relativize(directory).joinToString("/") { it.toString() }
    }.getOrDefault("")

    /** True when [pattern] names one directory rather than a shape — the kind that can be removed. */
    fun isLiteral(pattern: String): Boolean = normalizePattern(pattern).split('/').none { isWildcard(it) }

    private fun isWildcard(segment: String): Boolean =
        segment.contains('*') || segment.contains('?') || segment.contains('[') || segment.contains('{')

    /** Trailing slashes and `./` prefixes dropped, so `./packages/` and `packages` compare equal. */
    fun normalizePattern(pattern: String): String =
        pattern.trim().removePrefix("./").trim('/').trim()

    /** What a `pyproject.toml` is called. Named once so the scan and the watcher cannot disagree. */
    const val MANIFEST: String = "pyproject.toml"
}
