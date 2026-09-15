package dev.basedpython.pycharm.run.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.basedpython.pycharm.lsp.ext.ByEntryPointResponse
import dev.basedpython.pycharm.lsp.ext.ByTestItemReply
import dev.basedpython.pycharm.run.main.ByMainFunction

/**
 * What a real `by server` answered to `by/entryPoint` and `by/testItems` about a set of sources,
 * recorded in `by-replies/program-model.json`.
 *
 * Recorded rather than written by hand, because the point of asking the server is that the plugin no
 * longer has its own idea of what a signature means: a reply typed out here would be exactly that
 * idea again. Each entry holds the source text a file was given and the reply the server sent for
 * it, taken by opening a workspace of those files in `by server` and sending the two requests; to
 * cover a new source, add it and record again.
 */
internal object ByReplies {

    private val recorded: JsonObject by lazy {
        val stream = checkNotNull(ByReplies::class.java.getResourceAsStream("/by-replies/program-model.json")) {
            "by-replies/program-model.json missing from the test classpath"
        }
        stream.reader().use { Gson().fromJson(it, JsonObject::class.java) }
    }

    /** The reply to `by/entryPoint` for a file holding exactly [source]. */
    fun entryPoint(source: String, extension: String = "by"): ByEntryPointResponse {
        val entry = recorded.getAsJsonArray("entryPoints")
            .map { it.asJsonObject }
            .firstOrNull { it["source"].asString == source && it["extension"].asString == extension }
            ?: error("no recorded by/entryPoint reply for a .$extension holding:\n$source")
        return Gson().fromJson(entry["reply"], ByEntryPointResponse::class.java)
    }

    /** The reply to `by/testItems` for a test file holding exactly [source]. */
    fun testItems(source: String): List<ByTestItem> {
        val entry = recorded.getAsJsonArray("testItems")
            .map { it.asJsonObject }
            .firstOrNull { it["source"].asString == source }
            ?: error("no recorded by/testItems reply for a file holding:\n$source")
        return Gson().fromJson(entry["tests"], Array<ByTestItemReply>::class.java).map(ByTestItem::of)
    }

    /** The command line of `def main([signature]): ...`, as the server reads it. */
    fun main(signature: String): ByMainFunction =
        ByMainFunction.of(checkNotNull(entryPoint("def main($signature): ...\n").main) { "no main in $signature" })
}
