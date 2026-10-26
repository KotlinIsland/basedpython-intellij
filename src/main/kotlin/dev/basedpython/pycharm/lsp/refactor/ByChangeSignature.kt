package dev.basedpython.pycharm.lsp.refactor

import com.intellij.ui.components.JBTextField
import dev.basedpython.pycharm.lsp.ext.ByAnnotationEdit
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.changeSignature.ChangeSignatureHandler
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ColumnInfo
import com.intellij.util.ui.ListTableModel
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.ByChangeSignatureParams
import dev.basedpython.pycharm.lsp.ext.ByChangedParameter
import dev.basedpython.pycharm.lsp.ext.ByParameterKind
import dev.basedpython.pycharm.lsp.ext.BySignature
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.table.TableCellEditor

/**
 * Change Signature (Ctrl+F6) in a `.by` file: the function at the caret — in its name or
 * parameters, or in a name referring to it — its name, its return annotation, and its parameters as
 * `by/prepareChangeSignature` lists them, in a table the user adds to, removes from, reorders and
 * edits, each with its name, how it is passed, its default, its annotation and, for a new one, what
 * each call passes it. `by/changeSignature` rewrites the function and every call of it in the
 * project as python binds the call, renaming the function wherever the project refers to it, or
 * refuses — with the file and line of a call that cannot be rewritten to mean what it meant — and the
 * dialog stays open on a refusal for another try.
 *
 * A new name and an annotation are sent only where they change: a `by` that predates them refuses
 * the request with them in it, and its error is what the dialog says.
 */
internal class ByChangeSignatureHandler : ChangeSignatureHandler {

    /**
     * Any element of a `.by` file's text: which function it is in or refers to is `by`'s to answer,
     * once the action runs, and the platform asks this on the EDT as it updates the action.
     */
    override fun findTargetMember(element: PsiElement): PsiElement? =
        element.takeIf { it !is PsiFileSystemItem && it.containingFile?.language?.isKindOf(BasedPythonLanguage) == true }

    override fun invoke(project: Project, editor: Editor, file: PsiFile, dataContext: DataContext?) {
        val virtualFile = file.virtualFile ?: return
        perform(project, editor, virtualFile, file, editor.caretModel.offset)
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val element = elements.singleOrNull() ?: return
        val file = element.containingFile ?: return
        val virtualFile = file.virtualFile ?: return
        // the caret, when the editor shows the element's file: what the user asked about
        val editor = dataContext?.let { CommonDataKeys.EDITOR.getData(it) }
            ?.takeIf { FileDocumentManager.getInstance().getFile(it.document) == virtualFile }
        perform(project, editor, virtualFile, file, editor?.caretModel?.offset ?: element.textOffset)
    }

    override fun getTargetNotFoundMessage(): String = BasedPythonBundle.message("refactoring.by.signature.noFunction")

    private fun perform(project: Project, editor: Editor?, file: com.intellij.openapi.vfs.VirtualFile, psiFile: PsiFile, offset: Int) {
        val title = RefactoringBundle.message("changeSignature.refactoring.name")
        val site = ByProjectRefactorings.site(project, editor, file, offset, title) ?: return
        val asked = ByProjectRefactorings.ask(site, title, "by/prepareChangeSignature") { it.prepareChangeSignature(site.params) }
        ByProjectRefactorings.unanswered(asked, "by/prepareChangeSignature", targetNotFoundMessage)
            ?.let { return ByProjectRefactorings.error(project, editor, title, it) }
        val signature = (asked as ByAsked.Answer).value
        ByChangeSignatureDialog(project, site, psiFile, signature).show()
    }
}

