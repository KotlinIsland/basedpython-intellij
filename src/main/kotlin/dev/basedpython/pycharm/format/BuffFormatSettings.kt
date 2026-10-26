package dev.basedpython.pycharm.format

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.net.URI
import java.nio.file.Path
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.modifier.CodeStyleSettingsModifier
import com.intellij.psi.codeStyle.modifier.CodeStyleStatusBarUIContributor
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.ext.BuffFormatSettings
import dev.basedpython.pycharm.lsp.ext.BuffFormatSettingsParams
import dev.basedpython.pycharm.lsp.ext.BuffServerExtensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.intellij.openapi.fileEditor.FileEditorManager
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly

/**
 * What `buff` formats each `.by` file with, `buff/formatSettings`: the line length, indent, quote
 * style and line ending resolved from the configuration that governs the file, and that file.
 *
 * ## When it is asked
 *
 * Never while something waits: the editor's code style is computed inside a read action, sometimes
 * on the EDT, and the answer only changes when the configuration does. So [current] serves what
 * `buff` last said and, when that is missing or stale, asks in the background; an answer that
 * differs from what was served bumps [tracker], which every `.by` file's code style depends on, and
 * tells the platform the file's code style changed, so its editors read the margin again.
 *
 * An answer is stale when it came from another `buff` process (the server restarted, perhaps on a
 * different configuration) or when the configuration file it names has been saved since.
 */
@Service(Service.Level.PROJECT)
internal class BuffFormatSettingsService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    /** What one `buff` said about one file, and the configuration file's stamp when it said it. */
    private class Known(val server: LspClient?, val settings: BuffFormatSettings, val configurationStamp: Long)

    /** Weak keys: a file that is gone takes its settings with it. */
    private val known: MutableMap<VirtualFile, Known> = ContainerUtil.createConcurrentWeakMap()

    /** The files being asked about now, so a burst of recomputations asks once. */
    private val asking: MutableSet<VirtualFile> = ContainerUtil.newConcurrentSet()

    /** Bumped whenever what `buff` says about some file changes. */
    val tracker = SimpleModificationTracker()

    /** What `buff` last said about [file], or null; asks in the background when that is missing or stale. */
    fun current(file: VirtualFile): BuffFormatSettings? {
        val server = ByCleanup.findServer(project, file)?.takeIf { it.state == LspServerState.Running }
        val entry = known[file]
        if (entry != null && (server == null || entry.server === server) && entry.configurationStamp == stampOf(entry.settings)) {
            return entry.settings
        }
        if (server != null && asking.add(file)) {
            scope.launch {
                try {
                    ask(server, file)
                } finally {
                    asking.remove(file)
                }
            }
        }
        return entry?.settings
    }

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                /**
                 * A `buff` that has just started may read another configuration, and every `.by` file
                 * on screen had its code style computed while there was nobody to ask: ask for each.
                 */
                override fun serverInitialized(serverName: String) {
                    if (serverName != BUFF_SERVER) return
                    for (file in FileEditorManager.getInstance(project).openFiles) {
                        if (file.isValid && file.fileType == BasedPythonFileType.INSTANCE) current(file)
                    }
                }
            },
        )
    }

    private suspend fun ask(server: LspClient, file: VirtualFile) {
        val params = BuffFormatSettingsParams(TextDocumentIdentifier(server.getDocumentIdentifier(file).uri))
        var settings: BuffFormatSettings? = null
        // `null` is a document the platform has not opened on `buff` yet, which it does just after
        // the server starts or an editor opens: asked again a few times, then left for next time
        for (attempt in 1..NOT_OPEN_ATTEMPTS) {
            val answer = server.awaitBy("buff/formatSettings", TIMEOUT_MS) { (it as BuffServerExtensions).formatSettings(params) }
            if (answer is ByAnswer.Failed) return
            settings = answer.value
            if (settings != null) break
            delay(NOT_OPEN_DELAY_MS)
        }
        if (settings == null) return
        val stamp = readAction { stampOf(settings) }
        val before = known.put(file, Known(server, settings, stamp))
        if (before?.settings == settings) return
        tracker.incModificationCount()
        ApplicationManager.getApplication().invokeLater({
            if (file.isValid) CodeStyleSettingsManager.getInstance(project).fireCodeStyleSettingsChanged(file)
        }, project.disposed)
    }

    /** The configuration file [settings] were read from, as a file here, or null when none is. */
    fun configurationFile(settings: BuffFormatSettings): VirtualFile? {
        val uri = settings.configurationFile ?: return null
        val path = runCatching { Path.of(URI(uri)) }.getOrNull() ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    private fun stampOf(settings: BuffFormatSettings): Long = configurationFile(settings)?.modificationStamp ?: -1

    /** Puts in what `buff` would have said about [file]. */
    @TestOnly
    fun remember(file: VirtualFile, settings: BuffFormatSettings) {
        known[file] = Known(null, settings, stampOf(settings))
        tracker.incModificationCount()
    }

    override fun dispose() {
        known.clear()
    }

    companion object {
        /** A lookup in `buff`'s resolved configuration; one not had by this is a busy server. */
        private const val TIMEOUT_MS = 5_000L

        /** How often, and how far apart, a document `buff` has not been handed yet is asked about. */
        private const val NOT_OPEN_ATTEMPTS = 5
        private const val NOT_OPEN_DELAY_MS = 1_000L

        private const val BUFF_SERVER = "buff"

        fun getInstance(project: Project): BuffFormatSettingsService = project.service()
    }
}

/**
 * The editor's code style for a `.by` file follows what `buff` formats it with: the right margin at
 * `buff`'s line length, and the indent its width and style — so the margin is drawn where Reformat
 * wraps, and Enter indents as `buff` would. Python's own code style keeps PyCharm's margin, 120,
 * whatever the project's ruff configuration says; for `.by` the formatter is `buff`, and its
 * configuration is the one the editor shows.
 *
 * Until `buff` has said, or when it cannot, the file keeps the basedpython code style page's values.
 */
internal class BuffCodeStyleSettingsModifier : CodeStyleSettingsModifier {

    override fun modifySettings(settings: TransientCodeStyleSettings, file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        if (virtualFile.fileType != BasedPythonFileType.INSTANCE) return false
        val service = BuffFormatSettingsService.getInstance(file.project)
        settings.addDependency(service.tracker)
        val buff = service.current(virtualFile) ?: return false
        service.configurationFile(buff)?.let { settings.addDependency(it) }
        apply(buff, settings.getCommonSettings(BasedPythonLanguage))
        return true
    }

    override fun getName(): String = "buff"

    override fun getStatusBarUiContributor(transientSettings: TransientCodeStyleSettings): CodeStyleStatusBarUIContributor? = null

    companion object {
        /** [buff]'s values into [common], the basedpython language's settings. */
        fun apply(buff: BuffFormatSettings, common: CommonCodeStyleSettings) {
            buff.lineLength?.let { common.RIGHT_MARGIN = it }
            val indent = common.indentOptions ?: return
            buff.indentWidth?.let {
                indent.INDENT_SIZE = it
                indent.TAB_SIZE = it
            }
            when (buff.indentStyle) {
                "tab" -> indent.USE_TAB_CHARACTER = true
                "space" -> indent.USE_TAB_CHARACTER = false
            }
        }
    }
}
