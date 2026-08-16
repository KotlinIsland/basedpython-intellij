package dev.basedpython.pycharm.env.manager

import com.google.gson.JsonParser
import com.intellij.openapi.util.SystemInfo
import dev.basedpython.pycharm.env.ByEnvironments
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * uv as an [EnvBackend].
 *
 * Everything here was checked against uv 0.12.3: the argv each op produces, the JSON shapes
 * [parsePackages] and [parsePythons] read, and the exit codes `uv sync --check` returns (0 when the
 * environment matches the lock, 1 when syncing would change something).
 *
 * ### Why only one command names its environment
 *
 * uv discovers a project by walking up from the working directory, and the plugin always sets the
 * working directory to the project root — so `--project` would be redundant everywhere else.
 * `uv pip list` is the exception and gets an explicit `--python`, for the reason spelled out on
 * [EnvOp.ListPackages].
 */
object UvBackend : EnvBackend {

    override val id: String = "uv"
    override val displayName: String = "uv"
    override val executableName: String = "uv"
    override val installer: EnvToolInstaller = UvDownload
    override val projectMarkers: List<String> = listOf("uv.lock", "pyproject.toml")

    /**
     * `uv add`, `uv remove` and `uv lock` all rewrite these, and none of them goes through the IDE.
     *
     * `pyproject.toml` first: it is the one a user is likely to have open, and the one whose staleness
     * is visible.
     */
    override val managedFiles: List<String> = listOf("pyproject.toml", "uv.lock")

    override fun claims(projectRoot: Path): Boolean =
        projectMarkers.any { Files.isRegularFile(projectRoot.resolve(it)) }

    /**
     * `.venv` at the project root, or wherever `UV_PROJECT_ENVIRONMENT` points.
     *
     * The candidate list comes from [ByEnvironments.venvCandidatesAt] — the same function binary
     * resolution walks — so the two can never be looking at different sets of directories. Where
     * they differ is which one wins, and they differ for a good reason: resolution is asking "where
     * is `by`" and probes both, while this is asking "where will uv put the environment", and uv
     * obeys `UV_PROJECT_ENVIRONMENT` unconditionally. That override is therefore preferred here and
     * merely *tried* there, which is why this takes the last candidate rather than the first —
     * [ByEnvironments.venvCandidatesAt] lists the conventional `.venv` first.
     */
    override fun environmentRoot(projectRoot: Path): Path =
        ByEnvironments.venvCandidatesAt(projectRoot, System.getenv(UV_PROJECT_ENVIRONMENT))
            .lastOrNull()
            ?: projectRoot.resolve(ByEnvironments.VENV_DIR)

    override fun pythonExecutable(envRoot: Path): Path = ByEnvironments.venvBinary(envRoot, "python")

    override fun command(op: EnvOp): EnvCommand = when (op) {
        is EnvOp.Create ->
            EnvCommand(listOf("venv") + (op.python?.let { listOf("--python", it) } ?: emptyList()))

        EnvOp.Sync -> EnvCommand(listOf("sync"))

        // `--check` resolves and reports, and changes nothing. Its answer is the exit code.
        EnvOp.CheckSync -> EnvCommand(listOf("sync", "--check"), isQuery = true)

        EnvOp.Lock -> EnvCommand(listOf("lock"))

        EnvOp.Upgrade -> EnvCommand(listOf("lock", "--upgrade"))

        is EnvOp.Add ->
            EnvCommand(listOf("add") + targetFlags(op.target) + op.requirements)

        is EnvOp.Remove ->
            EnvCommand(listOf("remove") + targetFlags(op.target) + op.packages)

        // `--frozen` is load-bearing, not an optimisation. Without it `uv tree` re-locks the project
        // and writes `uv.lock` — verified against 0.12.3 on a project that had none — which would
        // make merely opening a project edit the user's repository, and make every save of
        // `pyproject.toml` rewrite the lock behind their back. Frozen reads the existing lock and
        // touches nothing; on a project with no lock it exits non-zero and the view falls back to
        // the flat installed list, which is the correct outcome for "there is nothing resolved yet".
        EnvOp.Tree ->
            EnvCommand(
                listOf("tree", "--all-groups", "--frozen", "--format", "json"),
                isQuery = true,
            )

        is EnvOp.ListPackages ->
            EnvCommand(listOf("pip", "list", "--format", "json") + pythonFlag(op.python), isQuery = true)

        // No `--only-installed`: the picker offers versions to install as well as ones already here,
        // and the download candidates are exactly the entries this leaves in.
        EnvOp.ListPythons ->
            EnvCommand(listOf("python", "list", "--output-format", "json"), isQuery = true)

        is EnvOp.InstallPython -> EnvCommand(listOf("python", "install", op.version))
    }

