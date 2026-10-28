package dev.basedpython.pycharm.vcs

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsMappingListener
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.lsp.api.LspClient
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lsp.BY_SERVER_NAME
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByGitMergeIntegration
import dev.basedpython.pycharm.lsp.ext.ByGitMergeIntegrationParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

private val LOG = Logger.getInstance(ByGitMergeIntegrations::class.java)

/**
 * Git merge integration: whether git merges the project's python files and `api.lock` with
 * `by merge-driver`, and setting it up — both `by/gitMergeIntegration`, so `by` writes
 * `.gitattributes` and the repository's configuration exactly as `by init --git-merge` does, naming
 * the `by` its server is. Nothing here writes either file.
 */
internal object ByGitMergeIntegrations {

    /** What asking came to: the integration's state, or why there is none. */
    sealed interface Answer {
        data class State(val state: ByGitMergeIntegration) : Answer
        data class Not(val why: String) : Answer
    }

    /** What a project in [state] is offered: nothing, setting the integration up, or repairing a driver that no longer works. */
    enum class Offer { None, Enable, Repair }

    fun offerFor(state: ByGitMergeIntegration): Offer = when {
        !state.repository || state.enabled -> Offer.None
        state.driver != null && (!state.driverFound || !state.driverCurrent) -> Offer.Repair
        else -> Offer.Enable
    }

    /** Asks [client] about the project, setting the integration up first when [enable]. */
    suspend fun ask(project: Project, client: LspClient, enable: Boolean): Answer {
        val params = ByGitMergeIntegrationParams(project.guessProjectDir()?.url, enable.takeIf { it })
        var refusal: Throwable? = null
        val answer = awaitingAgain<ByGitMergeIntegration>("by/gitMergeIntegration", ASK_TIMEOUT_MS) { sent ->
            try {
                client.sendRequest { (it as ByServerExtensions).gitMergeIntegration(params).also(sent) }
            } catch (e: Exception) {
                if (e !is CancellationException) refusal = e
                throw e
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> Answer.State(answer.value)
            ByAnswer.None -> Answer.Not(BasedPythonBundle.message("gitMerge.noProject"))
            ByAnswer.Failed -> Answer.Not(
                when {
                    refusal != null && isMethodNotFound(refusal!!) -> BasedPythonBundle.message("gitMerge.unsupported")
                    refusal != null -> BasedPythonBundle.message("gitMerge.refused", refusal!!.message.orEmpty())
                    else -> BasedPythonBundle.message("gitMerge.noAnswer")
                },
            )
        }
    }

    /** Sets the integration up, starting `by` if need be, and says how that went. */
    suspend fun enable(project: Project) {
        val answer = withBackgroundProgress(project, BasedPythonBundle.message("gitMerge.progress")) {
            when (val start = awaitByServer(project)) {
                is ByServerStart.Running -> ask(project, start.client, enable = true)
                is ByServerStart.Unavailable -> Answer.Not(start.why)
            }
        }
        when (answer) {
            is Answer.Not -> ByCli.notifyError(project, BasedPythonBundle.message("gitMerge.failed.title"), answer.why)
            is Answer.State ->
                if (answer.state.enabled) {
                    ByCli.notifyInfo(project, BasedPythonBundle.message("notification.basedPython.title"), BasedPythonBundle.message("gitMerge.enabled"))
                } else {
                    ByCli.notifyError(project, BasedPythonBundle.message("gitMerge.failed.title"), describe(answer.state))
                }
        }
    }

    /** What is still missing from [state], in a sentence. */
    fun describe(state: ByGitMergeIntegration): String = when {
        !state.repository -> BasedPythonBundle.message("gitMerge.notRepository")
        state.driver == null -> BasedPythonBundle.message("gitMerge.noDriver")
        !state.driverFound -> BasedPythonBundle.message("gitMerge.driverMissing", state.driver)
        !state.driverCurrent -> BasedPythonBundle.message("gitMerge.driverOld", state.driver)
        state.missingAttributes.isNotEmpty() -> BasedPythonBundle.message("gitMerge.missingAttributes", state.missingAttributes.joinToString(" "))
        else -> BasedPythonBundle.message("gitMerge.enabled")
    }

    private const val ASK_TIMEOUT_MS = 30_000L
}

/**
 * Offers git merge integration to a project in a git work tree that does not have it working, once
 * `by` is running: a notification with *Enable* (or *Repair*, where the driver configured names a
 * `by` that is gone or a command `by` no longer writes) and *Don't ask again*, kept per project. A
 * project created by the New Project wizard with the integration asked for has it set up instead, as
 * soon as `by` runs and the wizard's repository exists — the wizard creates the repository after the
 * project opens, so the state is asked again when the project's version control mappings change.
 */
@Service(Service.Level.PROJECT)
internal class ByGitMergeOffers(private val project: Project, private val scope: CoroutineScope) {

