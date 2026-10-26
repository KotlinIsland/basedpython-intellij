package dev.basedpython.pycharm.lsp.refactor

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.classMembers.MemberInfoBase
import com.intellij.refactoring.ui.AbstractMemberSelectionTable
import com.intellij.refactoring.ui.MemberSelectionPanelBase
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.icons.RowIcon
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.ByClassItem
import dev.basedpython.pycharm.lsp.ext.ByMemberItem
import dev.basedpython.pycharm.lsp.ext.ByMovableMembers
import dev.basedpython.pycharm.lsp.ext.ByPullMembersUpParams
import dev.basedpython.pycharm.lsp.ext.ByPushMembersDownParams
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Pull Members Up and Push Members Down in a `.by` file, as `.py`'s: the platform's member selection
 * table, filled with the members `by/prepareMoveMembers` lists for the class at the caret, the one
 * the caret is on checked; the members are moved by `by/pullMembersUp` or `by/pushMembersDown`.
 *
 * What may move is `by`'s to say, not the dialog's: each is refused, with the reason, where the
 * program would no longer mean what it did — a pulled-up member reading what the superclass lacks, a
 * pushed-down member something reads off the class itself — and the dialog stays open on a refusal
 * for another choice.
 */
internal object ByMoveMembers {

    /** What the platform's actions run: [direction] for the class at [editor]'s caret. */
    fun perform(project: Project, editor: Editor, file: PsiFile, direction: Direction) {
        val title = direction.title
        val virtualFile = file.virtualFile ?: return
        val site = ByProjectRefactorings.site(project, editor, virtualFile, title) ?: return
        val asked = ByProjectRefactorings.ask(site, title, "by/prepareMoveMembers") { it.prepareMoveMembers(site.params) }
        ByProjectRefactorings.unanswered(asked, "by/prepareMoveMembers", BasedPythonBundle.message("refactoring.by.members.noClass"))
            ?.let { return ByProjectRefactorings.error(project, editor, title, it) }
        val members = (asked as ByAsked.Answer).value
        val owner = members.owner?.name ?: return
        val targets = when (direction) {
            Direction.Up -> members.superclasses
            Direction.Down -> members.subclasses
        }.orEmpty()
        if (targets.isEmpty()) {
            return ByProjectRefactorings.error(project, editor, title, BasedPythonBundle.message(direction.noTargetsKey, owner))
        }
        if (members.members.isNullOrEmpty()) {
            return ByProjectRefactorings.error(project, editor, title, BasedPythonBundle.message("refactoring.by.members.none", owner))
        }
        // a base moves only up, into the superclass's bases
        val infos = memberInfos(project, members, site, editor.caretModel.offset, bases = direction == Direction.Up)
        when (direction) {
            Direction.Up -> ByPullUpDialog(project, site, file, owner, targets, infos)
            Direction.Down -> ByPushDownDialog(project, site, file, owner, infos)
        }.show()
    }

    /**
     * [members]'s members as the table's rows, the one whose name holds [caret] checked — what the
     * user asked from, as `.py` checks the member at the caret. The class's bases are rows only where
     * [bases] says they can move, as Pull Members Up moves one into the superclass's bases.
     */
    fun memberInfos(project: Project, members: ByMovableMembers, site: ByRefactoringSite, caret: Int, bases: Boolean): List<ByMemberInfo> =
        members.members.orEmpty().filter { it.name != null && (bases || !it.isBase) }.map { member ->
            ByMemberInfo(ByMemberElement(project, member)).apply {
                val at = member.range?.let { getRangeInDocument(site.document, it) }
                isChecked = at != null && at.startOffset <= caret && caret <= at.endOffset
            }
        }

    enum class Direction(private val titleKey: String, val noTargetsKey: String) {
        Up("pull.members.up.title", "refactoring.by.members.pullUp.noTargets"),
        Down("push.members.down.title", "refactoring.by.members.pushDown.noTargets"),
        ;

        val title: String get() = RefactoringBundle.message(titleKey)
    }
}

/** Pull Members Up, from the platform's action. */
internal class ByPullUpHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) =
        ByMoveMembers.perform(project, editor, file, ByMoveMembers.Direction.Up)

    /** Never called with anything to do: `by` is asked about a caret, and only an editor has one. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit
}

/** Push Members Down, from the platform's action. */
internal class ByPushDownHandler : RefactoringActionHandler {
    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) =
        ByMoveMembers.perform(project, editor, file, ByMoveMembers.Direction.Down)

    /** Never called with anything to do: `by` is asked about a caret, and only an editor has one. */
    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) = Unit
}

/**
 * One of the members `by` listed, as the element the platform's member table holds a row for.
 *
 * Not a part of the file's tree — a `.by` file's tree holds no declarations — so it stands alone: no
 * file, always valid while the dialog is up, named and drawn as `by` names its kind.
 */
