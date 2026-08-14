package dev.basedpython.pycharm.run.main

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.event.DocumentEvent

/**
 * Asks for the arguments a `main` needs, as the fields its signature describes.
 *
 * `main`'s parameters *are* the program's command-line interface, so the IDE knows the name, the
 * type and the default of every one of them before the run starts. That is worth more than a text
 * field: a `Path` gets a file chooser, an `int` is rejected here rather than by argparse twenty
 * seconds into a transpile, a `bool` is a checkbox, and an optional parameter left alone passes
 * nothing at all rather than re-stating its default.
 *
 * The command line stays visible at the bottom, and editing it directly takes over — a hand-written
 * one is never quietly reduced to the fields that happen to fit it.
 */
internal class ByMainArgumentsDialog(
    private val project: Project,
    private val module: String,
    private val main: ByMainFunction,
    initial: String,
) : DialogWrapper(project) {

    /** What the dialog was closed with: the arguments, and which executor was asked for. */
    data class Result(val arguments: String, val debug: Boolean)

    private val fields = main.exposed.map { Field(it) }
    private val commandLine = JBTextField()
    private val asText = JBCheckBox("Edit as a command line")
    private val recent = ByMainArgumentHistory.recent(project, module)
    private var debug = false
    private var syncing = false

    /**
     * Starts the run under the debugger instead; validated exactly like the Run button.
     *
     * Declared before [init] runs: `DialogWrapper.init` builds the button row, and a property
     * initialised further down the class body would still be null when it asks for the actions.
     */
    private val debugAction: Action = object : DialogWrapperAction(DEBUG) {
        override fun doAction(event: ActionEvent) {
            val errors = doValidateAll()
            if (errors.isNotEmpty()) {
                setErrorInfoAll(errors)
                errors.first().component?.requestFocus()
                return
            }
            debug = true
            doOKAction()
        }
    }

    init {
        title = "Run '$module'"
        setOKButtonText(RUN)
        val parsed = ByMainArguments.parse(main, initial)
        if (parsed == null && initial.isNotBlank()) {
            // A command line the form cannot express opens as what it is.
            asText.isSelected = true
            commandLine.text = initial
        } else {
            parsed?.let { values -> fields.forEach { it.value = values[it.parameter.name] } }
        }
        fields.forEach { it.onChange(::refresh) }
        commandLine.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!syncing) setErrorText(null)
            }
        })
        asText.addActionListener { toggleText() }
        init()
        updateEnabled()
        refresh()
    }

    /** The arguments as they stand, in whichever mode the dialog is in. */
    fun result(): Result =
        Result(if (asText.isSelected) commandLine.text.trim() else ByMainArguments.format(main, values()), debug)

    override fun createCenterPanel(): JComponent = panel {
        main.docstring?.let { doc ->
            // The docstring is the generated parser's `--help` description, so it belongs at the
            // top of the form the parser would otherwise fill. Rendered as text, not as the HTML
            // the DSL would otherwise read it as.
            row { text(StringUtil.escapeXmlEntities(doc.lineSequence().joinToString(" ") { it.trim() }.trim())) }
        }
        if (recent.size > 1) {
            row("Recent:") {
                val combo = ComboBox(recent.toTypedArray())
                // Starts on nothing: the dialog may have opened on the configuration's own
                // arguments, and a box claiming otherwise would be describing a run that is not
                // about to happen.
                combo.selectedItem = null
                combo.addActionListener { (combo.selectedItem as? String)?.let(::load) }
                cell(combo).align(AlignX.FILL)
            }
        }
        for (parameter in main.parameters) {
            val field = fields.firstOrNull { it.parameter == parameter }
            if (field == null) {
                row("${parameter.name}:") {
                    // Shown rather than hidden: a parameter missing from the command line is
                    // usually a surprise, and the annotation is the reason for it.
                    comment(unexposed(parameter))
                }
                continue
            }
            row("${parameter.name}:") {
                cell(field.component).align(AlignX.FILL).comment(hint(parameter))
            }
        }
        separator()
        row { cell(asText) }
        row("Command line:") { cell(commandLine).align(AlignX.FILL) }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction, debugAction, cancelAction)

    override fun doValidateAll(): List<ValidationInfo> = problems()

    /**
     * What is wrong with the form as it stands: a required parameter left empty, or a value the
     * annotation could not convert. A command line being edited as text is nobody's business but
     * the program's — argparse will have its own opinion.
     */
    fun problems(): List<ValidationInfo> =
        if (asText.isSelected) emptyList() else fields.mapNotNull { it.validate() }

    override fun getPreferredFocusedComponent(): JComponent? =
        if (asText.isSelected) commandLine
        else (fields.firstOrNull { it.parameter.isRequired && it.value == null } ?: fields.firstOrNull())?.component

    private fun values(): Map<String, String> =
        fields.mapNotNull { field -> field.value?.let { field.parameter.name to it } }.toMap()

    /** Form → command line. The form is the source of truth until the text field takes over. */
    private fun refresh() {
        if (asText.isSelected) return
        syncing = true
        commandLine.text = ByMainArguments.format(main, values())
        syncing = false
    }

    private fun toggleText() {
        if (!asText.isSelected) {
            val parsed = ByMainArguments.parse(main, commandLine.text)
            if (parsed == null) {
                asText.isSelected = true
                setErrorText("This command line has no form — leave it as text, or clear it first")
                return
            }
            fields.forEach { it.value = parsed[it.parameter.name] }
        }
        setErrorText(null)
        updateEnabled()
        refresh()
    }

    private fun load(arguments: String) {
        val parsed = ByMainArguments.parse(main, arguments)
        if (parsed == null) {
            asText.isSelected = true
            commandLine.text = arguments
        } else {
            asText.isSelected = false
            fields.forEach { it.value = parsed[it.parameter.name] }
        }
        updateEnabled()
        refresh()
    }

    private fun updateEnabled() {
        fields.forEach { it.component.isEnabled = !asText.isSelected }
    }

    private fun hint(parameter: ByMainParameter): String {
        val type = parameter.annotation
        val spelling = if (parameter.type == ByCliType.BOOL) {
            "$type — ${parameter.flag} / ${parameter.negativeFlag}"
        } else {
            type
        }
        return parameter.default?.let { "$spelling, default $it" } ?: spelling
    }

    private fun unexposed(parameter: ByMainParameter): String {
        val type = parameter.annotation.ifBlank { "no annotation" }
        return "$type — not available on the command line" +
            if (parameter.isRequired) ", so `main` is not an entry point" else ", keeps its default"
    }

    /**
     * One parameter's editor: a chooser for a `Path`, a three-state box for a `bool` (the third
     * state being "say nothing, let the default stand"), a text field otherwise.
     */
    private inner class Field(val parameter: ByMainParameter) {
        private val combo: ComboBox<String>? =
            if (parameter.type == ByCliType.BOOL) ComboBox(arrayOf(OMIT, TRUE, FALSE)) else null
        private val browse: TextFieldWithBrowseButton? =
            if (parameter.type == ByCliType.PATH) {
                TextFieldWithBrowseButton().apply {
                    addBrowseFolderListener(
                        project,
                        FileChooserDescriptorFactory.singleFileOrDir().withTitle("Choose ${parameter.name}"),
                    )
                }
            } else {
                null
            }
        private val plain: JBTextField? = if (combo == null && browse == null) JBTextField() else null

        val component: JComponent = combo ?: browse ?: plain!!

        var value: String?
            get() = when {
                combo != null -> when (combo.selectedItem) {
                    TRUE -> "true"
                    FALSE -> "false"
                    else -> null
                }
                else -> text().trim().ifBlank { null }
            }
            set(value) {
                when {
                    combo != null -> combo.selectedItem = when (value?.toBoolean()) {
                        true -> TRUE
                        false -> FALSE
                        null -> OMIT
                    }
                    browse != null -> browse.text = value.orEmpty()
                    else -> plain!!.text = value.orEmpty()
                }
            }

        fun onChange(listener: () -> Unit) {
            combo?.addActionListener { listener() }
            document()?.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = listener()
            })
        }

        fun validate(): ValidationInfo? {
            val text = value
                ?: return if (parameter.isRequired) error("${parameter.name} is required") else null
            // The annotation is the converter argparse is handed, so a value it cannot convert is
            // an error the program would report — earlier is better.
            val number = text.replace("_", "")
            return when (parameter.type) {
                ByCliType.INT ->
                    if (number.toLongOrNull() == null) error("invalid int value: '$text'") else null
                ByCliType.FLOAT ->
                    if (number.toDoubleOrNull() == null) error("invalid float value: '$text'") else null
                else -> null
            }
        }

        private fun error(message: String) = ValidationInfo(message, component)

        private fun text(): String = browse?.text ?: plain?.text.orEmpty()

        private fun document() = browse?.textField?.document ?: plain?.document
    }

    private companion object {
        const val RUN = "Run"
        const val DEBUG = "Debug"
        const val OMIT = "(default)"
        const val TRUE = "true"
        const val FALSE = "false"
    }
}