/** One row of the parameter table: a parameter of the new list, and the one it was. */
internal class ByParameterRow(
    var name: String,
    /** The parameter's name before the change; `null` for one added. */
    val oldName: String?,
    var kind: ByParameterKind,
    /** Its default, as source; blank for none. */
    var default: String,
    /** What each call passes a new parameter, as source; blank leaves it to its default. */
    var value: String,
    /** Its annotation as the source writes it; null for none. */
    val oldAnnotation: String?,
) {
    /** Its annotation as the user leaves it; blank for none. */
    var annotation: String = oldAnnotation.orEmpty()

    /** The parameter as `by/changeSignature` is sent it. */
    fun changed(): ByChangedParameter = ByChangedParameter(
        name = name.trim(),
        oldName = oldName,
        kind = kind.id,
        default = default.trim().ifEmpty { null },
        value = value.trim().takeIf { oldName == null && it.isNotEmpty() },
        annotation = ByAnnotationEdit.of(oldAnnotation, annotation),
    )

    companion object {
        /** [signature]'s parameters, as rows that change nothing yet. */
        fun of(signature: BySignature): List<ByParameterRow> = signature.parameters.orEmpty().mapNotNull { parameter ->
            val name = parameter.name ?: return@mapNotNull null
            val kind = ByParameterKind.of(parameter.kind) ?: return@mapNotNull null
            ByParameterRow(name, name, kind, parameter.default.orEmpty(), "", parameter.annotation)
        }
    }
}

/**
 * The parameter list [rows] describes, as a `def` would write it — `(width, height=1, *, scale=1.0)`
 * — for the dialog to show what the change makes of the signature. Only a picture of the user's
 * choices: whether it is a valid list is `by`'s to say when it is sent.
 */
internal fun signaturePreview(name: String, rows: List<ByParameterRow>, returns: String = ""): String {
    val parts = mutableListOf<String>()
    var keywordOnlyMarked = false
    rows.forEachIndexed { index, row ->
        if (row.kind == ByParameterKind.KeywordOnly && !keywordOnlyMarked && rows.none { it.kind == ByParameterKind.Variadic }) {
            parts += "*"
            keywordOnlyMarked = true
        }
        val annotation = row.annotation.trim().takeIf { it.isNotEmpty() }
        val default = row.default.trim().takeIf { it.isNotEmpty() }?.let { if (annotation != null) " = $it" else "=$it" }.orEmpty()
        val annotated = annotation?.let { "${row.name}: $it" } ?: row.name
        parts += when (row.kind) {
            ByParameterKind.Variadic -> "*$annotated"
            ByParameterKind.VariadicKeyword -> "**$annotated"
            else -> "$annotated$default"
        }
        val next = rows.getOrNull(index + 1)
        if (row.kind == ByParameterKind.PositionalOnly && next?.kind != ByParameterKind.PositionalOnly) parts += "/"
    }
    val arrow = returns.takeIf { it.isNotBlank() }?.let { " -> $it" }.orEmpty()
    return "$name(${parts.joinToString(", ")})$arrow"
}

