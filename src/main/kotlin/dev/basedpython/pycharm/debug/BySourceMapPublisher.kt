package dev.basedpython.pycharm.debug

import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.DapClient
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapEventConsumer
import kotlinx.coroutines.future.await
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

/**
 * The stock DAP client, subclassed rather than instantiated — and the one place a *custom* event
 * can be received.
 *
 * `DapClient` is `@ApiStatus.OverrideOnly` — every event method on it is `final` and forwards to
 * the [DapEventConsumer], which is the seam a plugin is meant to use for the events DAP defines.
 * Extending it satisfies that contract while [BySourceMapPublisher] does the work on the consumer
 * side.
 *
 * Adding to it is what gets us bpd's own events. lsp4j binds notifications by reflecting over the
 * **runtime class** of the local service — `GenericEndpoint.recursiveFindRpcMethods` calls
 * `service.getClass()` — and `DapDebugSessionImpl` hands the launcher the object
 * `DebugAdapterDescriptor.createClient` returned, which is this one. So a method annotated here is
 * routed, with a `JsonObject` rather than a fixed type, and nothing an adapter sends is discarded
 * for want of a field on a POJO. No platform change and no protocol change was needed for that; the
 * belief that DAP could not carry these facts was wrong.
 */
internal class ByDapClient(
    eventConsumer: DapEventConsumer,
    private val onMoved: (ByMoved) -> Unit,
) : DapClient(eventConsumer) {

    /**
     * What a jump or a frame restart really did — see [ByMoved].
     *
     * Nothing here throws: an event body from a newer bpd that this cannot read should cost the
     * report, never the session, and lsp4j would otherwise log a handler failure per move.
     */
    @JsonNotification(ByMoved.EVENT)
    fun moved(params: JsonObject?) {
        try {
            ByMoved.parse(params)?.let(onMoved)
        } catch (e: RuntimeException) {
            LOG.warn("could not read a ${ByMoved.EVENT} event", e)
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(ByDapClient::class.java)
    }
}

/**
 * Registers every `.by` file's source map with pydevd before the platform starts sending
 * breakpoints.
 *
 * Ordering is the entire point of this class. The platform's own consumer answers `initialized` by
 * submitting a command that releases the configuration sender, which sends `setBreakpoints` for
 * every file. Those breakpoints are addressed to `.by` paths and `.by` lines, so they are only
 * meaningful once the maps are in place. Wrapping the consumer and calling [delegate] from *inside*
 * the command that pushes the maps — rather than merely enqueuing the maps first — is what
 * guarantees that: commands run sequentially only up to their first suspension point, so a map
 * request left in flight would otherwise race the breakpoints behind it.
 *
 * A failed map is logged and skipped rather than fatal. Losing one file's mapping costs that file's
 * breakpoints; aborting the session would cost all of them.
 */
internal class BySourceMapPublisher(
    private val delegate: DapEventConsumer,
    private val commandProcessor: DapCommandProcessor,
    private val mappings: List<ByFileMapping>,
) : DapEventConsumer by delegate {

    override fun initialized() {
        commandProcessor.submitCommand {
            val byServer = server as? ByDebugProtocolServer
            if (byServer == null) {
                LOG.warn("Debug adapter server is not a ${ByDebugProtocolServer::class.simpleName}; " +
                    "breakpoints on .by files will not be mapped")
            } else {
                // Before the maps rather than after, because it is the cheaper request and both
                // are ahead of the breakpoints either way. bpd answers it; debugpy answers
                // `unknown command`, which costs nothing — it has no narration to switch off.
                try {
                    byServer.understands(ByUnderstandsArguments(UNDERSTOOD_EVENTS)).await()
                } catch (e: Exception) {
                    LOG.debug("the debug adapter does not answer bpd/understands", e)
                }
                for (mapping in mappings) {
                    try {
                        byServer.setPydevdSourceMap(mapping.toRequest()).await()
                    } catch (e: Exception) {
                        LOG.warn("setPydevdSourceMap failed for ${mapping.source}", e)
                    }
                }
            }
            delegate.initialized()
        }
    }

    private companion object {
        private val LOG = Logger.getInstance(BySourceMapPublisher::class.java)

        /**
         * Every event of bpd's this plugin reads, and therefore every narration it turns off.
         *
         * Naming one it does not in fact handle would be asking for silence about something nobody
         * is listening to, which is the one way this can lose information.
         */
        private val UNDERSTOOD_EVENTS = listOf(ByMoved.EVENT)
    }
}
