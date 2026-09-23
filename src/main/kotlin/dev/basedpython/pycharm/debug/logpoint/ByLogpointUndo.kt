package dev.basedpython.pycharm.debug.logpoint

import com.intellij.codeInspection.options.OptionController
import com.intellij.codeInspection.options.OptionControllerProvider
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.modcommand.ModUpdateSystemOptions
import com.intellij.modcommand.ModUpdateSystemOptions.ModifiedOption
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.env.bundled.BundledBinaries
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * Makes creating a log point part of whatever the user just did, so Ctrl+Z takes it back.
 *
 * A breakpoint is not document state, so nothing about it is undoable on its own — there is no
 * breakpoint undo anywhere in the platform (IntelliJ IDEA's lives in
 * `intellij.debugger.logpoints.backend`, which depends on the Java debugger). Left alone, the
 * `print` quick fix produced the worst possible half of that: the deleted line came back on undo and
 * the log point stayed, so the program logged the value twice.
 *
 * Registering against the document is what ties the two together. The platform groups everything in
 * one command into one undo step, so the text edit and this travel as a pair, in both directions.
 *
 * **The undo step holds nothing of this plugin's.** It used to be a `BasicUndoableAction` of our
 * own, and `UndoManager` keeps a step for as long as the document's history does — so disabling the
 * plugin found its class still reachable from the platform and reported that it "didn't unload
 * fully". Nothing public removes one recorded step, and the platform does not clear them on unload.
 * So the step is the platform's instead: [ModUpdateSystemOptions], executed by the platform's own
 * [ModCommandExecutor], records an action of the platform's class holding the project, the file and
 * the option change — a bind id and two values, all JDK types. Undo and redo find their way back
 * here by name, through [ByLogpointOptions] and the `com.intellij.optionController` extension point,
 * which an unload takes away with everything else. What the recorded step can reach is platform and
 * JDK classes and nothing more.
 */
object ByLogpointUndo {

    /**
     * The breakpoints an undo step has been recorded for. Weak, so a log point removed is not kept
     * alive by having once been undoable; EDT only, as [record] is.
     */
    private val recorded: MutableSet<XLineBreakpoint<*>> = Collections.newSetFromMap(WeakHashMap())

    /**
     * Records [breakpoint] as part of the command in progress, so <kbd>Ctrl+Z</kbd> takes it back.
     *
     * Joins the command when there is one — that is the `print` quick fix, where the deleted line
     * and the log point have to travel as a pair, and joining is the whole point.
     *
     * Opens one when there is not, which is every gutter route: *Add Logging Breakpoint…* from the
     * gutter menu, and IntelliJ IDEA's own click in the gutter gap, neither of which runs in a
     * command at all — which is exactly why neither could be undone. A command of our own gives the
     * log point an undo step of its own, which is what the user is reaching for.
     *
     * Once per breakpoint. The `print` quick fix records the log point it adds, and the breakpoint
     * listener hears the same addition and records it too; recorded twice, one redo put back two
     * log points on the line.
     *
     * Not while an undo or redo is running: redo putting a log point back is heard as an addition
     * like any other, and the platform would drop the step anyway — this says so rather than relying
     * on it.
     *
     * EDT only: whether a command is open is a question about the EDT, and joining one from another
     * thread would put this into whatever the EDT happens to be doing.
     */
    fun record(project: Project, document: Document, breakpoint: XLineBreakpoint<*>) {
        ThreadingAssertions.assertEventDispatchThread()
        if (UndoManager.getInstance(project).isUndoOrRedoInProgress) return
        if (!recorded.add(breakpoint)) return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val change = ModUpdateSystemOptions(
            listOf(ModifiedOption(ByLogpointOptions.bindId(breakpoint.line), null, ByLogpointOptions.describe(breakpoint))),
        )
        // Executing it is what records it: the executor sets the option — which finds the log point
        // already there and does nothing — and registers the platform's own undoable action for it.
        val execute = Runnable {
            val context = ActionContext(project, psiFile, 0, TextRange.EMPTY_RANGE, null)
            ModCommandExecutor.getInstance().executeInteractively(context, change, null)
        }
        val commands = CommandProcessor.getInstance()
        if (commands.currentCommand != null) {
            execute.run()
        } else {
            commands.executeCommand(
                project,
                execute,
                BasedPythonBundle.message("debug.logpoint.undo.add"),
                null,
                UndoConfirmationPolicy.DEFAULT,
                document,
            )
        }
        withSteps[document] = WeakReference(project)
    }

    /**
     * The documents whose history may hold a log point step, and the project whose history it is.
     * Weak both ways, so remembering one keeps neither alive; EDT only, as [record] is.
     */
    private val withSteps: MutableMap<Document, WeakReference<Project>> = WeakHashMap()

