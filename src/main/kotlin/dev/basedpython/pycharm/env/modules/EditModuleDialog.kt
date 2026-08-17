package dev.basedpython.pycharm.env.modules

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CheckBoxList
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * *Edit module*: the metadata a module declares about itself, and which of its siblings depend on it.
 *
 * ### The two halves are written by different things
 *
 * The metadata fields end up as an edit to this module's own `pyproject.toml`, made by the plugin,
 * because uv has no command that sets a project's version. The dependency checkboxes end up as
 * `uv add --package` / `uv remove --package`, because uv does — and doing those by hand would mean
 * writing the `[tool.uv.sources] … { workspace = true }` entry that makes a sibling resolve locally,
 * which is uv's job and is easy to get subtly wrong. See [TomlEdits] for where that line is drawn.
 *
 * ### Why the name cannot be changed here
 *
 * Renaming a module is five edits — the directory, `[project] name`, the `members` entry, the import
 * package under `src/`, and every sibling that declares it — and the fifth one has a sixth behind
 * it: the `import` statements in code, which only a language server that resolves modules can find.
 * `by` has symbol rename and does not implement the file-operation requests
 * (`workspace/willRenameFiles`) that would let it answer for a renamed module, so a rename offered
 * here would be one that quietly leaves imports pointing at a name that no longer exists. The field
 * is shown and disabled rather than hidden, so that the answer to "can I rename it" is on screen.
 */
internal class EditModuleDialog(
    project: Project,
    private val module: ProjectModule,
    private val layout: ModuleLayout,
) : DialogWrapper(project) {

    private val versionField = JBTextField(module.version.orEmpty(), 16)

    private val descriptionField = JBTextField(module.description.orEmpty(), 24)

    private val requiresPythonField = JBTextField(module.requiresPython.orEmpty(), 16)

    /** The other modules, ticked when they declare this one. */
    private val dependents = CheckBoxList<String>()

    private val candidates: List<ProjectModule> = layout.possibleDependents(module)

    init {
        title = BasedPythonBundle.message("modules.edit.title", module.name)
        candidates.forEach { candidate ->
            dependents.addItem(candidate.name, candidate.name, candidate.dependsOn(module.name).isNotEmpty())
        }
        init()
    }

    override fun getPreferredFocusedComponent(): JComponent = versionField

    override fun createCenterPanel(): JComponent = panel {
        row(BasedPythonBundle.message("modules.edit.name")) {
            cell(JBTextField(module.name, 24).apply { isEditable = false })
        }.rowComment(BasedPythonBundle.message("modules.edit.name.hint"))

        row(BasedPythonBundle.message("modules.edit.location")) {
            cell(
                JBLabel(module.relativePath.ifEmpty { "." }).apply {
                    foreground = UIUtil.getContextHelpForeground()
                },
            )
        }

        row(BasedPythonBundle.message("modules.edit.version")) {
            cell(versionField).align(AlignX.FILL)
        }

        row(BasedPythonBundle.message("modules.edit.description")) {
            cell(descriptionField).align(AlignX.FILL)
        }

        row(BasedPythonBundle.message("modules.edit.requiresPython")) {
            cell(requiresPythonField).align(AlignX.FILL)
        }.rowComment(BasedPythonBundle.message("modules.edit.requiresPython.hint"))

        // Nothing else in the project to depend on it — a single-module project, or the root.
        if (candidates.isNotEmpty()) {
            row(BasedPythonBundle.message("modules.edit.dependents")) {
                cell(
                    JBScrollPane(dependents).apply {
                        preferredSize = JBUI.size(320, 120)
                    },
                ).align(AlignX.FILL)
            }.rowComment(BasedPythonBundle.message("modules.edit.dependents.hint"))
        }
    }

    /** What the module should look like afterwards, or null when the dialog was cancelled. */
    fun ask(): ModuleOperations.ModuleEdit? {
        if (!showAndGet()) return null
        return ModuleOperations.ModuleEdit(
            version = versionField.text.trim(),
            description = descriptionField.text.trim(),
            requiresPython = requiresPythonField.text.trim(),
            dependents = candidates
                .map { it.name }
                .filter { dependents.isItemSelected(it) }
                .toSet(),
        )
    }

    /**
     * The two fields that can be made to say something a resolver will reject.
     *
     * **A version that was there cannot be cleared.** An empty value removes the key, and a project
     * that declares a build backend and no version is one uv refuses to build at all — so clearing
     * the field would be a way to break a module from a settings dialog. A module that never had a
     * static version is left alone: that is a project declaring `dynamic = ["version"]`, where the
     * absence is the point.
     *
     * **A `requires-python` that is not a specifier** is not rejected by the manifest; it is
     * rejected by the resolver, later, in a message about the project rather than about the field
     * that caused it. Checked loosely — anything starting with a comparison operator or a digit is
     * let through — because the full PEP 440 grammar is not this dialog's to enforce.
     */
    override fun doValidate(): ValidationInfo? {
        if (versionField.text.isBlank() && !module.version.isNullOrBlank()) {
            return ValidationInfo(BasedPythonBundle.message("modules.edit.error.noVersion"), versionField)
        }

        val requires = requiresPythonField.text.trim()
        if (requires.isEmpty()) return null
        val first = requires.first()
        if (first.isDigit() || first in "<>=!~^") return null
        return ValidationInfo(BasedPythonBundle.message("modules.edit.error.requiresPython"), requiresPythonField)
    }
}
