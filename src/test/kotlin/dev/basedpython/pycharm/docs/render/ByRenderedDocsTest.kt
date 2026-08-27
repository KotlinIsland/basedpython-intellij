package dev.basedpython.pycharm.docs.render

import com.intellij.codeInsight.documentation.render.DocRenderManager
import com.intellij.codeInsight.documentation.render.DocRenderPassFactory
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Rendered documentation through the platform's own pass, with no `by` server running.
 *
 * What that asserts is the contract rather than the rendering: where the docstrings are is the
 * server's answer, so with no server there are none, and the editor shows the file as written
 * rather than a guess at it. The rendering itself is [ByDocstringTokensTest] (what the server's
 * payload means), `ByHoverMarkupTest` (what it says) and [ByDocstringTextTest] (the module
 * docstring, which the server cannot answer for) — all against payloads captured from a real
 * `by server`.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByRenderedDocsTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `nothing renders without a server to say what a docstring is`() {
        val file = fixture.configureByText(
            "a.by",
            """
            def greet(name: str) -> None:
                ""\${'"'}Say hello.""\${'"'}
            """.trimIndent(),
        )
        DocRenderManager.setDocRenderingEnabled(fixture.editor, true)
        val items = DocRenderPassFactory.calculateItemsToRender(fixture.editor, file).toList()
        assertEquals(emptyList<DocRenderPassFactory.Item>(), items)
    }

    @Test
    fun `the pass is not confused by a file with no docstrings either`() {
        val file = fixture.configureByText("b.by", "x = 1\nprint(x)\n")
        DocRenderManager.setDocRenderingEnabled(fixture.editor, true)
        assertEquals(
            emptyList<DocRenderPassFactory.Item>(),
            DocRenderPassFactory.calculateItemsToRender(fixture.editor, file).toList(),
        )
    }
}
