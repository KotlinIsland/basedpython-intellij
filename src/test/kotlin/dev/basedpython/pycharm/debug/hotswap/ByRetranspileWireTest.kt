package dev.basedpython.pycharm.debug.hotswap

import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import dev.basedpython.pycharm.lsp.ext.ByRestage
import dev.basedpython.pycharm.lsp.ext.ByRetranspileParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The plugin's half of `by/retranspile`, and `by/transpileForBuild`'s `remap`, on the wire. */
class ByRetranspileWireTest {

    /** lsp4j's own Gson, so this is the json the plugin will actually put on the wire. */
    private val gson = MessageJsonHandler(emptyMap()).gson

    /** `RetranspileParams` is `deny_unknown_fields`: these two fields, by these names, and nothing else. */
    @Test
    fun `the params are the shape the server accepts`() {
        val params = ByRetranspileParams(
            textDocuments = listOf(TextDocumentIdentifier("file:///p/app/views.by")),
            runDirectory = "/cache/basedpython-run/p-1/runs/9f3e",
        )
        assertEquals(
            """{"textDocuments":[{"uri":"file:///p/app/views.by"}],"runDirectory":"/cache/basedpython-run/p-1/runs/9f3e"}""",
            gson.toJson(params),
        )
    }

    /**
     * The answer as `by server` 8d9c9e1cf wrote it for a saved module and for one added since the run
     * (shortened paths): read as json, every field of a module transpiled again kept as written, since
     * bpd is handed it and refuses a field it does not know.
     */
    @Test
    fun `the answer reads as the server writes it, every field kept`() {
        val transpiled = """{"byPath":"/p/app/calc.by","columns":"0 85 0 0;0 45 1 0;;","digests":{"by":"sha256:686d","py":"sha256:87d1"},""" +
            """"key":"/cache/runs/d5f7/retranspiled/a4/b52e.py","lines":"0,1,2,3","source":"/p/app/calc.by"}"""
        val refused = """{"diagnostics":[],"refused":"`/p/app/new.by` is not one of the modules the program was transpiled with","source":"/p/app/new.by"}"""
        val answers: List<JsonObject> = gson.fromJson("[$transpiled,$refused]", object : TypeToken<List<JsonObject>>() {}.type)
        assertEquals(transpiled, answers[0].toString())
        assertTrue(answers[1].has("refused"))
    }

    /** `by/transpileForBuild`'s `remap`, and its absence from a `by` before it. */
    @Test
    fun `transpileForBuild's remap is read, and absent is null`() {
        assertEquals(false, gson.fromJson("""{"files":[],"sourcemap":"x","remap":false}""", ByRestage::class.java).remap)
        assertEquals(null, gson.fromJson("""{"files":[],"sourcemap":"x"}""", ByRestage::class.java).remap)
    }
}
