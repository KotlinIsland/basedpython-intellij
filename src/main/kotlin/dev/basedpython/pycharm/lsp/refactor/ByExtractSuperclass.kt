package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.ui.MemberSelectionPanelBase
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.lsp.ext.ByExtractSuperclassParams
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * Extract Superclass in a `.by` file, as `.py`'s: the new class's name and the members to move into
 * it, from the platform's member selection table filled by `by/prepareMoveMembers`, the member at
 * the caret checked. `by/extractSuperclass` writes the class directly above the class in its module,
 * with the class's bases, and makes it the class's one base — so, unlike `.py`'s, there is no
 * directory or module to choose — and refuses, with the reason, what would change what the program
 * means.
 */
internal class ByExtractSuperclassHandler : RefactoringActionHandler {

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val title = RefactoringBundle.message("extract.superclass.title")
        val virtualFile = file.virtualFile ?: return
        val site = ByProjectRefactorings.site(project, editor, virtualFile, title) ?: return
        val asked = ByProjectRefactorings.ask(site, title, "by/prepareMoveMembers") { it.prepareMoveMembers(site.params) }
        ByProjectRefactorings.unanswered(asked, "by/prepareMoveMembers", BasedPythonBundle.message("refactoring.by.members.noClass"))
            ?.let { return ByProjectRefactorings.error(project, editor, title, it) }
        val members = (asked as ByAsked.Answer).value
        val owner = members.owner?.name ?: return
        // a base is not a member to extract: the new class takes all of them
        val infos = ByMoveMembers.memberInfos(project, members, site, editor.caretModel.offset, bases = false)
        ByExtractSuperclassDialog(project, site, file, owner, infos).show()
    }

    /** Never called with anything to do: `by` is asked about a caret, and only an editor has one. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit
}

/** Extract Superclass's dialog: the class it extracts from, the new class's name, and the members. */
internal class ByExtractSuperclassDialog(
    project: Project,
    private val site: ByRefactoringSite,
    private val subject: PsiFile,
    private val owner: String,
    private val infos: List<ByMemberInfo>,
) : RefactoringDialog(project, true) {

    private val name = JBTextField().apply {
        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = validateButtons()
        })
    }

    init {
        title = RefactoringBundle.message("extract.superclass.title")
        init()
        validateButtons()
    }

    override fun createNorthPanel(): JComponent = panel {
        row(UIUtil.removeMnemonic(RefactoringBundle.message("extract.superclass.from"))) { label(owner) }
        row(UIUtil.removeMnemonic(RefactoringBundle.message("superclass.name"))) { cell(name).align(AlignX.FILL) }
    }

    override fun createCenterPanel(): JComponent =
        MemberSelectionPanelBase(RefactoringBundle.message("members.to.form.superclass"), ByMemberSelectionTable(infos))

    override fun getPreferredFocusedComponent(): JComponent = name

    override fun hasHelpAction(): Boolean = false

    /** Extract Superclass writes the class, and nothing to preview but the class itself. */
    override fun hasPreviewButton(): Boolean = false

    override fun areButtonsValid(): Boolean = name.text.isNotBlank()

    override fun doAction() {
        val members = checkedNames(infos)
        if (members.isEmpty()) return CommonRefactoringUtil.showErrorMessage(title, BasedPythonBundle.message("refactoring.by.members.noneChecked"), null, project)
        val edited = ByProjectRefactorings.askEdit(site, title, "by/extractSuperclass") {
            it.extractSuperclass(ByExtractSuperclassParams(site.identifier, site.position, members, name.text.trim()))
        }
        ByProjectRefactorings.processor(project, title, subject, edited)?.let(::invokeRefactoring)
    }
}
