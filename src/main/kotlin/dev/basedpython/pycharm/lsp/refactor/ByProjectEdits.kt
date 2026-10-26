package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.refactoring.ui.UsageViewDescriptorAdapter
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewDescriptor
import com.intellij.util.containers.MultiMap
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByPositionParams
import dev.basedpython.pycharm.lsp.ext.ByProjectEditAnswer
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import java.util.concurrent.CompletableFuture

/** What one of `by`'s own requests came back with, a `by` that does not know it kept apart. */
internal sealed interface ByAsked<out R : Any> {

    /** The server answered with something. */
    data class Answer<R : Any>(val value: R) : ByAsked<R>

    /** The server answered `null`: nothing at the position, or language services are off. */
    data object None : ByAsked<Nothing>

    /** The server answered `MethodNotFound`: a `by` from before the request. */
    data object Unsupported : ByAsked<Nothing>

    /** The request failed, timed out, or was never sent. */
    data object Failed : ByAsked<Nothing>
}

/**
 * Sends one of [ByServerExtensions]' requests, as [dev.basedpython.pycharm.lsp.awaitBy] does, telling
 * a `by` that answers `MethodNotFound` apart from a failure: what to say about each is the caller's.
 */
internal suspend fun <R : Any> LspClient.awaitByExtension(
    what: String,
    timeoutMs: Long = LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong(),
    request: (ByServerExtensions) -> CompletableFuture<R?>,
): ByAsked<R> {
    var unknownRequest = false
    val answer = awaitingAgain<R>(what, timeoutMs) { sent ->
        try {
            sendRequest { (it as ByServerExtensions).let(request).also(sent) }
        } catch (e: Exception) {
            if (isMethodNotFound(e)) unknownRequest = true
            throw e
        }
    }
    return when (answer) {
        is ByAnswer.Answer -> ByAsked.Answer(answer.value)
        ByAnswer.None -> ByAsked.None
        ByAnswer.Failed -> if (unknownRequest) ByAsked.Unsupported else ByAsked.Failed
    }
}

/** What `by` said to a refactoring that edits the project. */
internal sealed interface ByProjectEdit {

    /** The edit to make, and what it leaves as it was that may no longer mean what it did. */
    data class Edit(val edit: WorkspaceEdit, val warnings: List<String>) : ByProjectEdit

    /** Nothing is changed, and why. */
    data class Refused(val reason: String, val warnings: List<String>) : ByProjectEdit

    /** Anything else: the message to show is the whole of it. */
    data class Failed(val message: String) : ByProjectEdit

    companion object {
        /** [asked] for the request [what], as the user is to be told it. */
        fun of(asked: ByAsked<ByProjectEditAnswer>, what: String): ByProjectEdit = when (asked) {
            is ByAsked.Answer -> {
                val answer = asked.value
                val warnings = answer.warnings.orEmpty()
                when {
                    answer.refused != null -> Refused(answer.refused, warnings)
                    answer.edit != null -> Edit(answer.edit, warnings)
                    else -> Failed(BasedPythonBundle.message("refactoring.by.project.noEdit", what))
                }
            }
            ByAsked.None -> Failed(BasedPythonBundle.message("refactoring.by.project.declined", what))
            ByAsked.Unsupported -> Failed(BasedPythonBundle.message("refactoring.by.project.unsupported", what))
            ByAsked.Failed -> Failed(BasedPythonBundle.message("refactoring.by.project.failed", what))
        }
    }
}

/** Where in which document one of these refactorings was asked for, as `by` is told it. */
internal data class ByRefactoringSite(
    val server: LspClient,
    val project: Project,
    val file: VirtualFile,
    val document: Document,
    val identifier: TextDocumentIdentifier,
    val position: Position,
) {
    val params: ByPositionParams get() = ByPositionParams(identifier, position)
}

/**
 * The steps every project-wide refactoring of `by`'s takes: find where it was asked for, ask the
 * question that opens its dialog, ask for the edit, and make it.
 *
 * Every question goes to `by` off the EDT under a cancellable modal progress, as the extractions'
 * do ([ByRefactoringRun]); the edit is made by a [ByProjectEditProcessor], the platform's refactoring
 * machinery, so it gets the usage preview, the conflicts dialog, one undoable command and the
 * refactoring listeners every other language's refactorings get.
 */
internal object ByProjectRefactorings {

    /**
     * Where the caret of [editor] is, for [file]'s `by`; `null` when no `by` is running for it, in
     * which case the user has been told.
     */
    fun site(project: Project, editor: Editor, file: VirtualFile, title: String): ByRefactoringSite? =
        site(project, editor, file, editor.caretModel.offset, title)

    /** [site] at [offset] of [file], whose document [editor], if any, shows. */
    fun site(project: Project, editor: Editor?, file: VirtualFile, offset: Int, title: String): ByRefactoringSite? {
        val server = byServerFor(project, file)
            ?: return null.also { error(project, editor, title, BasedPythonBundle.message("refactoring.by.noServer")) }
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return runWithModalProgressBlocking(project, title) {
            readAction {
                ByServerDocuments.ensureOpen(server, project, file)
                ByRefactoringSite(server, project, file, document, server.getDocumentIdentifier(file), getLsp4jPosition(document, offset))
            }
        }
    }

