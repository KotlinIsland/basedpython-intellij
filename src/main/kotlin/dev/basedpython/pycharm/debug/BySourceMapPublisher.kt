package dev.basedpython.pycharm.debug

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.dap.DapClient
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapEventConsumer
import kotlinx.coroutines.future.await

/**
 * The stock DAP client, subclassed rather than instantiated.
 *
 * `DapClient` is `@ApiStatus.OverrideOnly` — every event method on it is `final` and forwards to
 * the [DapEventConsumer], which is the seam a plugin is meant to use. Extending it satisfies the
 * contract while [BySourceMapPublisher] does the actual work on the consumer side.
 */
internal class ByDapClient(eventConsumer: DapEventConsumer) : DapClient(eventConsumer)

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
    }
}
