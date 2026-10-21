package dev.basedpython.pycharm.run.target

import com.intellij.execution.ExecutionException
import com.intellij.execution.Platform
import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetEnvironment.TargetPath
import com.intellij.execution.target.TargetEnvironment.UploadRoot
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.TargetProgressIndicator
import com.intellij.execution.target.TargetedCommandLine
import com.intellij.execution.target.TargetedCommandLineBuilder
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.io.IOException
import java.nio.file.Path

/**
 * One `by` launch on a run target, from the request the platform is about to send to the target to
 * the paths the command line is written in.
 *
 * Two steps, because the platform asks in two: [prepare] adds to the request before the target
 * exists — the project directory, as an upload root — and [created] reads what the target made of
 * it once it does. Only then is it known where the project is on the target: a Docker target mounts
 * it at the path its configuration names, an SSH target copies it there (or to a temporary
 * directory when no path is configured), and nothing before that moment can say which.
 *
 * The whole project is uploaded rather than the module being run: `by run` transpiles the project it
 * is started in, reads its `pyproject.toml`, and the program may import any of it.
 */
internal class ByTargetLaunch(target: TargetEnvironmentConfiguration, private val projectRoot: Path) {

    /** The `by` to run on the target, as its configuration spells it. */
    val byPath: String = ByLanguageRuntimeType.runtimeOf(target)?.byPath?.takeIf(String::isNotBlank)
        ?: throw ExecutionException(BasedPythonBundle.message("target.error.noRuntime", target.displayName))

    private var uploadRoot: UploadRoot? = null

    /**
     * Asks for the project directory on the target: at the target's configured project path when it
     * has one, and otherwise wherever the target chooses.
     *
     * The configured path is read from the target's configuration as well as from the request,
     * because a Docker request does not carry it: measured on 263.7355, a Docker target configured
     * with `/opt/project` produced a request whose `projectPathOnTarget` was blank, and the project
     * was mounted at a fresh `/tmp/<uuid>` instead.
     */
    fun prepare(request: TargetEnvironmentRequest) {
        val onTarget = request.projectPathOnTarget.takeIf(String::isNotBlank)
            ?: request.configuration?.projectRootOnTarget?.takeIf(String::isNotBlank)
        val root = UploadRoot(projectRoot, onTarget?.let(TargetPath::Persistent) ?: TargetPath.Temporary())
        request.uploadVolumes += root
        uploadRoot = root
    }

    /**
     * Uploads the project — a no-op for a target that mounts it, a copy for one that cannot — and
     * answers where it landed.
     */
    fun created(environment: TargetEnvironment, progress: TargetProgressIndicator): ByTargetPaths {
        val root = uploadRoot ?: throw IllegalStateException("created() before prepare()")
        val volume = environment.uploadVolumes[root]
            ?: throw ExecutionException(BasedPythonBundle.message("target.error.noProjectVolume", environment.request.configuration?.displayName.orEmpty()))
        try {
            volume.upload(".", progress)
        } catch (e: IOException) {
            throw ExecutionException(BasedPythonBundle.message("target.error.upload", e.message.orEmpty()), e)
        }
        return ByTargetPaths(projectRoot, volume.targetRoot, environment.targetPlatform.platform)
    }
}

/**
 * Where the project is locally and where it is on the target, and the translation between the two.
 *
 * The target's paths are strings in the target's own syntax — a Linux container's from a Windows IDE
 * included — so they are joined with [platform]'s separator rather than through [Path], which would
 * write them in the IDE's.
 */
internal class ByTargetPaths(localRoot: Path, targetRoot: String, val platform: Platform) {

    val localRoot: Path = localRoot.toAbsolutePath().normalize()

    private val separator = platform.fileSeparator

    val targetRoot: String = targetRoot.trimEnd(separator).ifEmpty { separator.toString() }

    /** [local] on the target, or null when it is outside the project and so was never uploaded. */
    fun toTarget(local: Path): String? {
        val normalized = local.toAbsolutePath().normalize()
        if (!normalized.startsWith(localRoot)) return null
        val relative = localRoot.relativize(normalized).map(Path::toString).filter(String::isNotEmpty)
        if (relative.isEmpty()) return targetRoot
        return relative.joinToString(separator.toString(), prefix = targetRoot.trimEnd(separator) + separator)
    }

    /**
     * The local file a path the target printed stands for, or null when it is not in the project —
     * a frame in the target's interpreter, say, which has no local counterpart to open.
     */
    fun toLocal(target: String): Path? {
        val root = targetRoot.trimEnd(separator)
        val rest = when {
            target == targetRoot || target == root -> return localRoot
            target.startsWith(root + separator) -> target.substring(root.length + 1)
            else -> return null
        }
        val local = rest.split(separator).filter(String::isNotEmpty).fold(localRoot, Path::resolve).normalize()
        // `..` in what a program printed must not reach outside the project the mapping is for
        return local.takeIf { it.startsWith(localRoot) }
    }
}

/**
 * The `by` command line a target runs: [byPath] with [arguments], in [workDirectory], with
 * [environment] — all of them already in the target's terms.
 *
 * The environment is exactly [environment]. A target process starts from the target's own
 * environment (a Docker image's `ENV`, an SSH login's), never the IDE's, so there is no parent
 * environment of the IDE's to pass or withhold.
 */
internal fun byTargetCommandLine(
    request: TargetEnvironmentRequest,
    byPath: String,
    arguments: List<String>,
    workDirectory: String,
    environment: Map<String, String>,
): TargetedCommandLine = TargetedCommandLineBuilder(request).apply {
    setExePath(byPath)
    addParameters(arguments)
    setWorkingDirectory(workDirectory)
    environment.forEach(::addEnvironmentVariable)
    charset = Charsets.UTF_8
}.build()
