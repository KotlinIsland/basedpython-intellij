package dev.basedpython.pycharm.env.manager

import dev.basedpython.pycharm.env.manager.index.PackageIndex
import dev.basedpython.pycharm.env.modules.ModuleKind
import dev.basedpython.pycharm.env.modules.ModuleLayout
import java.nio.file.Path

/**
 * Something the plugin asks an environment manager to do.
 *
 * A closed set of *intents*, not of command lines. Backends translate these into their own argv, so
 * "add a dependency" stays one concept whether it ends up as `uv add`, `conda install` or
 * `pixi add`. A backend that cannot express one returns null from [EnvBackend.command] and the UI
 * hides the button rather than offering something that would fail.
 */
sealed interface EnvOp {

    /**
     * Create the environment, optionally on a specific interpreter ([python] as the backend spells it).
     *
     * [replaceExisting] is the difference between creating one and *re*creating one, and it is not
     * optional detail: an environment is built against a single interpreter and cannot be moved to
     * another, so "change the Python version" is really "throw this one away and build a new one".
     * Backends refuse to overwrite silently — `uv venv` on an existing `.venv` fails outright and
     * tells you to pass `--clear` — so a recreate that does not say so does not happen at all.
     *
     * Only ever true after the user has confirmed: it discards everything installed.
     */
    data class Create(val python: String? = null, val replaceExisting: Boolean = false) : EnvOp

    /** Make the environment match the project's declared dependencies. */
    data object Sync : EnvOp

    /**
     * Ask whether [Sync] would change anything, without changing anything.
     *
     * Answered by the process's exit code rather than its output — see [EnvBackend.driftFromExitCode].
     */
    data object CheckSync : EnvOp

    /** Re-resolve the lock file from the declared dependencies. */
    data object Lock : EnvOp

    /** Re-resolve, allowing newer versions than the lock currently pins. */
    data object Upgrade : EnvOp

    /** Add [requirements] to the project's dependencies under [target] and install them. */
    data class Add(
        val requirements: List<String>,
        val target: EnvDependencyTarget = EnvDependencyTarget.Main,
        /**
         * The module whose manifest this is declared in, or null for the project's own.
         *
         * A project with modules has more than one manifest, and "add httpx" is not answerable
         * without knowing which — see [EnvOp.InitModule]. Null rather than the root module's name so
         * that a project with no modules produces exactly the command it produced before this
         * existed.
         */
        val module: String? = null,
    ) : EnvOp

    /** Remove [packages] from the project's dependencies under [target] and uninstall them. */
    data class Remove(
        val packages: List<String>,
        val target: EnvDependencyTarget = EnvDependencyTarget.Main,
        /** The module to remove them from — see [Add.module]. */
        val module: String? = null,
    ) : EnvOp

    /**
     * Create a new module of the project at [path], relative to the project root.
     *
     * A *module* is what this plugin calls what uv calls a workspace member: a directory with a
     * manifest of its own, sharing the project's lock file and environment, importable by its
     * siblings. See [dev.basedpython.pycharm.env.modules.ModuleLayout].
     *
     * The manager is asked to do the whole job — scaffold the sources *and* list the module in the
     * project's manifest — because for uv those are one command, and splitting them would mean this
     * plugin writing the manifest edit that uv already knows how to write. A backend with no
     * workspace concept returns null from [EnvBackend.command] and the structure UI is not offered.
     */
    data class InitModule(
        /** Where the module goes, relative to the project root, with `/` separators. */
        val path: String,
        /** Its distribution name, or null to let the manager take one from the directory. */
        val name: String?,
        val kind: ModuleKind,
        /** The interpreter its `requires-python` is derived from, as the backend spells it. */
        val python: String? = null,
        val description: String? = null,
    ) : EnvOp

