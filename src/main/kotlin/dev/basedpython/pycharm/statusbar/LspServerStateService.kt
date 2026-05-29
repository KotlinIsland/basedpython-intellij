package dev.basedpython.pycharm.statusbar

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicReference

internal enum class ServerLight { GREEN, GRAY, RED }

internal data class ServerSnapshot(
    val byLight: ServerLight,
    val buffLight: ServerLight,
    val byPath: String?,
    val buffPath: String?,
    val byRunning: Boolean,
    val buffRunning: Boolean,
)

/**
 * Convenience cache so widget can poll cheaply.
 * Stream B's LspServerListener pushes via [markRunning]; the widget reads [snapshot].
 */
@Service(Service.Level.PROJECT)
internal class LspServerStateService(private val project: Project) {
    private val byRunning = AtomicReference(false)
    private val buffRunning = AtomicReference(false)

    fun markByRunning(running: Boolean) { byRunning.set(running) }
    fun markBuffRunning(running: Boolean) { buffRunning.set(running) }

    fun snapshot(): ServerSnapshot {
        val settings = BasedPythonSettings.getInstance(project)
        val byResolved = BasedPythonBinaries.resolveBy(project)
        val buffResolved = BasedPythonBinaries.resolveBuff(project)
        val byMissing = byResolved == null || !Files.isExecutable(byResolved)
        val buffMissing = buffResolved == null || !Files.isExecutable(buffResolved)
        val byLight = when {
            !settings.byEnabled -> ServerLight.GRAY
            byMissing -> ServerLight.RED
            byRunning.get() -> ServerLight.GREEN
            else -> ServerLight.GRAY
        }
        val buffLight = when {
            !settings.buffEnabled -> ServerLight.GRAY
            buffMissing -> ServerLight.RED
            buffRunning.get() -> ServerLight.GREEN
            else -> ServerLight.GRAY
        }
        return ServerSnapshot(
            byLight = byLight,
            buffLight = buffLight,
            byPath = byResolved?.toString(),
            buffPath = buffResolved?.toString(),
            byRunning = byRunning.get(),
            buffRunning = buffRunning.get(),
        )
    }

    companion object {
        fun getInstance(project: Project): LspServerStateService = project.service()
    }
}
