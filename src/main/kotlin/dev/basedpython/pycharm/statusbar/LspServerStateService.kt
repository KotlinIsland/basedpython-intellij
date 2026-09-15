package dev.basedpython.pycharm.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.util.concurrency.AppExecutorUtil
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.version.runningServerVersion
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Files

/**
 * What the status bar reports for one server. Named for meaning rather than colour: a healthy
 * server is deliberately unobtrusive, and only a genuine problem is allowed to draw the eye.
 */
internal enum class ServerLight {
    /** Up, or on its way up. */
    RUNNING,

    /** Deliberately not running — switched off in settings, or shut down cleanly. */
    STOPPED,

    /** Wanted, but broken: the binary is missing, or the server died on its own. */
    PROBLEM,
}

internal data class ServerSnapshot(
    val byLight: ServerLight,
    val buffLight: ServerLight,
    val byPath: String?,
    val buffPath: String?,
    /** What the running server said its version is, or `null` while none is running. */
    val byVersion: String?,
    val buffVersion: String?,
    /** Whether the binaries have been looked for yet; until they have, [byPath] and [buffPath] mean nothing. */
    val resolved: Boolean,
)

/**
 * Pure state mapping, split out so it can be unit-tested without an IDE fixture.
 *
 * The distinction that matters is [LspServerState.ShutdownNormally] versus
 * [LspServerState.ShutdownUnexpectedly]: a server we stopped is fine, a server that stopped itself
 * is not. Collapsing those two into one "not running" state is what previously let a server that
 * never came up look identical to one that was simply switched off.
 */
internal object ServerLightMapping {

    /**
     * @param enabled the per-server toggle from settings.
     * @param binaryMissing the resolved binary is absent or not executable.
     * @param state the platform's view of the server, or `null` when no server has been created yet.
     */
    fun lightFor(enabled: Boolean, binaryMissing: Boolean, state: LspServerState?): ServerLight {
        // A server that's switched off isn't broken, even if its binary is missing.
        if (!enabled) return ServerLight.STOPPED
        if (binaryMissing) return ServerLight.PROBLEM
        return when (state) {
            LspServerState.Running, LspServerState.Initializing -> ServerLight.RUNNING
            LspServerState.ShutdownUnexpectedly -> ServerLight.PROBLEM
            // Cleanly stopped, or enabled-but-never-started.
            LspServerState.ShutdownNormally, null -> ServerLight.STOPPED
        }
    }
}

/**
 * What the status bar widget shows, cheap enough to read on every paint.
 *
 * Server state comes live from [LspClientManager] rather than being mirrored here, so a server that
 * fails to start is reported as such instead of being indistinguishable from one that was never
 * asked to start; so does each server's version, which is what it reported in its `initialize`
 * reply.
 *
 * Which binaries would run is the one part that is *not* cheap — resolving one walks the file system
 * for a `.venv`, asks for the project's SDK and searches `PATH` — and the widget is painted on the
 * EDT. So it is resolved off the EDT by [refresh], whenever something happens that could change the
 * answer (the widget appearing, a server starting or stopping, a restart), and [snapshot] reads what
 * was found last.
 */
@Service(Service.Level.PROJECT)
internal class LspServerStateService(private val project: Project) : Disposable {

    private class Binaries(val by: ByLaunch?, val buff: ByLaunch?)

    /** What [refresh] last found, or `null` before it first has. */
    @Volatile private var binaries: Binaries? = null

    /** The widget's state as of now. Reads no disk, starts no process: safe on the EDT. */
    fun snapshot(): ServerSnapshot {
        val settings = BasedPythonSettings.getInstance(project)
        val found = binaries
        return ServerSnapshot(
            byLight = lightFor(settings.byEnabled, found, found?.by, ByLspServerSupportProvider::class.java),
            buffLight = lightFor(settings.buffEnabled, found, found?.buff, BuffLspServerSupportProvider::class.java),
            // The full command, not just the exe: for a uv launch the exe alone reads as "uv",
            // which tells the user nothing about which toolchain is actually running.
            byPath = found?.by?.describe(),
            buffPath = found?.buff?.describe(),
            byVersion = runningServerVersion(project, ByLspServerSupportProvider::class.java),
            buffVersion = runningServerVersion(project, BuffLspServerSupportProvider::class.java),
            resolved = found != null,
        )
    }

    /** Looks for the binaries again in the background, then runs [then] on the EDT. */
    fun refresh(then: () -> Unit) {
        ReadAction.nonBlocking<Binaries> {
            Binaries(executable(BasedPythonBinaries.launchBy(project)), executable(BasedPythonBinaries.launchBuff(project)))
        }
            .coalesceBy(this)
            .expireWith(this)
            .finishOnUiThread(ModalityState.any()) {
                binaries = it
                then()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** Absent, or present and not executable, is a binary that cannot run. */
    private fun executable(launch: ByLaunch?): ByLaunch? = launch?.takeIf { Files.isExecutable(it.exe) }

    /** Before the binaries have been looked for, nothing is reported missing. */
    private fun lightFor(
        enabled: Boolean,
        found: Binaries?,
        launch: ByLaunch?,
        providerClass: Class<out LspIntegrationProvider>,
    ): ServerLight {
        val missing = found != null && launch == null
        return ServerLightMapping.lightFor(enabled, missing, serverState(providerClass))
    }

    private fun serverState(providerClass: Class<out LspIntegrationProvider>): LspServerState? =
        LspClientManager.getInstance(project).getClients(providerClass).firstOrNull()?.state

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): LspServerStateService = project.service()
    }
}
