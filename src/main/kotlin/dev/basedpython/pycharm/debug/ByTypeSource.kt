package dev.basedpython.pycharm.debug

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.platform.dap.DapVariable
import com.intellij.platform.dap.xdebugger.AbstractDapXValue
import com.intellij.platform.dap.xdebugger.DapXDebuggerPresentationFactory
import com.intellij.platform.dap.xdebugger.DefaultDapXValue
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.frame.XNavigatable
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import dev.basedpython.pycharm.lsp.ByLspServerSupportProvider
import dev.basedpython.pycharm.lsp.ext.ByDeclaration
import dev.basedpython.pycharm.lsp.ext.ByTypeDeclarationParams
import dev.basedpython.pycharm.lsp.refactor.ByAsked
import dev.basedpython.pycharm.lsp.refactor.awaitByExtension
import kotlinx.coroutines.launch
import javax.swing.Icon

private val LOG = logger<ByTypeSource>()

/**
 * Where the class a stopped session names a value's type by is declared, asked of `by`
 * (`by/typeDeclaration`) — what *Jump to Type Source* on a variable goes to.
 *
 * The session knows a value's type only as the adapter wrote it in the variable's `type`, and that
 * is a name, not a place: no position in a document to ask `by/declaration` about, and
 * `workspace/symbol` matches loosely and only among the project's own files, where `builtins.int`
 * is not. Which class a dotted name names is resolution — submodules, re-exports, a stub where
 * there is no source — and that is `by`'s.
 *
 * A `by` from before the request answers `MethodNotFound`, and then, as for a name that reaches no
 * class, there is nowhere to go: the action does nothing, which is what it did before this existed.
 */
internal class ByTypeSource(
    val project: Project,
    /** The `by` to ask; a test's stands in for the project's. */
    private val server: (Project) -> LspClient? = ::runningBy,
) {

    /** The position of the class [typeName] names, or `null` for none, however `by` came to say so. */
    suspend fun of(typeName: String): XSourcePosition? {
        val client = server(project) ?: return null
        val asked = client.awaitByExtension("by/typeDeclaration") { it.typeDeclaration(ByTypeDeclarationParams(typeName)) }
        val declaration = when (asked) {
            is ByAsked.Answer -> asked.value
            ByAsked.None -> return null
            ByAsked.Unsupported -> {
                LOG.info("by/typeDeclaration: this by does not know the request")
                return null
            }
            is ByAsked.Rejected, ByAsked.Failed -> return null
        }
        return readAction { position(client, declaration) }
    }

    private fun position(client: LspClient, declaration: ByDeclaration): XSourcePosition? {
        val uri = declaration.uri ?: return null
        val start = (declaration.selectionRange ?: declaration.range)?.start ?: return null
        val file = client.descriptor.findFileByUri(uri) ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        val offset = getOffsetInDocument(document, start) ?: return null
        return XDebuggerUtil.getInstance().createPositionByOffset(file, offset)
    }

    companion object {
        /**
         * The dotted name to ask `by` about for a variable whose `type` bpd wrote as [type], or `null`
         * when it names no type.
         *
         * bpd writes `type(value)` qualified by its module unless it is a builtin (`Value.kind` in
         * bpd_core): `pkgx.model.Box`, `pkgx.model.Box.Meta`, but `int`. So a name with no dot is a
         * builtin's, and `by` is asked for it in `builtins`.
         */
        fun typeName(type: String?): String? {
            val name = type?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return if ('.' in name) name else "builtins.$name"
        }
    }
}

/**
 * The `by` running for [project]. `by/typeDeclaration` names no document: every `by` answers it for
 * the environment it resolves imports in, as `by/endpoints` is answered for the whole project.
 */
private fun runningBy(project: Project): LspClient? =
    LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
        .firstOrNull { it.state == LspServerState.Running }

/**
 * A variable of a bpd session, which *Jump to Type Source* takes to its type's class
 * ([ByTypeSource]); everything else is the platform's `DefaultDapXValue`, whose presentation this
 * hands to one made for the same variable.
 *
 * Only for bpd: debugpy writes a variable's `type` as `type(value).__name__` with no module
 * (`pydevd_xml.get_type`), and a bare `Box` cannot be told apart from a builtin or found at all.
 * [typeSource] is `null` for any other session, and the action is then not offered.
 */
internal class ByDapXValue(
    factory: DapXDebuggerPresentationFactory,
    executor: DapSessionExecutor,
    variable: DapVariable,
    icon: Icon?,
    private val typeSource: ByTypeSource?,
) : AbstractDapXValue(factory, executor, variable, icon) {

    private val platform = DefaultDapXValue(factory, executor, variable, icon)

    override fun createValuePresentation(variable: DapVariable, isStructured: Boolean, isLazy: Boolean): XValuePresentation =
        platform.createValuePresentation(variable, isStructured, isLazy)

    override fun canNavigateToTypeSource(): Boolean =
        typeSource != null && ByTypeSource.typeName(variable.type) != null

    /**
     * Called on the EDT, which waits for [navigatable] to be told — a timeout's worth — rather than
     * for this to return; so `by` is asked off it.
     */
    override fun computeTypeSourcePosition(navigatable: XNavigatable) {
        val typeName = ByTypeSource.typeName(variable.type)
        if (typeSource == null || typeName == null) return navigatable.setSourcePosition(null)
        val scope = typeSource.project.service<ByEvaluatorScope>().scope
        scope.launch {
            var position: XSourcePosition? = null
            try {
                position = typeSource.of(typeName)
            } finally {
                navigatable.setSourcePosition(position)
            }
        }
    }
}
