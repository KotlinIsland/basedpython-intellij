package dev.basedpython.pycharm.format

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import dev.basedpython.pycharm.lsp.ext.BuffFormatSettings
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import javax.swing.JPanel
import java.awt.BorderLayout
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.basedpython.pycharm.util.BasedPythonBundle

// ---------------------------------------------------------------------------
// CodeStyleSettingsProvider — registers the top-level "basedpython" entry
// under Editor → Code Style.
// ---------------------------------------------------------------------------

class BuffCodeStyleSettingsProvider : CodeStyleSettingsProvider() {

    override fun getLanguage() = BasedPythonLanguage

    override fun createConfigurable(
        settings: CodeStyleSettings,
        modelSettings: CodeStyleSettings,
    ): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(settings, modelSettings, configurableDisplayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                BuffCodeStyleMainPanel(currentSettings, settings)
        }

    override fun getConfigurableDisplayName(): String = "basedpython"
}

// ---------------------------------------------------------------------------
// LanguageCodeStyleSettingsProvider — required to show the language tab inside
// the generic Code Style dialog and to supply a preview snippet.
// ---------------------------------------------------------------------------

class BuffLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage() = BasedPythonLanguage

    override fun getCodeSample(settingsType: SettingsType): String = CODE_SAMPLE

    override fun customizeDefaults(
        commonSettings: com.intellij.psi.codeStyle.CommonCodeStyleSettings,
        indentOptions: com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions,
    ) {
        commonSettings.RIGHT_MARGIN = BUFF_DEFAULT_LINE_LENGTH
        // `# total = 1`, as Python writes it; see BasedPythonCommenter for why not a `"# "` prefix.
        commonSettings.LINE_COMMENT_ADD_SPACE = true
        indentOptions.INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.USE_TAB_CHARACTER = false
    }

    private companion object {
        val CODE_SAMPLE = """
            |from typing import Optional
            |
            |
            |def greet(name: str, greeting: Optional[str] = None) -> str:
            |    msg = greeting or "Hello"
            |    return f"{msg}, {name}!"
            |
            |
            |class Greeter:
            |    def __init__(self, name: str) -> None:
            |        self.name = name
            |
            |    def greet(self) -> str:
            |        return greet(self.name)
        """.trimMargin()
    }
}

/** buff's own default `line-length`: the right margin until the project's configuration is read. */
private const val BUFF_DEFAULT_LINE_LENGTH = 88

// ---------------------------------------------------------------------------
// Main tabbed panel — wraps the buff tab.
// ---------------------------------------------------------------------------

private class BuffCodeStyleMainPanel(
    currentSettings: CodeStyleSettings,
    settings: CodeStyleSettings,
) : TabbedLanguageCodeStylePanel(BasedPythonLanguage, currentSettings, settings) {

    override fun initTabs(settings: CodeStyleSettings) {
        addTab(BuffOptionsTab(settings))
    }
}

/**
 * The buff tab: what buff formats basedpython with, and where that is set.
 *
 * buff, like ruff, reads its options from the project's configuration files, which is what
 * `buff format` applies on the command line and in CI; there is nothing to edit here, since sending
 * the IDE's values instead would let them override the files (`configurationPreference` defaults to
 * `editorFirst`), and the IDE would format differently from everything else that runs buff. What it
 * shows is what `buff/formatSettings` resolved for the `.by` file in the editor — line length, indent,
 * quote style, line ending and the file they were read from — the same values the editor's right
 * margin and indent follow ([BuffCodeStyleSettingsModifier]). Refreshed when they change.
 */
private class BuffOptionsTab(settings: CodeStyleSettings) :
    CodeStyleAbstractPanel(BasedPythonLanguage, null, settings) {

    private val resolved = JBLabel().apply { setAllowAutoWrapping(true) }

    /** Shown in the settings dialog of [project], once it is known; the listener goes with the tab. */
    private var project: Project? = null

    private val rootPanel: JPanel = object : JPanel(BorderLayout(0, JBUI.scale(12))) {
        override fun addNotify() {
            super.addNotify()
            if (project == null) {
                // the settings dialog says whose settings these are
                project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(this))
                    ?.takeIf { !it.isDefault && !it.isDisposed }
                project?.messageBus?.connect(this@BuffOptionsTab)?.subscribe(
                    CodeStyleSettingsListener.TOPIC,
                    CodeStyleSettingsListener { ApplicationManager.getApplication().invokeLater({ showResolved() }, ModalityState.any()) },
                )
            }
            showResolved()
        }
    }.apply {
        border = JBUI.Borders.empty(8)
        add(resolved, BorderLayout.NORTH)
        add(JBLabel(BasedPythonBundle.message("codeStyle.buff.fromConfiguration")).apply { setAllowAutoWrapping(true) }, BorderLayout.CENTER)
    }

    private fun showResolved() {
        val project = project
        val file = project?.let { BuffResolvedSettings.fileShown(it) }
        resolved.text = when {
            project == null || file == null -> BasedPythonBundle.message("codeStyle.buff.noFile")
            else -> BuffFormatSettingsService.getInstance(project).current(file)
                ?.let { BuffResolvedSettings.describe(project, file, it) }
                ?: BasedPythonBundle.message("codeStyle.buff.notYet", file.name)
        }
    }

    override fun getTabTitle(): String = "buff"

    override fun getPanel(): JPanel = rootPanel

    override fun resetImpl(settings: CodeStyleSettings) = Unit

    override fun apply(settings: CodeStyleSettings) = Unit

    override fun isModified(settings: CodeStyleSettings): Boolean = false

    // --- Preview panel (not used — no PSI for basedpython yet) -------------

    override fun getPreviewText(): String? = null

    override fun getRightMargin(): Int = BUFF_DEFAULT_LINE_LENGTH

    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter? = null

    override fun getFileType(): FileType = BasedPythonFileType.INSTANCE
}

/** What the buff tab says about the values `buff` resolved. */
internal object BuffResolvedSettings {

    /** The `.by` file the tab describes: the one in the selected editor, or else any open one. */
    fun fileShown(project: Project): VirtualFile? {
        val editors = FileEditorManager.getInstance(project)
        return (editors.selectedFiles.asSequence() + editors.openFiles.asSequence())
            .firstOrNull { it.fileType == BasedPythonFileType.INSTANCE }
    }

    fun describe(project: Project, file: VirtualFile, buff: BuffFormatSettings): String {
        val unknown = BasedPythonBundle.message("codeStyle.buff.unknown")
        val indent = when (buff.indentStyle) {
            "tab" -> BasedPythonBundle.message("codeStyle.buff.indent.tab", buff.indentWidth ?: unknown)
            else -> BasedPythonBundle.message("codeStyle.buff.indent.space", buff.indentWidth ?: unknown)
        }
        val source = BuffFormatSettingsService.getInstance(project).configurationFile(buff)
            ?.let { configuration -> VfsUtilCore.getRelativePath(configuration, project.guessProjectDir() ?: configuration) ?: configuration.path }
            ?.let { BasedPythonBundle.message("codeStyle.buff.from", StringUtil.escapeXmlEntities(it)) }
            ?: BasedPythonBundle.message("codeStyle.buff.fromDefaults")
        return BasedPythonBundle.message(
            "codeStyle.buff.resolved",
            StringUtil.escapeXmlEntities(file.name),
            buff.lineLength?.toString() ?: unknown,
            indent,
            buff.quoteStyle ?: unknown,
            buff.lineEnding ?: unknown,
            source,
        )
    }
}
