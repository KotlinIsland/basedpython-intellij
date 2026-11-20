package dev.basedpython.pycharm.debug.hotswap

import com.intellij.notification.Notification
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.xdebugger.hotswap.HotSwapProvider
import com.intellij.xdebugger.hotswap.HotSwapResultListener
import com.intellij.xdebugger.hotswap.HotSwapSession
import com.intellij.xdebugger.hotswap.SourceFileChangesCollector
import com.intellij.xdebugger.hotswap.SourceFileChangesListener
import dev.basedpython.pycharm.debug.ByBpdProse
import dev.basedpython.pycharm.debug.ByDapRequests
import dev.basedpython.pycharm.debug.send
import dev.basedpython.pycharm.debug.bpd.ByBpdRun
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 * One `bpd/replaceCode` over every changed file. bpd compiles each file, walks the tree that comes out
 * against the tree the process is running, and applies the difference **only** where every one of
 * them is inside the body of a function that exists in both and takes the same arguments —
 * assigning `function.__code__` on every function object in the process that held the old code,
 * including the ones no namespace still points at. Nothing is ever applied partially: a replacement
 * that cannot be made whole changes nothing at all and comes back naming everything that stood in
 * the way.
 *
 * Nothing goes on the console either way. A reload that applied is the platform's own tick on the
 * button. A refused one is a balloon carrying bpd's reasons — one sentence each, in the answer as
 * `said`, so the wording is bpd's and not re-rendered here from the variants.
 *
 * ## the frame you are stopped in
 *
 * The case people most want this in is stopped at a breakpoint *inside* the function just edited.
 * The reload applies there, as HotSwap does on the JVM: the call that is stopped finishes on the code
 * it started with, and the next call runs the edit. bpd refuses that by default, since until the
 * frame returns the process runs two versions of one function, so this asks for it
 * ([ByReplaceCodeArguments.evenUnderALiveFrame]). Which calls are still on the old code comes back in
 * the answer and is not announced: the next call running the edit is what anyone pressing the button
 * expects. Refusing would leave the button doing nothing in the one place it is pressed most.
 */
