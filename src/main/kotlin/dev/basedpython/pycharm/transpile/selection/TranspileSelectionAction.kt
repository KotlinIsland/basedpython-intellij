package dev.basedpython.pycharm.transpile.selection

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.transpile.ByTranspile
import dev.basedpython.pycharm.util.BasedPythonBundle
import dev.basedpython.pycharm.lang.BasedPythonFileType
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.BorderFactory
import java.awt.BorderLayout
import java.awt.Dimension

/**
 * Action: "Transpile Selection".
 *
 * Transpiles JUST the currently selected basedpython snippet to Python and shows the result in a
 * read-only popup with a "Copy" button.
 *
 * The selection travels to the running `by` server as the text of the request. It used to be
 * written to a temp `.by` file with the CLI run over it, which is the thing a fragment made look
 * necessary and never was: a request can carry source, and [CommonDataKeys.VIRTUAL_FILE] is only
 * there to say which document the fragment came from, so the request reaches a server at all.
 */
class TranspileSelectionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        e.presentation.isEnabledAndVisible =
            file != null && !file.isDirectory && isByFile(file) && hasSelection
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText ?: return
        if (selection.isBlank()) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Transpiling selection", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    val python = ByTranspile.sourceOrNotifySnippet(
                        project,
                        file,
                        selection,
                        failureTitle = BasedPythonBundle.message("notification.transpileFailed.title"),
                    ) ?: return

                    ApplicationManager.getApplication().invokeLater {
                        showPopup(project, editor, python)
                    }
                }
            },
        )
    }

    private fun showPopup(project: Project, editor: Editor, python: String) {
        if (project.isDisposed) return

        val textArea = JTextArea(python).apply {
            isEditable = false
            lineWrap = false
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }
        val scroll = JScrollPane(textArea).apply {
            preferredSize = Dimension(640, 360)
        }

        val panel = JPanel(BorderLayout()).apply {
            add(scroll, BorderLayout.CENTER)
        }

        val copyButton = JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(python))
            }
        }
        val buttonBar = JPanel(BorderLayout()).apply {
            add(copyButton, BorderLayout.EAST)
            border = BorderFactory.createEmptyBorder(0, 8, 8, 8)
        }
        panel.add(buttonBar, BorderLayout.SOUTH)

        val content: JComponent = panel
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, textArea)
            .setTitle("Transpiled Python")
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showInBestPositionFor(editor)
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
