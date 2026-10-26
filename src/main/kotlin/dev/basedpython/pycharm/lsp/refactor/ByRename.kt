package dev.basedpython.pycharm.lsp.refactor

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.codeInsight.template.impl.Variable
import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.impl.FinishMarkAction
import com.intellij.openapi.command.impl.StartMarkAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.rename.RenameDialog
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.util.CommonRefactoringUtil
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.awt.GridBagConstraints
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Rename (Shift+F6) in a `.by` file, through `by`, the way a `.py` file renames.
 *
 * The platform's own LSP rename edits one range in place — the occurrence under the caret, the
 * others changing only once the name is committed — and never opens a dialog, whatever *Specify
 * refactoring options* says. This one starts where `by` says the name is (`prepareRename`), and
 * then follows that setting:
 *
 *  - **In the editor**: every occurrence `by` highlights in the file (`documentHighlight`) is a
 *    segment of one template, so all of them change as the name is typed, as `.py`'s in-place
 *    rename does. Enter puts the old name back and renames to the new one through
 *    [ByRenamedNameProcessor], which asks `by`'s `textDocument/rename` for every use across the
 *    project; Escape, or leaving the name, puts the old name back and renames nothing.
 *  - **In modal dialogs**: the platform's Rename dialog, over the same processor, with its preview.
 *
 * What is renamed, and what is left alone, is `by`'s answer either way: the occurrences typed over
 * are only shown what the name will become.
 */
internal class ByRenameHandler : RenameHandler, TitledHandler {

    override fun getActionTitle(): String = RefactoringBundle.message("rename.title")

    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        if (CommonDataKeys.EDITOR.getData(dataContext) == null) return false
        val file = CommonDataKeys.PSI_FILE.getData(dataContext) as? BasedPythonFile ?: return false
        val virtualFile = file.virtualFile ?: return false
        return BasedPythonSettings.getInstance(file.project).byRename && byServerFor(file.project, virtualFile) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file == null) return
        ByRename.start(project, editor, file, editor.caretModel.offset)
    }

    /** Never called: the handler is available in an editor only. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit
}

/** The name `by` would rename at a position, and where else in the file it is written. */
internal data class ByRenameSites(val name: String, val primary: TextRange, val others: List<TextRange>) {

    companion object {
        /**
         * The sites of [name] at [primary], with [highlights] — `by`'s `documentHighlight` at the
         * same position — as the other occurrences. Only a highlight that spells the name is one:
         * the template mirrors what is typed into each, so anything else would be overwritten.
         */
        fun of(document: Document, name: String, primary: TextRange, highlights: List<DocumentHighlight>): ByRenameSites {
            val text = document.immutableCharSequence
            val others = highlights
                .mapNotNull { getRangeInDocument(document, it.range) }
                .filter { it != primary && !it.intersects(primary) && text.subSequence(it.startOffset, it.endOffset).toString() == name }
                .distinct()
                .sortedBy { it.startOffset }
            return ByRenameSites(name, primary, others)
        }
    }
}

internal object ByRename {

    /**
     * Renames the name at [offset] of [editor], as the user's *Specify refactoring options* setting
     * says to: in place or in the Rename dialog. Also what a refactoring that has just written a new
     * name, such as Introduce Variable, starts, so that the name can be chosen.
     */
    fun start(project: Project, editor: Editor, file: PsiFile, offset: Int) {
        val title = RefactoringBundle.message("rename.title")
        val virtualFile = file.virtualFile ?: return
        val server = byServerFor(project, virtualFile)
            ?: return error(project, editor, BasedPythonBundle.message("refactoring.by.noServer"))
        val document = editor.document
        val stamp = document.modificationStamp

        val found = runWithModalProgressBlocking(project, title) { find(server, project, file, document, offset) }

        when (found) {
            is Found.Refused -> error(project, editor, found.reason)
            Found.Nothing -> error(project, editor, RefactoringBundle.message("error.wrong.caret.position.symbol.to.rename"))
            is Found.Sites -> {
                if (document.modificationStamp != stamp) {
                    return error(project, editor, BasedPythonBundle.message("refactoring.by.changed"))
                }
                if (EditorSettingsExternalizable.getInstance().isVariableInplaceRenameEnabled) {
                    ByInplaceRename(project, editor, file, found.sites).start()
                } else {
                    ByRenameDialog(project, ByRenamedName(file, document, found.sites.primary), editor).show()
                }
            }
        }
    }

