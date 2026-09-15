package dev.basedpython.pycharm.lsp.refactor

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * A Refactor menu entry for one of `by`'s refactorings.
 *
 * The same refactorings are offered in Alt+Enter, by the platform's LSP client, whenever the server
 * says they apply. This entry is for asking explicitly: it always answers, and when the server
 * refuses it shows the server's reason rather than nothing.
 *
 * All it does is ask the server and apply what comes back. The request and the resolve of the edit
 * run off the EDT under a cancellable modal progress; the edit is applied on the EDT, as one
 * command, and only to the document the request was made against — if the document changed in the
 * meantime, the answer is about text that no longer exists and is dropped.
 */
abstract class ByRefactoringAction(private val refactoring: ByRefactoring) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val served = project != null && file != null && editor != null && byServerFor(project, file) != null
        e.presentation.isEnabledAndVisible = served
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val server = byServerFor(project, file) ?: return hint(editor, BasedPythonBundle.message("refactoring.by.noServer"))

        val document = editor.document
        val caret = editor.caretModel.primaryCaret
        val start = caret.selectionStart
        val end = caret.selectionEnd
        val stamp = document.modificationStamp
        val title = templatePresentation.text

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
            is Prepared.Message -> hint(editor, prepared.text)
            is Prepared.Apply -> {
                if (document.modificationStamp != stamp) {
                    return hint(editor, BasedPythonBundle.message("refactoring.by.changed"))
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

    private fun hint(editor: Editor, text: String) {
        HintManager.getInstance().showInformationHint(editor, text)
    }

    private sealed interface Prepared {
        data class Apply(val intention: LspIntentionAction, val title: String) : Prepared
        data class Message(val text: String) : Prepared
    }
}

class ByInlineVariableAction : ByRefactoringAction(ByRefactoring.InlineVariable)

class ByExtractVariableAction : ByRefactoringAction(ByRefactoring.ExtractVariable)

class ByIntroduceConstantAction : ByRefactoringAction(ByRefactoring.IntroduceConstant)

class ByExtractFunctionAction : ByRefactoringAction(ByRefactoring.ExtractFunction)
