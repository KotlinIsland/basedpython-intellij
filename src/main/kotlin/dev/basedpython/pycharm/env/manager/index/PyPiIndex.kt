package dev.basedpython.pycharm.env.manager.index

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.HttpRequests
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * A PEP 503 / PEP 691 package index — PyPI, or anything that speaks the same Simple API.
 *
 * Two endpoints, with very different shapes and costs:
 *
 * - The **simple index** (`/simple/`), which lists every project. Asked for as PEP 691 JSON, this is
 *   42 MB of document carrying 872,009 names, so it is *streamed*: [fetchNames] pulls names out with
 *   a token reader and never builds the document in memory. It is also almost perfectly static —
 *   append-mostly — which is what makes caching it for a week reasonable.
 * - The **project JSON** (`/pypi/<name>/json`), 27–138 KB per package, which carries the summary,
 *   the newest version and — the field this exists for — `provides_extra`.
 *
 * The project endpoint is PyPI's own rather than part of the Simple API standard. A private mirror
 * that does not implement it simply returns nothing for [fetchDetails], and the dialog degrades to
 * name completion without the extras — which is why the two are separate calls rather than one.
 */
class PyPiIndex(
    /** The Simple API root, e.g. `https://pypi.org/simple`. */
    private val simpleUrl: String,
    /** The project metadata root, or null when this index does not offer one. */
    private val projectJsonUrl: String?,
) : PackageIndex {

    override val id: String = idFor(simpleUrl)

    override val displayName: String = runCatching { URI(simpleUrl).host }.getOrNull() ?: simpleUrl

    /**
     * Streams every project name out of the simple index.
     *
     * The document is `{"meta": …, "projects": [{"name": …}, …]}`, read with a [JsonReader] so that
     * 42 MB never becomes a tree — only one name exists at a time, and the caller decides what to do
     * with each. Fields other than `projects` are skipped rather than rejected, so a future
     * `meta`-level addition costs nothing.
     */
    override fun fetchNames(consumer: (String) -> Unit) {
        val url = simpleUrl.trimEnd('/') + "/"
        HttpRequests.request(url)
            .accept(SIMPLE_JSON_ACCEPT)
            .productNameAsUserAgent()
            .connect { request ->
                JsonReader(InputStreamReader(request.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    readNames(reader, consumer)
                }
            }
    }

    override fun fetchDetailsDocument(name: String): String? {
        val root = projectJsonUrl?.trimEnd('/') ?: return null
        val normalised = PackageNameStore.normalise(name)
        if (normalised.isEmpty()) return null
        return try {
            HttpRequests.request("$root/$normalised/json").productNameAsUserAgent().readString()
        } catch (e: Exception) {
            // A package the index has never heard of is a 404, and an unreachable index is an
            // IOException. Neither is worth a notification: the field still takes free text.
            LOG.debug("no index metadata for $name", e)
            null
        }
    }

    override fun parseDetails(name: String, document: String): PackageDetails? =
        Companion.parseDetails(name, document)

    companion object {

        private val LOG = Logger.getInstance(PyPiIndex::class.java)

        /** PEP 691. Without it the simple index answers with 42 MB of HTML instead. */
        const val SIMPLE_JSON_ACCEPT: String = "application/vnd.pypi.simple.v1+json"

        const val DEFAULT_SIMPLE_URL: String = "https://pypi.org/simple"
        const val DEFAULT_PROJECT_JSON_URL: String = "https://pypi.org/pypi"

        /** The public PyPI. */
        fun pypi(): PyPiIndex = PyPiIndex(DEFAULT_SIMPLE_URL, DEFAULT_PROJECT_JSON_URL)

        /**
         * Pulls `projects[].name` out of a PEP 691 document.
         *
         * Split out from the connection so it can be driven from a string in a test — the shape of
         * this document is another service's, and a fixture is the only way to pin what happens when
         * it changes.
         */
        fun readNames(reader: JsonReader, consumer: (String) -> Unit) {
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() != "projects") {
                    reader.skipValue()
                    continue
                }
                reader.beginArray()
                while (reader.hasNext()) {
                    reader.beginObject()
                    var name: String? = null
                    while (reader.hasNext()) {
                        if (reader.nextName() == "name") name = reader.nextString() else reader.skipValue()
                    }
                    reader.endObject()
                    name?.takeIf { it.isNotBlank() }?.let(consumer)
                }
                reader.endArray()
            }
            reader.endObject()
        }

        /** [readNames] over a document already in hand. For tests. */
        fun readNames(document: String, consumer: (String) -> Unit) =
            JsonReader(document.reader()).use { readNames(it, consumer) }

        /**
         * A cache directory name for [url].
         *
         * Host and path, reduced to filename-safe characters, plus a hash so that two URLs which
         * flatten to the same string still get separate caches. Serving a private mirror's
         * catalogue to a project pointed at the public index would be worse than having no cache.
         */
        fun idFor(url: String): String {
            val host = runCatching { URI(url).host }.getOrNull() ?: "index"
            val safe = host.replace(Regex("[^A-Za-z0-9.-]"), "_").take(40)
            return "$safe-${Integer.toHexString(url.hashCode())}"
        }

        /** Reads the fields the dialog shows out of a project JSON document. */
        fun parseDetails(name: String, body: String): PackageDetails? = try {
            val info = JsonParser.parseString(body)
                .takeIf { it.isJsonObject }?.asJsonObject
                ?.getAsJsonObject("info")
            if (info == null) {
                null
            } else {
                fun string(key: String): String? = info.get(key)
                    ?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
                PackageDetails(
                    name = string("name") ?: name,
                    latestVersion = string("version"),
                    summary = string("summary"),
                    // `provides_extra` is JSON null for a package that declares none — which is most
                    // of them — and absent entirely on older metadata. Both have to be read as "no
                    // extras": `getAsJsonArray` throws on a null, and since this whole block is
                    // guarded, that turned every ordinary package into "no details at all", losing
                    // its summary and version along with the extras it never had.
                    extras = info.get("provides_extra")
                        ?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.distinct()
                        ?.sorted()
                        .orEmpty(),
                    homepage = string("home_page") ?: string("project_url") ?: string("package_url"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
