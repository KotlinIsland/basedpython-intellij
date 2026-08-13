package dev.basedpython.pycharm.env

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.EnvironmentUtil
import dev.basedpython.pycharm.env.bundled.BundledBinaries
import dev.basedpython.pycharm.env.download.ByBinaryDownloadPlan
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a `by` / `buff` invocation gets its Python environment.
 *
 * Persisted by id, so the ids are part of the run-configuration file format and must not change.
 */
enum class ByEnvironmentKind(val id: String, val display: String) {
    /** Try every source in [ByEnvironments.resolve]'s documented order. */
    AUTO("auto", "Auto-detect"),

    /** A `.venv` found by walking up from the content root / project base. */
    VENV("venv", "Project .venv"),

    /**
     * `uv run --project <dir> <binary>` — uv creates and syncs the environment as needed.
     *
     * Opt-in only, never part of [AUTO]: `uv run` will create a `.venv`, write a `uv.lock`, and
     * download a CPython toolchain if the environment does not exist yet. That is the right
     * behaviour when the user asks for it and an unacceptable side effect of merely opening a file.
     * The bootstrap path with consent is the "Install with uv" editor banner
     * ([dev.basedpython.pycharm.env.ByMissingBannerProvider]).
     */
    UV("uv", "uv (managed)"),

    /** The venv behind the configured Python interpreter (SDK), when one is set. */
    SDK("sdk", "Python interpreter (SDK)"),

    /** The plugin's own `~/.basedpython/bin`, populated by the binary download action. */
    DOWNLOADED("downloaded", "Downloaded binary"),

    /** The binaries shipped inside the plugin itself, when this is a bundled distribution. */
    BUNDLED("bundled", "Bundled with plugin"),

    /** Whatever is on `PATH`, with no venv activation. */
    PATH("path", "PATH"),
    ;

