package dev.basedpython.pycharm.lsp.inlay

import com.google.gson.Gson
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.util.messages.Topic
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler

/** `by/inlayHintCatalog`: every kind of inlay hint `by` computes, and how to present each. */
data class ByInlayHintCatalog(
    /** In the order they are listed. */
    val groups: List<ByHintGroup>? = null,
    /** In the order they are listed, which keeps each group's kinds together. */
    val kinds: List<ByHintKindInfo>? = null,
) {
    /** The kinds a `.by` or `.py` file can have; the django template ones are not drawn by this plugin. */
    val sourceKinds: List<ByHintKindInfo> get() = kinds.orEmpty().filter { it.isSource && it.setting.isNotEmpty() }

    fun kind(kind: ByHintKind): ByHintKindInfo? = kinds?.firstOrNull { it.setting == kind.setting }

    fun group(id: String): ByHintGroup? = groups?.firstOrNull { it.id == id }
}

data class ByHintGroup(val id: String = "", val title: String = "")

data class ByHintKindInfo(
    /** The `inlayHints` option, and the tag on each hint of the kind. */
    val setting: String = "",
    val title: String = "",
    /** A [ByHintGroup.id]. */
    val group: String = "",
    /** Plain text, a sentence or two. */
    val description: String = "",
    /** `preceding` for a hint that completes the code before it (`: int`), `following` for one that introduces the code after it (`x=`). */
    val attachesTo: String? = null,
    /** `source`, or `template` for a django template's. */
    val fileKind: String? = null,
    /** A short program showing the kind, with the hints `by` computes for it; `null` when none can be shown standalone. */
    val example: ByHintExample? = null,
    /**
     * The stub [example]'s hints were computed against, for a kind whose hints come from a stub
     * (`stubTypes`, `stubOnlyMembers`): what the example's module would have beside it as its `.pyi`.
     */
    val exampleStub: String? = null,
) {
    val kind: ByHintKind get() = ByHintKind(setting)

    /** Which side of the inlay the caret lands on, typing at its offset — `Inlay.isRelatedToPrecedingText`. */
    val relatesToPrecedingText: Boolean get() = attachesTo != "following"

    val isSource: Boolean get() = fileKind == null || fileKind == "source"
}

data class ByHintExample(
    val source: String = "",
    /** As `textDocument/inlayHint` answers for [source], every kind on, each tagged with its kind. */
    val hints: List<InlayHint>? = null,
)

/**
 * The catalog the last `by` that started gave, kept across restarts.
 *
 * Kept, because the settings pages are opened with no server running as often as with one: a project
 * not yet indexed, `by` switched off for a moment, or the page opened from the welcome screen's
 * project. The catalog is the server's, not the project's, so it is application-wide, and in the
 * cache file: losing it costs nothing but waiting for the next `by` to start.
 *
 * Stored as the server's JSON, read with lsp4j's own Gson so an [InlayHint]'s `string | parts`
 * label and `string | markup` tooltip come back as they went in.
 */
@Service(Service.Level.APP)
@State(name = "BasedPythonInlayHintCatalog", storages = [Storage(StoragePathMacros.CACHE_FILE)])
internal class ByInlayCatalog : PersistentStateComponent<ByInlayCatalog.State> {

    class State {
        var json: String = ""
    }

    private var state = State()

    @Volatile
    private var parsed: ByInlayHintCatalog? = null

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
        parsed = null
    }

    /** The last catalog a `by` gave, or `null` when none ever has. */
    val catalog: ByInlayHintCatalog?
        get() = parsed ?: state.json.takeIf { it.isNotEmpty() }?.let { json ->
            runCatching { GSON.fromJson(json, ByInlayHintCatalog::class.java) }
                .onFailure { LOG.warn("cached inlay hint catalog could not be read", it) }
                .getOrNull()
                ?.also { parsed = it }
        }

    /** Whether [catalog] is different from what was kept, which is then replaced by it. */
    fun update(catalog: ByInlayHintCatalog): Boolean {
        val json = GSON.toJson(catalog)
        if (json == state.json) return false
        state.json = json
        parsed = catalog
        ApplicationManager.getApplication().messageBus.syncPublisher(CHANGED).catalogChanged()
        return true
    }

    /** Which side of a [kind] hint the caret lands on; a kind the catalog does not name completes what precedes it. */
    fun relatesToPrecedingText(kind: ByHintKind): Boolean = catalog?.kind(kind)?.relatesToPrecedingText ?: true

    fun interface Listener {
        fun catalogChanged()
    }

    companion object {
        private val LOG = Logger.getInstance(ByInlayCatalog::class.java)

        private val GSON: Gson = MessageJsonHandler(emptyMap()).gson

        @Topic.AppLevel
        val CHANGED: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(): ByInlayCatalog = service()
    }
}

/**
 * Asks a `by` that has just started for its catalog ([ByInlayCatalog]). A `by` from before the
 * request leaves the last one kept as it is.
 */
internal class ByInlayCatalogRefresh(private val project: Project) : ByLspLifecycleListener {

    /** On a thread of its own, for the reason `ByRuleRefresh.serverInitialized` gives. */
    override fun serverInitialized(serverName: String) {
        if (serverName != "by" || project.isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val server = LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
                .firstOrNull { it.state == LspServerState.Running } ?: return@executeOnPooledThread
            val catalog = server.askBy("by/inlayHintCatalog", TIMEOUT_MS) { (it as ByServerExtensions).inlayHintCatalog() }.value
                ?: return@executeOnPooledThread
            if (catalog.kinds.isNullOrEmpty()) return@executeOnPooledThread
            ByInlayCatalog.getInstance().update(catalog)
        }
    }

    private companion object {
        /** A server still loading its project answers once it has. */
        const val TIMEOUT_MS = 60_000
    }
}
