package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsSettings
import com.intellij.codeInsight.hints.settings.InlayProviderSettingsModel
import com.intellij.codeInsight.hints.settings.InlaySettingsProvider
import com.intellij.lang.Language
import com.intellij.ide.DataManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.ui.components.ActionLink
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.Component
import javax.swing.JComponent

/**
 * basedpython on *Settings | Editor | Inlay Hints*: one entry, its checkbox the language's switch for
 * every hint, and under it one option for each kind of hint `by` computes ([ByHintKind]).
 *
 * The options are the very settings *Settings | basedpython | Inlay hints* shows, read and written
 * there and nowhere else, so the two pages cannot disagree: an option is checked when its kind is
 * shown — always, or while the push key is held — and unchecking it sets the kind to *never*, which
 * `by` is told not to compute. Checking one shows its kind always, or as it was shown before it was
 * unchecked. Which kinds wait for the push key, and the key, are on basedpython's page, which the
 * entry links to: the platform's page has a checkbox per option, not a choice of three.
 *
 * No preview: the hints are `by`'s answer about a file of the project, and the page's preview is a
 * file no server has.
 */
internal class ByInlaySettingsProvider : InlaySettingsProvider {

    override fun createModels(project: Project, language: Language): List<InlayProviderSettingsModel> =
        if (language == BasedPythonLanguage && BasedPythonProjectDetector.isPythonProject(project)) listOf(ByInlaySettingsModel(project)) else emptyList()

    override fun getSupportedLanguages(project: Project): Collection<Language> =
        if (BasedPythonProjectDetector.isPythonProject(project)) listOf(BasedPythonLanguage) else emptyList()
}

internal class ByInlaySettingsModel(private val project: Project) :
    InlayProviderSettingsModel(InlayHintsSettings.instance().hintsEnabled(BasedPythonLanguage), ID, BasedPythonLanguage) {

    private val settings get() = BasedPythonSettings.getInstance(project)

    /** The mode of each kind as the page has it now, applied on Apply. */
    private val modes = HashMap<ByHintKind, ByHintMode>()

    /** What a kind unchecked here goes back to when it is checked again before Apply. */
    private val shownAs = HashMap<ByHintKind, ByHintMode>()

    init {
        reset()
    }

    override val name: String = BasedPythonBundle.message("inlay.hints.name")

    override val description: String = BasedPythonBundle.message("inlay.settings.description")

    override val previewText: String? = null

    override fun getCasePreview(case: ImmediateConfigurable.Case?): String? = null

    override fun getCasePreviewLanguage(case: ImmediateConfigurable.Case?): Language = BasedPythonLanguage

    override fun getCaseDescription(case: ImmediateConfigurable.Case): String? = null

    @Deprecated("Not used in new UI")
    override val mainCheckBoxLabel: String = BasedPythonBundle.message("inlay.hints.name")

    override val component: JComponent by lazy {
        panel {
            row {
                cell(
                    ActionLink(BasedPythonBundle.message("inlay.settings.link")) { event ->
                        // within the settings dialog this page is in, as the platform's own links go
                        val dialog = Settings.KEY.getData(DataManager.getInstance().getDataContext(event.source as Component))
                        val page = dialog?.find(CONFIGURABLE_ID)
                        if (dialog != null && page != null) {
                            dialog.select(page)
                        } else {
                            ShowSettingsUtil.getInstance().showSettingsDialog(project, BasedPythonBundle.message("inlay.settings.configurable"))
                        }
                    },
                )
            }
        }
    }

    override val cases: List<ImmediateConfigurable.Case> = ByHintKind.entries.map { kind ->
        ImmediateConfigurable.Case(
            kind.display,
            kind.option ?: kind.name,
            { modes[kind]?.isCollected == true },
            { checked ->
                modes[kind] = if (checked) shownAs[kind] ?: ByHintMode.ALWAYS else ByHintMode.NEVER
                onChangeListener?.settingsChanged()
            },
        )
    }

    override fun isModified(): Boolean =
        isEnabled != InlayHintsSettings.instance().hintsEnabled(BasedPythonLanguage) ||
            ByHintKind.entries.any { modes[it] != settings.inlayMode(it) }

    override fun apply() {
        InlayHintsSettings.instance().setHintsEnabledForLanguage(BasedPythonLanguage, isEnabled)
        if (ByHintKind.entries.none { modes[it] != settings.inlayMode(it) }) return
        for (kind in ByHintKind.entries) settings.setInlayMode(kind, modes.getValue(kind))
        // which kinds `by` computes is read at start, from the modes
        BasedPythonLspReloader.getInstance(project).onSettingsChanged()
    }

    override fun reset() {
        isEnabled = InlayHintsSettings.instance().hintsEnabled(BasedPythonLanguage)
        for (kind in ByHintKind.entries) {
            val mode = settings.inlayMode(kind)
            modes[kind] = mode
            if (mode.isCollected) shownAs[kind] = mode
        }
    }

    companion object {
        const val ID = "basedpython.inlay.kinds"

        /** basedpython's own settings page, which has each kind's three modes and the push key. */
        private const val CONFIGURABLE_ID = "dev.basedpython.pycharm.settings"
    }
}