internal class ByMemberElement(private val project: Project, val member: ByMemberItem) : FakePsiElement() {
    override fun getParent(): PsiElement? = null
    override fun getContainingFile(): PsiFile? = null
    override fun getProject(): Project = project
    override fun getManager(): PsiManager = PsiManager.getInstance(project)
    override fun getLanguage(): Language = BasedPythonLanguage
    override fun isValid(): Boolean = !project.isDisposed
    override fun isPhysical(): Boolean = false
    override fun getName(): String? = member.name
    override fun getPresentableText(): String? = member.name
    override fun getIcon(open: Boolean): Icon = when (member.kind) {
        ByMemberItem.BASE -> AllIcons.Nodes.Class
        "attribute" -> AllIcons.Nodes.Field
        "property" -> AllIcons.Nodes.Property
        "classmethod", "staticmethod" -> AllIcons.Nodes.Static
        else -> AllIcons.Nodes.Method
    }
}

/** A row of the member table: the member and whether it is checked. */
internal class ByMemberInfo(member: ByMemberElement) : MemberInfoBase<ByMemberElement>(member) {
    init {
        // as `by` writes it in a list: a method's parameters, `fetch(self, key: str)`
        displayName = member.member.detail ?: member.name
        isStatic = member.member.kind == "staticmethod" || member.member.kind == "classmethod"
    }

    val name: String = member.name.orEmpty()
}

/**
 * The platform's member selection table over [ByMemberInfo]s, with no *Make abstract* column: `by`
 * moves a member as it is written.
 */
internal class ByMemberSelectionTable(infos: List<ByMemberInfo>) :
    AbstractMemberSelectionTable<ByMemberElement, ByMemberInfo>(infos, null, null) {

    override fun getAbstractColumnValue(memberInfo: ByMemberInfo): Any? = null

    override fun isAbstractColumnEditable(rowIndex: Int): Boolean = false

    override fun getOverrideIcon(memberInfo: ByMemberInfo): Icon = EMPTY_OVERRIDE_ICON

    /** Python has no visibility to draw. */
    override fun setVisibilityIcon(memberInfo: ByMemberInfo, icon: RowIcon) = Unit
}

/** The names of the members checked in [infos], in the table's order. */
internal fun checkedNames(infos: List<ByMemberInfo>): List<String> = infos.filter { it.isChecked }.map { it.name }

/** Pull Members Up's dialog: the superclass to pull into, nearest first, and the members. */
internal class ByPullUpDialog(
    project: Project,
    private val site: ByRefactoringSite,
    private val subject: PsiFile,
    private val owner: String,
    superclasses: List<ByClassItem>,
    private val infos: List<ByMemberInfo>,
) : RefactoringDialog(project, true) {

    private val target = ComboBox(superclasses.toTypedArray()).apply {
        renderer = textListCellRenderer { item: ByClassItem -> item.qualifiedName ?: item.name }
    }

    init {
        title = ByMoveMembers.Direction.Up.title
        init()
    }

    override fun createNorthPanel(): JComponent = panel {
        row { label(UIUtil.removeMnemonic(RefactoringBundle.message("pull.up.members.to", owner))) }
        row { cell(target).align(AlignX.FILL) }
    }

    override fun createCenterPanel(): JComponent =
        MemberSelectionPanelBase(RefactoringBundle.message("members.to.be.pulled.up"), ByMemberSelectionTable(infos))

    override fun hasHelpAction(): Boolean = false

    override fun doAction() {
        val members = checkedNames(infos)
        val superclass = (target.selectedItem as? ByClassItem)?.qualifiedName ?: return
        if (members.isEmpty()) return CommonRefactoringUtil.showErrorMessage(title, BasedPythonBundle.message("refactoring.by.members.noneChecked"), null, project)
        val edited = ByProjectRefactorings.askEdit(site, title, "by/pullMembersUp") {
            it.pullMembersUp(ByPullMembersUpParams(site.identifier, site.position, members, superclass))
        }
        ByProjectRefactorings.processor(project, title, subject, edited)?.let(::invokeRefactoring)
    }
}

/** Push Members Down's dialog: the members, pushed into every subclass of the project. */
internal class ByPushDownDialog(
    project: Project,
    private val site: ByRefactoringSite,
    private val subject: PsiFile,
    private val owner: String,
    private val infos: List<ByMemberInfo>,
) : RefactoringDialog(project, true) {

    init {
        title = ByMoveMembers.Direction.Down.title
        init()
    }

    override fun createNorthPanel(): JComponent = panel {
        row { label(BasedPythonBundle.message("refactoring.by.members.pushDown.from", owner)) }
    }

    override fun createCenterPanel(): JComponent =
        MemberSelectionPanelBase(RefactoringBundle.message("members.to.be.pushed.down.panel.title"), ByMemberSelectionTable(infos))

    override fun hasHelpAction(): Boolean = false

    override fun doAction() {
        val members = checkedNames(infos)
        if (members.isEmpty()) return CommonRefactoringUtil.showErrorMessage(title, BasedPythonBundle.message("refactoring.by.members.noneChecked"), null, project)
        val edited = ByProjectRefactorings.askEdit(site, title, "by/pushMembersDown") {
            it.pushMembersDown(ByPushMembersDownParams(site.identifier, site.position, members))
        }
        ByProjectRefactorings.processor(project, title, subject, edited)?.let(::invokeRefactoring)
    }
}