internal class ByHotSwapProvider(
    private val project: Project,
    private val executor: DapSessionExecutor,
    /**
     * The program: the file the wrapper writes the runner `by run` chose into, and whether it runs in
     * place.
     *
     * The directory is read when the button is pressed rather than held as a value, because it is not
     * knowable before then: `by run` chooses it after the IDE has built this, so a value captured at
     * construction is a read of a file that does not exist yet. That was the first thing this feature
     * got wrong in a real session — every reload answered that the build directory was not known.
     */
    private val run: ByBpdRun?,
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
     * Built as [ByChangesCollector], this plugin's own, since the platform's one implementation is
     * internal and its constructor differs between builds.
     *
     * `.by` is watched as `.py` is, since both reach the running program the same way — see
     * [performHotSwap].
     */
    override fun createChangesCollector(
        session: HotSwapSession<VirtualFile>,
        coroutineScope: CoroutineScope,
        listener: SourceFileChangesListener,
    ): SourceFileChangesCollector<VirtualFile> = ByChangesCollector(listener) { file ->
        // The platform's filters were `SourceFileChangeFilter`, an `impl` interface, and they were
        // `suspend` — which is why project membership was asked inside a `readAction`. Ours is an
        // ordinary predicate called from a document listener, so the index is asked directly; both
        // reads are cheap and neither needs a read action on the EDT.
        file.extension?.lowercase() in BasedPythonSources.MODULE_EXTENSIONS &&
            ProjectFileIndex.getInstance(project).isInContent(file)
    }

    /**
     * Give the running program everything the user has edited since it started.
     *
     * ## a program run in place
     *
     * `by run --in-place` runs the program against the project's own directories, so a hand-written
     * `.py` the program imports *is* the file on screen, and a `.by` is imported from its transpile in
     * `by`'s run cache. Nothing is restaged and nothing written: see [reloadInPlace]. What follows is
     * a staged run's.
     *
     * ## nothing you edit is the file that is running
     *
     * Under a staged `by run` the program runs out of a tree in a temp directory, and every module in it
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
     * Save; ask `by`, in one request for the whole edit, what every file's slot in the running tree
     * should now hold; write that into the tree — the modules, then the one `_by_sourcemap.py` that
     * describes all of them — keeping what was replaced; then one `bpd/replaceCode` over the lot,
     * with `remap` set when the map was rewritten, because every `.by` breakpoint is armed on a
     * generated line that came out of the table it replaced. See [ByReload].
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
        // A "not reloaded" balloon from the previous attempt describes a state this one is about
        // to replace, so it goes now rather than sitting beside the new result.
        expireLastReport()
        val listener = session.startHotSwapListening()
        val changes = session.getChanges()
        // What the platform tracked are *documents*; what is transpiled and what bpd compiles are
        // files. Saving is what makes those the same thing — see [saveEdits].
        saveEdits(changes)

        if (changes.isEmpty()) {
            // Nothing to ask about, and `by` refuses an empty set — the process matches the source.
            listener.onSuccessfulReload()
            return
        }

        val directory = run?.runnerDirectory()
        if (run == null || directory == null) {
            // The enabler offers this for bpd sessions only, and a bpd session is one `by run`
            // started — so this is a record that could not be read rather than a session of a shape
            // that was never meant to work. The path is named because without it there is nothing
            // to look at.
            tell(
                listOf(
                    "nothing was reloaded: the directory `by run` is running this program out of " +
                        "is not known — nothing readable in ${run?.record ?: "the session's record"}",
                ),
            )
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

        if (run.inPlace) {
            reloadInPlace(changes.sortedBy { it.path }, directory, listener)
            return
        }

        // What the edit's slots in the running tree should now hold, asked of `by` because producing
        // those bytes is `by`'s: it owns the transpiler, the line table and the digests. One request
        // for the whole set, because the tree has one `_by_sourcemap.py` and the answer has to be
        // one map carrying every file's entry — see [ByRestage].
        val plan = when (val asked = ByRestage.ask(project, changes.sortedBy { it.path }, directory)) {
            ByRestage.Asked.NoAnswer ->
                ByReload.Plan.Refused(listOf("nothing was reloaded: the `by` language server did not answer"))
            ByRestage.Asked.SeveralServers -> ByReload.Plan.Refused(
                listOf(
                    "nothing was reloaded: the edited files are served by different `by` language " +
                        "servers, and a running program is one build",
                ),
            )
            is ByRestage.Asked.Answered -> ByReload.plan(asked.answer)
        }

        val write = when (plan) {
            // Nothing is written when anything refused. The set goes in together or not at all, for
            // the reason bpd applies one that way: a tree holding half of an edit describes a program
            // that never existed. `by` answers a set that way too, and names every file that stood
            // in the way.
            is ByReload.Plan.Refused -> {
                tell(plan.reasons)
                listener.onFailure()
                return
            }
            ByReload.Plan.UpToDate -> {
                // `onSuccessfulReload`, and it is the one place that is honest without anything
                // having been replaced: the claim being made is that the process matches the source,
                // and here it does.
                listener.onSuccessfulReload()
                return
            }
            is ByReload.Plan.Write -> plan
        }

        val written = ByBuildTree()
        try {
            ByReload.write(write, Path.of(directory), written)
        } catch (e: IOException) {
            LOG.warn("could not write the re-staged build", e)
            written.rollback()
            tell(listOf("nothing was reloaded: the build directory could not be written — ${e.message}"))
            listener.onFailure()
            return
        }

        executor.post {
            // What the adapter said when it answered with an error rather than an account
            var failure: String? = null
            val replaced = try {
                ByReplaced.parse(
                    send(
                        ByDapRequests.replaceCode,
                        ByReplaceCodeArguments(
                            files = write.replace,
                            // Whether the line tables of the `_by_sourcemap.py` just written moved,
                            // which is `by`'s answer rather than a default: when they did, every
                            // `.by` breakpoint is armed on a generated line that came out of the
                            // table it replaced, and bpd installs the new one and translates them
                            // again, in the same message, before it assigns any `__code__`. A map
                            // rewritten only to list a re-staged copy is not one bpd reads again.
                            remap = write.remap,
                            // See "the frame you are stopped in" above
                            evenUnderALiveFrame = true,
                        ).toJson(),
                    ),
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
                // and the reasons in the body. Landing here means the request itself failed — an
                // adapter that does not have it, a session going away, or bpd failing while it
                // answered, which it says in a sentence of its own that is the only account there is.
                LOG.info("bpd/replaceCode failed", e)
                failure = ByDapRequests.refusalOf(e)
                null
            }

            if (replaced?.applied == true) {
                listener.onSuccessfulReload()
                return@post
            }

            // Everything written goes back, and the balloon says why in bpd's own sentences — one
            // per refusal, carried in the answer as `said`, so the reason is where the user pressed
            // the button rather than in the program's console.
            val stranded = written.rollback()
            val notReloaded = notReloaded(replaced, failure)
            if (stranded.isNotEmpty()) {
                notReloaded += "and ${stranded.size} file(s) of the build could not be put back: " +
                    stranded.joinToString(", ") { it.fileName.toString() }
            }
            tell(notReloaded)
            listener.onFailure()
        }
    }

    /**
     * Give a program `by run --in-place` runs everything the user has edited, which writes nothing:
     * there is no tree to restage into, and the run directory and its map are `by`'s.
     *
     * A `.by` is transpiled again into the run directory by `by` ([ByRetranspile]), which answers with
     * the transpile's place and the module's new entry of the map's tables; a file the program imports
     * from where it is, a hand-written `.py`, needs nothing of `by`, since the code bpd compiles is
     * the file on disk. Then one `bpd/replaceCode` names every edited file and carries `by`'s answers
     * as `retranspiled`, which bpd checks against disk as it checks the map before it compiles through
     * them. `by` refusing any module — a module that no longer checks, a file the run was not
     * transpiled with — hands bpd nothing, and the reasons are said the way a staged reload's are.
     */
    private fun reloadInPlace(changes: List<VirtualFile>, runDirectory: String, listener: HotSwapResultListener) {
        val (modules, asIs) = changes.partition { it.extension?.lowercase() == BasedPythonSources.BY }
        val answers = if (modules.isEmpty()) emptyList() else when (val asked = ByRetranspile.ask(project, modules, runDirectory)) {
            ByRetranspile.Asked.NoAnswer -> {
                tell(listOf("nothing was reloaded: the `by` language server did not answer"))
                listener.onFailure()
                return
            }
            ByRetranspile.Asked.SeveralServers -> {
                tell(
                    listOf(
                        "nothing was reloaded: the edited files are served by different `by` language " +
                            "servers, and a running program is one project's",
                    ),
                )
                listener.onFailure()
                return
            }
            is ByRetranspile.Asked.Answered -> asked.answers
        }
        val replace = when (val plan = ByReload.planInPlace(modules.map(::pathOf), asIs.map(::pathOf), answers)) {
            is ByReload.InPlace.Refused -> {
                tell(plan.reasons)
                listener.onFailure()
                return
            }
            is ByReload.InPlace.Replace -> plan
        }
        executor.post {
            var failure: String? = null
            val replaced = try {
                ByReplaced.parse(
                    send(
                        ByDapRequests.replaceCode,
                        ByReplaceCodeArguments(
                            files = replace.files,
                            // the run's map is never rewritten: what moved rides in `retranspiled`
                            remap = false,
                            // See "the frame you are stopped in" above
                            evenUnderALiveFrame = true,
                            retranspiled = replace.retranspiled,
                        ).toJson(),
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.info("bpd/replaceCode failed", e)
                failure = ByDapRequests.refusalOf(e)
                null
            }
            if (replaced?.applied == true) {
                listener.onSuccessfulReload()
                return@post
            }
            // nothing to put back: `by` wrote the transpiles into the run directory, where nothing
            // the program runs reads one until bpd is handed it
            tell(notReloaded(replaced, failure))
            listener.onFailure()
        }
    }

    /** What bpd said about a replacement it did not apply, or why it could not be asked, one sentence each. */
    private fun notReloaded(replaced: ByReplaced?, failure: String?): MutableList<String> {
        val said = replaced?.files?.flatMap { it.said }.orEmpty()
        return when {
            said.isNotEmpty() -> said.toMutableList()
            replaced != null -> mutableListOf("the debugger refused it")
            failure != null -> mutableListOf("the debugger could not do it: $failure")
            else -> mutableListOf("the debug adapter did not answer the request")
        }
    }

    /** A file as bpd is handed it: its path on disk, which is what the program's frames name. */
    private fun pathOf(file: VirtualFile): String = file.fileSystem.getNioPath(file)?.toString() ?: file.path

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
     * the program's.
     *
     * A stale "not reloaded" must not sit on screen beside a session where it is no longer true,
     * which used to be the platform's `HotSwapStatusNotificationManager.trackNotification` — an
     * `@ApiStatus.Internal` call that expires a tracked balloon when the next hot swap starts. The
     * last one is held and expired here instead, at the top of [performHotSwap] and before a new
     * one is raised. Narrower than the platform's, which also clears balloons raised elsewhere,
     * and this plugin only ever raises this one. See docs/internal-api.md.
     *
     * The reasons are bpd's sentences, as it answered them, and bpd puts nothing about a reload on
     * the console — so they are said once, here. The balloon reads its content as HTML, so each is
     * rendered by [ByBpdProse] and they are separated by `<br>`, which a `\n` was not.
     */
    private fun tell(notReloaded: List<String>) {
        if (notReloaded.isEmpty()) return
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                BasedPythonBundle.message("notification.hotswap.notReloaded.title"),
                notReloaded.joinToString("<br>") { ByBpdProse.html(it) },
                NotificationType.WARNING,
            )
        expireLastReport()
        lastReport = notification
        notification.notify(project)
    }

    /** Takes down the previous "not reloaded" balloon, whose session has moved on. */
    private fun expireLastReport() {
        lastReport?.expire()
        lastReport = null
    }

    /** The last balloon [tell] raised, so the next hot swap can take it down. */
    @Volatile
    private var lastReport: Notification? = null

    private companion object {
        private val LOG = Logger.getInstance(ByHotSwapProvider::class.java)

        /** The group registered in `plugin.xml`, which is the one every balloon of this plugin uses. */
        private const val NOTIFICATION_GROUP = "basedpython"
    }
}
