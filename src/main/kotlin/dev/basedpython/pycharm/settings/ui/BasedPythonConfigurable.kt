package dev.basedpython.pycharm.settings.ui

import dev.basedpython.pycharm.run.test.ByTestRunnerChoice
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
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
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.layout.ComponentPredicate
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.debug.dfa.ByDataFlowSession
import dev.basedpython.pycharm.debug.recompose.ByRecompositionSession
import dev.basedpython.pycharm.lang.dialect.BasedPythonOptIn
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lang.dialect.ProjectKind
import dev.basedpython.pycharm.lang.dialect.ProjectOptIn
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.lsp.diagnostics.ByProjectDiagnostics
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.lsp.inlay.ByInlayCatalog
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.BasedPythonSettingsEffects
import dev.basedpython.pycharm.settings.ByImportStyle
import dev.basedpython.pycharm.settings.app.BasedPythonAppConfigurable
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Project-level Configurable for basedpython, at Settings | Languages & Frameworks | basedpython.
 *
 * Laid out by server rather than by kind of setting: everything about `by` — whether it runs, which
 * binary, its arguments, its features — sits in one section that is disabled as a whole when `by`
 * is off, and the same for `buff`. It used to be a Binaries section, a Servers section and an Args
 * section each naming both servers, then a server's features at the very bottom, so turning one
 * server off left its path, arguments and features looking as live as the other's.
 *
 * Every explanation is a short comment *under* its control. Comments to the right of a control
 * widened the page past the dialog's default size, pushing the path fields and their Test buttons
 * off the right edge (measured in the sandbox: the page asked for 1002 px of a 649 px viewport); see
 * [WRAP] for the width a comment asks for.
 */
