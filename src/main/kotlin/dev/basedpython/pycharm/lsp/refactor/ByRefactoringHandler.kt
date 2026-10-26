package dev.basedpython.pycharm.lsp.refactor

import com.intellij.lang.Language
import com.intellij.lang.refactoring.InlineActionHandler
import com.intellij.lang.refactoring.RefactoringSupportProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.platform.lsp.util.getLsp4jRange
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.refactoring.IntroduceTargetChooser
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.introduce.IntroduceTarget
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.WorkspaceEdit

/**
 * The platform's own refactoring actions — Refactor | Extract/Introduce, Ctrl+Alt+V, Ctrl+Alt+C,
 * Ctrl+Alt+P, Ctrl+Alt+F, Ctrl+Alt+M — for a `.by` file.
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
    override fun getIntroduceParameterHandler(): RefactoringActionHandler = ByRefactoringHandler(ByRefactoring.IntroduceParameter)
    override fun getIntroduceFieldHandler(): RefactoringActionHandler = ByRefactoringHandler(ByRefactoring.IntroduceField)
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
 *
 * With nothing selected, `by` offers a refactoring that replaces an expression for each expression
 * around the caret, and they are shown in the platform's expression chooser, each highlighting its
 * expression, as `.py`'s Introduce Variable does. Once such a refactoring is made, the name it chose
 * is renamed in place ([ByRename]), so it can be given a name of the user's choosing.
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

        val offer = runWithModalProgressBlocking(project, title) {
            readAction {
                ByServerDocuments.ensureOpen(server, project, file)
                ByRefactoringRequest.ask(server, file, document, start, end, refactoring)
            }
        }

        val actions = when (offer) {
            is ByRefactoringOffer.Available -> offer.actions
            is ByRefactoringOffer.Refused ->
                return error(project, editor, title, BasedPythonBundle.message("refactoring.by.refused", offer.title, offer.reason))
            ByRefactoringOffer.NotOffered ->
                return error(project, editor, title, BasedPythonBundle.message("refactoring.by.notHere", title))
            ByRefactoringOffer.Failed ->
                return error(project, editor, title, BasedPythonBundle.message("refactoring.by.noServer"))
        }
        if (document.modificationStamp != stamp) {
            return error(project, editor, title, BasedPythonBundle.message("refactoring.by.changed"))
        }

        // what each action rewrites: the range `by` names, or for a selection the selection itself
        val requested = getLsp4jRange(document, start, end - start)
        val targets = actions.map { action ->
            val range = ByRefactoringRequest.expressionRange(action) ?: requested
            ByRefactoringTarget(action, range, getRangeInDocument(document, range), document, stamp)
        }
        val choosable = targets.filter { it.documentRange != null }
        if (refactoring.replacesExpression && targets.size > 1 && choosable.size == targets.size) {
            IntroduceTargetChooser.showIntroduceTargetChooser(
                editor,
                choosable,
                { target -> apply(project, editor, file, server, refactoring, target) },
                RefactoringBundle.message("introduce.target.chooser.expressions.title"),
                0,
            )
        } else {
            apply(project, editor, file, server, refactoring, targets.first())
        }
    }

    private fun apply(
        project: Project,
        editor: Editor,
        file: VirtualFile,
        server: LspClient,
        refactoring: ByRefactoring,
        target: ByRefactoringTarget,
    ) {
        val title = refactoring.title
        val document = editor.document
        if (!target.isValid) return error(project, editor, title, BasedPythonBundle.message("refactoring.by.changed"))
        val intention = ByRefactoringIntention(server, target.action, server.getDocumentIdentifier(file).uri, document, target.range)
        // resolving is what computes the edit, and it is the server's to refuse too
        val resolved = runWithModalProgressBlocking(project, title) { readAction { intention.isAvailable() } }
        if (!resolved) return error(project, editor, title, BasedPythonBundle.message("refactoring.by.notApplied", target.action.title))
        if (!target.isValid) return error(project, editor, title, BasedPythonBundle.message("refactoring.by.changed"))

        CommandProcessor.getInstance().executeCommand(project, { intention.invoke(file) }, target.action.title, null, document)

        val nameEnd = intention.nameEnd ?: return
        if (!refactoring.replacesExpression) return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        PsiDocumentManager.getInstance(project).commitDocument(document)
        // inside the name rather than just after it, where a `(` or `.` could be what is asked about
        ByRename.start(project, editor, psiFile, nameEnd - 1)
    }

    /** The platform's "cannot perform refactoring" hint, which every language's refactorings show. */
    private fun error(project: Project, editor: Editor, title: String, text: String) {
        CommonRefactoringUtil.showErrorHint(project, editor, text, title, null)
    }
}

/**
 * One of `by`'s actions as an entry of the platform's expression chooser: the expression it
 * rewrites, highlighted as it is chosen, and shown as its text.
 */
internal class ByRefactoringTarget(
    val action: CodeAction,
    /** The range the action rewrites, as `by` gave it. */
    val range: Range,
    /** The same range in the document, `null` when it is not a range of the document. */
    val documentRange: TextRange?,
    private val document: Document,
    private val stamp: Long,
) : IntroduceTarget {

    override fun getTextRange(): TextRange = checkNotNull(documentRange)

    override fun getPlace(): PsiElement? = null

    override fun render(): String = document.getText(getTextRange())

    /** Only for the text the actions were offered for. */
    override fun isValid(): Boolean = document.modificationStamp == stamp
}

/**
 * One of `by`'s code actions, applied as the platform applies any, that also notes where the new
 * name ends in the document it edits ([ByExtractedName]) — read from the edit as it is made, since
 * the edit is resolved only once the action is chosen.
 */
internal class ByRefactoringIntention(
    server: LspClient,
    action: CodeAction,
    private val uri: String,
    private val document: Document,
    private val replaced: Range,
) : LspIntentionAction(server, action) {

    /** Where the new name ends once the edit is made; `null` before, or when no edit replaced [replaced]. */
    var nameEnd: Int? = null
        private set

    override fun applyWorkspaceEdit(workspaceEdit: WorkspaceEdit, uriToDocumentMap: Map<String, Document>) {
        nameEnd = ByExtractedName.endAfter(document, ByCleanup.editsFor(workspaceEdit, uri).edits, replaced)
        super.applyWorkspaceEdit(workspaceEdit, uriToDocumentMap)
    }
}