    /**
     * Ends the history of every document that may hold a log point step, because the plugin is
     * about to go.
     *
     * The step is the platform's and survives the unload, but undoing it asks for
     * `basedpythonLogpoint.<line>` by name, and with [ByLogpointOptions] unregistered nothing answers:
     * the platform's empty controller throws `IllegalArgumentException` out of Ctrl+Z (measured in
     * PyCharm 263.5153). `UndoableGroup` catches only `UnexpectedUndoException`, so in the `print`
     * quick fix's step — the log point and the deleted line are one group — the throw would stop the
     * group part way, and the document's history would go on replaying against text it no longer
     * matches.
     *
     * A non-undoable action — public API, and the platform's own class — makes the step
     * unreachable instead. What that costs is exactly this: for each `.by` document that had a
     * log point added in this session, every undo step recorded **before** the plugin was
     * unloaded; edits made afterwards undo as usual. No other document's history is touched, and
     * nothing is lost while the plugin stays loaded.
     */
    fun endHistoriesBeforeUnload() {
        ThreadingAssertions.assertEventDispatchThread()
        val references = DocumentReferenceManager.getInstance()
        for ((document, owner) in withSteps.entries.toList()) {
            val project = owner.get()?.takeUnless { it.isDisposed } ?: continue
            UndoManager.getInstance(project).nonundoableActionPerformed(references.create(document), false)
        }
        withSteps.clear()
    }
}

/** Calls [ByLogpointUndo.endHistoriesBeforeUnload] when it is this plugin that is unloading. */
class ByLogpointUndoUnloadGuard : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != BundledBinaries.PLUGIN_ID) return
        ByLogpointUndo.endHistoriesBeforeUnload()
    }
}

/**
 * The log points of a `.by` file, as options the platform's undo can set.
 *
 * One option per line: `basedpythonLogpoint.<line>`. Its value is null where there is no log point,
 * and otherwise a description of the one there — [describe] — made of `String`s only, since the
 * values are what the platform's undo step keeps.
 *
 * A breakpoint restored from its description is indistinguishable from the original, since a line
 * breakpoint *is* its file, line and settings; undo removes the object, so redo could not reuse it
 * anyway.
 */
class ByLogpointOptions : OptionControllerProvider {

    override fun name(): String = NAME

    override fun forContext(context: PsiElement): OptionController {
        val project = context.project
        val file = context.containingFile?.virtualFile ?: return OptionController.empty()
        return OptionController.of(
            { bindId -> line(bindId)?.let { logpointAt(project, file, it) }?.let(::describe) },
            { bindId, value -> line(bindId)?.let { set(project, file, it, value) } },
        )
    }

    companion object {
        private const val NAME = "basedpythonLogpoint"

        fun bindId(line: Int): String = "$NAME.$line"

        /**
         * What it takes to put [breakpoint] back: its suspend policy, then its expression if it has
         * one. A `java.util.List` of `String`s, deliberately — this is held by the platform's undo
         * step, where anything of this plugin's would keep the plugin from unloading.
         */
        fun describe(breakpoint: XLineBreakpoint<*>): List<String> {
            val expression = breakpoint.logExpressionObject?.expression
            return if (expression == null) java.util.List.of(breakpoint.suspendPolicy.name)
            else java.util.List.of(breakpoint.suspendPolicy.name, expression)
        }

        private fun line(bindId: String): Int? = bindId.toIntOrNull()

        private fun type() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)

        /**
         * The log point on [line], if there is one. The placement-filtered overload is
         * `@ApiStatus.Internal`; filtering on our own definition instead means undo removes the log
         * point it recorded and never a plain breakpoint somebody put on the same line.
         */
        private fun logpoints(project: Project, file: VirtualFile, line: Int): List<XLineBreakpoint<*>> {
            val type = type() ?: return emptyList()
            return XDebuggerManager.getInstance(project).breakpointManager
                .findBreakpointsAtLine(type, file, line)
                .filter { ByLogpoints.asLogpoint(it) != null }
        }

        private fun logpointAt(project: Project, file: VirtualFile, line: Int) = logpoints(project, file, line).firstOrNull()

        /**
         * Makes [line] hold what [value] describes: nothing (undo of an addition), or a log point
         * (redo). Idempotent, since executing the change the first time sets it to what is already
         * there — and a redo onto a line that has a log point again should not make it two.
         */
        private fun set(project: Project, file: VirtualFile, line: Int, value: Any?) {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            if (value == null) {
                logpoints(project, file, line).forEach { manager.removeBreakpoint(it) }
                return
            }
            if (logpointAt(project, file, line) != null) return
            val description = (value as? List<*>)?.filterIsInstance<String>() ?: return
            val suspendPolicy = description.firstOrNull()
                ?.let { name -> SuspendPolicy.entries.firstOrNull { it.name == name } } ?: return
            val expression = description.getOrNull(1)?.let { ByLogpoints.expressionOf(it) }
            val type = type() ?: return
            manager.addLineBreakpoint(
                type,
                file.url,
                line,
                ByLogpoints.logpointProperties(),
                PlatformLogpointInfo.of(suspendPolicy, expression),
            )
        }
    }
}
