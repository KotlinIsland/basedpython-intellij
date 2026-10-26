package dev.basedpython.pycharm.navigation.endpoints

import com.intellij.microservices.endpoints.HTTP_SERVER_TYPE
import com.intellij.microservices.endpoints.presentation.HttpMethodPresentation
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByEndpoint
import dev.basedpython.pycharm.lsp.ext.ByEndpointSymbol
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The Endpoints tool window's rows for a basedpython project: each route `by/endpoints` names, one
 * framework a provider, shown by its methods and whole path, opening at its handler's name.
 */
@TestFixtures
class ByEndpointsProviderTest {

    private val fixture by codeInsightFixture()

    private fun endpoint(framework: String, methods: List<String>, path: String, uri: String, line: Int, start: Int, name: String) = ByEndpoint(
        framework = framework,
        methods = methods,
        path = path,
        handler = ByEndpointSymbol(name, uri, Range(Position(line, 0), Position(line + 1, 0)), Range(Position(line, start), Position(line, start + name.substringAfterLast('.').length))),
        application = ByEndpointSymbol("app.main.app", uri),
    )

    @Test
    fun `a route is its methods and whole path, in its handler's file`() = onEdt {
        val provider = ByFastApiEndpointsProvider()
        val route = endpoint("fastapi", listOf("GET", "POST"), "/v1/items/", "file:///p/app/items.by", 12, 4, "app.items.list_items")
        val presentation = provider.getEndpointPresentation(ByEndpointGroup("app.main.app", listOf(route)), route) as HttpMethodPresentation
        assertEquals("/v1/items/", presentation.presentableText)
        assertEquals(listOf("GET", "POST"), presentation.endpointMethods)
        assertEquals("items.by", presentation.locationString)
        assertEquals(HTTP_SERVER_TYPE, provider.endpointType)
        assertEquals("basedpython-fastapi", provider.presentation.queryTag)
        assertEquals("basedpython-django", ByDjangoEndpointsProvider().presentation.queryTag)
    }
}