internal class BasedPythonConfigurable(private val project: Project) :
    BoundConfigurable("basedpython", "dev.basedpython.pycharm.settings") {

    private val settings get() = BasedPythonSettings.getInstance(project)

    /** Owns the binary fields' background resolution; replaced on every [createPanel]. */
    private var uiDisposable = Disposer.newDisposable()

    private lateinit var byBinary: BinaryRow

    /** The project's choice from [pythonFilesRow], stored in [apply] once the rest is. */
    private var chosenOptIn: ProjectOptIn? = null
    private lateinit var buffBinary: BinaryRow

    override fun createPanel(): DialogPanel {
        Disposer.dispose(uiDisposable)
        uiDisposable = Disposer.newDisposable()
        val s = settings
        val app = BasedPythonAppSettings.getInstance()

        return panel {
            pythonFilesRow()

            // by
            group("by: type checker and language server") {
                lateinit var enabled: ComponentPredicate
                row {
                    enabled = checkBox("Run by in this project")
                        // Written only when changed, so a project that never touched the box keeps
                        // following the IDE-wide default instead of freezing the value it showed.
                        .bindSelected({ s.byEnabled }, { if (it != s.byEnabled) s.byEnabled = it })
                        .selected
                }
                indent {
                    byBinary = binaryRow(
                        "by", BasedPythonBinaries.BIN_BY, { s.byPath }, { s.byPath = it }, { app.defaultByPath },
                    )
                    extraArgsRow({ s.byExtraArgs }, { s.byExtraArgs = it }, app.defaultByExtraArgs)
                    row {
                        checkBox("Check every file in the project, not only open ones")
                            .bindSelected(s::byProjectDiagnostics)
                            .comment("Listed in Problems | Project Errors. Takes several times the memory of checking open files.", maxLineLength = WRAP)
                    }
                    row("Import a name with:") {
                        comboBox(ByImportStyle.entries, textListCellRenderer("") { it.display })
                            .bindItem({ s.importStyle }, { s.importStyle = it ?: ByImportStyle.FROM })
                            .comment("For a module the file imports nothing from yet.", maxLineLength = WRAP)
                    }
                    collapsibleGroup("Features") {
                        row { checkBox("Completion").bindSelected(s::byCompletion) }
                        row { checkBox("Go to definition and type definition").bindSelected(s::byGoToDefinition) }
                        row { checkBox("Find usages").bindSelected(s::byFindReferences) }
                        row { checkBox("Rename").bindSelected(s::byRename) }
                        row { checkBox("Semantic highlighting").bindSelected(s::bySemanticTokens) }
                        row { checkBox("Code vision (code lens)").bindSelected(s::byCodeLens) }
                        row { checkBox("Highlight usages at caret").bindSelected(s::byDocumentHighlight) }
                        row { checkBox("Parameter info").bindSelected(s::bySignatureHelp) }
                        row { checkBox("Language injection (HTML, SQL, … in strings)").bindSelected(s::byLanguageInjection) }
                    }
                }.enabledIf(enabled)
            }

            // buff
            group("buff: formatter and linter") {
                lateinit var enabled: ComponentPredicate
                row {
                    enabled = checkBox("Run buff in this project")
                        .bindSelected({ s.buffEnabled }, { if (it != s.buffEnabled) s.buffEnabled = it })
                        .selected
                }
                indent {
                    buffBinary = binaryRow(
                        "buff", BasedPythonBinaries.BIN_BUFF, { s.buffPath }, { s.buffPath = it }, { app.defaultBuffPath },
                    )
                    extraArgsRow({ s.buffExtraArgs }, { s.buffExtraArgs = it }, app.defaultBuffExtraArgs)
                    collapsibleGroup("Features") {
                        row { checkBox("Formatting").bindSelected(s::buffFormatting) }
                        row { checkBox("Lint fixes").bindSelected(s::buffCodeActions) }
                        row { checkBox("Quick documentation for lint rules").bindSelected(s::buffHover) }
                    }
                }.enabledIf(enabled)
                row {
                    comment(
                        "Fix all on save is on <a href='actionsOnSave'>Tools | Actions on Save</a>, beside Reformat code.",
                        maxLineLength = WRAP,
                        action = { e -> selectInSettings(e.source as? java.awt.Component, ACTIONS_ON_SAVE_ID) },
                    )
                }
            }

            inlayHintsGroup()

            group(".py files") {
                row("Treat .py as basedpython:") {
                    comboBox(PyFileHandling.entries, textListCellRenderer("") { it.display })
                        .bindItem({ s.pyFileHandling }, { s.pyFileHandling = it ?: PyFileHandling.AUTO })
                        .comment("Only who highlights them: by checks them either way.", maxLineLength = WRAP)
                }
                // Only an IDE with a Python plugin has anything to index the generated code for.
                if (PyFileHandling.isPythonLanguageAvailable()) {
                    row {
                        checkBox("Index the generated .py in build/")
                            .bindSelected(s::indexGeneratedPython)
                            .comment("Lets the Python plugin navigate the code basedpython compiles to.", maxLineLength = WRAP)
                    }
                }
            }

            group(BasedPythonBundle.message("settings.tests.group")) {
                row(BasedPythonBundle.message("settings.tests.runner")) {
                    comboBox(
                        ByTestRunnerChoice.entries,
                        textListCellRenderer("") {
                            when (it) {
                                ByTestRunnerChoice.AUTOMATIC -> BasedPythonBundle.message("settings.tests.runner.automatic")
                                ByTestRunnerChoice.PYTEST -> BasedPythonBundle.message("settings.tests.runner.pytest")
                                ByTestRunnerChoice.DJANGO -> BasedPythonBundle.message("settings.tests.runner.django")
                            }
                        },
                    )
                        .bindItem({ s.testRunner }, { s.testRunner = it ?: ByTestRunnerChoice.AUTOMATIC })
                        .comment(BasedPythonBundle.message("settings.tests.runner.comment"), maxLineLength = WRAP)
                }
            }

            group("Debugger") {
                lateinit var backend: ComboBox<ByDebugBackend>
                row(BasedPythonBundle.message("settings.debug.backend")) {
                    backend = comboBox(
                        ByDebugBackend.entries,
                        textListCellRenderer("") {
                            when (it) {
                                ByDebugBackend.BPD -> BasedPythonBundle.message("settings.debug.backend.bpd")
                                ByDebugBackend.DEBUGPY -> BasedPythonBundle.message("settings.debug.backend.debugpy")
                            }
                        },
                    )
                        .bindItem({ s.debugBackend }, { s.debugBackend = it ?: ByDebugBackend.BPD })
                        .comment("debugpy needs no extra binary, but cannot do the two below.", maxLineLength = WRAP)
                        .component
                }
                val bpd = object : ComponentPredicate() {
                    override fun invoke() = backend.selectedItem == ByDebugBackend.BPD
                    override fun addListener(listener: (Boolean) -> Unit) {
                        backend.addItemListener { listener(invoke()) }
                    }
                }
                indent {
                    row {
                        checkBox(BasedPythonBundle.message("settings.debug.dataFlow"))
                            .bindSelected(s::debuggerDataFlow)
                            .comment("While stopped, draws which branches will be taken.", maxLineLength = WRAP)
                    }
                    row {
                        checkBox(BasedPythonBundle.message("settings.debug.recompositions"))
                            .bindSelected(s::debuggerRecompositions)
                            .comment("The Recompositions tool window, and a label on each composable that ran in the latest frame.", maxLineLength = WRAP)
                    }
                }.enabledIf(bpd)
            }

            group("Version control") {
                row {
                    checkBox("Offer to merge .by and .py files with by's git merge driver")
                        .bindSelected(s::offerGitMergeIntegration)
                        .comment("Asked once, when by starts in a git repository that does not use it yet.", maxLineLength = WRAP)
                }
            }
        }
    }

    /**
     * Whether basedpython is used for this project's Python files, above everything else
     * ([BasedPythonOptIn]): the same question the banner above a `.py` file asks.
     *
     * Not offered where the project names basedpython itself, which is an answer no setting here
     * overrides. The page is in every project's Settings, and in a Rust repository, or a Python one
     * that never names basedpython, a page full of server switches read as though they were running
     * there; this row says first whether they are.
     */
    private fun Panel.pythonFilesRow() {
        val kind = BasedPythonProjectDetector.kind(project)
        if (kind == ProjectKind.BASEDPYTHON) return
        val default = BasedPythonOptIn.ideDefault()
        val ideAnswer = when {
            BasedPythonOptIn.uses(kind, ProjectOptIn.UNDECIDED, default) -> "Yes"
            BasedPythonOptIn.shouldAsk(kind, ProjectOptIn.UNDECIDED, default) -> "Ask"
            else -> "No"
        }
        row("Use basedpython for Python files:") {
            comboBox(
                ProjectOptIn.entries,
                textListCellRenderer("") { if (it == ProjectOptIn.UNDECIDED) "$ideAnswer (IDE default)" else it.display },
            )
                .bindItem({ BasedPythonOptIn.projectChoice(project) }, { chosenOptIn = it ?: ProjectOptIn.UNDECIDED })
        }
        // a row of its own: under the combo, in the second column, it widened the page past the
        // dialog (measured: 699 px of a 649 px viewport), for the reason [WRAP] gives
        row {
            comment(
                "If not, by and buff check only .by files here. For every project: <a href='defaults'>basedpython Defaults</a>.",
                maxLineLength = WRAP,
                action = { e -> selectInSettings(e.source as? java.awt.Component, BasedPythonAppConfigurable.ID) },
            )
        }.bottomGap(BottomGap.SMALL)
    }

    private fun Panel.binaryRow(
        name: String,
        binary: String,
        get: () -> String?,
        set: (String?) -> Unit,
        default: () -> String?,
    ): BinaryRow = BinaryRow(project, name, binary, default, uiDisposable).also { it.addTo(this, get, set) }

    private fun Panel.extraArgsRow(get: () -> String, set: (String) -> Unit, default: String) {
        row("Extra arguments:") {
            textField()
                .align(AlignX.FILL)
                .bindText(get, set)
                .applyToComponent { emptyText.text = if (default.isBlank()) "None" else "IDE default: $default" }
        }
    }

    /**
     * What the hints are set to, in one line, and the way to the page that sets them.
     *
     * The page of its own ([ByInlayHintsConfigurable]) because each kind there has a description and a
     * preview, which two dozen rows of drop-downs here had neither of.
     */
    private fun Panel.inlayHintsGroup() {
        group("Inlay hints") {
            row {
                comment(inlaySummary(), maxLineLength = WRAP)
            }
            row {
                link("Configure inlay hints…") { e -> selectInSettings(e.source as? java.awt.Component, ByInlayHintsConfigurable.ID) }
            }
        }
    }

    private fun inlaySummary(): String {
        val kinds = ByInlayCatalog.getInstance().catalog?.sourceKinds.orEmpty()
        if (kinds.isEmpty()) return "by names its kinds of hint when it starts, which it has not in this IDE yet."
        val counts = kinds.groupingBy { settings.inlayMode(it.kind) }.eachCount()
        return listOfNotNull(
            counts[ByHintMode.ALWAYS]?.let { "$it always shown" },
            counts[ByHintMode.ON_PUSH]?.let { "$it while ${settings.inlayPushKey.display} is held" },
            counts[ByHintMode.NEVER]?.let { "$it off" },
        ).joinToString(", ", postfix = ".")
    }

    override fun reset() {
        super.reset()
        byBinary.refresh()
        buffBinary.refresh()
    }

    override fun apply() {
        val s = settings
        val before = BasedPythonSettingsEffects.snapshot(s)
        val projectDiagnostics = s.byProjectDiagnostics
        val dataFlow = s.debuggerDataFlow
        val recompositions = s.debuggerRecompositions
        super.apply()

        // Off must take what a stopped session drew down now: the pass that would have removed it
        // no longer runs once the factory declines, so each service removes its marks itself.
        if (s.byProjectDiagnostics != projectDiagnostics) ByProjectDiagnostics.getInstance(project).settingChanged()
        if (s.debuggerDataFlow != dataFlow) ByDataFlowSession.getInstance(project).settingChanged()
        if (s.debuggerRecompositions != recompositions) ByRecompositionSession.getInstance(project).settingChanged()
        BasedPythonSettingsEffects.announce(project, before)
        BasedPythonLspReloader.getInstance(project).onSettingsChanged()
        chosenOptIn?.let { choice ->
            chosenOptIn = null
            if (choice != BasedPythonOptIn.projectChoice(project)) BasedPythonOptIn.choose(project, choice)
        }
        byBinary.refresh()
        buffBinary.refresh()
    }

    override fun disposeUIResources() {
        super.disposeUIResources()
        Disposer.dispose(uiDisposable)
    }

    private companion object {
        /**
         * Every comment wraps at the page's width rather than at a fixed 70 characters.
         *
         * Wrapping still asks for up to 70 columns (about 560 px) of width, and the Settings dialog
         * lays a page out at the width it asks for, so a long comment under a control in the second
         * column made the page wider than the dialog's default 900 px and pushed the Test buttons out
         * of sight (measured: the page asked for 783 px of a 649 px viewport). The comments there are
         * kept short for that reason.
         */
        const val WRAP = MAX_LINE_LENGTH_WORD_WRAP

        /** The platform's Settings | Tools | Actions on Save page. */
        const val ACTIONS_ON_SAVE_ID = "actions.on.save"
    }
}
