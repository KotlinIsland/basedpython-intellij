package dev.basedpython.pycharm.debug.hotswap

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.hotswap.HotSwapProvider
import com.intellij.xdebugger.hotswap.HotSwapResultListener
import com.intellij.xdebugger.hotswap.HotSwapSession
import com.intellij.xdebugger.hotswap.SourceFileChangesCollector
import com.intellij.xdebugger.hotswap.SourceFileChangesListener
import com.intellij.xdebugger.impl.hotswap.SourceFileChangeFilter
import com.intellij.xdebugger.impl.hotswap.SourceFileChangesCollectorImpl
import dev.basedpython.pycharm.debug.ByDebugProtocolServer
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await

/**
 * Hot reload for a `bpd` session: what changed since the program started, and giving it to the
 * running process.
 *
 * ## the platform already owns the half that is UI
 *
 * `com.intellij.xdebugger.hotswap` is the platform's own generic hot swap, and everything visible
 * belongs to it: the floating toolbar over the editor that appears the moment a tracked file stops
 * matching what is running, the button on it, the spinner, the tick, the success balloon, the
 * `XDebugger.Hotswap.Modified.Files` action and its shortcut. A plugin supplies two things and no
 * more — what to watch, and what to do when the button is pressed — which is what this class is.
 * Nothing here draws anything.
 *
 * PyCharm registers no implementation of the enabling extension point at all, so this is the only
 * one in the IDE and the platform's defaults are what the user sees.
 *
 * ## what pressing the button does
 *
 * One `bpd/replaceCode` per changed file. bpd compiles the file, walks the tree that comes out
 * against the tree the process is running, and applies the difference **only** where every one of
 * them is inside the body of a function that exists in both and takes the same arguments —
 * assigning `function.__code__` on every function object in the process that held the old code,
 * including the ones no namespace still points at. Nothing is ever applied partially: a replacement
 * that cannot be made whole changes nothing at all and comes back naming everything that stood in
 * the way.
 *
 * The refusals are bpd's to explain and it explains them: its DAP adapter writes each one to the
 * `output` stream under category `important`, which is the category this plugin puts where a person
 * cannot miss it. So what is printed from here is the other half — what an *applied* replacement
 * changed about the process, which nothing else says.
 *
 * ## the frame you are stopped in
 *
 * bpd refuses a replacement while any frame of the process — on a thread, or suspended inside a
 * generator, a coroutine or an async generator — is running code the replacement would change. That
 * covers the case people most want this in: stopped at a breakpoint *inside* the function just
 * edited. The refusal names the frame and says to let it return first, and it is the default
 * because between the assignment and that frame returning the process really is running two
 * versions of one function. bpd will do it anyway for a caller that asks by name; nothing here asks
 * — see [ByReplaceCodeArguments.evenUnderALiveFrame].
 */
internal class ByHotSwapProvider(
    private val process: XDebugProcess,
    private val project: Project,
    private val commandProcessor: DapCommandProcessor,
) : HotSwapProvider<VirtualFile> {

    /**
     * Watch the project's basedpython and python files, and nothing else.
     *
     * The platform's own collector, which is what makes reverting an edit put the button away
     * again: it remembers the content each file had at the last reset and compares against local
     * history, so a file typed into and typed back out of is not a change. Reimplementing that with
     * a document listener would get the common half right and that half wrong.
     *
     * Two filters. **A file of this language**, because a debugger reloading a `.json` is a button
     * offered for nothing. And **a file of this project**: a `.py` opened from a library root or
     * from `site-packages` is not code the user is working on, and the transpiled tree `by run`
     * runs from is not in the project at all, so neither should raise the toolbar.
     *
     * `.by` is watched even though it cannot be reloaded — see [ByHotSwap.refuse]. Knowing the
     * source on screen is not the code that is running is most of what this feature is for, and
     * that is exactly as true of the file the whole project is written in.
     */
    override fun createChangesCollector(
        session: HotSwapSession<VirtualFile>,
        coroutineScope: CoroutineScope,
        listener: SourceFileChangesListener,
    ): SourceFileChangesCollector<VirtualFile> = SourceFileChangesCollectorImpl(
        coroutineScope,
        listener,
        SourceFileChangeFilter { file ->
            file.extension?.lowercase() in BasedPythonSources.MODULE_EXTENSIONS
        },
        SourceFileChangeFilter { file ->
            readAction { ProjectFileIndex.getInstance(project).isInContent(file) }
        },
    )

    override fun performHotSwap(session: HotSwapSession<VirtualFile>) {
        // Before anything else: the platform's status goes to "in progress" here, and every path
        // out of this method has to end on one of the four calls this listener offers or the
        // toolbar spins forever.
        val listener = session.startHotSwapListening()
        val plan = ByHotSwap.plan(session.getChanges().map { it.path })
        val console = process.session.consoleView

        for (line in plan.refusals()) console.say(line, prominent = true)

        if (plan.replaceable.isEmpty()) {
            // Nothing was even asked, so nothing was reloaded. `onFailure` rather than `onFinish`:
            // the two differ in whether the edits are still outstanding, and they are — `onFinish`
            // would have the platform treat this file as reloaded and stop offering it, so the next
            // edit to it would raise the button for the first change and quietly drop this one.
            listener.onFailure()
            return
        }

        commandProcessor.submitCommand {
            val server = server as? ByDebugProtocolServer
            if (server == null) {
                // Only reachable with a backend that is not bpd, which the enabler does not offer
                // this for — but the server is read per command and nothing here may assume it.
                LOG.warn("the debug adapter is not a ${ByDebugProtocolServer::class.simpleName}; nothing was reloaded")
                listener.onFailure()
                return@submitCommand
            }
            var applied = 0
            for (path in plan.replaceable) {
                val replaced = try {
                    ByReplaced.parse(server.replaceCode(ByReplaceCodeArguments(file = path)).await())
                } catch (e: CancellationException) {
                    // The session is going away and took its commands with it. Rethrown rather than
                    // reported: there is nobody left to tell, and swallowing it would send the next
                    // request down a scope that is already cancelled.
                    throw e
                } catch (e: Exception) {
                    // A refusal is not this: bpd answers a replacement it would not make with
                    // `success` and the reasons in the body. Landing here means the request itself
                    // was not accepted — an adapter that does not have it, or a session going away.
                    LOG.info("bpd/replaceCode failed for $path", e)
                    console.say(
                        "could not reload ${path.substringAfterLast('/')}: ${e.message ?: "the debug adapter refused the request"}",
                        prominent = true,
                    )
                    null
                }
                if (replaced?.applied == true) applied++
                replaced?.report()?.let { console.say(it, prominent = false) }
            }
            // Every file, or the edits stay outstanding. Only a whole success may reset them:
            // "reloaded" is a claim about the process matching the source, and a set where one file
            // was refused is a set where it does not.
            if (applied == plan.replaceable.size && plan.refused.isEmpty()) {
                listener.onSuccessfulReload()
            } else {
                listener.onFailure()
            }
        }
    }

    /**
     * The console rather than a balloon, for the reason a jump's account goes there: it belongs in
     * sequence with the rest of the session's account of itself, and bpd's own half of this
     * conversation is already on that stream.
     */
    private fun ConsoleView?.say(text: String, prominent: Boolean) {
        this ?: return
        print(
            "$text\n",
            if (prominent) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.SYSTEM_OUTPUT,
        )
    }

    private companion object {
        private val LOG = Logger.getInstance(ByHotSwapProvider::class.java)
    }
}
