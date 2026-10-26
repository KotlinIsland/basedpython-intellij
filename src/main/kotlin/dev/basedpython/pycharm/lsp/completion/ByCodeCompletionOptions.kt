package dev.basedpython.pycharm.lsp.completion

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.UiDslUnnamedConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * basedpython's part of *Settings | Editor | General | Code Completion*: whether completion offers
 * names the file does not import yet, with the import — `by`'s `completions.autoImport`, as
 * *Python | Suggest importable names in basic completion* is `.py`'s — and whether it lists `by`'s
 * postfix templates ([ByPostfixCompletion]).
 *
 * IDE-wide, as the page's other options are. `by` reads it once, at start, and has no
 * `workspace/didChangeConfiguration`, so a change restarts the servers of every basedpython project
 * open.
 */
internal class ByCodeCompletionOptions : UiDslUnnamedConfigurable.Simple(), Configurable {

    override fun getDisplayName(): String = BasedPythonBundle.message("completion.options.group")

    override fun Panel.createContent() {
        val settings = BasedPythonAppSettings.getInstance()
        group(BasedPythonBundle.message("completion.options.group")) {
            row {
                lateinit var box: JBCheckBox
                checkBox(BasedPythonBundle.message("completion.options.importableNames"))
                    .comment(BasedPythonBundle.message("completion.options.importableNames.comment"))
                    .also { box = it.component }
                    .onReset { box.isSelected = settings.suggestImportableNames }
                    .onIsModified { box.isSelected != settings.suggestImportableNames }
                    .onApply {
                        if (box.isSelected == settings.suggestImportableNames) return@onApply
                        settings.suggestImportableNames = box.isSelected
                        for (project in ProjectManager.getInstance().openProjects) {
                            if (BasedPythonProjectDetector.isPythonProject(project)) BasedPythonLspReloader.getInstance(project).onSettingsChanged()
                        }
                    }
            }
            row {
                // read by the completion as it lists `by`'s items, so nothing restarts
                checkBox(BasedPythonBundle.message("completion.options.postfix"))
                    .comment(BasedPythonBundle.message("completion.options.postfix.comment"))
                    .bindSelected(settings::offerPostfixTemplates)
            }
        }
    }
}
