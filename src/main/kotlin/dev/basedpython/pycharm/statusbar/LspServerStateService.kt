package dev.basedpython.pycharm.statusbar

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspServerManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspServerSupportProvider
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.lsp.BuffLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
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
 * Reads live server state for the status bar widget. State comes from [LspServerManager] rather
 * than being cached locally, so a server that fails to start is reported as such instead of being
 * indistinguishable from one that was never asked to start.
 */
@Service(Service.Level.PROJECT)
internal class LspServerStateService(private val project: Project) {

    fun snapshot(): ServerSnapshot {
        val settings = BasedPythonSettings.getInstance(project)
        val byLaunch = BasedPythonBinaries.launchBy(project)
        val buffLaunch = BasedPythonBinaries.launchBuff(project)
        return ServerSnapshot(
            byLight = lightFor(settings.byEnabled, byLaunch, ByLspServerSupportProvider::class.java),
            buffLight = lightFor(settings.buffEnabled, buffLaunch, BuffLspServerSupportProvider::class.java),
            // The full command, not just the exe: for a uv launch the exe alone reads as "uv",
            // which tells the user nothing about which toolchain is actually running.
            byPath = byLaunch?.describe(),
            buffPath = buffLaunch?.describe(),
        )
    }

    private fun lightFor(
        enabled: Boolean,
        launch: ByLaunch?,
        providerClass: Class<out LspServerSupportProvider>,
    ): ServerLight {
        val missing = launch == null || !Files.isExecutable(launch.exe)
        return ServerLightMapping.lightFor(enabled, missing, serverState(providerClass))
    }

    private fun serverState(providerClass: Class<out LspServerSupportProvider>): LspServerState? =
        LspServerManager.getInstance(project).getServersForProvider(providerClass).firstOrNull()?.state

    companion object {
        fun getInstance(project: Project): LspServerStateService = project.service()
    }
}
