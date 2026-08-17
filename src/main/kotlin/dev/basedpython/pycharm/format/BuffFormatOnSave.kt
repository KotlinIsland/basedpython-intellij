package dev.basedpython.pycharm.format

import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle

// ---------------------------------------------------------------------------
// The action itself
// ---------------------------------------------------------------------------

/**
 * Applies the project's lint fixes to each document as it is saved.
 *
 * Reformatting and import tidying are not here: *Reformat code* and *Optimize imports* already sit
 * at the top of the same list, and both reach `buff` for the files this plugin owns — a third and
 * fourth row saying the same thing would be a puzzle rather than a choice.
 *
 * [DocumentUpdatingActionOnSave] rather than the older `processDocuments`, because the platform
 * waits for this to finish before it saves the document. The previous implementation shelled out to
 * `buff format` from a background task it did not wait on, so the save raced the formatter and the
 * formatter's output was written to disk behind the editor.
 */
internal class BuffFormatOnSaveAction : DocumentUpdatingActionOnSave() {

  override val presentableName: String = BasedPythonBundle.message("actionOnSave.cleanupName")

  override fun isEnabledForProject(project: Project): Boolean =
    BasedPythonSettings.getInstance(project).fixAllOnSave

  override suspend fun updateDocument(project: Project, document: Document) {
    if (!BasedPythonSettings.getInstance(project).fixAllOnSave) return

    val file = fileOf(document) ?: return
    // No file-type test of its own: the fixes apply to every file the formatter/linter server is
    // given — `.py` and `.pyi` as much as `.by` and `.byi` — and that server's descriptor is the
    // one place that says which those are.
    ByCleanup.run(project, file, document, ByCleanupOp.FixAll)
  }
}

// ---------------------------------------------------------------------------
// The row under Settings | Tools | Actions on Save
// ---------------------------------------------------------------------------

internal class BuffFormatOnSaveInfoProvider : ActionOnSaveInfoProvider() {
  override fun getActionOnSaveInfos(context: ActionOnSaveContext): List<ActionOnSaveInfo> =
    listOf(FixAllOnSaveInfo(context))
}

private class FixAllOnSaveInfo(context: ActionOnSaveContext) : ActionOnSaveInfo(context) {

  private val settings get() = BasedPythonSettings.getInstance(project)

  // Shadows the persisted value until Apply is clicked.
  private var uiEnabled: Boolean = settings.fixAllOnSave

  override fun getActionOnSaveName(): String = BasedPythonBundle.message("actionOnSave.fixAllName")

  override fun isActionOnSaveEnabled(): Boolean = uiEnabled

  override fun setActionOnSaveEnabled(enabled: Boolean) {
    uiEnabled = enabled
  }

  override fun isModified(): Boolean = uiEnabled != settings.fixAllOnSave

  override fun apply() {
    settings.fixAllOnSave = uiEnabled
  }
}
