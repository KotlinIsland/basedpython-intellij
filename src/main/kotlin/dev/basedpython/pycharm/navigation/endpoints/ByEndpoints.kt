package dev.basedpython.pycharm.navigation.endpoints

import com.intellij.microservices.endpoints.EndpointType
import com.intellij.microservices.endpoints.HTTP_SERVER_TYPE
import com.intellij.microservices.endpoints.EndpointsFilter
import com.intellij.microservices.endpoints.EndpointsProvider
import com.intellij.microservices.endpoints.FrameworkPresentation
import com.intellij.microservices.endpoints.SearchScopeEndpointsFilter
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiModificationTracker
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByEndpoint
import dev.basedpython.pycharm.lsp.ext.ByEndpointSymbol
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles

/**
 * What `by/endpoints` says the project serves, kept against the project's PSI: a route added in any
 * file is one more. Asked off the EDT, as the Endpoints view gathers its rows; on it, only an answer
 * already had is given. A `by` without the request, or none running, serves no routes here.
 */
@Service(Service.Level.PROJECT)
internal class ByEndpoints(private val project: Project) {

    private class Answer(val stamp: Long, val endpoints: List<ByEndpoint>)

    @Volatile
    private var answer: Answer? = null

    fun endpoints(): List<ByEndpoint> {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount
        answer?.takeIf { it.stamp == stamp }?.let { return it.endpoints }
        if (ApplicationManager.getApplication().isDispatchThread) return answer?.endpoints.orEmpty()
        val server = LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running } ?: return emptyList()
        val endpoints = server.askBy("by/endpoints", TIMEOUT_MS) { (it as ByServerExtensions).endpoints(emptyMap()) }.value
            ?: return emptyList()
        answer = Answer(stamp, endpoints)
        return endpoints
    }

    companion object {
        fun getInstance(project: Project): ByEndpoints = project.service()

        /** Every route of the session's projects is read. */
        private const val TIMEOUT_MS = 30_000
    }
}

/** The routes one application serves, or a router no application includes when [application] is null. */
internal data class ByEndpointGroup(val application: String?, val endpoints: List<ByEndpoint>)

/**
 * The Endpoints tool window's rows for a basedpython project, one framework a provider, from
 * `by/endpoints`: each route with its methods and whole path, every prefix it is mounted under
 * included, in the file its handler is written in, opening at the handler. Which decorator or
 * `path()` is a route, and what it is mounted under, is `by`'s, read through what the code means.
 *
 * A route whose handler is in a file this plugin does not own — a `.py` the Python plugin reads in
 * PyCharm — is left to that plugin's own providers, so no route is listed twice.
 */
internal abstract class ByEndpointsProvider(private val framework: String, title: String) : EndpointsProvider<ByEndpointGroup, ByEndpoint> {

    override val presentation: FrameworkPresentation = FrameworkPresentation("basedpython-$framework", title, BasedPythonIcons.Logo)

    override val endpointType: EndpointType = HTTP_SERVER_TYPE

    override fun getStatus(project: Project): EndpointsProvider.Status = when {
        !BasedPythonProjectDetector.isBasedPythonProject(project) -> EndpointsProvider.Status.UNAVAILABLE
        ApplicationManager.getApplication().isDispatchThread -> EndpointsProvider.Status.AVAILABLE
        routes(project).isNotEmpty() -> EndpointsProvider.Status.HAS_ENDPOINTS
        else -> EndpointsProvider.Status.AVAILABLE
    }

    override fun getEndpointGroups(project: Project, filter: EndpointsFilter): Iterable<ByEndpointGroup> {
        val scope = (filter as? SearchScopeEndpointsFilter)?.contentSearchScope
        return routes(project)
            .filter { endpoint -> scope == null || handlerFile(project, endpoint)?.let(scope::contains) == true }
            .groupBy { it.application?.name }
            .map { (application, endpoints) -> ByEndpointGroup(application, endpoints) }
    }

    override fun getEndpoints(group: ByEndpointGroup): Iterable<ByEndpoint> = group.endpoints

    override fun isValidEndpoint(group: ByEndpointGroup, endpoint: ByEndpoint): Boolean = true

    override fun getEndpointPresentation(group: ByEndpointGroup, endpoint: ByEndpoint): ItemPresentation =
        HttpMethodPresentation(endpoint.path.orEmpty(), endpoint.methods, endpoint.handler?.uri?.substringAfterLast('/'), BasedPythonIcons.Logo)

    override fun getModificationTracker(project: Project): ModificationTracker = PsiModificationTracker.getInstance(project)

    override fun getNavigationElement(group: ByEndpointGroup, endpoint: ByEndpoint): PsiElement? = handlerName(endpoint)

    override fun getDocumentationElement(group: ByEndpointGroup, endpoint: ByEndpoint): PsiElement? = handlerName(endpoint)

    /** The routes of [framework] whose handler is in a file of this plugin's. */
    private fun routes(project: Project): List<ByEndpoint> =
        ByEndpoints.getInstance(project).endpoints().filter { it.framework == framework && handlerPsi(project, it) != null }

    private fun handlerFile(project: Project, endpoint: ByEndpoint) = handlerPsi(project, endpoint)?.virtualFile

    private fun handlerPsi(project: Project, endpoint: ByEndpoint): BasedPythonFile? {
        val file = endpoint.handler?.uri?.let(ByWorkspaceEditFiles::fileOf) ?: return null
        return PsiManager.getInstance(project).findFile(file) as? BasedPythonFile
    }

    /** The token of the handler's name, where the row opens. */
    private fun handlerName(endpoint: ByEndpoint): PsiElement? {
        val handler: ByEndpointSymbol = endpoint.handler ?: return null
        val file = handler.uri?.let(ByWorkspaceEditFiles::fileOf) ?: return null
        val projects = com.intellij.openapi.project.ProjectLocator.getInstance().getProjectsForFile(file)
        val project = projects.firstOrNull() ?: return null
        val psi = PsiManager.getInstance(project).findFile(file) as? BasedPythonFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return null
        val offset = (handler.selectionRange ?: handler.range)?.start?.let { getOffsetInDocument(document, it) } ?: return null
        return psi.findElementAt(offset)
    }
}

/** FastAPI's routes, as `by` reads them. */
internal class ByFastApiEndpointsProvider : ByEndpointsProvider("fastapi", "FastAPI (basedpython)")

/** django's routes, as `by` reads them. */
internal class ByDjangoEndpointsProvider : ByEndpointsProvider("django", "Django (basedpython)")