    /** Asks [request] under a modal progress titled [title]. */
    fun <R : Any> ask(
        site: ByRefactoringSite,
        title: String,
        what: String,
        timeoutMs: Long = LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong(),
        request: (ByServerExtensions) -> CompletableFuture<R?>,
    ): ByAsked<R> = runWithModalProgressBlocking(site.project, title) { site.server.awaitByExtension(what, timeoutMs, request) }

    /**
     * Asks for the edit of a refactoring the user has chosen, under a modal progress titled [title].
     *
     * Every document is saved first. `by` reads a file nobody has open in an editor from the disk,
     * and a refactoring edits files nobody has open — the last one's edits to them would otherwise be
     * unknown to the server working out this one, which would be about text that is not there.
     */
    fun askEdit(
        site: ByRefactoringSite,
        title: String,
        what: String,
        request: (ByServerExtensions) -> CompletableFuture<ByProjectEditAnswer?>,
    ): ByProjectEdit {
        FileDocumentManager.getInstance().saveAllDocuments()
        return ByProjectEdit.of(ask(site, title, what, EDIT_TIMEOUT_MS, request), what)
    }

    /**
     * What to tell the user about [asked], a question that opens a refactoring's dialog, when it did
     * not answer: `null` when it did. [notHere] is what a `null` answer means.
     */
    fun <R : Any> unanswered(asked: ByAsked<R>, what: String, notHere: String): String? = when (asked) {
        is ByAsked.Answer -> null
        ByAsked.None -> notHere
        ByAsked.Unsupported -> BasedPythonBundle.message("refactoring.by.project.unsupported", what)
        ByAsked.Failed -> BasedPythonBundle.message("refactoring.by.project.failed", what)
    }

    /**
     * The edit of [edited] as a processor, or `null` when it is not one the IDE can make, in which
     * case the user has been told: a refusal, a failure, or an edit to a file the IDE cannot find,
     * which made in part would leave the project broken.
     */
    fun processor(project: Project, title: String, subject: PsiFile, edited: ByProjectEdit): ByProjectEditProcessor? {
        val message = when (edited) {
            is ByProjectEdit.Edit -> {
                val missing = ByWorkspaceEditFiles.uris(edited.edit).filter { ByWorkspaceEditFiles.fileOf(it) == null }
                if (missing.isEmpty()) return ByProjectEditProcessor(project, title, subject, edited.edit, edited.warnings)
                BasedPythonBundle.message("refactoring.by.project.missingFile", missing.joinToString())
            }
            is ByProjectEdit.Refused -> refusal(edited)
            is ByProjectEdit.Failed -> edited.message
        }
        CommonRefactoringUtil.showErrorMessage(title, message, null, project)
        return null
    }

    /** [refused]'s reason, with what it would have left as it was. */
    fun refusal(refused: ByProjectEdit.Refused): String =
        (listOf(refused.reason) + refused.warnings).joinToString("\n")

    /** The platform's "cannot perform refactoring" message, as a hint in [editor] or a dialog without one. */
    fun error(project: Project, editor: Editor?, title: String, text: String) {
        if (editor != null) {
            CommonRefactoringUtil.showErrorHint(project, editor, text, title, null)
        } else {
            CommonRefactoringUtil.showErrorMessage(title, text, null, project)
        }
    }

    /**
     * How long `by` gets to work out an edit to the project: longer than an editor request, since it
     * reads every file that could refer to what changes, and bounded, since the user is waiting.
     */
    private const val EDIT_TIMEOUT_MS = 30_000L
}

/**
 * The edit `by` answered for one of its project-wide refactorings, made as the platform makes any
 * refactoring's.
 *
 * Each of the edit's text edits is a usage ([ByImportEdit]) the preview shows under its file and can
 * leave out; the references `by` says the edit leaves as they were are conflicts, shown before
 * anything changes for the user to go on or not; and the edits are made in one command, undone
 * together.
 */
internal class ByProjectEditProcessor(
    project: Project,
    private val title: String,
    /** The file the refactoring was asked from, which the preview names as what is refactored. */
    private val subject: PsiFile,
    private val edit: WorkspaceEdit,
    private val warnings: List<String>,
) : BaseRefactoringProcessor(project) {

    override fun createUsageViewDescriptor(usages: Array<out UsageInfo>): UsageViewDescriptor =
        object : UsageViewDescriptorAdapter() {
            override fun getElements(): Array<PsiElement> = arrayOf(subject)
            override fun getProcessedElementsHeader(): String = title
        }

    override fun findUsages(): Array<UsageInfo> = ByImportEdit.of(myProject, edit, subject).toTypedArray()

    override fun preprocessUsages(refUsages: Ref<Array<UsageInfo>>): Boolean {
        val conflicts = MultiMap<PsiElement, String>()
        for (warning in warnings) conflicts.putValue(subject, warning)
        return showConflicts(conflicts, refUsages.get())
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) =
        ByImportEdit.apply(myProject, usages.filterIsInstance<ByImportEdit>())

    override fun getCommandName(): String = title
}
