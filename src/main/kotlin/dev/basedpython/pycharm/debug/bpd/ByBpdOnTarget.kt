package dev.basedpython.pycharm.debug.bpd

import com.intellij.execution.ExecutionException
import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetEnvironment.DownloadRoot
import com.intellij.execution.target.TargetEnvironment.LocalPortBinding
import com.intellij.execution.target.TargetEnvironment.TargetPath
import com.intellij.execution.target.TargetEnvironment.UploadRoot
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.TargetProgressIndicator
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import dev.basedpython.pycharm.env.Executables
import dev.basedpython.pycharm.run.ByTargetInfrastructure
import dev.basedpython.pycharm.run.target.ByLanguageRuntimeType
import dev.basedpython.pycharm.run.target.ByTargetPaths
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * A `bpd` session on a run target: the IDE listens, and `bpd dap --connect` dials it.
 *
 * `bpd dap --listen` binds loopback alone, and a container's published port is not loopback:
 * measured on Docker, a listener on the container's `127.0.0.1` answered the published port with a
 * reset, where one on `0.0.0.0` answered. So the direction turns round — the IDE listens on its own
 * loopback, the platform's [LocalPortBinding] gives the target the address that reaches it
 * (`host.docker.internal` from a container), and `bpd` connects out, presenting the token the IDE
 * chose in [ByBpdWrapper.ENV_TOKEN] on its first message.
 *
 * What the target is handed, besides that address and token:
 * - **the wrapper**, uploaded as a root of its own, which `by run --launcher` starts as it does here;
 * - **a directory for the record**, a download root, which the wrapper writes what `by run` chose
 *   into and the IDE reads back once `bpd` has connected — a container mounts it, a copying target
 *   copies it back;
 * - **which `bpd`**: the runtime's ([dev.basedpython.pycharm.run.target.ByLanguageRuntimeConfiguration.bpdPath]),
 *   else the one beside the interpreter `by run` chose, as the wrapper looks here.
 *
 * Paths the session reports are the target's — `by run`'s source maps name the files where it ran —
 * and [paths] translates them, once the target exists.
 */