    /**
     * How `uv add` / `uv remove` are told which list to act on.
     *
     * `dev` goes out as `--group dev` rather than as uv's `--dev` shorthand. The two are the same
     * operation — `--dev` *is* `[dependency-groups].dev` — and spelling every group the one way
     * means there is no second code path for the group that happens to have a shorthand.
     */
    private fun targetFlags(target: EnvDependencyTarget): List<String> = when (target) {
        EnvDependencyTarget.Main -> emptyList()
        is EnvDependencyTarget.Group -> listOf("--group", target.name)
        is EnvDependencyTarget.Extra -> listOf("--optional", target.name)
    }

    private fun pythonFlag(python: Path?): List<String> =
        python?.let { listOf("--python", it.toString()) } ?: emptyList()

    /**
     * `uv pip list --format json` — an array of `{name, version, editable_project_location?}`.
     *
     * Sorted by name, case-insensitively, because uv's own order is the resolver's and a list that
     * reorders itself between refreshes is unreadable.
     */
    override fun parsePackages(stdout: String): List<EnvPackage> =
        parseArray(stdout) { obj ->
            val name = obj.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@parseArray null
            EnvPackage(
                name = name,
                version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                editableLocation = obj.get("editable_project_location")
                    ?.takeIf { it.isJsonPrimitive }?.asString,
            )
        }.sortedBy { it.name.lowercase() }

    /**
     * `uv python list --output-format json` — an array of interpreter entries.
     *
     * An entry with a `path` is on this machine; one without is a download uv offers. Both are kept,
     * and the same interpreter can legitimately appear more than once (a symlink in `~/.local/bin`
     * and the real thing under uv's own directory), so entries are de-duplicated on key and version
     * with the installed copy winning — a version the user already has must never be presented as a
     * download.
     */
    override fun parsePythons(stdout: String): List<PythonCandidate> {
        val parsed = parseArray(stdout) { obj ->
            val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asString ?: return@parseArray null
            val rawPath = obj.get("path")?.takeIf { it.isJsonPrimitive }?.asString
            PythonCandidate(
                key = obj.get("key")?.takeIf { it.isJsonPrimitive }?.asString ?: version,
                version = version,
                implementation = obj.get("implementation")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: "cpython",
                path = rawPath?.let { runCatching { Paths.get(it) }.getOrNull() },
            )
        }
        return parsed
            .groupBy { it.key to it.version }
            .map { (_, group) -> group.firstOrNull { it.isInstalled } ?: group.first() }
            .sortedWith(compareByDescending<PythonCandidate> { it.isInstalled }.thenBy { it.key })
    }

