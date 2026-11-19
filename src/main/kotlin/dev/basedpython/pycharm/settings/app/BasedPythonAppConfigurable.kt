package dev.basedpython.pycharm.settings.app

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import dev.basedpython.pycharm.lang.dialect.BasedPythonOptIn
import dev.basedpython.pycharm.lang.dialect.PythonProjectsDefault
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.settings.ui.BinaryRow

/**
 * Application-level Configurable for basedpython defaults, at Settings | Languages & Frameworks |
 * basedpython Defaults.
 *
 * Edits [BasedPythonAppSettings]; every project follows these until its own page sets a value (see
 * [BasedPythonDefaults]). Laid out as the project page is — a section per server — so the same
 * setting is in the same place on both.
 */
internal class BasedPythonAppConfigurable :
    BoundConfigurable("basedpython Defaults", ID) {

    /** The IDE's choice from the Python projects row, stored in [apply] once the rest is. */
    private var chosenDefault: PythonProjectsDefault? = null

    private val settings get() = BasedPythonAppSettings.getInstance()

    private var uiDisposable = Disposer.newDisposable()
    private val binaryRows = mutableListOf<BinaryRow>()

    override fun createPanel(): DialogPanel {
        Disposer.dispose(uiDisposable)
        uiDisposable = Disposer.newDisposable()
        binaryRows.clear()
        val s = settings
        return panel {
            row {
                comment(
                    "Every project uses these until it chooses for itself on Languages &amp; Frameworks | basedpython.",
                    maxLineLength = MAX_LINE_LENGTH_WORD_WRAP,
                )
            }.bottomGap(BottomGap.SMALL)
            row("Use basedpython in Python projects:") {
                comboBox(PythonProjectsDefault.entries, textListCellRenderer("") { it.display })
                    .bindItem({ s.pythonProjects }, { chosenDefault = it ?: PythonProjectsDefault.ASK })
            }
            // a row of its own: under the combo it widened the page past the dialog (measured: 771 px
            // of a 649 px viewport), since a wrapped comment there still asks for about 560 px
            row {
                comment(
                    "For projects that do not name basedpython. Each can choose otherwise on its own page.",
                    maxLineLength = MAX_LINE_LENGTH_WORD_WRAP,
                )
            }.bottomGap(BottomGap.SMALL)
            group("by: type checker and language server") {
                row {
                    checkBox("Run by in projects that have not chosen").bindSelected(s::defaultByEnabled)
                }
                server(BasedPythonBinaries.BIN_BY, { s.defaultByPath }, { s.defaultByPath = it }, { s.defaultByExtraArgs }, { s.defaultByExtraArgs = it })
            }
            group("buff: formatter and linter") {
                row {
                    checkBox("Run buff in projects that have not chosen").bindSelected(s::defaultBuffEnabled)
                }
                server(BasedPythonBinaries.BIN_BUFF, { s.defaultBuffPath }, { s.defaultBuffPath = it }, { s.defaultBuffExtraArgs }, { s.defaultBuffExtraArgs = it })
            }
        }
    }

    private fun Panel.server(
        name: String,
        getPath: () -> String?,
        setPath: (String?) -> Unit,
        getArgs: () -> String,
        setArgs: (String) -> Unit,
    ) {
        indent {
            binaryRows += BinaryRow(null, name, name, { null }, uiDisposable).also { it.addTo(this, getPath, setPath) }
            row("Extra arguments:") {
                textField().align(AlignX.FILL).bindText(getArgs, setArgs).applyToComponent { emptyText.text = "None" }
            }
        }
    }

    override fun reset() {
        super.reset()
        binaryRows.forEach { it.refresh() }
    }

    override fun apply() {
        val s = settings
        val before = listOf(s.defaultByPath, s.defaultBuffPath, s.defaultByEnabled, s.defaultBuffEnabled, s.defaultByExtraArgs, s.defaultBuffExtraArgs)
        val byEnabledBefore = s.defaultByEnabled
        super.apply()
        val after = listOf(s.defaultByPath, s.defaultBuffPath, s.defaultByEnabled, s.defaultBuffEnabled, s.defaultByExtraArgs, s.defaultBuffExtraArgs)
        // Every open project that has not chosen for itself follows these, so its servers are
        // started again the way a change on its own page restarts them.
        if (after != before) {
            for (project in ProjectManager.getInstance().openProjects) {
                BasedPythonLspReloader.getInstance(project).onSettingsChanged()
            }
        }
        if (s.defaultByEnabled != byEnabledBefore) {
            BasedPythonProjectDetector.fileTypesMayHaveChanged("basedpython default for the by server changed")
        }
        chosenDefault?.let { default ->
            chosenDefault = null
            if (default != s.pythonProjects) BasedPythonOptIn.chooseForIde(default)
        }
        binaryRows.forEach { it.refresh() }
    }

    override fun disposeUIResources() {
        super.disposeUIResources()
        Disposer.dispose(uiDisposable)
    }

    companion object {
        const val ID: String = "dev.basedpython.pycharm.settings.app"
    }
}