internal class ByBpdOnTarget private constructor(
    /** The directory holding the wrapper alone, uploaded as it is. */
    private val launcherDir: Path,
    /** The directory the record comes back into. */
    val recordDir: Path,
    /** The IDE's end: a loopback port `bpd` reaches through the platform's binding. */
    val server: ServerSocket,
    /** What `bpd` must present: 64 hex digits, the IDE's choice. */
    val token: String,
) : ByTargetInfrastructure {

    private val launcherRoot = UploadRoot(launcherDir, TargetPath.Temporary())
    private val recordRoot = DownloadRoot(recordDir, TargetPath.Temporary())
    private val binding = LocalPortBinding(server.localPort, null)

    /** Where the project is on the target and here, once the target exists. */
    @Volatile
    var paths: ByTargetPaths? = null
        private set

    @Volatile
    private var created: Created? = null

    private class Created(
        val environment: TargetEnvironment,
        val wrapper: String,
        val record: String,
        val address: String,
        val bpd: String,
    )

    override fun prepare(request: TargetEnvironmentRequest) {
        request.uploadVolumes += launcherRoot
        request.downloadVolumes += recordRoot
        request.localPortBindings += binding
    }

    override fun created(environment: TargetEnvironment, progress: TargetProgressIndicator, paths: ByTargetPaths) {
        val launcher = environment.uploadVolumes[launcherRoot]
            ?: throw ExecutionException(BasedPythonBundle.message("debug.target.error.noVolume"))
        val record = environment.downloadVolumes[recordRoot]
            ?: throw ExecutionException(BasedPythonBundle.message("debug.target.error.noVolume"))
        val endpoint = environment.localPortBindings[binding]?.targetEndpoint
            ?: throw ExecutionException(BasedPythonBundle.message("debug.target.error.noPort"))
        try {
            launcher.upload(".", progress)
        } catch (e: IOException) {
            throw ExecutionException(BasedPythonBundle.message("target.error.upload", e.message.orEmpty()), e)
        }
        val separator = environment.targetPlatform.platform.fileSeparator
        val target = environment.request.configuration
        this.paths = paths
        created = Created(
            environment = environment,
            wrapper = launcher.targetRoot.trimEnd(separator) + separator + WRAPPER,
            record = record.targetRoot.trimEnd(separator) + separator + RECORD,
            address = "${endpoint.host}:${endpoint.port}",
            bpd = target?.let(ByLanguageRuntimeType::runtimeOf)?.bpdPath.orEmpty(),
        )
    }

    override fun arguments(): List<String> = listOf(BY_LAUNCHER_FLAG, created().wrapper)

    override fun environment(): Map<String, String> {
        val created = created()
        return mapOf(
            ByBpdWrapper.ENV_CONNECT to created.address,
            ByBpdWrapper.ENV_TOKEN to token,
            ByBpdWrapper.ENV_RECORD to created.record,
            // empty rather than absent, as for a session here: the target's own environment cannot
            // stand in for an answer the IDE did not give. Blank, the wrapper looks beside the
            // interpreter `by run` chose
            ByBpdWrapper.ENV_BPD to created.bpd,
            ByBpdWrapper.ENV_BPD_FALLBACK to "",
            ByBpdWrapper.ENV_PORT to "",
        )
    }

    /**
     * What the wrapper recorded, read back from the target: complete once `bpd` has connected, since
     * the wrapper writes it before starting `bpd`.
     */
    fun readRecord(): String? {
        val created = created ?: return null
        try {
            created.environment.downloadVolumes[recordRoot]?.download(RECORD, EmptyProgressIndicator())
        } catch (e: IOException) {
            LOG.info("could not read the bpd record back from the run target", e)
        }
        val local = recordDir.resolve(RECORD)
        return try {
            if (Files.isRegularFile(local)) Files.readString(local) else null
        } catch (_: IOException) {
            null
        }
    }

    /** Stops listening; a connection already made is the session's. */
    fun close() {
        try {
            server.close()
        } catch (e: IOException) {
            LOG.info("could not close the debug listener", e)
        }
    }

    private fun created(): Created = created ?: throw ExecutionException(BasedPythonBundle.message("target.error.notPrepared"))

    companion object {
        private val LOG = Logger.getInstance(ByBpdOnTarget::class.java)

        /** `by run`'s flag naming the program its launcher is. */
        private const val BY_LAUNCHER_FLAG = "--launcher"

        private const val WRAPPER = "bpd-python"
        private const val RECORD = "bpd-record"

        /**
         * Prepares one: the wrapper in a directory of its own under [dir], a directory for the
         * record, a loopback listener and a fresh token.
         */
        @Throws(ExecutionException::class)
        fun create(dir: Path): ByBpdOnTarget {
            val launcherDir = dir.resolve("launcher")
            val recordDir = dir.resolve("record")
            try {
                Files.createDirectories(launcherDir)
                Files.createDirectories(recordDir)
                Files.writeString(launcherDir.resolve(WRAPPER), ByBpdWrapper.script())
            } catch (e: IOException) {
                throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapFailed", e.message ?: ""), e)
            }
            if (!Executables.makeExecutable(launcherDir.resolve(WRAPPER))) {
                throw ExecutionException(
                    BasedPythonBundle.message("debug.bpd.error.wrapperNotExecutable", launcherDir.resolve(WRAPPER).toString()),
                )
            }
            val server = try {
                ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            } catch (e: IOException) {
                throw ExecutionException(BasedPythonBundle.message("debug.error.bootstrapFailed", e.message ?: ""), e)
            }
            return ByBpdOnTarget(launcherDir, recordDir, server, token())
        }

        /** 64 hex digits from a secure source: what `bpd --listen` mints for itself. */
        internal fun token(): String {
            val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
