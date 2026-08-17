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
 * ### The name, and what it costs to change
 *
 * Renaming a module is six edits: the directory, the import package under `src/`, `[project] name`,
 * the `members` entry, every sibling that declares it — and the `import` statements in code, which
 * are the ones no editor can find on its own. That last one is asked of `by`
 * (`workspace/willRenameFiles`), which resolves every import in the project against the same search
 * paths the checker uses and can tell a use of the module from a local variable spelled like it.
 *
 * So the field is editable exactly when a running `by` says it can answer that question, and
 * disabled with the reason when it cannot. A rename that moved the directory and left every import
 * naming the old one would be worse than no rename at all: a broken project, made by a button that
 * looked like it worked.
 */
internal class EditModuleDialog(
    private val project: Project,
    private val module: ProjectModule,
    private val layout: ModuleLayout,
) : DialogWrapper(project) {

    /**
     * Editable only when the server can say what the rename costs — see the class documentation.
     *
     * Read once, when the dialog opens, rather than on each keystroke: it is a question about the
     * server that started with the project, and a field that became editable halfway through typing
     * would be stranger than one that never did.
     */
    private val canRename: Boolean = ModuleImportEdits.isSupported(project) && !module.isRoot

    private val nameField = JBTextField(module.name, 24).apply { isEditable = canRename }

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

    override fun getPreferredFocusedComponent(): JComponent = if (canRename) nameField else versionField

    override fun createCenterPanel(): JComponent = panel {
        row(BasedPythonBundle.message("modules.edit.name")) {
            cell(nameField)
        }.rowComment(
            BasedPythonBundle.message(
                if (canRename) "modules.edit.name.hint" else "modules.edit.name.hint.unsupported",
            ),
        )

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
            newName = nameField.text.trim().takeIf { canRename && it != module.name },
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
        val name = nameField.text.trim()
        if (canRename && name != module.name) {
            if (!ModuleNames.isValid(name)) {
                return ValidationInfo(BasedPythonBundle.message("modules.edit.error.badName"), nameField)
            }
            if (layout.byName(name) != null) {
                return ValidationInfo(BasedPythonBundle.message("modules.edit.error.nameTaken", name), nameField)
            }
        }

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