    /**
     * Reads a JSON array of objects, mapping each with [map] and dropping what it rejects.
     *
     * Tolerant on purpose: this parses another program's output, and a uv that grows a field, emits
     * a warning line before the array, or is interrupted mid-write must degrade to a shorter list
     * rather than take the refresh down.
     */
    private fun <T : Any> parseArray(
        stdout: String,
        map: (com.google.gson.JsonObject) -> T?,
    ): List<T> = try {
        val root = JsonParser.parseString(stdout.trim().ifEmpty { "[]" })
        if (!root.isJsonArray) {
            emptyList()
        } else {
            root.asJsonArray.mapNotNull { element ->
                element.takeIf { it.isJsonObject }?.asJsonObject?.let(map)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    override fun parseTree(stdout: String): List<EnvDependencyGroup> = UvTree.parse(stdout)

    /**
     * `uv sync --check`: 0 when the environment already matches, 1 when it would change.
     *
     * Anything else is a uv that failed for its own reasons — no network to resolve against, an
     * unparseable `pyproject.toml` — and reporting that as "in sync" or "drifted" would both be
     * inventing an answer.
     */
    override fun driftFromExitCode(exitCode: Int): EnvDrift = when (exitCode) {
        0 -> EnvDrift.IN_SYNC
        1 -> EnvDrift.OUT_OF_SYNC
        else -> EnvDrift.UNKNOWN
    }

    /** uv's override for where a project's environment lives. */
    private const val UV_PROJECT_ENVIRONMENT = "UV_PROJECT_ENVIRONMENT"
}

/**
 * Where to fetch uv itself.
 *
 * uv publishes a static, dependency-free binary per target on every release, which is what makes
 * the one-click bootstrap honest: the plugin downloads one file and unpacks one entry, rather than
 * piping an install script into a shell — which is uv's documented install method and not something
 * this plugin is going to do to a user's machine on a button press.
 *
 * The `latest/download` redirect is used rather than a pinned version. uv is not this plugin's
 * dependency in any versioned sense — it is the tool the user would otherwise install themselves —
 * and pinning would mean shipping a plugin update to keep up with it.
 */
object UvDownload : EnvToolInstaller {

    const val BASE_URL: String = "https://github.com/astral-sh/uv/releases/latest/download"

    /** uv's release target triples, keyed by the OS/arch pair they are built for. */
    override fun plan(osName: String?, osArch: String?): EnvToolDownload? {
        val name = osName?.lowercase()?.trim() ?: return null
        val arch = osArch?.lowercase()?.trim() ?: return null
        val isArm = arch.contains("aarch64") || arch.contains("arm64") || arch == "arm"
        val is64 = arch.contains("64") || arch == "amd64" || arch == "x86_64"

        val triple = when {
            name.contains("mac") || name.contains("darwin") || name.contains("os x") ->
                if (isArm) "aarch64-apple-darwin" else "x86_64-apple-darwin"
            // ARM before width: `aarch64` also satisfies [is64], so testing the width first would
            // call every Windows-on-ARM machine x64.
            name.contains("win") ->
                if (isArm) "aarch64-pc-windows-msvc" else if (is64) "x86_64-pc-windows-msvc" else return null
            name.contains("nux") || name.contains("nix") ->
                if (isArm) "aarch64-unknown-linux-gnu" else if (is64) "x86_64-unknown-linux-gnu" else return null
            else -> return null
        }

        val windows = triple.contains("windows")
        // Windows releases are zipped and hold `uv.exe` at the archive root; the rest are tarballs
        // holding `uv-<triple>/uv`. Matching on the suffix covers both without hard-coding either.
        return EnvToolDownload(
            url = "$BASE_URL/uv-$triple${if (windows) ".zip" else ".tar.gz"}",
            archive = if (windows) EnvToolDownload.ArchiveKind.ZIP else EnvToolDownload.ArchiveKind.TAR_GZ,
            memberSuffix = if (windows) "uv.exe" else "uv",
            fileName = if (windows) "uv.exe" else "uv",
        )
    }

    /** The plan for the machine this is running on, or null when uv publishes nothing for it. */
    fun current(): EnvToolDownload? = plan(
        if (SystemInfo.isWindows) "windows" else System.getProperty("os.name"),
        System.getProperty("os.arch"),
    )
}
