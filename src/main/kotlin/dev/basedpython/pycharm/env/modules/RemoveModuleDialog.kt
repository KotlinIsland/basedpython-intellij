package dev.basedpython.pycharm.env.modules

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * *Remove module*: what is about to stop being true, and whether the files go with it.
 *
 * ### Why this is a dialog and not a Yes/No
 *
 * Because there are two different removals and the difference is not obvious from the button. Taking
 * a module out of the project means its siblings stop declaring it and the project stops listing it;
 * that is reversible, and it is what the platform's own *Remove module* does. Deleting the directory
 * is neither, so it is a checkbox — **off by default** — rather than something the same word does
 * both of.
 *
 * The dependents are named rather than counted. "Remove this" and "remove this and stop `web` and
 * `cli` depending on it" are different decisions, and the second one is the one actually on offer.
 */
internal class RemoveModuleDialog(
    project: Project,
    private val module: ProjectModule,
    private val dependents: List<ProjectModule>,
) : DialogWrapper(project) {

    private val deleteFiles = JBCheckBox(
        BasedPythonBundle.message("modules.remove.deleteFiles", module.relativePath.ifEmpty { "." }),
        false,
    )

    init {
        title = BasedPythonBundle.message("modules.remove.title", module.name)
        setOKButtonText(BasedPythonBundle.message("modules.remove.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            cell(JBLabel(BasedPythonBundle.message("modules.remove.message", module.name)))
        }

        if (dependents.isNotEmpty()) {
            row {
                cell(
                    JBLabel(
                        BasedPythonBundle.message(
                            "modules.remove.dependents",
                            dependents.joinToString(", ") { it.name },
                        ),
                    ),
                )
            }
        }

        row {
            cell(deleteFiles)
        }

        // Only worth saying when it is the case: a module covered by a glob cannot simply be
        // un-listed, so keeping its files means excluding the path instead — a different edit, and
        // one the user should not discover afterwards in a diff.
        if (module.memberEntry == null) {
            row {
                cell(
                    JBLabel(BasedPythonBundle.message("modules.remove.excludeNote", module.relativePath)).apply {
                        foreground = UIUtil.getContextHelpForeground()
                    },
                )
            }
        }
    }

    /** True to delete the files as well, false to only unlist, null when cancelled. */
    fun ask(): Boolean? {
        if (!showAndGet()) return null
        return deleteFiles.isSelected
    }
}