    private suspend fun find(server: LspClient, project: Project, file: PsiFile, document: Document, offset: Int): Found {
        val virtualFile = file.virtualFile
        val (identifier, position) = readAction {
            ByServerDocuments.ensureOpen(server, project, virtualFile)
            server.getDocumentIdentifier(virtualFile) to getLsp4jPosition(document, offset)
        }
        val prepared = try {
            server.sendRequest { it.textDocumentService.prepareRename(PrepareRenameParams(identifier, position)) }
        } catch (e: ResponseErrorException) {
            // `by`'s reason for refusing, such as a name declared outside the project
            return Found.Refused(e.responseError.message)
        } ?: return Found.Nothing
        val range: Range = when {
            prepared.isFirst -> prepared.first
            prepared.isSecond -> prepared.second.range
            else -> return Found.Nothing
        }
        val highlights = try {
            server.sendRequest { it.textDocumentService.documentHighlight(DocumentHighlightParams(identifier, position)) }
        } catch (_: ResponseErrorException) {
            null
        }.orEmpty()
        return readAction {
            val primary = getRangeInDocument(document, range) ?: return@readAction Found.Nothing
            val name = prepared.second?.placeholder ?: document.getText(primary)
            // a placeholder that is not the text in the range is not a name the template can edit
            if (document.getText(primary) != name) return@readAction Found.Nothing
            Found.Sites(ByRenameSites.of(document, name, primary, highlights))
        }
    }

    private fun error(project: Project, editor: Editor, text: String) {
        CommonRefactoringUtil.showErrorHint(project, editor, text, RefactoringBundle.message("rename.title"), null)
    }

    private sealed interface Found {
        data class Sites(val sites: ByRenameSites) : Found
        data class Refused(val reason: String) : Found
        data object Nothing : Found
    }
}

/**
 * The platform's Rename dialog for a name `by` renames: the new name, Preview and Refactor.
 *
 * Without the platform's options that `by`'s rename has no answer for. It renames the name's uses
 * across the project and nothing else, so there is no scope to narrow it to, and comments, strings
 * and text occurrences are `by`'s to rename or leave — checkboxes for them would change nothing.
 */
private class ByRenameDialog(project: Project, name: ByRenamedName, editor: Editor) :
    RenameDialog(project, name, null, editor) {

    /** The name alone: `by` says what it is, the platform's description would name the class standing for it. */
    // asked while the dialog is built in RenameDialog's constructor, so from the element it was given
    override fun getFullName(): String = "'${(psiElement as ByRenamedName).name}'"

    override fun createCheckboxes(panel: JPanel, gbConstraints: GridBagConstraints) {
        // the dialog reads and saves its own checkbox, so it is made and not shown
        super.createCheckboxes(panel, gbConstraints)
        cbSearchInComments.isVisible = false
    }

    override fun createSearchScopePanel(): JComponent? = null

    /** What the processor is handed in place of a chosen scope; `by` renames across the project regardless. */
    override fun getRefactoringScope(): SearchScope = GlobalSearchScope.projectScope(project)
}

/**
 * One in-place rename: a template over the name's occurrences in the file, the one at the caret
 * edited and the others following it, inside the start and finish marks that make it and the
 * rename it ends in one step to undo, as the platform's in-place refactorings are.
 */
