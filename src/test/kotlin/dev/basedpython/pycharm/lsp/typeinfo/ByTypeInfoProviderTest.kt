package dev.basedpython.pycharm.lsp.typeinfo

import com.intellij.codeInsight.hint.ShowExpressionTypeHandler
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.util.TextRange
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ext.ByExpressionType
import dev.basedpython.pycharm.lsp.ext.ByExpressionTypesParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

/**
 * Type Info is a platform action driven by whichever [com.intellij.lang.ExpressionTypeProvider] is
 * registered for the caret's language, so the two things worth asserting without a live server are
 * that the provider is *found* for `.by` (registration, not code, is what was missing before) and
 * that it offers only the tokens `by` can type.
 *
 * With a `by` that answers `by/expressionTypes`, every expression around the token is offered with
 * its type; the hover-based hint for a `by` without it needs a running server, and its binary-free
 * half — turning a hover payload into hint HTML — is [ByHoverMarkupTest].
 */
@TestFixtures
class ByTypeInfoProviderTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `the action finds a provider for by files`() = onEdt {
        val handlers = ShowExpressionTypeHandler.getHandlers(fixture.project, BasedPythonLanguage)
        assertTrue(
            handlers.any { it is ByTypeInfoProvider },
            "Type Info (Ctrl+Shift+P) is dead in a .by file unless a codeInsight.typeInfo " +
                "extension is registered for the basedpython language; found: $handlers",
        )
    }

    @Test
    fun `offers the name under the caret`() = onEdt {
        val file = fixture.configureByText("a.by", "value = 1\nprint(val<caret>ue)")
        val leaf = file.findElementAt(fixture.caretOffset)!!
        val expressions = ByTypeInfoProvider().getExpressionsAt(leaf)
        assertEquals(listOf(leaf), expressions)
        assertEquals("value", expressions.single().text)
    }

    /** `by` reports nothing for a literal, so a number would only ever produce an empty hint. */
    @Test
    fun `does not offer numbers, operators or whitespace`() = onEdt {
        val file = fixture.configureByText("a.by", "x = 1 + 2  # note")
        val offered = { offset: Int ->
            ByTypeInfoProvider().getExpressionsAt(file.findElementAt(offset)!!).isNotEmpty()
        }
        assertFalse(offered(file.text.indexOf('1')), "number literal")
        assertFalse(offered(file.text.indexOf('+')), "operator")
        assertFalse(offered(file.text.indexOf(' ')), "whitespace")
        assertFalse(offered(file.text.indexOf('#')), "comment")
        assertTrue(offered(file.text.indexOf('x')), "identifier")
    }

    /** A `.py` file with no basedpython marker is Python's, and its own provider answers there. */
    @Test
    fun `ignores elements from other languages`() = onEdt {
        val file = fixture.configureByText("a.txt", "value")
        assertTrue(ByTypeInfoProvider().getExpressionsAt(file.findElementAt(0)!!).isEmpty())
    }

    @Test
    fun `says why there is no type when no server is running`() = onEdt {
        val file = fixture.configureByText("a.by", "val<caret>ue = 1")
        val leaf = file.findElementAt(fixture.caretOffset)!!
        assertEquals(
            "No type: the by language server is not running",
            ByTypeInfoProvider().getInformationHint(leaf),
        )
    }

    @Test
    fun `every expression around the token is offered with by's type, innermost first`() = onEdt {
        val source = "kind, _, size = spec.partition(\":\")\n"
        val file = fixture.configureByText("a.by", source)
        val at = source.indexOf("spec")
        val client = AnsweringClient(fixture.project) {
            answered(
                listOf(
                    ByExpressionType(Range(Position(0, 16), Position(0, 20)), "str"),
                    ByExpressionType(Range(Position(0, 16), Position(0, 30)), "bound method str.partition(sep: str, /) -> tuple[str, str, str]"),
                    ByExpressionType(Range(Position(0, 16), Position(0, 35)), "tuple[str, str, str]"),
                ),
            )
        }

        val onEdt = ByTypeInfoProvider.expressionsAround(client, file, TextRange.from(at, 4))
        val inBackground = withContext(Dispatchers.Default) { ByTypeInfoProvider.expressionsAround(client, file, TextRange.from(at, 4)) }

        for (answer in listOf(onEdt, inBackground)) {
            val expressions = (answer as ByAnswer.Answer).value
            assertEquals(listOf("spec", "spec.partition", "spec.partition(\":\")"), expressions.map { it.text })
            assertEquals("tuple[str, str, str]", ByTypeInfoProvider().getInformationHint(expressions.last()))
            assertEquals("bound method str.partition(sep: str, /) -&gt; tuple[str, str, str]", ByTypeInfoProvider().getAdvancedInformationHint(expressions[1]))
        }
        assertEquals(Range(Position(0, 16), Position(0, 20)), (client.sentParams.first() as ByExpressionTypesParams).range)
        assertEquals(
            (onEdt as ByAnswer.Answer).value.first(),
            (inBackground as ByAnswer.Answer).value.first(),
            "a second press on the same expression is the same one, which asks for the advanced information",
        )
    }

    @Test
    fun `a range in no expression is none, and a by without the request falls back to the token`() = onEdt {
        val file = fixture.configureByText("a.by", "return x\n")
        val none = AnsweringClient(fixture.project) { answered(emptyList<ByExpressionType>()) }
        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound) }

        assertEquals(ByAnswer.None, ByTypeInfoProvider.expressionsAround(none, file, TextRange(0, 6)))
        assertEquals(ByAnswer.Failed, ByTypeInfoProvider.expressionsAround(old, file, TextRange(0, 6)))
    }
}