    private val checking = Mutex()

    /** Whether this session has shown the offer already: a mapping change asks again, and the offer is not repeated. */
    @Volatile
    private var offered = false

    fun check() {
        scope.launch { checking.withLock { checkNow() } }
    }

    private suspend fun checkNow() {
        val settings = BasedPythonSettings.getInstance(project)
        if (!settings.enableGitMergeIntegration && (!settings.offerGitMergeIntegration || offered)) return
        val client = ByMerges.server(project) ?: return
        val state = when (val answer = ByGitMergeIntegrations.ask(project, client, enable = false)) {
            is ByGitMergeIntegrations.Answer.State -> answer.state
            is ByGitMergeIntegrations.Answer.Not -> return LOG.debug("git merge integration not checked: ${answer.why}")
        }
        if (!state.repository) return
        if (settings.enableGitMergeIntegration) {
            settings.enableGitMergeIntegration = false
            if (!state.enabled) ByGitMergeIntegrations.enable(project)
            return
        }
        val offer = ByGitMergeIntegrations.offerFor(state)
        if (offer == ByGitMergeIntegrations.Offer.None || offered) return
        offered = true
        val repair = offer == ByGitMergeIntegrations.Offer.Repair
        NotificationGroupManager.getInstance().getNotificationGroup(ByCli.NOTIFICATION_GROUP_ID)
            .createNotification(
                BasedPythonBundle.message(if (repair) "gitMerge.offer.repair.title" else "gitMerge.offer.title"),
                if (repair) ByGitMergeIntegrations.describe(state) else BasedPythonBundle.message("gitMerge.offer.content"),
                if (repair) NotificationType.WARNING else NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring(BasedPythonBundle.message(if (repair) "gitMerge.offer.repair" else "gitMerge.offer.enable")) {
                scope.launch { ByGitMergeIntegrations.enable(project) }
            })
            .addAction(NotificationAction.createSimpleExpiring(BasedPythonBundle.message("gitMerge.offer.never")) {
                settings.offerGitMergeIntegration = false
            })
            .notify(project)
    }

    companion object {
        fun getInstance(project: Project): ByGitMergeOffers = project.service()
    }
}

/** Asks about the integration when `by` has started. */
internal class ByGitMergeOfferOnStart(private val project: Project) : ByLspLifecycleListener {
    override fun serverInitialized(serverName: String) {
        if (serverName == BY_SERVER_NAME) ByGitMergeOffers.getInstance(project).check()
    }
}

/** Asks again when the project's version control mappings change, as when the wizard's repository is created. */
internal class ByGitMergeOfferOnMappings(private val project: Project) : VcsMappingListener {
    override fun directoryMappingChanged() {
        ByGitMergeOffers.getInstance(project).check()
    }
}

/** *Set Up Git Merge Integration*: sets it up, or repairs it, whenever the user asks, offer or not. */
internal class ByGitMergeIntegrationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.let { ProjectLevelVcsManager.getInstance(it).hasActiveVcss() } == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<dev.basedpython.pycharm.apilock.ByApiLockScope>().scope.launch { ByGitMergeIntegrations.enable(project) }
    }
}
