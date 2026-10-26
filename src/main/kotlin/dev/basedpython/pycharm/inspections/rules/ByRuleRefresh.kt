package dev.basedpython.pycharm.inspections.rules

import com.intellij.codeInspection.InspectionProfile
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.profile.ProfileChangeAdapter
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider

/**
 * Asks a `by` that has just started what its rules are, and makes them the inspections
 * ([ByRuleInspections]). A `by` without `by/rules` leaves the inspections as the last one said.
 */
internal class ByRuleRefresh(private val project: Project) : ByLspLifecycleListener {

    /**
     * Asks on a thread of its own: this is called before the client has told the server it is
     * `initialized`, and a server answers nothing before that, so asking here waited out its timeout.
     */
    override fun serverInitialized(serverName: String) {
        if (serverName != "by" || project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val server = LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
                .firstOrNull { it.state == LspServerState.Running } ?: return@executeOnPooledThread
            val rules = server.askBy("by/rules", TIMEOUT_MS) { (it as ByServerExtensions).rules() }.value ?: return@executeOnPooledThread
            if (!ByRuleInspections.getInstance().update(rules)) return@executeOnPooledThread
            // a rule `by` did not have before may be one the profile changes
            ByRuleConfiguration.getInstance(project).pushIfChanged()
        }
    }

    private companion object {
        /** A server still loading its project answers once it has. */
        const val TIMEOUT_MS = 60_000
    }
}

/** On a project's opening, its profiles get the rules' inspections the last `by` named. */
internal class ByRuleInstall : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!BasedPythonProjectDetector.isPythonProject(project)) return
        val rules = ByRuleInspections.getInstance()
        rules.install(InspectionProfileManager.getInstance(), null)
        rules.install(InspectionProfileManager.getInstance(project), project)
        ByRuleConfiguration.getInstance(project)
    }
}

/**
 * What [ByRuleLevels.overrides] says of the project's current profile, sent to `by` as its
 * `configuration.rules`, and the servers restarted when a profile change changes it, since `by` reads
 * it once, at start.
 */
@Service(Service.Level.PROJECT)
internal class ByRuleConfiguration(private val project: Project) {

    /** What the running `by` was started with. */
    @Volatile
    private var sent: Map<String, String>? = null

    init {
        project.messageBus.connect(project).subscribe(
            ProfileChangeAdapter.TOPIC,
            object : ProfileChangeAdapter {
                override fun profileChanged(profile: InspectionProfile) = pushIfChanged()
                override fun profileActivated(oldProfile: InspectionProfile?, profile: InspectionProfile?) = pushIfChanged()
            },
        )
    }

    /** The rules to send `by` now; remembered as what it was started with. */
    fun current(): Map<String, String> =
        ByRuleLevels.overrides(InspectionProjectProfileManager.getInstance(project).currentProfile, ByRuleInspections.getInstance().rules)
            .also { sent = it }

    /** Restarts the servers when the rules to send differ from what the running `by` was started with. */
    fun pushIfChanged() {
        val before = sent ?: return
        val now = ByRuleLevels.overrides(InspectionProjectProfileManager.getInstance(project).currentProfile, ByRuleInspections.getInstance().rules)
        if (now != before) BasedPythonLspReloader.getInstance(project).onSettingsChanged()
    }

    companion object {
        fun getInstance(project: Project): ByRuleConfiguration = project.service()
    }
}
