package dev.basedpython.pycharm.run.test.node

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.testFramework.LightVirtualFile
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Opens what the last `--collect-only` actually ran and printed, in a read-only editor tab.
 *
 * The tree shows what was collected; this shows *why* that is what was collected. It is the first
 * thing to reach for when the view disagrees with a `pytest --collect-only` run by hand, because
 * the command, its working directory and pytest's own rootdir line are all in there.
 */
internal class ByShowCollectionOutputAction(private val project: Project) : DumbAwareAction(
    BasedPythonBundle.messagePointer("testNodes.action.viewOutput"),
    BasedPythonBundle.messagePointer("testNodes.action.viewOutput.description"),
    AllIcons.Actions.Show,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) = show(project)

    companion object {

        /** The project's one output tab, so asking twice refreshes it instead of stacking tabs. */
        private val OUTPUT_FILE = Key.create<LightVirtualFile>("basedpython.collectOutput")

        /**
         * Opens the output of the last collection, refreshing the tab if it is already open.
         *
         * One file per project rather than a new one per look: this gets opened, read, and left
         * open while the user fixes whatever it revealed, and a second look should update that tab
         * rather than add another beside it. The text is rewritten on every call, so the tab always
         * shows the latest run — and carries the time that run started, for the case where it is
         * left open across a Refresh nobody told it about.
         */
        fun show(project: Project) {
            val text = ByCollectionOutput.render(ByTestNodeService.getInstance(project).lastRuns)
            val file = project.getUserData(OUTPUT_FILE)
                ?: LightVirtualFile(ByCollectionOutput.FILE_NAME, PlainTextFileType.INSTANCE, "")
                    .also { project.putUserData(OUTPUT_FILE, it) }
            val document = FileDocumentManager.getInstance().getDocument(file)
            if (document == null) {
                file.setContent(null, text, false)
            } else {
                // Read-only except for the moment it is rewritten: a log of something that already
                // happened, where an accidental keystroke can only lose what it said.
                WriteAction.run<RuntimeException> {
                    document.setReadOnly(false)
                    document.setText(text)
                    document.setReadOnly(true)
                }
            }
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }
}
