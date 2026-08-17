package dev.basedpython.pycharm.transpile.explain

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.actions.ByCli
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
    fun of(project: Project, file: VirtualFile): List<ByTranspilationNote>? {
        val title = BasedPythonBundle.message("notification.transpileFailed.title")

        val server = ByTranspile.findServer(project, file) ?: run {
            ByCli.notifyError(project, title, BasedPythonBundle.message("transpile.serverNotRunning"))
            return null
        }

        val params = ByExplainTranspilationParams(server.getDocumentIdentifier(file))
        return server.sendRequestSync { (it as ByServerExtensions).explainTranspilation(params) }
            ?: run {
                ByCli.notifyError(
                    project,
                    title,
                    BasedPythonBundle.message("transpile.serverDidNotAnswer"),
                )
                null
            }
    }
}
