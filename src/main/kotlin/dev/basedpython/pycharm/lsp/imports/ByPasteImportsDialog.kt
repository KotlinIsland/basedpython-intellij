package dev.basedpython.pycharm.lsp.imports

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.JBUI
import dev.basedpython.pycharm.lsp.ext.ByCopiedImport
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * *Insert imports on paste: Ask* — which of the imports the pasted code is missing to add, all of
 * them ticked. Each is listed as the import it is, the way it will be written.
 */
internal class ByPasteImportsDialog private constructor(
    project: Project,
    private val imports: List<ByCopiedImport>,
) : DialogWrapper(project, true) {

    private val list = CheckBoxList<ByCopiedImport>().apply {
        imports.forEach { addItem(it, statementOf(it), true) }
    }

    init {
        title = BasedPythonBundle.message("paste.imports.dialog.title")
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel(BasedPythonBundle.message("paste.imports.dialog.label")), BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(list), BorderLayout.CENTER)
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    private fun chosen(): List<ByCopiedImport> = imports.filter { list.isItemSelected(it) }

    companion object {

        /** The imports of [imports] the user chose to add; none when the dialog was cancelled. */
        @RequiresEdt
        fun choose(project: Project, imports: List<ByCopiedImport>): List<ByCopiedImport> {
            val dialog = ByPasteImportsDialog(project, imports)
            return if (dialog.showAndGet()) dialog.chosen() else emptyList()
        }

        /** [import] as the statement that adds it: `from pathlib import Path`, `import numpy as np`. */
        fun statementOf(import: ByCopiedImport): String {
            val member = import.member
            return when {
                member == null && import.module.substringBefore('.') == import.name -> "import ${import.module}"
                member == null -> "import ${import.module} as ${import.name}"
                member == import.name -> "from ${import.module} import $member"
                else -> "from ${import.module} import $member as ${import.name}"
            }
        }
    }
}
