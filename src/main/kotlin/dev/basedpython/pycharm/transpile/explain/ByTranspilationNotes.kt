package dev.basedpython.pycharm.transpile.explain

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByExplainTranspilationParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByTranspilationNote
import dev.basedpython.pycharm.transpile.ByTranspile
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Asks the running `by` server which basedpython constructs a file uses.
 *
 * The recognition is the server's, off the parse tree the transpiler runs on. Doing it here meant a
 * regex per construct over the source text, which cannot tell an operator from the same characters
 * inside a string or a comment, cannot see that `?` in a type position means something else, and
 * goes stale the moment the language grows a construct — the plugin having no way to know it had.
 */
internal object ByTranspilationNotes {

    /** Every construct [file] uses, or `null` after telling the user why there is nothing to show. */
    fun of(project: Project, file: VirtualFile): List<ByTranspilationNote>? =
        of(project, file, ByTranspile.findServer(project, file))

    /**
     * [of], asking [server]. Through `askBy`, because `sendRequestSync` returns the same `null` for
     * an error answer — a `ContentModified` from an edit landing mid-request among them — as for the
     * server having no file to explain, and the two are told to the user differently.
     */
    fun of(project: Project, file: VirtualFile, server: LspClient?): List<ByTranspilationNote>? {
        val title = BasedPythonBundle.message("notification.transpileFailed.title")
        if (server == null) {
            ByCli.notifyError(project, title, BasedPythonBundle.message("transpile.serverNotRunning"))
            return null
        }

        val params = ByExplainTranspilationParams(server.getDocumentIdentifier(file))
        val answer = server.askBy("by/explainTranspilation") { (it as ByServerExtensions).explainTranspilation(params) }
        return when (answer) {
            is ByAnswer.Answer -> answer.value
            // `by` answered, and has no file of its own for this document to explain.
            ByAnswer.None -> null.also {
                ByCli.notifyError(project, title, BasedPythonBundle.message("transpile.serverHasNoFile"))
            }
            ByAnswer.Failed -> null.also {
                ByCli.notifyError(project, title, BasedPythonBundle.message("transpile.serverDidNotAnswer"))
            }
        }
    }
}
