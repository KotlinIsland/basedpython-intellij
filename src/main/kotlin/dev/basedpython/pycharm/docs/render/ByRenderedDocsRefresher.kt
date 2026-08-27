package dev.basedpython.pycharm.docs.render

import com.intellij.codeInsight.documentation.render.DocRenderManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspClientManagerListener
import com.intellij.psi.PsiManager
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider

/**
 * Renders a file's docstrings once `by` is actually able to say where they are.
 *
 * The rendering pass and the language server disagree about when a file is ready, and the pass
 * loses. `by` answers no document request for a file it has not been sent `textDocument/didOpen`
 * for — *"Document … is not open in the session"* — and the client sends that asynchronously, off
 * the event that opened the file. The pass, meanwhile, runs the moment the editor appears. So the
 * first look at a freshly opened file finds no docstrings, and it is not because there are none.
 *
 * On its own that would be permanent. `DocRenderPassFactory` skips the pass entirely while the PSI
 * modification count is unchanged, so the empty answer computed a moment too early is what the file
 * keeps until an edit — and a stub in a library is never edited. A feature that only worked after
 * you typed a character is what this fixes.
 *
 * The signal is exact rather than a retry: [LspClientManagerListener.fileOpened] fires when the
 * client has told a server about a file, which is precisely the moment the earlier answer became
 * wrong. [DocRenderManager.resetEditorToDefaultState] is the public way to tell the platform that a
 * pass's inputs changed, since its own skip is keyed on PSI alone.
 *
 * It only acts when the file has no docstrings recorded, so a file that rendered correctly is never
 * reset — that call also returns manually toggled blocks to their default, and doing it under a
 * user who has just expanded one would be its own bug.
 */
internal class ByRenderedDocsRefresher : ProjectActivity {

    override suspend fun execute(project: Project) {
        LspClientManager.getInstance(project).addListener(Listener(project), project)
    }

    private class Listener(private val project: Project) : LspClientManagerListener {

        override fun fileOpened(lspClient: LspClient, file: VirtualFile) {
            if (lspClient.providerClass != ByLspServerSupportProvider::class.java) return

            val stale = ReadAction.compute<Boolean, RuntimeException> {
                if (project.isDisposed || !file.isValid) return@compute false
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@compute false
                psiFile is BasedPythonFile && ByDocstringSpans.cached(psiFile).isEmpty()
            }
            if (!stale) return

            ApplicationManager.getApplication().invokeLater({
                if (project.isDisposed || !file.isValid) return@invokeLater
                FileEditorManager.getInstance(project).getAllEditors(file)
                    .filterIsInstance<TextEditor>()
                    .forEach { DocRenderManager.resetEditorToDefaultState(it.editor) }
            }, ModalityState.any(), project.disposed)
        }
    }
}