/** Change Signature's dialog: the parameter table, and the signature it makes. */
internal class ByChangeSignatureDialog(
    project: Project,
    private val site: ByRefactoringSite,
    private val subject: PsiFile,
    private val signature: BySignature,
) : RefactoringDialog(project, true) {

    private val functionName = signature.name.orEmpty()

    private val nameField = JBTextField(functionName).apply {
        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = updatePreview()
        })
    }

    private val returnsField = JBTextField(signature.returns.orEmpty()).apply {
        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = updatePreview()
        })
    }

    private val model = ListTableModel<ByParameterRow>(
        arrayOf(NameColumn(), KindColumn(), DefaultColumn(), ValueColumn(), AnnotationColumn()),
        ByParameterRow.of(signature).toMutableList(),
    )

    private val table = TableView(model).apply { setShowGrid(false) }

    private val preview = JBLabel().apply { setCopyable(true) }

    init {
        title = RefactoringBundle.message("changeSignature.refactoring.name")
        model.addTableModelListener { updatePreview() }
        updatePreview()
        init()
    }

    private fun updatePreview() {
        preview.text = signaturePreview(nameField.text.trim(), model.items, returnsField.text.trim())
        validateButtons()
    }

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                table.stopEditing()
                model.addRow(ByParameterRow("", null, ByParameterKind.PositionalOrKeyword, "", "", null))
                val row = model.rowCount - 1
                table.selectionModel.setSelectionInterval(row, row)
                table.editCellAt(row, 0)
            }
            .createPanel()
        return panel {
            row(BasedPythonBundle.message("refactoring.by.signature.function")) { cell(nameField).align(AlignX.FILL) }
            row(BasedPythonBundle.message("refactoring.by.signature.returns")) { cell(returnsField).align(AlignX.FILL) }
            row { cell(decorated).align(Align.FILL) }.resizableRow()
            row(BasedPythonBundle.message("refactoring.by.signature.preview")) { cell(preview).align(AlignX.FILL) }
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = table

    override fun hasHelpAction(): Boolean = false

    override fun areButtonsValid(): Boolean = nameField.text.isNotBlank() && model.items.all { it.name.isNotBlank() }

    override fun doAction() {
        table.stopEditing()
        val parameters = model.items.map { it.changed() }
        val name = nameField.text.trim().takeIf { it != functionName }
        val returns = ByAnnotationEdit.of(signature.returns, returnsField.text)
        val edited = ByProjectRefactorings.askEdit(site, title, "by/changeSignature") {
            it.changeSignature(ByChangeSignatureParams(site.identifier, site.position, parameters, name, returns))
        }
        ByProjectRefactorings.processor(project, title, subject, edited)?.let(::invokeRefactoring)
    }

    private class NameColumn : ColumnInfo<ByParameterRow, String>(BasedPythonBundle.message("refactoring.by.signature.column.name")) {
        override fun valueOf(item: ByParameterRow): String = item.name
        override fun isCellEditable(item: ByParameterRow): Boolean = true
        override fun setValue(item: ByParameterRow, value: String) {
            item.name = value
        }
    }

    private class KindColumn : ColumnInfo<ByParameterRow, ByParameterKind>(BasedPythonBundle.message("refactoring.by.signature.column.kind")) {
        override fun valueOf(item: ByParameterRow): ByParameterKind = item.kind
        override fun isCellEditable(item: ByParameterRow): Boolean = true
        override fun setValue(item: ByParameterRow, value: ByParameterKind) {
            item.kind = value
        }

        override fun getEditor(item: ByParameterRow): TableCellEditor =
            DefaultCellEditor(ComboBox(ByParameterKind.entries.toTypedArray()).apply { renderer = kindRenderer() })

        override fun getRenderer(item: ByParameterRow) = object : javax.swing.table.DefaultTableCellRenderer() {
            override fun setValue(value: Any?) {
                text = (value as? ByParameterKind)?.let(::kindName).orEmpty()
            }
        }

        private fun kindRenderer() = com.intellij.ui.dsl.listCellRenderer.textListCellRenderer { kind: ByParameterKind -> kindName(kind) }
    }

    private class DefaultColumn : ColumnInfo<ByParameterRow, String>(BasedPythonBundle.message("refactoring.by.signature.column.default")) {
        override fun valueOf(item: ByParameterRow): String = item.default
        override fun isCellEditable(item: ByParameterRow): Boolean = true
        override fun setValue(item: ByParameterRow, value: String) {
            item.default = value
        }
    }

    /** What calls pass a parameter added; an existing one is passed what each call passes it already. */
    private class ValueColumn : ColumnInfo<ByParameterRow, String>(BasedPythonBundle.message("refactoring.by.signature.column.value")) {
        override fun valueOf(item: ByParameterRow): String = item.value
        override fun isCellEditable(item: ByParameterRow): Boolean = item.oldName == null
        override fun setValue(item: ByParameterRow, value: String) {
            item.value = value
        }
    }

    private class AnnotationColumn : ColumnInfo<ByParameterRow, String>(BasedPythonBundle.message("refactoring.by.signature.column.annotation")) {
        override fun valueOf(item: ByParameterRow): String = item.annotation
        override fun isCellEditable(item: ByParameterRow): Boolean = true
        override fun setValue(item: ByParameterRow, value: String) {
            item.annotation = value
        }
    }

    companion object {
        fun kindName(kind: ByParameterKind): String = BasedPythonBundle.message(
            when (kind) {
                ByParameterKind.PositionalOnly -> "refactoring.by.signature.kind.positionalOnly"
                ByParameterKind.PositionalOrKeyword -> "refactoring.by.signature.kind.positionalOrKeyword"
                ByParameterKind.Variadic -> "refactoring.by.signature.kind.variadic"
                ByParameterKind.KeywordOnly -> "refactoring.by.signature.kind.keywordOnly"
                ByParameterKind.VariadicKeyword -> "refactoring.by.signature.kind.variadicKeyword"
            },
        )
    }
}