    companion object {
        /** Unknown and blank ids degrade to [AUTO] rather than throwing — see `ByCommonOptions`. */
        fun fromId(id: String?): ByEnvironmentKind = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * A fully-resolved launch.
 *
 * [prependArgs] is what turns uv into just another source rather than a special case: uv contributes
 * `exe=uv, prependArgs=["run", "--project", dir, "by"]` while every venv-backed source contributes
 * `exe=<venv>/bin/by, prependArgs=[]`. Callers concatenate [prependArgs] + their own arguments and
 * never branch on [kind].
 *
 * [env] carries venv activation (see [ByEnvironments.activationEnv]) and is empty for non-venv sources.
 */
data class ByLaunch(
    val exe: Path,
    val prependArgs: List<String>,
    val env: Map<String, String>,
    val venvRoot: Path?,
    val kind: ByEnvironmentKind,
    /**
     * True when the explicitly configured binary path produced this launch rather than [kind]'s
     * source.
     *
     * A flag rather than a [ByEnvironmentKind] value on purpose. As a kind it would have to be
     * excluded from the run-configuration picker (nobody *chooses* "an override happened"), and a
     * non-editable combo box silently ignores a selection that is not in its model — so a stored
     * value of that kind would load as [ByEnvironmentKind.AUTO] and be written back on apply,
     * silently destroying the setting.
     */
    val fromOverride: Boolean = false,
) {
    /** The full command, for display in UI and logs. */
    fun describe(): String = (listOf(exe.toString()) + prependArgs).joinToString(" ")

    /** Which source produced this launch, for the detection label. */
    val sourceLabel: String get() = if (fromOverride) "Configured path" else kind.display
}

/**
 * Single source of truth for locating a basedpython binary and the environment it runs in.
 *
 * Resolution used to live in three places that disagreed ([dev.basedpython.pycharm.lsp.BasedPythonBinaries],
 * the pdb action's own `python` lookup, and [UvSupport]); they all funnel through here now.
 *
 * The important part beyond locating the binary is [activationEnv]: resolving `.venv/bin/by` and then
 * running it with the IDE's inherited environment means anything `by` spawns can escape the venv it
 * came from. A venv-backed launch sets `VIRTUAL_ENV` and puts the venv's bin directory at the front
 * of `PATH`, which is what activation actually is.
 */
object ByEnvironments {

    private val LOG = Logger.getInstance(ByEnvironments::class.java)

    /** How far up from a start directory to look for a `.venv`. */
    private const val MAX_WALK_UP = 5

    const val VENV_DIR: String = ".venv"

    /** uv's override for where a project's environment lives. A *path*, not a directory name. */
    private const val UV_PROJECT_ENVIRONMENT = "UV_PROJECT_ENVIRONMENT"

    /** Marker file present at the root of every PEP 405 virtual environment. */
    private const val VENV_MARKER = "pyvenv.cfg"

    // --- Pure path helpers (unit tested) ------------------------------------

    /** The `bin` (POSIX) / `Scripts` (Windows) directory of a venv rooted at [venvRoot]. */
    fun venvBinDir(venvRoot: Path): Path =
        if (SystemInfo.isWindows) venvRoot.resolve("Scripts") else venvRoot.resolve("bin")

    /** Path to [binary] inside the venv rooted at [venvRoot] (adds `.exe` on Windows). */
    fun venvBinary(venvRoot: Path, binary: String): Path =
        venvBinDir(venvRoot).resolve(if (SystemInfo.isWindows) "$binary.exe" else binary)

    /**
     * Ordered, de-duplicated directories to begin the venv walk-up from. The file's content root
     * takes precedence over the project base so a per-module `.venv` wins in a multi-root project.
     */
    fun searchStartDirs(contentRoot: Path?, projectBase: Path?): List<Path> =
        listOfNotNull(contentRoot, projectBase).distinct()

    /**
     * The venv root for an interpreter at [pythonExe] (`<root>/bin/python`), or `null` when the
     * interpreter is not inside a virtual environment (a system Python, say).
     *
     * Confirmed via `pyvenv.cfg` rather than by assuming the directory layout, so a system
     * interpreter never gets mistaken for a venv.
     */
    fun venvRootOfInterpreter(pythonExe: Path): Path? {
        val root = pythonExe.parent?.parent ?: return null
        return root.takeIf { Files.isRegularFile(it.resolve(VENV_MARKER)) }
    }

    /**
     * Environment variables that activate the venv at [venvRoot].
     *
     * Mirrors what `activate` does: point `VIRTUAL_ENV` at the root, prepend the venv's bin
     * directory to `PATH`, and drop `PYTHONHOME` (which would otherwise override the venv).
     * [parentPath] is the `PATH` to prepend onto — defaults to the IDE's own.
     *
     * `PYTHONHOME` is cleared by setting it to the empty string: [com.intellij.execution.configurations.GeneralCommandLine]
     * has no "unset this variable" operation, and an empty `PYTHONHOME` is ignored by CPython.
     */
    fun activationEnv(venvRoot: Path, parentPath: String? = EnvironmentUtil.getValue("PATH")): Map<String, String> {
        val bin = venvBinDir(venvRoot).toString()
        val path = if (parentPath.isNullOrEmpty()) bin else bin + File.pathSeparator + parentPath
        return linkedMapOf(
            "VIRTUAL_ENV" to venvRoot.toString(),
            "PATH" to path,
            "PYTHONHOME" to "",
        )
    }

    // --- Sources ------------------------------------------------------------

    /**
     * Walk up from [startDirs] (at most [MAX_WALK_UP] hops each) looking for a `.venv` that actually
     * contains [binary], and return that venv's root.
     *
     * Keyed on the binary being present rather than on the directory merely existing, so a venv that
     * has not had basedpython installed into it does not shadow one further up that has.
     * Pure apart from the executable check — unit tested.
     */
    fun findVenvWithBinary(startDirs: List<Path>, binary: String): Path? {
        val uvEnv = EnvironmentUtil.getValue(UV_PROJECT_ENVIRONMENT)
        for (start in startDirs) {
            var dir: Path? = start
            var hops = 0
            while (dir != null && hops <= MAX_WALK_UP) {
                for (venv in venvCandidatesAt(dir, uvEnv)) {
                    if (Files.isExecutable(venvBinary(venv, binary))) return venv
                }
                dir = dir.parent
                hops++
            }
        }
        return null
    }

    /** True when [dir] is the root of a uv project. */
    private fun isUvProjectRoot(dir: Path): Boolean =
        Files.isRegularFile(dir.resolve("uv.lock")) || Files.isRegularFile(dir.resolve("pyproject.toml"))

    /**
     * The venv directories worth probing at [dir], conventional first.
     *
     * `.venv` always. Plus, **only when [dir] is a uv project root**, uv's
     * [UV_PROJECT_ENVIRONMENT] — which lets a uv project put its environment elsewhere, and without
     * which such a project could never auto-detect `by`: the "Install with uv" banner's
     * `uv add --dev basedpython` would succeed into a directory detection cannot see, so the banner
     * would reappear forever with no way out.
     *
     * The project-root restriction is load-bearing, not tidiness. The variable is a *path* resolved
     * against the uv project root (verified against uv 0.11.28: `envA`, `build/envB`, and absolute
     * values all resolve from the root, never from the CWD). Probing it at every hop would mean an
     * absolute value matches at hop 0 of every start directory — so a single exported
     * `UV_PROJECT_ENVIRONMENT=/abs/env` would shadow the real `.venv` of every unrelated project
     * open in the IDE. Consulting it only where uv itself would keeps this project-scoped.
     *
     * `.venv` is listed first so the conventional layout wins when both exist.
     */
    fun venvCandidatesAt(dir: Path, uvProjectEnvironment: String?): List<Path> {
        val candidates = mutableListOf(dir.resolve(VENV_DIR))
        if (!uvProjectEnvironment.isNullOrBlank() && isUvProjectRoot(dir)) {
            // resolve() handles both shapes uv accepts: relative to the root, or absolute as-is.
            guarded { dir.resolve(uvProjectEnvironment) }?.let(candidates::add)
        }
        return candidates.distinct()
    }

    /**
     * The venv behind a configured Python interpreter, or `null`.
     *
     * Deliberately reads the SDK through platform-only API ([ProjectRootManager] / [ModuleRootManager]).
     * The Python plugin is not a dependency and is not bundled in the IDE this plugin targets
     * (see FEATURES.md §5) — but a *configured* Python SDK is still visible as a plain [Sdk] whose
     * `homePath` is the interpreter. That is all we need to find the venv, so no dependency on
     * `com.jetbrains.python` is required and nothing breaks when the plugin is absent.
     */
    fun sdkVenvRoot(project: Project): Path? {
        val sdk = pythonSdk(project) ?: return null
        val home = sdk.homePath ?: return null
        return guarded { venvRootOfInterpreter(Paths.get(home)) }
    }

    /** The project SDK if it is a Python one, else the first module SDK that is. */
    private fun pythonSdk(project: Project): Sdk? {
        if (project.isDefault) return null
        return guarded {
            ProjectRootManager.getInstance(project).projectSdk?.takeIf(::isPythonSdk)
                ?: ModuleManager.getInstance(project).modules
                    .firstNotNullOfOrNull { ModuleRootManager.getInstance(it).sdk?.takeIf(::isPythonSdk) }
        }
    }

    /**
     * Matches on the SDK type's *name* rather than on `PythonSdkType`, which would need the Python
     * plugin on the classpath. When that plugin is absent the persisted type degrades to an unknown
     * type that still reports its original name, so this keeps working either way.
     */
    private fun isPythonSdk(sdk: Sdk): Boolean =
        guarded { sdk.sdkType.name }?.contains("Python", ignoreCase = true) == true

    /** The binary shipped inside the plugin (`<plugin>/bin`), when this distribution carries one. */
    private fun bundledBinary(binary: String): Path? = BundledBinaries.find(binary)

    /** The plugin-managed download directory (`~/.basedpython/bin`), when it holds [binary]. */
    private fun downloadedBinary(binary: String): Path? {
        val home = System.getProperty("user.home") ?: return null
        val platform = ByBinaryDownloadPlan.detectPlatform(
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
        ) ?: return null
        return ByBinaryDownloadPlan.installPath(home, binary, platform).takeIf { Files.isExecutable(it) }
    }

    /**
     * The content root of [file] as an NIO path, or `null`.
     *
     * The whole lookup is guarded, not just `toNioPath()`: the index query itself can fail (it
     * asserts read access, and this runs from LSP startup and from process launch, neither of which
     * holds one implicitly). A missing content root only costs the multi-root preference, so
     * degrading to the project base beats propagating out of resolution.
     */
    private fun contentRootPath(project: Project, file: VirtualFile?): Path? {
        if (file == null || project.isDefault) return null
        return guarded {
            val index = ProjectFileIndex.getInstance(project)
            val root = index.getContentRootForFile(file) ?: index.getSourceRootForFile(file)
            root?.toNioPath()
        }
    }

    private fun basePath(project: Project): Path? = project.basePath?.let { guarded { Paths.get(it) } }

    // --- Resolution ---------------------------------------------------------

    /**
     * Resolve [binary] for [project].
     *
     * For [ByEnvironmentKind.AUTO] the order is:
     *
     *  0. [override] — the configured binary path, when it is executable.
     *  1. `.venv` walk-up from the content root, then the project base — the long-standing behaviour,
     *     kept first so existing projects resolve exactly as they did before.
     *  2. The venv behind a configured Python SDK, when it contains [binary].
     *  3. The plugin's own download directory.
     *  4. The binaries bundled in the plugin, if this distribution carries any.
     *  5. `PATH`.
     *
     * Bundled sits *after* the download directory — a binary the user went and fetched is a newer,
     * deliberate choice and should not be shadowed by whatever the plugin shipped with — and
     * *before* `PATH`, because the bundled pair is known to match this plugin build while anything
     * on `PATH` is whatever the machine happens to have.
     *
     * [ByEnvironmentKind.UV] is deliberately absent from that chain — it can create environments and
     * download interpreters, so it only ever runs when explicitly selected. See [ByEnvironmentKind.UV].
     *
     * A non-AUTO [kind] restricts resolution to that single source and fails rather than falling back,
     * so an explicit choice is never silently redirected to a different environment. **[override] does
     * not apply to a non-AUTO [kind]**: it is layered over an IDE-wide default
     * ([dev.basedpython.pycharm.settings.BasedPythonSettings.effectiveByPath]), so honouring it there
     * would let a global preference beat a per-run-configuration choice — precedence backwards. It also
     * cannot express uv at all, so an override plus `kind = UV` would silently never run uv while the
     * picker still read "uv (managed)".
     *
     * Returns `null` when nothing matches — callers must handle that and not launch.
     */
    fun resolve(
        project: Project,
        binary: String,
        contextFile: VirtualFile? = null,
        kind: ByEnvironmentKind = ByEnvironmentKind.AUTO,
        override: String? = null,
    ): ByLaunch? {
        if (kind == ByEnvironmentKind.AUTO && !override.isNullOrBlank()) {
            val p = guarded { Paths.get(override) }
            if (p != null && Files.isExecutable(p)) {
                // An override names one executable; the venv around it, if any, still gets activated.
                val venv = venvRootOfInterpreter(p)
                return ByLaunch(
                    exe = p,
                    prependArgs = emptyList(),
                    env = venv?.let { activationEnv(it) }.orEmpty(),
                    venvRoot = venv,
                    kind = ByEnvironmentKind.AUTO,
                    fromOverride = true,
                )
            }
            LOG.warn("Configured $binary override path is not executable, ignoring: $override")
        }

        val startDirs = searchStartDirs(contentRootPath(project, contextFile), basePath(project))

        fun venvLaunch(root: Path, k: ByEnvironmentKind) =
            ByLaunch(venvBinary(root, binary), emptyList(), activationEnv(root), root, k)

        fun fromVenv(): ByLaunch? =
            findVenvWithBinary(startDirs, binary)?.let { venvLaunch(it, ByEnvironmentKind.VENV) }

        fun fromSdk(): ByLaunch? =
            sdkVenvRoot(project)
                ?.takeIf { Files.isExecutable(venvBinary(it, binary)) }
                ?.let { venvLaunch(it, ByEnvironmentKind.SDK) }

        fun fromUv(): ByLaunch? {
            val uv = UvSupport.findUv() ?: return null
            val base = basePath(project)?.takeIf { UvSupport.hasProjectMarker(project) } ?: return null
            return ByLaunch(
                exe = uv,
                prependArgs = listOf("run", "--project", base.toString(), binary),
                env = emptyMap(), // uv establishes the environment itself
                venvRoot = null,
                kind = ByEnvironmentKind.UV,
            )
        }

        fun fromDownload(): ByLaunch? =
            downloadedBinary(binary)?.let {
                ByLaunch(it, emptyList(), emptyMap(), null, ByEnvironmentKind.DOWNLOADED)
            }

        fun fromBundled(): ByLaunch? =
            bundledBinary(binary)?.let {
                ByLaunch(it, emptyList(), emptyMap(), null, ByEnvironmentKind.BUNDLED)
            }

        fun fromPath(): ByLaunch? =
            PathEnvironmentVariableUtil.findInPath(exeName(binary))
                ?.let { ByLaunch(it.toPath(), emptyList(), emptyMap(), null, ByEnvironmentKind.PATH) }

        return when (kind) {
            ByEnvironmentKind.VENV -> fromVenv()
            ByEnvironmentKind.SDK -> fromSdk()
            ByEnvironmentKind.UV -> fromUv()
            ByEnvironmentKind.DOWNLOADED -> fromDownload()
            ByEnvironmentKind.BUNDLED -> fromBundled()
            ByEnvironmentKind.PATH -> fromPath()
            // uv is absent by design — it can create environments; see ByEnvironmentKind.UV.
            ByEnvironmentKind.AUTO -> fromVenv() ?: fromSdk() ?: fromDownload() ?: fromBundled() ?: fromPath()
        }
    }

    /** `findInPath` matches the exact file name and does not apply PATHEXT, so add it ourselves. */
    private fun exeName(binary: String): String = if (SystemInfo.isWindows) "$binary.exe" else binary

    /**
     * Runs [block], turning failures into `null`.
     *
     * [ProcessCanceledException] is rethrown: the platform requires it to propagate, and a bare
     * `runCatching` would swallow it and quietly break cancellation.
     */
    private inline fun <T> guarded(block: () -> T): T? =
        try {
            block()
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.debug("basedpython environment resolution step failed", e)
            null
        }

    /**
     * Resolve a real Python interpreter for [project] — used by the pdb action, which runs the
     * transpiled `.py` output rather than a `by` subcommand.
     *
     * Same sources as [resolve], so the interpreter and the toolchain agree on the environment
     * instead of the two drifting apart.
     */
    fun resolvePython(project: Project, contextFile: VirtualFile? = null): ByLaunch? {
        val startDirs = searchStartDirs(contentRootPath(project, contextFile), basePath(project))

        findVenvWithBinary(startDirs, "python")?.let {
            return ByLaunch(venvBinary(it, "python"), emptyList(), activationEnv(it), it, ByEnvironmentKind.VENV)
        }
        sdkVenvRoot(project)?.takeIf { Files.isExecutable(venvBinary(it, "python")) }?.let {
            return ByLaunch(venvBinary(it, "python"), emptyList(), activationEnv(it), it, ByEnvironmentKind.SDK)
        }
        // A Python SDK pointing at a system interpreter has no venv, but is still the interpreter
        // the user chose — prefer it over a bare PATH lookup.
        sdkInterpreter(project)?.let {
            return ByLaunch(it, emptyList(), emptyMap(), null, ByEnvironmentKind.SDK)
        }
        // `python3` first on POSIX; Windows ships only `python`. Both names are tried either way so a
        // POSIX box with only `python`, or a Windows box with a `python3` shim, still resolves.
        for (name in listOf("python3", "python")) {
            PathEnvironmentVariableUtil.findInPath(exeName(name))?.let {
                return ByLaunch(it.toPath(), emptyList(), emptyMap(), null, ByEnvironmentKind.PATH)
            }
        }
        return null
    }

    /** The configured Python interpreter itself, venv-backed or not. */
    private fun sdkInterpreter(project: Project): Path? {
        val home = pythonSdk(project)?.homePath ?: return null
        return guarded { Paths.get(home) }?.takeIf { Files.isExecutable(it) }
    }
}