    /**
     * The declared dependency graph, grouped by where each requirement is declared.
     *
     * Must not modify the project. That is a real constraint rather than a note: the obvious command
     * for this re-locks as a side effect, so a backend implementing it has to ask for the graph the
     * lock file already describes. A refresh happens whenever a manifest is saved, and a refresh
     * that rewrites the lock file is a refresh that edits the user's repository.
     */
    data object Tree : EnvOp

    /**
     * List what is installed in the environment whose interpreter is [python].
     *
     * The interpreter is part of the op rather than something the backend remembers, because "the
     * current environment" is not a thing a tool can be trusted to work out: uv reads `VIRTUAL_ENV`,
     * which in an IDE launched from an activated shell names whatever the user had active at login.
     * Every caller already knows which environment it is asking about, so it says so.
     */
    data class ListPackages(val python: Path?) : EnvOp

    /** List interpreters, installed and available. Output goes through [EnvBackend.parsePythons]. */
    data object ListPythons : EnvOp

    /** Install an interpreter the machine does not have yet. */
    data class InstallPython(val version: String) : EnvOp
}

/**
 * One invocation of a backend's tool: the arguments, and whether it is being run for its output.
 *
 * The executable is deliberately absent. Where the tool lives is a question about this machine, and
 * it is answered once in [EnvTools]; a backend only decides what to say to it. That is what keeps
 * [EnvBackend] pure enough to unit test every command it can produce.
 */
data class EnvCommand(
    val args: List<String>,
    /**
     * True when the command exists to be parsed rather than watched.
     *
     * Query commands are run captured and off-screen; the rest stream into the plugin's log so the
     * user can see a sync resolving. Nothing branches on the op itself — a backend that answers a
     * query by spawning a visible process is free to say so.
     */
    val isQuery: Boolean = false,
) {
    /** The command as a user would type it, given the tool's [exe] name. For logs and tooltips. */
    fun describe(exe: String): String = (listOf(exe) + args).joinToString(" ")
}

/**
 * An environment manager the plugin can drive.
 *
 * ### Adding a backend
 *
 * Implement this, then add the object to [EnvBackends.ALL]. Nothing else in the plugin needs to
 * change: the tool window, the actions, the drift banner and the run-configuration wiring all go
 * through this interface. The three things a new backend has to get right are [claims] (which
 * projects are yours), [environmentRoot] (where you put the environment), and [command] (returning
 * null for every op you cannot express, rather than a command that would fail).
 *
 * Implementations must be pure and side-effect free apart from [claims] and [environmentRoot],
 * which read the filesystem. In particular nothing here starts a process — that is [EnvRunner]'s
 * job — which is why every command a backend can produce is unit testable.
 */
interface EnvBackend {

    /** Stable identifier, persisted in settings and in [ManagedEnvironment.backendId]. Never change one. */
    val id: String

    /** How the backend is named in the UI. */
    val displayName: String

    /** The tool's executable name, without any platform extension. [EnvTools] adds `.exe`. */
    val executableName: String

    /** Where the tool can be downloaded from, for a backend the plugin is able to install itself. */
    val installer: EnvToolInstaller?

    /**
     * File names at a project root that mean "this project is mine".
     *
     * Used only for the cheap existence check that decides whether to offer the tool window while a
     * project is still opening; [claims] is the real answer.
     */
    val projectMarkers: List<String>

    /**
     * File names at a project root that an operation may rewrite.
     *
     * Separate from [projectMarkers] even where the two happen to list the same files, because they
     * answer different questions and diverge for real backends: conda recognises a project by its
     * `environment.yml` and then never writes back to it, so treating "how I recognise you" as "what
     * I edit" would have the IDE re-reading a file that cannot have changed while missing the one
     * that did.
     *
     * What this drives is [EnvFiles]: unsaved editor changes to these are flushed before a command
     * reads them, and they are re-read afterwards so the editor is not left showing a version of the
     * file that no longer exists on disk.
     */
    val managedFiles: List<String>

    /** True when this backend manages the project rooted at [projectRoot]. */
    fun claims(projectRoot: Path): Boolean

