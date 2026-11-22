package dev.basedpython.pycharm.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.BaseProjectDirectories
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.BaseProjectDirectoriesDiff
import com.intellij.openapi.project.BaseProjectDirectoriesListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider

/**
 * The directories `by` and `buff` are given as a project's workspace, and the one they run in.
 *
 * The project's base directories, as the platform's project-wide descriptor reads them — but read
 * when there are none too. A folder IntelliJ IDEA opens for the first time is given its module only
 * after the project has started its servers, and until then it has no base directory: the servers
 * were initialised with no workspace folder at all and fell back to the directory they were started
 * in, which nothing set, so it was the IDE's install directory (measured in IDEA 263.7307: `by` logged
 * *No workspace(s) were provided … default workspace: …/idea-263.7307.112-EAP-CANDIDATE* and indexed
 * one file). And when the module came, the platform started a second `by` on the new roots beside the
 * first, which stayed up holding the IDE's directory and kept answering: `by/testItems` listed no
 * tests.
 *
 * The project's own directory stands in for its base directories while it has none, and is where
 * both servers always run, so the roots before the module and after it are the same and one server
 * stays up throughout (measured: one `by`, its tests listed, before the module and after it). When
 * the base directories really change — a content root added outside the project — nothing restarts
 * the servers onto them on its own (measured: `by` kept the one root), so [Watcher] does.
 */
internal object ByServerRoots {

    /** The workspace folders for [project]'s servers. */
    fun of(project: Project): Array<VirtualFile> {
        val base = project.getBaseDirectories()
        if (base.isNotEmpty()) return base.toTypedArray()
        return listOfNotNull(project.guessProjectDir()).toTypedArray()
    }

    /** Where [project]'s servers run: the project's directory, never the IDE's. */
    fun workingDirectory(project: Project): String? = project.basePath

    /**
     * Restarts a project's `by` and `buff` when the roots they were started with are no longer the
     * project's, so that one server per project holds its whole workspace.
     *
     * On the base directories' own change, not the module roots': a watcher on `rootsChanged` left `by`
     * on the one root when a content root was added outside the project (measured), presumably asked
     * before the base directories had taken it in.
     */
    @Service(Service.Level.PROJECT)
    internal class Watcher(private val project: Project) : Disposable {

        init {
            project.messageBus.connect(this).subscribe(
                BaseProjectDirectories.TOPIC,
                object : BaseProjectDirectoriesListener {
                    override fun changed(project: Project, diff: BaseProjectDirectoriesDiff) = restartIfStale()
                },
            )
        }

        private fun restartIfStale() {
            if (project.isDisposed) return
            val roots = of(project).toSet()
            val manager = LspClientManager.getInstance(project)
            val stale = PROVIDERS.filter { (provider, _) ->
                manager.getClients(provider).any { it.descriptor.roots.toSet() != roots }
            }
            if (stale.isEmpty()) return
            // resolving a binary can mean asking uv
            ApplicationManager.getApplication().executeOnPooledThread {
                for ((provider, start) in stale) {
                    if (project.isDisposed) return@executeOnPooledThread
                    manager.stopClients(provider)
                    start(project)
                }
            }
        }

        override fun dispose() = Unit

        private companion object {
            val PROVIDERS: List<Pair<Class<out LspIntegrationProvider>, (Project) -> Unit>> = listOf(
                ByLspServerSupportProvider::class.java to { project -> startByServer(project) },
                BuffLspServerSupportProvider::class.java to ::startBuffServer,
            )
        }
    }

    /** Has every open project's [Watcher] listening from the start. */
    internal class Activity : ProjectActivity {
        override suspend fun execute(project: Project) {
            project.service<Watcher>()
        }
    }
}
