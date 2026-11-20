package dev.basedpython.pycharm.navigation.endpoints

import com.intellij.microservices.oas.OasEndpointPath
import com.intellij.microservices.oas.OasHttpMethod
import com.intellij.microservices.oas.OasOperation
import com.intellij.microservices.oas.OasParameter
import com.intellij.microservices.oas.OasParameterIn
import com.intellij.microservices.oas.OasSchema
import com.intellij.microservices.oas.OasSchemaType
import com.intellij.microservices.url.Authority
import com.intellij.microservices.url.UrlPath
import com.intellij.microservices.url.UrlTargetInfo
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.lsp.ext.ByEndpoint
import dev.basedpython.pycharm.lsp.ext.ByEndpointParameter
import javax.swing.Icon

/**
 * A route of `by/endpoints` as the URL model the Endpoints view's *HTTP Client* and *OpenAPI* tabs
 * are drawn from, and *Generate Request in HTTP Client* writes a request from.
 *
 * Where a path parameter is written, and what it is called, is `by`'s: each [ByEndpointParameter]
 * names the stretch of [ByEndpoint.path] it is written as — `<int:question_id>` in django, `{id}` in
 * FastAPI — so nothing here reads either framework's syntax. A route whose parameters `by` does not
 * send is all exact text.
 */
internal object ByEndpointUrls {

    /** [endpoint]'s path as segments: exact text, a parameter, or a mix of both inside one segment. */
    fun path(endpoint: ByEndpoint): UrlPath {
        val path = endpoint.path.orEmpty()
        val parameters = endpoint.parameters
            .filter { it.name != null && 0 <= it.start && it.start < it.end && it.end <= path.length }
            .sortedBy { it.start }
        val segments = mutableListOf<UrlPath.PathSegment>()
        var segmentStart = 0
        // a `/` inside a parameter's text (a regex's) does not end its segment
        fun insideParameter(offset: Int) = parameters.any { offset in it.start until it.end }
        for (offset in 0..path.length) {
            if (offset < path.length && (path[offset] != '/' || insideParameter(offset))) continue
            segments += segment(path, segmentStart, offset, parameters)
            segmentStart = offset + 1
        }
        return UrlPath(segments)
    }

    private fun segment(path: String, start: Int, end: Int, parameters: List<ByEndpointParameter>): UrlPath.PathSegment {
        val inside = parameters.filter { it.start >= start && it.end <= end }
        if (inside.isEmpty()) return UrlPath.PathSegment.Exact(path.substring(start, end))
        val parts = mutableListOf<UrlPath.PathSegment>()
        var at = start
        for (parameter in inside) {
            if (parameter.start > at) parts += UrlPath.PathSegment.Exact(path.substring(at, parameter.start))
            parts += UrlPath.PathSegment.Variable(parameter.name!!, null)
            at = parameter.end
        }
        if (at < end) parts += UrlPath.PathSegment.Exact(path.substring(at, end))
        return parts.singleOrNull() ?: UrlPath.PathSegment.Composite(parts)
    }

    /**
     * [endpoint] as an OpenAPI path: an operation for each of its methods, or a `GET` for a route
     * that serves every method, as each of django's does unless it says otherwise — the method a
     * browser sends — and each path parameter typed as `by` types it.
     */
    fun openApi(endpoint: ByEndpoint): OasEndpointPath {
        val parameters = endpoint.parameters.mapNotNull { parameter ->
            val name = parameter.name ?: return@mapNotNull null
            OasParameter(name, OasParameterIn.PATH, parameter.type, true, false, OasSchema(schemaType(parameter.type)), null)
        }
        val methods = endpoint.methods.mapNotNull { method -> OasHttpMethod.entries.firstOrNull { it.name == method.uppercase() } }
            .ifEmpty { listOf(OasHttpMethod.GET) }
        val summary = endpoint.handler?.name
        val operations = methods.map { method ->
            OasOperation(method, emptyList(), null, summary, summary?.let { "$it.${method.name.lowercase()}" }, false, parameters, null, emptyList())
        }
        return OasEndpointPath(path(endpoint).getPresentation(UrlPath.FULL_PATH_VARIABLE_PRESENTATION), summary, operations)
    }

    /** The JSON schema type of a parameter `by` types as the python [type] its converter gives. */
    fun schemaType(type: String?): OasSchemaType = when (type) {
        "int" -> OasSchemaType.INTEGER
        "float", "decimal.Decimal" -> OasSchemaType.NUMBER
        "bool" -> OasSchemaType.BOOLEAN
        else -> OasSchemaType.STRING
    }
}

/** One route as the URL model knows it: any host, `http` or `https`, opening at its handler. */
internal class ByUrlTargetInfo(
    private val endpoint: ByEndpoint,
    private val resolve: () -> PsiElement?,
) : UrlTargetInfo {
    /** The platform's `UrlConstants.HTTP_SCHEMES`, spelled as it spells them; that object is not on the compile classpath. */
    override val schemes: List<String> = listOf("http://", "https://")
    override val authorities: List<Authority> = listOf(Authority.Placeholder())
    override val path: UrlPath = ByEndpointUrls.path(endpoint)
    override val methods: Set<String> = endpoint.methods.map { it.uppercase() }.toSet()
    override val icon: Icon = BasedPythonIcons.Logo
    override val source: String = endpoint.handler?.name.orEmpty()
    override fun resolveToPsiElement(): PsiElement? = resolve()
}