internal class ByInplaceRename(
    private val project: Project,
    private val editor: Editor,
    private val file: PsiFile,
    private val sites: ByRenameSites,
) {
    private val document = editor.document
    private val commandName = RefactoringBundle.message("renaming.command.name", sites.name)

    fun start() {
        var mark: StartMarkAction? = null
        var busy: String? = null
        CommandProcessor.getInstance().executeCommand(project, {
            try {
                mark = StartMarkAction.start(editor, project, commandName)
            } catch (e: StartMarkAction.AlreadyStartedException) {
                busy = e.message
            }
        }, commandName, null)
        busy?.let { return CommonRefactoringUtil.showErrorHint(project, editor, it, commandName, null) }
        val started = mark ?: return

        val all = (sites.others + sites.primary).sortedBy { it.startOffset }
        val span = TextRange(all.first().startOffset, all.last().endOffset)
        val template = template(all, span)
        WriteCommandAction.writeCommandAction(project).withName(commandName).run<RuntimeException> {
            // An inline template's text is the text already in the document from the caret on:
            // only its variables are written, so the names are taken out and the template puts
            // them back as its segments.
            for (range in all.asReversed()) document.deleteString(range.startOffset, range.endOffset)
            editor.caretModel.moveToOffset(span.startOffset)
            val state = TemplateManager.getInstance(project).runTemplate(editor, template)
            val markers = (0 until state.segmentsCount).map { segment ->
                document.createRangeMarker(state.getSegmentRange(segment)).apply {
                    isGreedyToLeft = true
                    isGreedyToRight = true
                }
            }
            state.addTemplateStateListener(Ending(markers, all.indexOf(sites.primary), started))
        }
    }

    private fun template(all: List<TextRange>, span: TextRange): Template {
        val text = document.immutableCharSequence
        val template = TemplateManager.getInstance(project).createTemplate("", "")
        template.setInline(true)
        template.setToIndent(false)
        template.setToReformat(false)
        template.setToShortenLongNames(false)
        var at = span.startOffset
        all.forEachIndexed { index, range ->
            template.addTextSegment(text.subSequence(at, range.startOffset).toString())
            template.addVariableSegment(if (range == sites.primary) PRIMARY else "$OTHER$index")
            at = range.endOffset
        }
        // the name typed at the caret; every other occurrence repeats it and is never a stop
        template.addVariable(Variable(PRIMARY, ConstantNode(sites.name), ConstantNode(sites.name), true, false))
        all.indices.filter { all[it] != sites.primary }.forEach { template.addVariable(Variable("$OTHER$it", PRIMARY, PRIMARY, false)) }
        return template
    }

    private inner class Ending(
        private val markers: List<RangeMarker>,
        private val primaryIndex: Int,
        private val mark: StartMarkAction,
    ) : TemplateEditingAdapter() {

        private var newName: String? = null

        override fun beforeTemplateFinished(state: TemplateState, template: Template?) {
            newName = state.getVariableValue(PRIMARY)?.text
        }

        override fun templateFinished(template: Template, brokenOff: Boolean) {
            val typed = newName.takeUnless { brokenOff }
            restore()
            try {
                if (typed != null && typed.isNotBlank() && typed != sites.name && markers[primaryIndex].isValid) {
                    val name = ByRenamedName(file, document, markers[primaryIndex].textRange)
                    RenameProcessor(project, name, typed, false, false).run()
                }
            } finally {
                finish()
            }
        }

        override fun templateCancelled(template: Template?) {
            restore()
            finish()
        }

        /** Puts the old name back in every occurrence, for the rename to start from the text `by` read. */
        private fun restore() {
            WriteCommandAction.writeCommandAction(project).withName(commandName).run<RuntimeException> {
                for (marker in markers.sortedByDescending { it.startOffset }) {
                    if (marker.isValid) document.replaceString(marker.startOffset, marker.endOffset, sites.name)
                }
            }
        }

        private fun finish() {
            markers.forEach(RangeMarker::dispose)
            FinishMarkAction.finish(project, editor, mark)
        }
    }

    private companion object {
        const val PRIMARY = "BY_RENAME_PRIMARY"
        const val OTHER = "BY_RENAME_OTHER_"
    }
}