    /**
     * The environment directory for [projectRoot] — whether or not it exists yet.
     *
     * Returning a path for an environment that has not been created is the point: it is what the
     * "no environment yet" state shows the user before they press Create.
     */
    fun environmentRoot(projectRoot: Path): Path

    /** The interpreter inside an environment rooted at [envRoot]. */
    fun pythonExecutable(envRoot: Path): Path

    /** The command implementing [op], or null when this backend cannot express it. */
    fun command(op: EnvOp): EnvCommand?

    /**
     * Where this project's packages can be looked up, or null when the backend has no index this
     * plugin knows how to read.
     *
     * Takes the project root because the answer is a property of the *project*, not of the manager:
     * two uv projects can install from different indexes, and the one pointed at a private mirror
     * must not be offered the public catalogue.
     */
    fun packageIndex(projectRoot: Path): PackageIndex? = null

    /**
     * How the project at [projectRoot] is divided into modules, or null when this manager has no
     * notion of dividing one.
     *
     * Reads the filesystem, like [claims] and [environmentRoot] and for the same reason: the answer
     * is a property of the project on this disk, not of the manager, and there is nothing to run a
     * process about. It must not *write* — this is called from a background refresh, and a scan that
     * edits the user's manifests is the thing [EnvOp.Tree] documents at length not to do.
     *
     * Null and [ModuleLayout.EMPTY] are different answers. Null is "this manager does not do
     * modules", and hides the structure UI outright; an empty layout is "it does, and this project
     * has none yet", which is the ordinary state of every single-package project and the one the
     * *New module* button acts on.
     */
    fun moduleLayout(projectRoot: Path): ModuleLayout? = null

    /** The installed packages, from the stdout of [EnvOp.ListPackages]. */
    fun parsePackages(stdout: String): List<EnvPackage>

    /** The interpreters, from the stdout of [EnvOp.ListPythons]. */
    fun parsePythons(stdout: String): List<PythonCandidate>

    /**
     * The grouped dependency graph, from the stdout of [EnvOp.Tree].
     *
     * Defaulted, because it is only reachable for a backend whose [command] answers [EnvOp.Tree] —
     * a manager with no notion of declared-versus-transitive returns null there and never gets
     * asked. The view falls back to the flat installed list in that case.
     */
    fun parseTree(stdout: String): List<EnvDependencyGroup> = emptyList()

    /**
     * What [EnvOp.CheckSync]'s exit code means.
     *
     * A method rather than a convention because exit codes are the one thing tools disagree about
     * most, and a backend that reports drift as exit 2 must not be read as "in sync" by a rule
     * written for another tool.
     */
    fun driftFromExitCode(exitCode: Int): EnvDrift
}

/**
 * Where a backend's own tool can be fetched, for the one-click bootstrap.
 *
 * Separate from [EnvBackend] so a backend that has no self-install story (conda, whose installers
 * are interactive and multi-hundred-megabyte) simply returns null and the UI tells the user to
 * install it themselves instead of offering a button that cannot work.
 */
interface EnvToolInstaller {

    /**
     * The download URL and archive member for this OS/arch, or null when the platform is unsupported.
     *
     * Takes the raw `os.name` / `os.arch` strings rather than reading them, so every platform is
     * reachable from a test.
     */
    fun plan(osName: String?, osArch: String?): EnvToolDownload?
}

/**
 * A single archive to fetch and the one file to take out of it.
 *
 * Archives rather than bare binaries because that is how these tools are published; [memberSuffix]
 * is matched against the end of each entry's name so a versioned top-level directory inside the
 * archive does not have to be guessed.
 */
data class EnvToolDownload(
    val url: String,
    /** `tar.gz` or `zip` — which of the two extractors to use. */
    val archive: ArchiveKind,
    /** The tail of the archive entry holding the executable, e.g. `/uv` or `/uv.exe`. */
    val memberSuffix: String,
    /** The file name to install it under. */
    val fileName: String,
) {
    enum class ArchiveKind { TAR_GZ, ZIP }
}
