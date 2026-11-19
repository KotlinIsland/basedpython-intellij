package dev.basedpython.pycharm.settings.ui

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.util.Alarm
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import java.awt.Component
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.event.DocumentEvent

/**
 * A server's binary on a settings page: the path field, a line under it saying what will actually
 * run, and a Test button that runs it.
 *
 * The line answers for what is *typed*, not for what was last applied, and resolves the way the
 * server will ([ByEnvironments.resolve] with the field, or the IDE default when the field is blank,
 * as the override). That matters most when the path is wrong: resolution ignores a path that is not
 * an executable file and falls back to autodetection, which used to happen silently — the page said
 * nothing, and the server ran some other binary.
 *
 * Resolution and the test run off the EDT, since resolving can mean asking uv and a test is a
 * process: the test used to wait up to five seconds on the EDT and answer in a modal message box.
 *
 * [project] is null on the IDE defaults page, which has no project to autodetect in; there the line
 * only checks the path.
 */
internal class BinaryRow(
    private val project: Project?,
    private val name: String,
    private val binary: String,
    private val default: () -> String?,
    parent: Disposable,
) {
    val field = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFile()
                .withTitle("Select the $name Binary")
                .withDescription("The $name executable, or blank to find it in the project's environment"),
        )
    }

    /**
     * The line under the field. A comment rather than a label: a label is as wide as its text, and a
     * long resolved path made the whole page wider than the dialog.
     */
    private var status: JEditorPane? = null

    private val testButton = JButton("Test").apply { addActionListener { test() } }

    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, parent)

    /** What the typed path resolves to, or `null` until the first resolution finishes. */
    @Volatile private var resolved: Resolution? = null

    private sealed interface Resolution {
        /** What the path set (or its absence) runs; [pathProblem] says why that path was passed over for it. */
        data class Found(val launch: ByLaunch, val pathProblem: String?) : Resolution
        data class NotFound(val pathProblem: String?) : Resolution
        data class Unchecked(val pathProblem: String?) : Resolution
    }

    init {
        Disposer.register(parent) { resolved = null }
        field.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = refresh()
        })
    }

    fun addTo(panel: Panel, get: () -> String?, set: (String?) -> Unit) {
        panel.row("Binary:") {
            status = cell(field)
                .align(AlignX.FILL)
                .resizableColumn()
                .bindText({ get().orEmpty() }, { set(it.trim().ifEmpty { null }) })
                .comment(" ", maxLineLength = MAX_LINE_LENGTH_WORD_WRAP)
                .comment as? JEditorPane
            cell(testButton)
        }
    }

    private fun setStatus(text: String) {
        status?.text = StringUtil.escapeXmlEntities(text)
    }

    /** What a blank field means, shown in it. */
    private fun whenBlank(): String =
        default()?.takeIf { it.isNotBlank() }?.let { "IDE default: $it" }
            ?: if (project != null) "Find it in the project's environment" else "Find it in each project's environment"

    /** Re-resolves what the field says, a moment after the last keystroke. */
    fun refresh() {
        (field.textField as? JBTextField)?.emptyText?.text = whenBlank()
        val typed = field.text.trim()
        alarm.cancelAllRequests()
        alarm.addRequest({ resolve(typed).also { show(it, typed) } }, 200)
    }

    private fun resolve(typed: String): Resolution {
        val override = typed.ifEmpty { default()?.trim().orEmpty() }
        val pathProblem = override.takeIf { it.isNotEmpty() }?.let(::pathProblem)
        if (project == null) return Resolution.Unchecked(pathProblem)
        val launch = ByEnvironments.resolve(project, binary, null, ByEnvironmentKind.AUTO, override.ifEmpty { null })
        return if (launch != null) Resolution.Found(launch, pathProblem) else Resolution.NotFound(pathProblem)
    }

    /** Why [path] cannot be run, or `null` when it can. */
    private fun pathProblem(path: String): String? {
        val p = try {
            Path.of(path)
        } catch (_: InvalidPathException) {
            return "is not a valid path"
        }
        return when {
            !Files.exists(p) -> "does not exist"
            Files.isDirectory(p) -> "is a directory, not the $name executable"
            !Files.isExecutable(p) -> "is not executable"
            else -> null
        }
    }

    private fun show(resolution: Resolution, typed: String) {
        resolved = resolution
        val which = if (typed.isNotEmpty()) "This path" else "The IDE default path"
        val text = when (resolution) {
            is Resolution.Found -> {
                val runs = short(resolution.launch.describe()) +
                    if (resolution.launch.fromOverride) "" else "  (${resolution.launch.sourceLabel})"
                resolution.pathProblem?.let { "$which $it, so this runs $runs instead." } ?: "Runs $runs"
            }
            // Short: the line sits under the field, in the page's second column (see BasedPythonConfigurable.WRAP).
            is Resolution.NotFound -> resolution.pathProblem
                ?.let { "$which $it, and no $name was found." }
                ?: "No $name in the project's environment or on PATH."
            is Resolution.Unchecked -> resolution.pathProblem?.let { "$which $it." } ?: ""
        }
        ApplicationManager.getApplication().invokeLater({ setStatus(text) }, ModalityState.any())
    }

    /** Runs `<binary> version` the way the server would be run, and shows its answer on the line. */
    private fun test() {
        val typed = field.text.trim()
        testButton.isEnabled = false
        setStatus("Running $name version…")
        ApplicationManager.getApplication().executeOnPooledThread {
            val answer = runCatching { testAnswer(resolve(typed)) }.getOrElse { "Failed: ${it.message}" }
            ApplicationManager.getApplication().invokeLater({
                testButton.isEnabled = true
                setStatus(answer)
            }, ModalityState.any())
        }
    }

    private fun testAnswer(resolution: Resolution): String {
        val command = when (resolution) {
            is Resolution.Found -> GeneralCommandLine(listOf(resolution.launch.exe.toString()) + resolution.launch.prependArgs + "version")
                .withEnvironment(resolution.launch.env)
            is Resolution.Unchecked -> {
                val path = field.text.trim().ifEmpty { default()?.trim().orEmpty() }
                if (path.isEmpty()) return "Nothing to test: each project finds $name in its own environment."
                resolution.pathProblem?.let { return "$path $it." }
                GeneralCommandLine(path, "version")
            }
            is Resolution.NotFound -> return "No $name to test."
        }
        val out = ExecUtil.execAndGetOutput(command, TEST_TIMEOUT_MS)
        val text = (out.stdout.ifBlank { out.stderr }).trim().lineSequence().firstOrNull().orEmpty()
        return when {
            out.isTimeout -> "${short(command.exePath)} did not answer within ${TEST_TIMEOUT_MS / 1000} s."
            out.exitCode != 0 -> "${short(command.exePath)} failed (exit ${out.exitCode})${if (text.isNotEmpty()) ": $text" else "."}"
            else -> "✓ ${text.ifEmpty { "$name answered" }}  (${short(command.commandLineString.removeSuffix(" version"))})"
        }
    }

    /**
     * [command] with the project's directory left out and the home directory as `~`: the line sits
     * under the field, and an absolute path into the project's `.venv` asked for more width than the
     * page has. Nothing is lost, since the path is relative to the project the page is for.
     */
    private fun short(command: String): String {
        var s = command
        project?.basePath?.let { base -> s = s.replace("$base/", "").replace(base, ".") }
        return FileUtil.getLocationRelativeToUserHome(s.substringBefore(' ')) + s.drop(s.substringBefore(' ').length)
    }

    private companion object {
        const val TEST_TIMEOUT_MS = 10_000
    }
}

/**
 * Selects the settings page [id] in the Settings dialog [from] is in, rather than opening a second
 * Settings dialog on top of the first.
 */
internal fun selectInSettings(from: Component?, id: String) {
    val settings = from?.let { Settings.KEY.getData(DataManager.getInstance().getDataContext(it)) } ?: return
    settings.find(id)?.let { settings.select(it) }
}
