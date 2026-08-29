package dev.basedpython.pycharm.debug.hotswap

import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.hotswap.HotSwapInDebugSessionEnabler
import com.intellij.xdebugger.hotswap.HotSwapProvider
import dev.basedpython.pycharm.debug.ByDapXDebugProcess
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend

/**
 * Turns hot reload on for the sessions that have it, which is the bpd ones.
 *
 * The platform asks every implementation of this at `processStarted` and takes the first that
 * answers with a provider; answering null is what leaves a session without the toolbar, the button
 * and the file tracking behind them. So this is the whole of the switch, and it is a question about
 * the *backend* rather than about the plugin: `bpd/replaceCode` is bpd's own request and debugpy
 * has nothing like it, so a debugpy session would raise a button whose only possible answer is that
 * the adapter does not know the request.
 *
 * Asked of [dev.basedpython.pycharm.debug.bpd.ByDebugBackend] rather than of an advertised
 * capability, which is how [dev.basedpython.pycharm.debug.ByRestartFrame] decides the same shape of
 * question: `restartFrame` is a DAP capability an adapter announces in `initialize`, and there is
 * no capability flag for a custom request. Nothing is on the wire to believe, so the thing that
 * chose the backend is what says which one it is.
 */
internal class ByHotSwapEnabler : HotSwapInDebugSessionEnabler {

    override fun createProvider(process: XDebugProcess): HotSwapProvider<*>? {
        val dap = process as? ByDapXDebugProcess ?: return null
        if (dap.backend != ByDebugBackend.BPD) return null
        return ByHotSwapProvider(
            process = dap,
            project = dap.session.project,
            commandProcessor = dap.dapDebugSession.commandProcessor,
            buildDirectory = dap.buildDirectory,
        )
    }
}
