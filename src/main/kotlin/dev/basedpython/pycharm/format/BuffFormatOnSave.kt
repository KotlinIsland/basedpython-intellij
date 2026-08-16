package dev.basedpython.pycharm.format

import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.DocumentUpdatingActionOnSave
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle

// ---------------------------------------------------------------------------
// The action itself
// ---------------------------------------------------------------------------

/**
 * Runs the enabled [ByCleanupOp]s over each `.by` document as it is saved.
 *
 * One action rather than one per pass, because the platform gives no ordering guarantee across
 * separately registered actions — they run in extension-point order — while these passes are not
 * interchangeable: the formatter has to go last. Registering one keeps the order here, where it can
 * be stated.
 *
 * [DocumentUpdatingActionOnSave] rather than the older `processDocuments`, because the platform
 * waits for this to finish before it saves the document. The previous implementation shelled out to
 * `buff format` from a background task it did not wait on, so the save raced the formatter and the
 * formatter's output was written to disk behind the editor.
 */
internal class BuffFormatOnSaveAction : DocumentUpdatingActionOnSave() {

  override val presentableName: String = BasedPythonBundle.message("actionOnSave.cleanupName")

  override fun isEnabledForProject(project: Project): Boolean =
    BasedPythonSettings.getInstance(project).cleanupOnSave.isNotEmpty()

  override suspend fun updateDocument(project: Project, document: Document) {
    val ops = BasedPythonSettings.getInstance(project).cleanupOnSave
    if (ops.isEmpty()) return

    val file = fileOf(document) ?: return
    if (file.fileType != BasedPythonFileType.INSTANCE) return

    ByCleanup.run(project, file, document, ops)
  }
}

// ---------------------------------------------------------------------------
// The rows under Settings | Tools | Actions on Save
// ---------------------------------------------------------------------------

internal class BuffFormatOnSaveInfoProvider : ActionOnSaveInfoProvider() {
  override fun getActionOnSaveInfos(context: ActionOnSaveContext): List<ActionOnSaveInfo> =
    listOf(
      CleanupOnSaveInfo(context, ByCleanupToggle.FormatAndOptimizeImports),
      CleanupOnSaveInfo(context, ByCleanupToggle.FixAll),
    )
}

/**
 * One row per pass, so each can be turned on by itself.
 *
 * Two rows behind one action is deliberate: the checkboxes are independent because the passes do
 * different things — one formats and tidies imports, the other applies the project's lint fixes —
 * but whichever are ticked run together, in the order [ByCleanupOp] fixes.
 */
private class CleanupOnSaveInfo(
  context: ActionOnSaveContext,
  private val toggle: ByCleanupToggle,
) : ActionOnSaveInfo(context) {

  private val settings get() = BasedPythonSettings.getInstance(project)

  private fun persisted(): Boolean = when (toggle) {
    ByCleanupToggle.FormatAndOptimizeImports -> settings.formatOnSave
    ByCleanupToggle.FixAll -> settings.fixAllOnSave
  }

  // Shadows the persisted value until Apply is clicked.
  private var uiEnabled: Boolean = persisted()

  override fun getActionOnSaveName(): String = when (toggle) {
    ByCleanupToggle.FormatAndOptimizeImports -> BasedPythonBundle.message("actionOnSave.formatName")
    ByCleanupToggle.FixAll -> BasedPythonBundle.message("actionOnSave.fixAllName")
  }

  override fun isActionOnSaveEnabled(): Boolean = uiEnabled

  override fun setActionOnSaveEnabled(enabled: Boolean) {
    uiEnabled = enabled
  }

  override fun isModified(): Boolean = uiEnabled != persisted()

  override fun apply() {
    when (toggle) {
      ByCleanupToggle.FormatAndOptimizeImports -> settings.formatOnSave = uiEnabled
      ByCleanupToggle.FixAll -> settings.fixAllOnSave = uiEnabled
    }
  }
}
