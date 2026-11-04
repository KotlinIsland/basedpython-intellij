package dev.basedpython.pycharm.lsp.refactor

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.codeInsight.template.impl.Variable
import com.intellij.codeInsight.lookup.LookupFocusDegree
import com.intellij.ide.TitledHandler
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.impl.FinishMarkAction
import com.intellij.openapi.command.impl.StartMarkAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.options.ConfigurationException
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
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByNameSuggestionsParams
import dev.basedpython.pycharm.lsp.ByExperimental
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.navigation.ByDeclarations
import com.intellij.usageView.UsageViewUtil
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightParams
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import java.awt.GridBagConstraints
import java.util.WeakHashMap
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.openapi.util.Disposer
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.Disposable
import dev.basedpython.pycharm.lsp.runningByServer
import com.intellij.openapi.application.ModalityState
import org.jetbrains.annotations.TestOnly
import javax.swing.JCheckBox
import java.awt.BorderLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.Alarm
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

        val inPlace = EditorSettingsExternalizable.getInstance().isVariableInplaceRenameEnabled
        val found = runWithModalProgressBlocking(project, title) { find(server, project, file, document, offset, suggest = inPlace) }

        when (found) {
            is Found.Refused -> error(project, editor, found.reason)
            Found.Nothing -> error(project, editor, RefactoringBundle.message("error.wrong.caret.position.symbol.to.rename"))
            is Found.Sites -> {
                if (document.modificationStamp != stamp) {
                    return error(project, editor, BasedPythonBundle.message("refactoring.by.changed"))
                }
                if (inPlace) {
                    ByInplaceRename(project, editor, file, found.sites, found.suggestions, found.kind).start()
                } else {
                    val name = ByRenamedName(file, document, found.sites.primary).apply { kind = found.kind }
                    ByRenameDialog(project, name, editor).show()
                }
            }
        }
    }

    /**
     * Where `by` would rename at [offset], and — when [suggest] says the name is about to be typed
     * in place — the names it suggests for the symbol, asked now, while the text still holds the
     * name being renamed. (The Rename dialog asks for its own, through [ByRenameNameSuggestionProvider].)
     */
    private suspend fun find(server: LspClient, project: Project, file: PsiFile, document: Document, offset: Int, suggest: Boolean): Found {
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
        // what the name is, for what the rename is called: by's kind of it, as `.py` names it
        val kind = ByDeclarations.await(server, virtualFile, document, offset, LspClient.DEFAULT_REQUEST_TIMEOUT_MS).value?.kind
        val highlights = try {
            server.sendRequest { it.textDocumentService.documentHighlight(DocumentHighlightParams(identifier, position)) }
        } catch (_: ResponseErrorException) {
            null
        }.orEmpty()
        val suggestions = if (suggest && ByExperimental.advertises(server, ByExperimental.NAME_SUGGESTIONS)) {
            val params = readAction {
                ByNameSuggestionsParams(identifier, position, ByTextHash.of(document.immutableCharSequence))
            }
            server.awaitBy("by/nameSuggestions") { (it as ByServerExtensions).nameSuggestions(params) }.value?.names.orEmpty()
        } else {
            emptyList()
        }
        return readAction {
            val primary = getRangeInDocument(document, range) ?: return@readAction Found.Nothing
            val name = prepared.second?.placeholder ?: document.getText(primary)
            // a placeholder that is not the text in the range is not a name the template can edit
            if (document.getText(primary) != name) return@readAction Found.Nothing
            Found.Sites(ByRenameSites.of(document, name, primary, highlights), suggestions.filter { it != name }, kind)
        }
    }

    private fun error(project: Project, editor: Editor, text: String) {
        CommonRefactoringUtil.showErrorHint(project, editor, text, RefactoringBundle.message("rename.title"), null)
    }

    private sealed interface Found {
        data class Sites(val sites: ByRenameSites, val suggestions: List<String>, val kind: String?) : Found
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
 *
 * With the renames `by` says go with renaming to the name typed — overriding methods, the same
 * parameter of each, the module named after a class ([ByRenameGroup]) — as a checkbox per group,
 * where PyCharm's dialog has *Rename inheritors*: what goes with a rename is only known once the new
 * name is, so the groups are `by/rename`'s answer for the name in the field, asked as it is checked,
 * and shown under the name.
 */
internal class ByRenameDialog(project: Project, name: ByRenamedName, editor: Editor) :
    RenameDialog(project, name, null, editor) {

    private val renamed: ByRenamedName get() = psiElement as ByRenamedName

    private val choices = ByRenameGroupChoices()

    /**
     * Where the groups' checkboxes go, under the name. Made by [createCheckboxes], which RenameDialog's
     * constructor calls before this class's own initializers run — so it has none, which would put it
     * back to null.
     */
    private lateinit var groupsPanel: JPanel

    /** The groups on screen, for the name they were asked for. */
    private var shown: ByAnnotatedRenameFor? = null

    /** Checks the name once typing pauses. Made after RenameDialog's constructor, which may change the name already. */
    private lateinit var checking: Alarm

    init {
        checking = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)
    }

    /**
     * `method 'speak'`, as `.py`'s dialog says it: the kind `by` gave the name ([ByRenamedNameDescriptionProvider]).
     * Asked while the dialog is built in RenameDialog's constructor, so from the element it was given.
     */
    override fun getFullName(): String = "${UsageViewUtil.getType(psiElement)} '${(psiElement as ByRenamedName).name}'"

    override fun createCheckboxes(panel: JPanel, gbConstraints: GridBagConstraints) {
        // the dialog reads and saves its own checkbox, so it is made and not shown
        super.createCheckboxes(panel, gbConstraints)
        cbSearchInComments.isVisible = false
        gbConstraints.gridx = 0
        gbConstraints.gridwidth = GridBagConstraints.REMAINDER
        gbConstraints.insets = JBUI.emptyInsets()
        groupsPanel = JPanel(BorderLayout())
        panel.add(groupsPanel, gbConstraints)
    }

    override fun createSearchScopePanel(): JComponent? = null

    /**
     * Checked as it is typed, by `by` ([ByRenameNames]), once typing pauses: the rule a new name must
     * follow is the one `textDocument/rename` refuses it by, and the reason shown is the one it would
     * give. Asked off the EDT, in the platform's cancellable non-blocking read action, so typing never
     * waits for `by`; the constructor's own first check goes the same way.
     */
    override fun validateButtons() = validateButtonsAsync()

    override fun processNewNameChanged() {
        // not super's, which checks at once
        if (!::checking.isInitialized) return validateButtonsAsync()
        checking.cancelAllRequests()
        checking.addRequest({ validateButtonsAsync() }, CHECK_DELAY_MS)
    }

    override fun canRun() {
        val newName = newName
        if (newName.isEmpty()) throw ConfigurationException(null)
        // never asked on the EDT once validateButtons is the async one; nothing to wait for there
        if (ApplicationManager.getApplication().isDispatchThread) return
        val verdict = ByRenameNames.validate(renamed, newName)
        if (verdict is ByNameVerdict.Invalid) throw ConfigurationException(verdict.reason)
        if (newName != renamed.name) ask(newName)?.let { asked -> ApplicationManager.getApplication().invokeLater({ show(asked) }, ModalityState.stateForComponent(contentPanel)) }
    }

    /**
     * Refactor, with the groups kept that are checked. When the name was never checked — Enter
     * before typing paused — its groups are asked for now, and a group the user has not seen stops
     * it: they are shown, unchecked by nobody, for Refactor to be pressed again with them in sight.
     */
    override fun doAction() {
        val newName = newName
        if (newName != renamed.name && shown?.newName != newName) {
            val asked = runWithModalProgressBlocking(project, RefactoringBundle.message("rename.title")) { readAction { ask(newName) } }
            if (asked != null) {
                val unseen = asked.answer.groups.any { group -> shown?.answer?.groups?.none { it.label == group.label } ?: true }
                show(asked)
                if (unseen) return
            }
        }
        renamed.asked = shown?.takeIf { it.newName == newName }
        renamed.keptGroups = shown?.takeIf { it.newName == newName }?.let { choices.kept(it.answer.groups) }.orEmpty()
        super.doAction()
    }

    /** `by/rename`'s answer for [newName], or null with no `by` that answers it. Off the EDT, in a read action. */
    private fun ask(newName: String): ByAnnotatedRenameFor? {
        val file = renamed.containingFile.virtualFile ?: return null
        val client = runningByServer(project, file) ?: return null
        val document = renamed.containingFile.viewProvider.document ?: return null
        val stamp = document.modificationStamp
        val answer = (ByAnnotatedRename.ask(client, project, file, document, renamed.textOffset, newName) as? ByAsked.Answer)?.value ?: return null
        return ByAnnotatedRenameFor(newName, stamp, answer)
    }

    /** Puts the checkboxes of [asked]'s groups under the name, when it is still the name in the field. */
    private fun show(asked: ByAnnotatedRenameFor) {
        if (isDisposed || asked.newName != newName) return
        val panel = groupsPanel
        val same = shown?.answer?.groups?.map { it.label to it.descriptions } == asked.answer.groups.map { it.label to it.descriptions }
        shown = asked
        if (same) return
        panel.removeAll()
        if (asked.answer.groups.isNotEmpty()) panel.add(choices.panel(asked.answer.groups), BorderLayout.CENTER)
        panel.revalidate()
        panel.repaint()
        pack()
    }

    /** What the processor is handed in place of a chosen scope; `by` renames across the project regardless. */
    override fun getRefactoringScope(): SearchScope = GlobalSearchScope.projectScope(project)

    /** The groups' checkbox labelled [label], for a test. */
    @TestOnly
    fun groupBox(label: String): JCheckBox? = choices.box(label)

    private companion object {
        /** How long typing pauses before the name is checked, as the platform's own name fields wait. */
        const val CHECK_DELAY_MS = 300
    }
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
    /** `by`'s names for the symbol, offered in a lookup under the name, as `.py`'s in-place rename offers its own. */
    private val suggestions: List<String> = emptyList(),
    /** What `by` says the name is, for what the rename is called; see [ByRenamedName.kind]. */
    private val kind: String? = null,
    /** The `by` asked about the name; the project's running one but in a test. Off the EDT. */
    private val client: () -> LspClient? = { file.virtualFile?.let { runningByServer(project, it) } },
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
            TypingCheck(state)
        }
    }

    /**
     * Checks the name as it is typed, once typing pauses, by `by` ([ByRenameNames]) — at the position
     * of the name being typed, in the text that holds it — and marks a name `by` would refuse with an
     * error underline and its reason, as the Rename dialog says it under the field. Lives as long as
     * the template.
     */
    inner class TypingCheck(private val state: TemplateState) : DocumentListener, Disposable {

        private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
        private var marked: RangeHighlighter? = null

        /** The reason the name on screen was refused, while it is marked. */
        @get:TestOnly
        val refusal: String? get() = marked?.takeIf { it.isValid }?.errorStripeTooltip as? String

        init {
            Disposer.register(state, this)
            document.addDocumentListener(this, this)
            checks[editor] = this
        }

        override fun documentChanged(event: DocumentEvent) {
            unmark()
            alarm.cancelAllRequests()
            alarm.addRequest(::check, CHECK_DELAY_MS)
        }

        private fun check() {
            if (state.isFinished) return
            val range = state.getVariableRange(PRIMARY) ?: return
            val typed = document.getText(range)
            if (typed.isEmpty() || typed == sites.name) return
            val virtualFile = file.virtualFile ?: return
            val stamp = document.modificationStamp
            ReadAction.nonBlocking<ByNameVerdict> {
                val by = client() ?: return@nonBlocking ByNameVerdict.Unknown
                ByRenameNames.validate(by, project, virtualFile, document, range.startOffset, typed)
            }
                .expireWhen { state.isFinished || document.modificationStamp != stamp }
                .finishOnUiThread(ModalityState.defaultModalityState()) { verdict ->
                    if (verdict is ByNameVerdict.Invalid) mark(range, verdict.reason)
                }
                .submit(AppExecutorUtil.getAppExecutorService())
        }

        private fun mark(range: TextRange, reason: String) {
            unmark()
            marked = editor.markupModel.addRangeHighlighter(
                CodeInsightColors.ERRORS_ATTRIBUTES, range.startOffset, range.endOffset,
                HighlighterLayer.ERROR + 1, HighlighterTargetArea.EXACT_RANGE,
            ).apply { errorStripeTooltip = reason }
            HintManager.getInstance().showErrorHint(editor, reason, HintManager.ABOVE)
        }

        private fun unmark() {
            marked?.let(editor.markupModel::removeHighlighter)
            marked = null
        }

        override fun dispose() {
            unmark()
            checks.remove(editor, this)
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
        // the name typed at the caret; every other occurrence repeats it and is never a stop. by's
        // suggestions, if it has any, are a lookup under it that typing goes past: unfocused, so
        // Enter takes what was typed, as the platform's in-place rename offers its names
        val named = ConstantNode(sites.name).let {
            if (suggestions.isEmpty()) it
            else it.withLookupStrings(listOf(sites.name) + suggestions).withLookupFocusDegree(LookupFocusDegree.UNFOCUSED)
        }
        template.addVariable(Variable(PRIMARY, named, ConstantNode(sites.name), true, false))
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
                    val name = ByRenamedName(file, document, markers[primaryIndex].textRange).also { it.kind = kind }
                    // Asked once the old name is back, which is the text the rename is about: the
                    // name, and the renames by says go with renaming it to the one typed.
                    val (verdict, asked) = runWithModalProgressBlocking(project, commandName) {
                        readAction {
                            val by = client() ?: return@readAction ByNameVerdict.Unknown to null
                            val virtualFile = file.virtualFile ?: return@readAction ByNameVerdict.Unknown to null
                            val verdict = ByRenameNames.validate(by, project, virtualFile, document, name.textOffset, typed)
                            val asked = if (verdict is ByNameVerdict.Invalid) null else {
                                (ByAnnotatedRename.ask(by, project, virtualFile, document, name.textOffset, typed) as? ByAsked.Answer)?.value
                                    ?.let { ByAnnotatedRenameFor(typed, document.modificationStamp, it) }
                            }
                            verdict to asked
                        }
                    }
                    if (verdict is ByNameVerdict.Invalid) {
                        CommonRefactoringUtil.showErrorHint(project, editor, verdict.reason, commandName, null)
                    } else if (keep(name, typed, asked)) {
                        RenameProcessor(project, name, typed, false, false).run()
                    }
                }
            } finally {
                finish()
            }
        }

        /**
         * Which of the renames `by` says go with this one to make, asked in a dialog as PyCharm asks
         * about a method's overriding methods once its new name is typed; false when it is cancelled,
         * and nothing is renamed.
         */
        private fun keep(name: ByRenamedName, typed: String, asked: ByAnnotatedRenameFor?): Boolean {
            name.asked = asked
            val groups = asked?.answer?.groups.orEmpty()
            if (groups.isEmpty()) return true
            val dialog = groupsDialog(project, sites.name, typed, groups)
            if (!dialog.ask()) return false
            name.keptGroups = dialog.kept()
            return true
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

    internal companion object {
        const val PRIMARY = "BY_RENAME_PRIMARY"
        const val OTHER = "BY_RENAME_OTHER_"

        /** How long typing pauses before the name is checked. */
        const val CHECK_DELAY_MS = 300

        /** The check of each editor renaming in place now; EDT only. */
        private val checks = WeakHashMap<Editor, ByInplaceRename.TypingCheck>()

        /** What `by` refused the name being typed in [editor] for, while it is marked. */
        @TestOnly
        fun refusal(editor: Editor): String? = checks[editor]?.refusal

        /** Asks which groups to keep; a test answers instead. */
        @TestOnly
        internal var groupsDialog: (Project, String, String, List<ByRenameGroup>) -> ByRenameGroupsAsker =
            { project, oldName, newName, groups -> ByRenameGroupsDialog(project, oldName, newName, groups) }
    }
}
