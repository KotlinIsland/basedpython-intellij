package dev.basedpython.pycharm.navigation

import com.intellij.ide.actions.QualifiedNameProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileSystemItem
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByModuleNameParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import org.jetbrains.annotations.TestOnly

/**
 * Copy Reference (Ctrl+Alt+Shift+C) on a `.by` module or a package: its dotted name, `shapes.circle`
 * for `src/shapes/circle.by` and `shapes` for `src/shapes`, as `by` imports it ([ByModuleNames]).
 *
 * Without this the Project view hides Copy Reference on a `.by` file and a package altogether: the
 * action shows itself on a file or a directory only when some provider names it, and offers Copy
 * Relative Path instead, `src/shapes/circle.by`. Measured in PyCharm 263.5701.32: hidden on both.
 *
 * ## What this cannot do: a name inside a `.by` editor
 *
 * In an editor the action names what the caret is on only when the platform finds a PSI element
 * there — a reference at the caret, or `PSI_ELEMENT` from the target-element search — and hands it
 * to the providers. A `.by` file's PSI is one leaf per token, with no declarations and no references
 * in it (see [dev.basedpython.pycharm.lang.BasedPythonParserDefinition]), so in a `.by` editor both
 * are null and the action copies `path:line` without asking a provider anything. Measured: the
 * caret on `Circle.area`'s name, on `Circle`, in a body and on a use of `Circle` in a test all copy
 * `src/shapes/circle.by:8` and the like, and `PSI_ELEMENT` is null at each. Naming a declaration
 * there would take a PSI element per declaration or per reference, which is the parser this plugin
 * no longer has, so `by` is not asked for one.
 *
 * Nor does this resolve a name back ([qualifiedNameToElement]): the platform asks that only to paste
 * a copied reference, which then inserts the text it would have pasted anyway unless the provider
 * also adds the import — inside the paste's write action, where `by` cannot be asked.
 */
class ByQualifiedNameProvider : QualifiedNameProvider {

    override fun adjustElementToCopy(element: PsiElement): PsiElement? = null

    override fun getQualifiedName(element: PsiElement): String? {
        val item = element as? PsiFileSystemItem ?: return null
        if (item !is BasedPythonFile && item !is PsiDirectory) return null
        val file = item.virtualFile ?: return null
        return ByModuleNames.getInstance(item.project).name(file)
    }

    override fun qualifiedNameToElement(fqn: String, project: Project): PsiElement? = null
}

/**
 * The names `by` imports files and directories by, `by/moduleName`.
 *
 * ## Which thread asks
 *
 * Copy Reference asks a provider twice: in its update, on a background thread, to decide whether to
 * show itself, and again in its action, on the EDT, for the name to copy. So off the EDT this asks
 * `by` and waits, briefly and cancellably, and remembers the answer; on the EDT it serves the answer
 * the update has just had for the same file at the same path, and asks nothing. A file moved or
 * renamed since is a path that answer is not for.
 *
 * A `by` from before the request answers `MethodNotFound`, which is no name: the action stays as it
 * was, offering the relative path.
 */
@Service(Service.Level.PROJECT)
internal class ByModuleNames(private val project: Project) {

    /** What `by` last said about a file, and the path it was at when it said it. */
    private class Named(val path: String, val name: String?)

    /** Weak keys: a file that is gone takes its name with it. */
    private val names: MutableMap<VirtualFile, Named> = ContainerUtil.createConcurrentWeakMap()

    /** [file]'s name, or null when `by` gives it none or has not been heard from. */
    fun name(file: VirtualFile): String? {
        if (ApplicationManager.getApplication().isDispatchThread) {
            return names[file]?.takeIf { it.path == file.path }?.name
        }
        val client = runningClient() ?: return null
        val path = file.path
        val answer = client.askBy("by/moduleName", TIMEOUT_MS) {
            (it as ByServerExtensions).moduleName(ByModuleNameParams(client.descriptor.getFileUri(file)))
        }
        return when (answer) {
            is ByAnswer.Answer -> answer.value.also { names[file] = Named(path, it) }
            ByAnswer.None -> null.also { names[file] = Named(path, null) }
            // not remembered: nobody answering is not a file with no name
            ByAnswer.Failed -> null.also { names.remove(file) }
        }
    }

    /** Puts in a name as though `by` had just given it. */
    @TestOnly
    fun remember(file: VirtualFile, name: String?) {
        names[file] = Named(file.path, name)
    }

    private fun runningClient(): LspClient? =
        LspClientManager.getInstance(project)
            .getClients(ByLspServerSupportProvider::class.java)
            .firstOrNull { it.state == LspServerState.Running }

    companion object {
        /**
         * How long an action update waits. A name is a lookup in the resolver's search paths, a
         * millisecond or two; one that has not come by this is a busy server, and the action is better
         * off showing what it shows without one.
         */
        private const val TIMEOUT_MS = 1_000

        fun getInstance(project: Project): ByModuleNames = project.service()
    }
}
