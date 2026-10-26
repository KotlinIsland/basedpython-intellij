package dev.basedpython.pycharm.navigation

import com.intellij.ide.actions.CopyReferenceAction
import com.intellij.injected.editor.EditorWindow
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Copy Reference (Ctrl+Alt+Shift+C) in a `.by` editor: the dotted name of what the name at the caret
 * declares or refers to, `shapes.base.Circle.area`, from `by/declaration` — as PyCharm copies a
 * `.py`'s — instead of `shapes/base.by:34`.
 *
 * ## Why the action itself
 *
 * The platform's action names what the caret is on only when it finds a PSI element there: a
 * reference at the caret, or `PSI_ELEMENT` from the data context. A `.by` file's PSI is one leaf per
 * token, with neither, so it copied `path:line` without asking any [ByQualifiedNameProvider]. Giving
 * the data context a `PSI_ELEMENT`, or the file references, would hand every other consumer of them
 * — rename, safe delete, move, run configurations — an element they would try to act on. So this
 * replaces the one action, as JSON's Copy JSON Pointer extends it, and leaves every other file to it.
 *
 * Only the elements differ: for a `.by` editor they are the declaration `by` names at the caret
 * ([ByNamedDeclaration]), which [ByQualifiedNameProvider] names. A name local to a function has no
 * dotted name, and is copied as `path:line` as Python copies a local; so is a caret on no name, an
 * old `by` that answers `MethodNotFound`, and a server that is not running.
 *
 * ## Which thread asks
 *
 * The action asks for elements on the EDT when it runs, and on a background thread when the Copy
 * Path/Reference popup updates it to show the name beside it. On a background thread this asks and
 * waits; on the EDT it asks under a modal progress, which shows only if `by` is slow.
 *
 * The Toolbox URL entry of the same popup is left as it is, `path:line:column`: it is the
 * platform's internal provider, and a `fqn=` link would be opened by asking the providers to find
 * the name again, which `by` has no request for.
 */
class ByCopyReferenceAction : CopyReferenceAction() {

    override fun getPsiElements(dataContext: DataContext, editor: Editor?): List<PsiElement> {
        val declaration = editor?.takeIf { it !is EditorWindow }?.let { declarationAt(it) }
        return if (declaration != null) listOf(declaration) else super.getPsiElements(dataContext, editor)
    }

    /** The declaration at [editor]'s caret, or `null` outside a `.by` file `by` serves. */
    private fun declarationAt(editor: Editor): ByNamedDeclaration? {
        val project = editor.project ?: return null
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return null
        val psi = runReadActionBlocking { PsiDocumentManager.getInstance(project).getPsiFile(document) } as? BasedPythonFile
            ?: return null
        val client = byServerFor(project, file) ?: return null
        return declarationAt(editor, psi, file, client)
    }

    /** What [client] says the name at [editor]'s caret declares, on whichever thread this is. */
    internal fun declarationAt(editor: Editor, psi: BasedPythonFile, file: VirtualFile, client: LspClient): ByNamedDeclaration? {
        val project = psi.project
        val document = editor.document
        val offset = editor.caretModel.offset
        val declaration = if (ApplicationManager.getApplication().isDispatchThread) {
            runWithModalProgressBlocking(project, BasedPythonBundle.message("copyReference.progress")) {
                ByDeclarations.await(client, file, document, offset, TIMEOUT_MS)
            }
        } else {
            ByDeclarations.at(client, file, document, offset, TIMEOUT_MS)
        }.value ?: return null
        return runReadActionBlocking { ByNamedDeclaration.of(psi, document, declaration) }
    }

    private companion object {
        /** A declaration is one lookup; one not had by this is a busy server, and `path:line` will do. */
        const val TIMEOUT_MS = 2_000
    }
}
