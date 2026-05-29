package dev.basedpython.pycharm.format

import com.intellij.ide.actionsOnSave.ActionOnSaveContext
import com.intellij.ide.actionsOnSave.ActionOnSaveInfo
import com.intellij.ide.actionsOnSave.ActionOnSaveInfoProvider
import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.ActionOnSave
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings

// ---------------------------------------------------------------------------
// ActionOnSave — executes buff format when documents are saved
// ---------------------------------------------------------------------------

/**
 * Runs `buff format <path>` on every `.by` document that is being saved,
 * provided the user has enabled "Format .by files on save" in the
 * Actions on Save settings panel.
 *
 * The enabled flag is read from [BasedPythonSettings.formatOnSave] via
 * reflection so that this file compiles before the integrator adds the field;
 * if the field is absent the action is silently skipped (safe default: off).
 */
class BuffFormatOnSaveAction : ActionOnSave() {

    override fun isEnabledForProject(project: Project): Boolean =
        FormatOnSaveUtil.isEnabled(project)

    override fun processDocuments(project: Project, documents: Array<Document>) {
        if (!isEnabledForProject(project)) return

        val fdm = FileDocumentManager.getInstance()
        val toFormat = documents.mapNotNull { doc ->
            val vf = fdm.getFile(doc) ?: return@mapNotNull null
            if (vf.fileType == BasedPythonFileType.INSTANCE) vf else null
        }
        if (toFormat.isEmpty()) return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Formatting .by files with buff", false) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    for (vf in toFormat) {
                        indicator.text2 = vf.name
                        val path = vf.toNioPath()
                        val out = ByCli.runBuff(
                            project,
                            "format", path.toString(),
                            cwd = path.parent,
                            title = "buff format",
                        ) ?: continue
                        if (out.exitCode != 0) {
                            ByCli.notifyError(
                                project,
                                "buff format failed",
                                out.stderr.ifBlank { "exit ${out.exitCode}" },
                            )
                        } else {
                            VfsUtil.markDirtyAndRefresh(true, false, false, vf)
                        }
                    }
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// ActionOnSaveInfoProvider — registers the checkbox in the Actions on Save UI
// ---------------------------------------------------------------------------

class BuffFormatOnSaveInfoProvider : ActionOnSaveInfoProvider() {
    override fun getActionOnSaveInfos(context: ActionOnSaveContext): List<ActionOnSaveInfo> =
        listOf(BuffFormatOnSaveInfo(context))
}

/**
 * One-checkbox entry shown under Editor → Actions on Save.
 *
 * [apply] / [isModified] delegate to [FormatOnSaveUtil] so the persistent
 * state goes through [BasedPythonSettings.formatOnSave] (added reflectively).
 * The "settings" snapshot held by the parent [ActionOnSaveContext] is used for
 * the Apply/Revert cycle; here we keep a simple local dirty flag.
 */
class BuffFormatOnSaveInfo(context: ActionOnSaveContext) : ActionOnSaveInfo(context) {

    // Shadow of the persisted value, tracks UI state until Apply is clicked.
    private var uiEnabled: Boolean = FormatOnSaveUtil.isEnabled(project)

    override fun getActionOnSaveName(): String = "Format .by files with buff"

    override fun isActionOnSaveEnabled(): Boolean = uiEnabled

    override fun setActionOnSaveEnabled(enabled: Boolean) {
        uiEnabled = enabled
    }

    override fun isModified(): Boolean = uiEnabled != FormatOnSaveUtil.isEnabled(project)

    override fun apply() {
        FormatOnSaveUtil.setEnabled(project, uiEnabled)
    }
}

// ---------------------------------------------------------------------------
// Helper — reflective read/write of BasedPythonSettings.formatOnSave
// ---------------------------------------------------------------------------

internal object FormatOnSaveUtil {

    /** Reads `BasedPythonSettings.formatOnSave`; defaults to `false` if absent. */
    fun isEnabled(project: Project): Boolean {
        val settings = BasedPythonSettings.getInstance(project)
        return try {
            val field = settings.javaClass.getDeclaredField("formatOnSave")
            field.isAccessible = true
            field.getBoolean(settings)
        } catch (_: NoSuchFieldException) {
            // Field not yet added by the integrator — safe default: off
            false
        }
    }

    /** Writes `BasedPythonSettings.formatOnSave`; no-op if the field is absent. */
    fun setEnabled(project: Project, value: Boolean) {
        val settings = BasedPythonSettings.getInstance(project)
        try {
            val field = settings.javaClass.getDeclaredField("formatOnSave")
            field.isAccessible = true
            field.setBoolean(settings, value)
        } catch (_: NoSuchFieldException) {
            // Field not yet added — ignore
        }
    }
}
