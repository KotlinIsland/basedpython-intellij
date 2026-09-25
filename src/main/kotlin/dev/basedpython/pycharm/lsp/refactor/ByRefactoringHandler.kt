package dev.basedpython.pycharm.lsp.refactor

import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * The platform's own refactoring actions — Refactor | Extract/Introduce, Ctrl+Alt+V, Ctrl+Alt+C,
 * Ctrl+Alt+M — for a `.by` file.
 *
 * The platform finds them through this provider: an action is shown for a language only when the
 * language's provider hands it a handler, and without one the shortcuts did nothing at all in a
 * `.by` file. Each handler is one of `by`'s refactorings; the provider offers exactly the ones
 * `by` performs and leaves every other slot empty, so no action shows that has nothing behind it.
 * Inline (Ctrl+Alt+N) is found through its own extension point instead, see [ByInlineActionHandler].
 */
internal class ByRefactoringSupportProvider : RefactoringSupportProvider() {
    override fun getIntroduceVariableHandler(): RefactoringActionHandler = ByRefactoringHandler(ByRefactoring.ExtractVariable)
    override fun getIntroduceConstantHandler(): RefactoringActionHandler = ByRefactoringHandler(ByRefactoring.IntroduceConstant)
    override fun getExtractMethodHandler(): RefactoringActionHandler = ByRefactoringHandler(ByRefactoring.ExtractFunction)
}

/**
 * Inline (Ctrl+Alt+N) in a `.by` file, as `by`'s inline-variable refactoring.
 *
 * Only in an editor: `by` is asked about the caret, not about an element, so the element the
 * platform hands over is not read — [isEnabledOnElement] only asks which file the editor holds, and
 * [canInlineElement], the question the platform asks with no editor (a selection in the Project
 * view), is always no.
 */
internal class ByInlineActionHandler : InlineActionHandler() {

    override fun isEnabledForLanguage(language: Language): Boolean = language.isKindOf(BasedPythonLanguage)

    override fun isEnabledOnElement(element: PsiElement, editor: Editor?): Boolean = editor != null && isByEditor(editor)

    override fun canInlineElement(element: PsiElement): Boolean = false

    override fun canInlineElementInEditor(element: PsiElement, editor: Editor): Boolean = isByEditor(editor)

    override fun inlineElement(project: Project, editor: Editor?, element: PsiElement) {
        if (editor == null) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        ByRefactoringRun.perform(project, editor, file, ByRefactoring.InlineVariable)
    }

    private fun isByEditor(editor: Editor): Boolean =
        FileDocumentManager.getInstance().getFile(editor.document)?.let {
            (it.fileType as? com.intellij.openapi.fileTypes.LanguageFileType)?.language?.isKindOf(BasedPythonLanguage)
        } == true
}

/** One of `by`'s refactorings, as the handler behind a platform refactoring action. */
internal class ByRefactoringHandler(private val refactoring: ByRefactoring) : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val virtualFile = file.virtualFile ?: return
        ByRefactoringRun.perform(project, editor, virtualFile, refactoring)
    }

    /** Never called: the actions these handlers are for are available in an editor only. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit
}

/**
 * Asks `by` for one refactoring at the editor's selection and applies what it answers.
 *
 * All it does is ask the server and apply what comes back. The request and the resolve of the edit
 * run off the EDT under a cancellable modal progress; the edit is applied on the EDT, as one
 * command, and only to the document the request was made against — if the document changed in the
 * meantime, the answer is about text that no longer exists and is dropped.
 *
 * Asked this way the server always answers: when it refuses, it says why (the request is an explicit
 * invocation, see [ByRefactoringRequest.params]), and that reason is what the user is shown, in the
 * error hint every refactoring shows when it cannot be performed.
 */
internal object ByRefactoringRun {

    fun perform(project: Project, editor: Editor, file: VirtualFile, refactoring: ByRefactoring) {
        val title = refactoring.title
        val server = byServerFor(project, file) ?: return error(project, editor, title, BasedPythonBundle.message("refactoring.by.noServer"))

        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val start = caret.selectionStart
        val end = caret.selectionEnd
        val stamp = document.modificationStamp

        val prepared: Prepared = runWithModalProgressBlocking(project, title) {
            val offer = readAction {
                ByServerDocuments.ensureOpen(server, project, file)
                ByRefactoringRequest.ask(server, file, document, start, end, refactoring)
            }
            when (offer) {
                is ByRefactoringOffer.Available -> {
                    val intention = LspIntentionAction(server, offer.action)
                    // resolving is what computes the edit, and it is the server's to refuse too
                    if (readAction { intention.isAvailable() }) {
                        Prepared.Apply(intention, offer.action.title)
                    } else {
                        Prepared.Message(BasedPythonBundle.message("refactoring.by.notApplied", offer.action.title))
                    }
                }
                is ByRefactoringOffer.Refused ->
                    Prepared.Message(BasedPythonBundle.message("refactoring.by.refused", offer.title, offer.reason))
                ByRefactoringOffer.NotOffered ->
                    Prepared.Message(BasedPythonBundle.message("refactoring.by.notHere", title))
                ByRefactoringOffer.Failed ->
                    Prepared.Message(BasedPythonBundle.message("refactoring.by.noServer"))
            }
        }

        when (prepared) {
            is Prepared.Message -> error(project, editor, title, prepared.text)
            is Prepared.Apply -> {
                if (document.modificationStamp != stamp) {
                    return error(project, editor, title, BasedPythonBundle.message("refactoring.by.changed"))
                }
                CommandProcessor.getInstance().executeCommand(
                    project,
                    { prepared.intention.invoke(file) },
                    prepared.title,
                    null,
                    document,
                )
            }
        }
    }

    /** The platform's "cannot perform refactoring" hint, which every language's refactorings show. */
    private fun error(project: Project, editor: Editor, title: String, text: String) {
        CommonRefactoringUtil.showErrorHint(project, editor, text, title, null)
    }

    private sealed interface Prepared {
        data class Apply(val intention: LspIntentionAction, val title: String) : Prepared
        data class Message(val text: String) : Prepared
    }
}
