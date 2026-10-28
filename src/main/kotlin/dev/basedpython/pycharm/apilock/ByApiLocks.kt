package dev.basedpython.pycharm.apilock

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByApiLock
import dev.basedpython.pycharm.lsp.ext.ByApiLockParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/** Where the `api.lock` work the user asks for runs: cancelled with the project, or with the plugin. */
@Service(Service.Level.PROJECT)
internal class ByApiLockScope(val scope: CoroutineScope)

/** What asking `by` for the project's `api.lock` came to: the lockfile, or why there is none. */
internal sealed interface ByApiLockAnswer {

    class Lock(val lock: ByApiLock) : ByApiLockAnswer

    class Not(val why: String) : ByApiLockAnswer
}

/**
 * The project's `api.lock`, as `by` generates it from what its server holds — unsaved edits
 * included, for the python target the lockfile on disk names — and written where `by` says the
 * project keeps it. `by/apiLock` is the one place that knows what the lockfile should say; nothing
 * here runs `by generate-api-file`, which reads the disk and would miss what the editor holds.
 */
internal object ByApiLocks {

    /**
     * The project's lockfile, starting `by` if it is not running, or why there is none.
     *
     * No timeout: the first answer of a fresh server is a whole-project pass (6–8 s measured on 395
     * modules; tens of milliseconds after that), and every caller runs it under a progress the user
     * can cancel.
     */
    suspend fun ask(project: Project): ByApiLockAnswer {
        val client = when (val start = awaitByServer(project)) {
            is ByServerStart.Running -> start.client
            is ByServerStart.Unavailable -> return ByApiLockAnswer.Not(BasedPythonBundle.message("apiLock.noServer", start.why))
        }
        val params = ByApiLockParams(project.guessProjectDir()?.url)
        var refusal: Throwable? = null
        val answer = awaitingAgain<ByApiLock>("by/apiLock", Long.MAX_VALUE) { sent ->
            try {
                client.sendRequest { (it as ByServerExtensions).apiLock(params).also(sent) }
            } catch (e: Exception) {
                if (e !is CancellationException) refusal = e
                throw e
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> ByApiLockAnswer.Lock(answer.value)
            ByAnswer.None -> ByApiLockAnswer.Not(BasedPythonBundle.message("apiLock.noProject"))
            ByAnswer.Failed -> ByApiLockAnswer.Not(
                when {
                    refusal != null && isMethodNotFound(refusal!!) -> BasedPythonBundle.message("apiLock.unsupported")
                    refusal != null -> BasedPythonBundle.message("apiLock.refused", refusal!!.message.orEmpty())
                    else -> BasedPythonBundle.message("apiLock.noAnswer")
                },
            )
        }
    }

    /**
     * Writes [lock]'s text to its lockfile, creating it when there is none, and saves it, so that a
     * commit, which reads the disk, sees it. One undoable command; a lockfile that already says
     * exactly this is left alone. Returns the lockfile, or `null` when its folder is gone.
     */
    suspend fun write(project: Project, lock: ByApiLock): VirtualFile? {
        val path = Path.of(URI(lock.uri))
        val folder = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.parent) } ?: return null
        return withContext(Dispatchers.EDT) {
            WriteCommandAction.writeCommandAction(project).withName(BasedPythonBundle.message("apiLock.write.command")).compute<VirtualFile, Exception> {
                val name = path.fileName.toString()
                // A new lockfile is written whole: an empty file of a name no file type claims has its
                // type detected from its content, and until then it may have no document to edit.
                val file = folder.findChild(name) ?: return@compute folder.createChildData(this, name).also { VfsUtil.saveText(it, lock.text) }
                val documents = FileDocumentManager.getInstance()
                val document = documents.getDocument(file) ?: return@compute file.also { VfsUtil.saveText(it, lock.text) }
                if (document.text != lock.text) document.setText(lock.text)
                documents.saveDocument(document)
                file
            }
        }
    }

    /** How many records [text], a lockfile, has: every line that is not a `#` header. */
    fun records(text: String): Int = text.lineSequence().count { it.isNotEmpty() && !it.startsWith("#") }
}
