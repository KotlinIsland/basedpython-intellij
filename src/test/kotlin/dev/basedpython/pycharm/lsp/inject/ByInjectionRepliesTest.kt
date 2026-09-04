package dev.basedpython.pycharm.lsp.inject

import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByInjectionFragment
import dev.basedpython.pycharm.lsp.ext.ByInjectionsParams
import dev.basedpython.pycharm.lsp.ext.ByInjectionsResponse
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two ends of the `by/injections` contract: the json that goes out, and what comes back turned
 * into offsets.
 *
 * `ty_server`'s `InjectionsParams` is `deny_unknown_fields`, so a field spelled differently here is
 * a refused request rather than an ignored field — and a refused request looks exactly like a file
 * with nothing in it. The crate has the same test from the other side; this is the half that would
 * catch a rename made only there.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByInjectionRepliesTest {

    private val fixture by codeInsightFixture()

    /** lsp4j's own Gson, so this is the json the plugin will actually put on the wire. */
    private val gson = MessageJsonHandler(emptyMap()).gson

    // region: the wire

    @Test
    fun `the params a client sends are the shape the server accepts`() {
        val params = ByInjectionsParams(TextDocumentIdentifier("file:///main.by"))
        assertEquals("""{"textDocument":{"uri":"file:///main.by"}}""", gson.toJson(params))
    }

    @Test
    fun `a response the server sends reads back whole`() {
        val json = """
            {"injections":[{"language":"html","ranges":[{"start":{"line":3,"character":8},
            "end":{"line":3,"character":21}}],"origin":"comment"}]}
        """.trimIndent().replace("\n", "")

        val response = gson.fromJson(json, ByInjectionsResponse::class.java)
        val fragment = response.injections.single()

        assertEquals("html", fragment.language)
        assertEquals("comment", fragment.origin)
        assertEquals(Range(Position(3, 8), Position(3, 21)), fragment.ranges.single())
    }

    // endregion

    // region: positions to offsets

    /** The document `by` was asked about, and the reply read against it. */
    private fun read(source: String, vararg fragments: ByInjectionFragment): List<ByInjection> {
        val file = fixture.configureByText("main.by", source)
        val document = file.viewProvider.document!!
        return ByInjectionReplies.read(ByInjectionsResponse(fragments.toList()), document)
    }

    private fun fragment(
        language: String = "html",
        origin: String = "comment",
        vararg ranges: Range,
    ) = ByInjectionFragment(language = language, ranges = ranges.toList(), origin = origin)

    @Test
    fun `a line and a character become an offset in the document`() {
        // Line 1 is `b = "<i>x</i>"`, whose content starts at character 5.
        val found = read(
            "a = 1\nb = \"<i>x</i>\"\n",
            fragment(ranges = arrayOf(Range(Position(1, 5), Position(1, 13)))),
        )
        assertEquals(listOf(TextRange(11, 19)), found.single().ranges)
    }

    @Test
    fun `the origin comes back as the reason it names`() {
        val range = Range(Position(0, 5), Position(0, 6))
        assertEquals(
            listOf(ByInjectionOrigin.COMMENT, ByInjectionOrigin.DECLARED, ByInjectionOrigin.PROPAGATED),
            read(
                "a = \"x\"\n",
                fragment(origin = "comment", ranges = arrayOf(range)),
                fragment(origin = "declared", ranges = arrayOf(range)),
                fragment(origin = "propagated", ranges = arrayOf(range)),
            ).map { it.origin },
        )
    }

    @Test
    fun `a fragment with a position the document no longer has is dropped`() {
        val found = read(
            "a = \"x\"\n",
            fragment(ranges = arrayOf(Range(Position(99, 0), Position(99, 4)))),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `a fragment is dropped whole when only one of its parts is placeable`() {
        // The second part is off the end, and a fragment is its parts joined: keeping the first
        // alone would report a different string as the same fragment.
        val found = read(
            "a = \"x\" \"y\"\n",
            fragment(
                ranges = arrayOf(
                    Range(Position(0, 5), Position(0, 6)),
                    Range(Position(50, 0), Position(50, 1)),
                ),
            ),
        )
        assertTrue(found.isEmpty())
    }

    @Test
    fun `an answer this plugin cannot read is dropped rather than guessed at`() {
        val range = Range(Position(0, 5), Position(0, 6))
        assertTrue(read("a = \"x\"\n", fragment(language = "", ranges = arrayOf(range))).isEmpty())
        assertTrue(read("a = \"x\"\n", fragment(origin = "whatever", ranges = arrayOf(range))).isEmpty())
        assertTrue(read("a = \"x\"\n", fragment()).isEmpty())
    }

    // endregion
}
