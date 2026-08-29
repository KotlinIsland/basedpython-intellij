package dev.basedpython.pycharm.debug.hotswap

import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
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
import com.intellij.xdebugger.impl.hotswap.HotSwapStatusNotificationManager
import com.intellij.xdebugger.impl.hotswap.SourceFileChangeFilter
import com.intellij.xdebugger.impl.hotswap.SourceFileChangesCollectorImpl
import dev.basedpython.pycharm.debug.ByDebugProtocolServer
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.lsp.ext.ByRestaged
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import java.io.IOException
import java.nio.file.Path

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
    /**
     * The tree `by run` transpiled into and is running the program out of.
     *
     * Where a re-staged file has to be written for the interpreter to be given it, and the one
     * thing about this session the IDE could not have worked out for itself — `by run` chooses a
     * temp directory, and only the process that started the program ever sees the name.
     */
    private val buildDirectory: String?,
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

    /**
     * Give the running program everything the user has edited since it started.
     *
     * ## nothing you edit is the file that is running
     *
     * Under `by run` the program runs out of a tree in a temp directory, and every module in it
     * arrived there from the project: a `.by` because it was **transpiled**, a hand-written `.py`
     * because it was **copied**. `sys.path[0]` is that tree, so what the interpreter compiled is
     * never the file on screen. Measured rather than argued — a project with a `helper.py` beside
     * its `main.by` reports both `__file__`s inside `/var/folders/.../T/.tmpXXXX/`.
     *
     * So both kinds of file take the same route, which is the honest shape and was not always the
     * one here. A `.by` used to be refused, with a long and correct explanation of why the debugger
     * could not transpile it. What was wrong was the other half of that argument — that a plain
     * `.py` needed none of it, because `by run` "copies nothing else". It copies everything else.
     * The `.py` path had quietly stopped working, and the refusal was the only part anybody could
     * see.
     *
     * ## the route
     *
     * Save; ask `by` what each file's slot in the running tree should now hold; write that into the
     * tree, keeping what was replaced; then one `bpd/replaceCode` over the lot with `remap` set,
     * because re-staging rewrote `_by_sourcemap.py` beside the generated python and every `.by`
     * breakpoint is armed on a generated line that came out of the table it replaced.
     *
     * Producing the bytes is `by`'s: it owns the transpiler, the line table and the digests, and a
     * debugger inventing any of those would be writing a map describing a file it guessed at.
     * Writing them is this plugin's, because it is the only party that can take the write back when
     * the replacement is refused.
     *
     * ## no file is judged by its name here
     *
     * The set is only **sorted** — a change set is a hash set and the order it iterates in is not a
     * fact about anything, so a console account of one session would otherwise read differently from
     * the next. Every reason a file might not reload comes from whoever knows it: `by` refuses a
     * tree it did not build or a source that does not check, and bpd refuses a replacement it cannot
     * make whole. None of it is guessed at from an extension.
     */
    override fun performHotSwap(session: HotSwapSession<VirtualFile>) {
        // Before anything else: the platform's status goes to "in progress" here, and every path
        // out of this method has to end on one of the four calls this listener offers or the
        // toolbar spins forever.
        val listener = session.startHotSwapListening()
        val changes = session.getChanges()
        // What the platform tracked are *documents*; what is transpiled and what bpd compiles are
        // files. Saving is what makes those the same thing — see [saveEdits].
        saveEdits(changes)

        // Gathered rather than said as it is found: one balloon naming everything that did not
        // reload is a notification, and four of them are spam.
        val notReloaded = mutableListOf<String>()

        val directory = buildDirectory
        if (directory == null) {
            // The enabler offers this for bpd sessions only, and a bpd session is one `by run`
            // started — so this is a session whose record could not be read rather than one of a
            // shape that was never meant to work.
            tell(listOf("nothing was reloaded: the build directory this program runs out of is not known"))
            listener.onFailure()
            return
        }

        // The work below is synchronous and can take seconds — an LSP request whose server may be
        // cold, then a write. The platform calls this from a coroutine, not the EDT, which is what
        // makes that acceptable; if that ever changes this is a frozen IDE rather than a slow
        // reload, so it is asserted rather than assumed. `Logger.error` fails a test and an EAP
        // build and is a logged error in a release, which is the right severity for a thing that is
        // true today and would be serious if it stopped being.
        if (ApplicationManager.getApplication().isDispatchThread) {
            LOG.error("hot reload is running on the EDT; the LSP request below would freeze the IDE")
        }

        // What each edited file's slot in the running tree should now hold, asked of `by` because
        // producing those bytes is `by`'s: it owns the transpiler, the line table and the digests.
        val staged = mutableListOf<ByRestagedFile>()
        for (file in changes.sortedBy { it.path }) {
            when (val answer = ByRestage.ask(project, file, directory)) {
                null -> notReloaded += "${file.name}: the `by` language server did not answer"
                else -> when {
                    answer.refused != null -> notReloaded += refusalOf(file.name, answer)
                    // The file already being what the tree holds is a different fact from nothing
                    // being replaceable, and it is not a failure: an edit typed and typed back out
                    // again lands here.
                    !answer.changed -> Unit
                    answer.generated == null || answer.content == null ->
                        notReloaded += "${file.name}: the `by` language server answered without the bytes to write"
                    else -> staged += ByRestagedFile(file, answer)
                }
            }
        }

        // Nothing is written when anything refused. The set goes in together or not at all, for the
        // reason bpd applies one that way: a tree holding half of an edit describes a program that
        // never existed.
        if (notReloaded.isNotEmpty()) {
            tell(notReloaded)
            listener.onFailure()
            return
        }
        if (staged.isEmpty()) {
            process.session.consoleView.say("every edited file already was the code the process is running")
            // `onSuccessfulReload`, and it is the one place that is honest without anything having
            // been replaced: the claim being made is that the process matches the source, and here
            // it does.
            listener.onSuccessfulReload()
            return
        }

        val written = ByBuildTree()
        try {
            for (one in staged) {
                written.write(Path.of(one.restaged.generated!!), one.restaged.content!!)
                // Rewritten whole beside the python it describes, and only for a file the build
                // transpiled — a hand-written `.py` was copied into the tree, so nothing in the map
                // is about it and `by` sends null.
                one.restaged.sourcemap?.let {
                    written.write(Path.of(directory, BY_SOURCEMAP), it)
                }
            }
        } catch (e: IOException) {
            LOG.warn("could not write the re-staged build", e)
            written.rollback()
            tell(listOf("nothing was reloaded: the build directory could not be written — ${e.message}"))
            listener.onFailure()
            return
        }

        commandProcessor.submitCommand {
            val server = server as? ByDebugProtocolServer
            if (server == null) {
                // Only reachable with a backend that is not bpd, which the enabler does not offer
                // this for — but the server is read per command and nothing here may assume it.
                LOG.warn("the debug adapter is not a ${ByDebugProtocolServer::class.simpleName}; nothing was reloaded")
                written.rollback()
                tell(listOf("nothing was reloaded: this session's debug adapter is not bpd"))
                listener.onFailure()
                return@submitCommand
            }

            val replaced = try {
                ByReplaced.parse(
                    server.replaceCode(
                        ByReplaceCodeArguments(
                            files = staged.map { it.restaged.generated!! },
                            // Re-staging rewrote `_by_sourcemap.py`, so every `.by` breakpoint is
                            // armed on a generated line that came out of the table it replaced.
                            // bpd installs the new one and translates them again, in the same
                            // message, before it assigns any `__code__`.
                            remap = true,
                        ),
                    ).await(),
                )
            } catch (e: CancellationException) {
                // The session is going away and took its commands with it. Rethrown rather than
                // reported: there is nobody left to tell, and swallowing it would send the next
                // request down a scope that is already cancelled. The tree goes back first — the
                // program may outlive this and would otherwise run out of a tree nothing wrote.
                written.rollback()
                throw e
            } catch (e: Exception) {
                // A refusal is not this: bpd answers a replacement it would not make with `success`
                // and the reasons in the body. Landing here means the request itself was not
                // accepted — an adapter that does not have it, or a session going away.
                LOG.info("bpd/replaceCode failed", e)
                null
            }

            if (replaced?.applied == true) {
                replaced.report()?.let { process.session.consoleView.say(it) }
                listener.onSuccessfulReload()
                return@submitCommand
            }

            // Everything written goes back. bpd's own reasons are already on the `output` stream
            // under `important`, so what the balloon adds is the one thing bpd cannot say: that the
            // tree is once again what the process is running.
            val stranded = written.rollback()
            val why = when {
                replaced == null -> "the debug adapter did not answer the request"
                else -> "the debugger refused it — see the console for what stood in the way"
            }
            notReloaded += "nothing was reloaded: $why"
            if (stranded.isNotEmpty()) {
                notReloaded += "and ${stranded.size} file(s) of the build could not be put back: " +
                    stranded.joinToString(", ") { it.fileName.toString() }
            }
            tell(notReloaded)
            listener.onFailure()
        }
    }

    /** One edited file and what `by` says its slot in the tree should now hold. */
    private data class ByRestagedFile(val file: VirtualFile, val restaged: ByRestaged)

    /** A refusal from `by`, with the checker's own sentences under it when that is the reason. */
    private fun refusalOf(name: String, answer: ByRestaged): String {
        val head = "$name: ${answer.refused}"
        if (answer.diagnostics.isEmpty()) return head
        return head + answer.diagnostics.joinToString("\n  ", prefix = "\n  ")
    }

    /**
     * Flush the edits that raised the button, before anything reads a file.
     *
     * **Without this the feature lies.** The platform's collector tracks *documents* — it listens
     * to the editor, which is what makes the toolbar appear the instant you type — while a
     * replacement is bpd compiling the file **on disk**. Nothing in the platform saves in between:
     * `FrontendHotSwapManager.performHotSwap` goes straight to the provider. So an unsaved edit
     * asked bpd to replace the file with the content it already had, bpd answered `applied` with
     * nothing changed, and the session was reported as matching a screen it did not match — the
     * one outcome this whole feature exists to prevent.
     *
     * Targeted rather than [FileDocumentManager.saveAllDocuments], for the reason
     * [dev.basedpython.pycharm.env.manager.EnvFiles.saveBeforeOperation] is: these files are saved
     * because the program is about to be given them, and that reason does not extend to whatever
     * else the user has open. A cached document only exists for a file something opened, which is
     * every file that can be in this set.
     *
     * Safe from any thread — the save itself needs the EDT.
     */
    private fun saveEdits(changes: Collection<VirtualFile>) {
        val documents = FileDocumentManager.getInstance()
        val unsaved = changes
            .mapNotNull { documents.getCachedDocument(it) }
            .filter { documents.isDocumentUnsaved(it) }
        if (unsaved.isEmpty()) return

        ApplicationManager.getApplication().invokeAndWait {
            if (project.isDisposed) return@invokeAndWait
            unsaved.forEach { documents.saveDocument(it) }
        }
    }

    /**
     * Say what did not reload, where a person will see it.
     *
     * A balloon rather than the console, and specifically rather than the console's **error**
     * stream, which is where this used to go: a file the debugger would not reload is a thing that
     * happened to the user's *request*, not a line of the program's output, and the run console is
     * the program's. The platform has the channel — [HotSwapStatusNotificationManager] is what its
     * own success balloon goes through, and a tracked notification is expired the moment the next
     * hot swap starts, so a stale "not reloaded" cannot sit on screen next to a session where it is
     * no longer true.
     *
     * Created, tracked, then shown, which is the order the platform's own does it in.
     *
     * The *reasons* bpd gave stay on the console and are not repeated here — bpd writes each one to
     * the `output` stream under category `important`, and a balloon re-rendering that vocabulary is
     * the duplication [dev.basedpython.pycharm.debug.ByUnderstandsArguments] exists to stop.
     */
    private fun tell(notReloaded: List<String>) {
        if (notReloaded.isEmpty()) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                BasedPythonBundle.message("notification.hotswap.notReloaded.title"),
                notReloaded.joinToString("\n"),
                NotificationType.WARNING,
            )
        HotSwapStatusNotificationManager.getInstance(project).trackNotification(notification)
        notification.notify(project)
    }

    /**
     * The console, for what an applied replacement changed about the process.
     *
     * That belongs in sequence with the rest of the session's account of itself, for the reason a
     * jump's account does, and bpd's own half of this conversation is already on that stream.
     */
    private fun ConsoleView?.say(text: String) {
        this ?: return
        print("$text\n", ConsoleViewContentType.SYSTEM_OUTPUT)
    }

    private companion object {
        private val LOG = Logger.getInstance(ByHotSwapProvider::class.java)

        /** The group registered in `plugin.xml`, which is the one every balloon of this plugin uses. */
        private const val NOTIFICATION_GROUP = "basedpython"

        /**
         * The map `by` writes beside the python it generated, named by `by_stage::sourcemap`.
         *
         * Spelled here because the plugin writes it: `by` answers with the whole new text of it and
         * says nothing about where it goes, since it goes where it always was.
         */
        private const val BY_SOURCEMAP = "_by_sourcemap.py"
    }
}
