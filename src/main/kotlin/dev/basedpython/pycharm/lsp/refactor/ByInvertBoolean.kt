package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.invertBoolean.InvertBooleanDelegate
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByExperimental
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByInvertBooleanParams
import dev.basedpython.pycharm.lsp.ext.ByPrepareInvertBooleanParams
import dev.basedpython.pycharm.lsp.ext.ByPreparedInvertBoolean
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.jetbrains.annotations.TestOnly
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * *Refactor | Invert Boolean* (the platform's `InvertBoolean` action) in a `.by` file, through `by`:
 * `by/prepareInvertBoolean` says which boolean variable, parameter or function is at the caret and
 * the name it proposes for it inverted, a dialog asks for the new name — pre-filled with that one and
 * checked by `by/checkName` as it is typed — and `by/invertBoolean` answers the edit, made as the
 * platform makes any refactoring's, with its preview and one undo ([ByProjectEditProcessor]).
 *
 * ## Why through the delegate's `adjustElement`
 *
 * The platform's action and handler are internal and take no handler of a language's own; what a
 * language gives them is this delegate, which they ask whether the action shows and is enabled, and
 * then to adjust the element to invert — "null if user canceled". Everything after that, the
 * platform's dialog and processor, inverts by walking the PSI's references and expressions, which a
 * `.by` file's flat tree does not have and `by` answers instead. So [adjustElement] is where the
 * `.by` refactoring runs, start to end, and its null leaves the platform nothing more to do. The
 * action, its menu item and its shortcut stay the platform's, unchanged for every other language.
 */
class ByInvertBooleanDelegate : InvertBooleanDelegate() {

    /** Anything in a `.by` file's text: which names are booleans is `by`'s to say, once asked. */
    override fun isVisibleOnElement(element: PsiElement): Boolean =
        element !is PsiFileSystemItem && element.containingFile is BasedPythonFile

    /** Where a `by` that inverts booleans is running for the file. */
    override fun isAvailableOnElement(element: PsiElement): Boolean {
        if (!isVisibleOnElement(element)) return false
        val file = element.containingFile.virtualFile ?: return false
        val server = byServerFor(element.project, file) ?: return false
        return ByExperimental.advertises(server, ByExperimental.INVERT_BOOLEAN)
    }

    /** Runs the whole refactoring, in an editor, and leaves the platform nothing to do; see the class. */
    override fun adjustElement(element: PsiElement, project: Project, editor: Editor?): PsiElement? {
        if (editor != null) ByInvertBoolean.start(project, editor, element.containingFile)
        return null
    }

    // Never asked: adjustElement leaves the platform's processor no element to invert.

    override fun collectRefElements(element: PsiElement, renameProcessor: RenameProcessor?, newName: String, elementsToInvert: MutableCollection<in PsiElement>) = Unit

    override fun getElementToInvert(namedElement: PsiElement, expression: PsiElement): PsiElement? = null

    override fun replaceWithNegatedExpression(expression: PsiElement) = Unit

    override fun invertElementInitializer(`var`: PsiElement) = Unit
}

internal object ByInvertBoolean {

    val title: String get() = RefactoringBundle.message("invert.boolean.title")

    /** Inverts the boolean at [editor]'s caret, asking for its new name. */
    fun start(project: Project, editor: Editor, file: PsiFile) {
        val virtualFile = file.virtualFile ?: return
        val site = ByProjectRefactorings.site(project, editor, virtualFile, title) ?: return
        if (!ByExperimental.advertises(site.server, ByExperimental.INVERT_BOOLEAN)) {
            return ByProjectRefactorings.error(project, editor, title, BasedPythonBundle.message("refactoring.by.invertBoolean.unsupported"))
        }
        val hash = ByTextHash.of(site.document.immutableCharSequence)
        val asked = ByProjectRefactorings.ask(site, title, "by/prepareInvertBoolean") {
            it.prepareInvertBoolean(ByPrepareInvertBooleanParams(site.identifier, site.position, hash))
        }
        ByProjectRefactorings.unanswered(asked, "by/prepareInvertBoolean", BasedPythonBundle.message("refactoring.by.invertBoolean.nothing"))
            ?.let { return ByProjectRefactorings.error(project, editor, title, it) }
        val prepared = (asked as ByAsked.Answer).value
        if (prepared.range?.let { getRangeInDocument(site.document, it) } == null) {
            return ByProjectRefactorings.error(project, editor, title, BasedPythonBundle.message("refactoring.by.invertBoolean.nothing"))
        }
        ByInvertBooleanDialog(project, site, file, prepared).show()
    }

    /**
     * `by/invertBoolean`'s answer for renaming the inverted boolean to [newName], as the edit to make.
     * Every document is saved first, as for every project-wide refactoring ([ByProjectRefactorings.askEdit]).
     */
    fun edit(site: ByRefactoringSite, newName: String): ByProjectEdit {
        FileDocumentManager.getInstance().saveAllDocuments()
        val params = ByInvertBooleanParams(site.identifier, site.position, newName, ByTextHash.of(site.document.immutableCharSequence))
        val asked = ByProjectRefactorings.ask(site, title, "by/invertBoolean", ByProjectRefactorings.EDIT_TIMEOUT_MS) { it.invertBoolean(params) }
        return when (asked) {
            is ByAsked.Answer -> ByProjectEdit.Edit(asked.value, emptyList())
            is ByAsked.Refused -> ByProjectEdit.Refused(asked.message, emptyList())
            else -> ByProjectEdit.Failed(
                ByProjectRefactorings.unanswered(asked, "by/invertBoolean", BasedPythonBundle.message("refactoring.by.project.declined", "by/invertBoolean"))!!,
            )
        }
    }
}

/**
 * Invert Boolean's dialog, as the platform's: the name of the inverted boolean, pre-filled with the
 * one `by` proposes and selected, checked by `by/checkName` once typing pauses ([ByRenameNames]).
 */
internal class ByInvertBooleanDialog(
    project: Project,
    private val site: ByRefactoringSite,
    private val subject: PsiFile,
    private val prepared: ByPreparedInvertBoolean,
) : RefactoringDialog(project, true) {

    @get:TestOnly
    val nameField = JBTextField(prepared.newName).apply { selectAll() }

    private val checking = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

    /** Where the boolean's name starts, as `by/checkName` is asked about it. */
    private val nameOffset: Int = getRangeInDocument(site.document, prepared.range!!)!!.startOffset

    init {
        title = ByInvertBoolean.title
        init()
        nameField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                checking.cancelAllRequests()
                checking.addRequest({ validateButtonsAsync() }, CHECK_DELAY_MS)
            }
        })
        validateButtonsAsync()
    }

    override fun createNorthPanel(): JComponent = panel {
        // "Name of inverted variable:", as the platform's dialog says it, of what by says the name is
        val what = prepared.kind?.let { "$it '${prepared.name}'" } ?: "'${prepared.name}'"
        row(UIUtil.removeMnemonic(RefactoringBundle.message("invert.boolean.name.of.inverted.element", what))) {
            cell(nameField).align(AlignX.FILL)
        }
    }

    override fun createCenterPanel(): JComponent? = null

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun hasHelpAction(): Boolean = false

    /** Off the EDT, in the platform's non-blocking read action: the name, as `by` judges it. */
    override fun canRun() {
        val newName = nameField.text.trim()
        if (newName.isEmpty()) throw ConfigurationException(null)
        if (ApplicationManager.getApplication().isDispatchThread) return
        val verdict = ByRenameNames.validate(site.server, site.project, site.file, site.document, nameOffset, newName)
        if (verdict is ByNameVerdict.Invalid) throw ConfigurationException(verdict.reason)
    }

    override fun validateButtons() = validateButtonsAsync()

    override fun doAction() {
        val edited = ByInvertBoolean.edit(site, nameField.text.trim())
        ByProjectRefactorings.processor(project, ByInvertBoolean.title, subject, edited)?.let(::invokeRefactoring)
    }

    private companion object {
        const val CHECK_DELAY_MS = 300
    }
}
