package dev.basedpython.pycharm.env.modules

import java.nio.file.FileSystems
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Reading a uv workspace off the disk.
 *
 * The root `pyproject.toml` names its members as globs relative to the root — a literal
 * `libs/thing`, a star under `packages`, a `**` — and every directory one of them matches that
 * holds a `pyproject.toml` is a module. That is uv's rule, and it is implemented here rather than
 * approximated, because the two ways of approximating it are both wrong in a way the user would
 * see: listing every nested `pyproject.toml` invents modules uv does not have (a vendored copy, a
 * test fixture), and listing only the literal entries loses every project a glob covers, which is
 * most of them.
 *
 * ### Cost
 *
 * Bounded by the patterns, not by the repository. A pattern is split at its first wildcard and the
 * walk starts from the literal part, no deeper than the pattern's remaining segments — so a star
 * under `packages` reads one directory listing, and only a `**` walks a tree. Directories that cannot
 * hold a module the user meant are pruned outright ([PRUNED]); an environment directory alone is
 * tens of thousands of files, and every one of them would be visited on a scan that runs whenever a
 * manifest is saved.
 */
internal object UvWorkspace {

    /**
     * The structure at [projectRoot], or null when there is no project there at all.
     *
     * Null and empty are different answers and both are reachable: null is "no `pyproject.toml`", so
     * there is nothing to show and nothing to create a module in, while a layout with a root and no
     * members is an ordinary single-package project — which is exactly the project the *New module*
     * button turns into a workspace.
     */
    fun read(projectRoot: Path): ModuleLayout? {
        val rootManifest = manifestAt(projectRoot) ?: return null
        val patterns = rootManifest.workspaceMembers
        val excludes = rootManifest.workspaceExclude

        val members = patterns
            .flatMap { expand(projectRoot, it) }
            .distinct()
            .filter { it != projectRoot }
            .filterNot { directory -> excludes.any { matches(projectRoot, directory, it) } }
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
            excludePatterns = excludes,
        )
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
     * The directories [pattern] matches under [projectRoot].
     *
     * Split at the first wildcard: everything before it is resolved as a path and everything after
     * it decides how deep the walk goes. A pattern with no wildcard at all is a single directory and
     * costs one `stat`.
     */
    private fun expand(projectRoot: Path, pattern: String): List<Path> {
        val normalized = normalizePattern(pattern)
        if (normalized.isEmpty()) return emptyList()
        val segments = normalized.split('/')
        val literal = segments.takeWhile { !isWildcard(it) }
        val base = literal.fold(projectRoot) { path, segment -> path.resolve(segment) }
        if (!Files.isDirectory(base)) return emptyList()
        if (literal.size == segments.size) return listOf(base)

        val rest = segments.drop(literal.size)
        // `**` crosses directories, so its depth is not knowable from the pattern; everything else
        // is exactly one directory level per remaining segment.
        val depth = if (rest.any { it.contains("**") }) MAX_DEPTH else rest.size
        return walk(base, depth).filter { matches(projectRoot, it, normalized) }
    }

    /** Directories under [base], at most [depth] levels down, with [PRUNED] never entered. */
    private fun walk(base: Path, depth: Int): List<Path> {
        val found = mutableListOf<Path>()
        runCatching {
            Files.walkFileTree(
                base,
                emptySet(),
                depth,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (dir == base) return FileVisitResult.CONTINUE
                        val name = dir.fileName?.toString().orEmpty()
                        if (isPruned(name)) return FileVisitResult.SKIP_SUBTREE
                        found.add(dir)
                        return FileVisitResult.CONTINUE
                    }

                    /**
                     * The deepest level arrives here, directories included.
                     *
                     * `walkFileTree` stops descending at `maxDepth` and hands everything at that
                     * depth to this method — so for the commonest pattern of all, one wildcard under
                     * a directory, *every* candidate comes through here and none through
                     * [preVisitDirectory]. Reading only that one would have found no members at all.
                     */
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val name = file.fileName?.toString().orEmpty()
                        if (attrs.isDirectory && !isPruned(name)) found.add(file)
                        return FileVisitResult.CONTINUE
                    }

                    /** A tree that cannot be read is not a tree with no modules in it; it is skipped. */
                    override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult =
                        FileVisitResult.SKIP_SUBTREE
                },
            )
        }
        return found
    }

    /** True when [directory] is what [pattern] describes, relative to [projectRoot]. */
    private fun matches(projectRoot: Path, directory: Path, pattern: String): Boolean {
        val relative = relativePath(projectRoot, directory).ifEmpty { return false }
        return matches(relative, pattern)
    }

    /**
     * Glob matching, on `/`-separated relative paths.
     *
     * The platform's own matcher, whose glob syntax is the one uv's is: `*` stays inside a directory
     * level and `**` crosses them. Paths are rebuilt from the relative string rather than passed
     * through as filesystem paths so that a pattern written with `/` — the only separator uv accepts
     * — matches on Windows too.
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

    private fun isPruned(name: String): Boolean = name in PRUNED || name.startsWith('.')

    /** What a `pyproject.toml` is called. Named once so the scan and the watcher cannot disagree. */
    const val MANIFEST: String = "pyproject.toml"

    /**
     * Directories a workspace member is never found in, and which are expensive to walk.
     *
     * `.venv` is the one that matters — it holds a `pyproject.toml` for every installed package that
     * ships one — but the rest are all directories a `**` pattern would otherwise descend into for
     * no possible result. Dot-directories are pruned as a class for the same reason; a member kept
     * inside one is not something uv's own documentation contemplates.
     */
    private val PRUNED: Set<String> = setOf(
        "node_modules", "__pycache__", "site-packages", "venv", "out", "dist", "build", "target",
    )

    /** How deep a `**` is allowed to go. Deep enough for any real layout, bounded against a symlink loop. */
    private const val MAX_DEPTH = 8
}
