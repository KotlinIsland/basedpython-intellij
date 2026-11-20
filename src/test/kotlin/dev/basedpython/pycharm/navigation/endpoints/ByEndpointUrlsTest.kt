package dev.basedpython.pycharm.navigation.endpoints

import com.intellij.microservices.oas.OasHttpMethod
import com.intellij.microservices.oas.OasSchemaType
import com.intellij.microservices.url.UrlPath
import dev.basedpython.pycharm.lsp.ext.ByEndpoint
import dev.basedpython.pycharm.lsp.ext.ByEndpointParameter
import dev.basedpython.pycharm.lsp.ext.ByEndpointSymbol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A route of `by/endpoints` as the Endpoints view's URL model: segments split where `by` says its
 * parameters are written, never by reading a framework's syntax.
 */
class ByEndpointUrlsTest {

    private fun parameter(path: String, written: String, name: String, type: String? = null): ByEndpointParameter {
        val start = path.indexOf(written)
        return ByEndpointParameter(name, type, start, start + written.length)
    }

    private fun describe(path: UrlPath): List<String> = path.segments.map(::describe)

    private fun describe(segment: UrlPath.PathSegment): String = when (segment) {
        is UrlPath.PathSegment.Exact -> segment.value
        is UrlPath.PathSegment.Variable -> "{${segment.variableName}}"
        is UrlPath.PathSegment.Composite -> segment.segments.joinToString("", "[", "]") { describe(it) }
        else -> "?"
    }

    @Test
    fun `a django parameter is a variable segment of its own name`() {
        val path = "/polls/<int:question_id>/vote/"
        val endpoint = ByEndpoint(path = path, parameters = listOf(parameter(path, "<int:question_id>", "question_id", "int")))
        assertEquals(listOf("", "polls", "{question_id}", "vote", ""), describe(ByEndpointUrls.path(endpoint)))
    }

    @Test
    fun `text beside a parameter in one segment is a composite`() {
        val path = "/files/report-{year}.csv"
        val endpoint = ByEndpoint(path = path, parameters = listOf(parameter(path, "{year}", "year")))
        assertEquals(listOf("", "files", "[report-{year}.csv]"), describe(ByEndpointUrls.path(endpoint)))
    }

    @Test
    fun `a slash inside a parameter's text does not split it`() {
        val path = "/re/(?P<slug>[a-z/]+)/end"
        val endpoint = ByEndpoint(path = path, parameters = listOf(parameter(path, "(?P<slug>[a-z/]+)", "slug")))
        assertEquals(listOf("", "re", "{slug}", "end"), describe(ByEndpointUrls.path(endpoint)))
    }

    @Test
    fun `a route by sends no parameters for is exact text, and a range off the path is dropped`() {
        val endpoint = ByEndpoint(path = "/a/<int:pk>/", parameters = listOf(ByEndpointParameter("pk", "int", 3, 99)))
        assertEquals(listOf("", "a", "<int:pk>", ""), describe(ByEndpointUrls.path(endpoint)))
    }

    @Test
    fun `the openapi path has an operation per method, a GET for a route serving every method, and typed parameters`() {
        val path = "/polls/<int:question_id>/"
        val parameters = listOf(parameter(path, "<int:question_id>", "question_id", "int"))
        val handler = ByEndpointSymbol(name = "polls.views.detail")
        val any = ByEndpointUrls.openApi(ByEndpoint(path = path, parameters = parameters, handler = handler))
        assertEquals("/polls/{question_id}/", any.path)
        assertEquals(listOf(OasHttpMethod.GET), any.operations.map { it.method })
        val parameter = any.operations.single().parameters.single()
        assertEquals("question_id", parameter.name)
        assertEquals(OasSchemaType.INTEGER, parameter.schema?.type)

        val two = ByEndpointUrls.openApi(ByEndpoint(path = "/vote/", methods = listOf("POST", "PUT"), handler = handler))
        assertEquals(listOf(OasHttpMethod.POST, OasHttpMethod.PUT), two.operations.map { it.method })
        assertEquals("polls.views.detail", two.summary)
    }
}
