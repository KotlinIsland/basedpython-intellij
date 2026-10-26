package dev.basedpython.pycharm.lsp.usages

import com.intellij.find.actions.FindUsagesAction
import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageHandler
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.injected.editor.EditorWindow
import com.intellij.model.Pointer
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DataSnapshot
import com.intellij.openapi.actionSystem.UiDataRule
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.Query
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByDeclaration
import dev.basedpython.pycharm.navigation.ByDeclarations
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.settings.BasedPythonSettings
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Find Usages (Alt+F7) and Show Usages (Ctrl+Alt+F7) on a name in a `.by` file, from `by`'s
 * `textDocument/references` — the usages of the name, without the declaration itself.
 *
 * The platform's own LSP find usages could not do either: it asks with `includeDeclaration = true`
 * written into its request, so the declaration was listed as one of its own usages (9 results
 * against Python's 8 for a class, a Show Usages popup with the `def` line in it marked Current), and
 * it names what was searched for by the file and position it was searched from, `basedpython: Find
 * References (base.by:28:8)`. Neither is a customization it offers. So this provides the search
 * target ahead of it ([BySearchTargetsRule], registered first) and searches for it ([ByUsageSearcher]),
 * and names it by the name at the caret, drawn as the kind `by/declaration` says it declares and
 * placed in what declares it.
 *
 * Which name is at the caret is lexical, an identifier token; what it refers to, and so where it is
 * used, is `by`'s. A caret on anything that is not a name has no target here, and the platform's
 * own takes over.
 */
internal class BySearchTarget(
    val project: Project,
    val file: VirtualFile,
    /** Where the name starts. */
    val offset: Int,
    val name: String,
    /**
     * What `by/declaration` says the name declares, when it was asked and answered: the kind it is
     * drawn with and the dotted name it is placed by. Not part of what the target is — two asks at
     * one name are the same search whatever each was told.
     */
    val declaration: ByDeclaration? = null,
) : SearchTarget {

    override fun createPointer(): Pointer<out SearchTarget> = Pointer.hardPointer(this)

    /**
     * The name, drawn as the kind it declares and placed in what declares it, `area` in
     * `shapes.base.Circle` — as PyCharm presents a `.py` target, `area (Circle in shapes.base)`.
     * Without an answer from `by`, the name in its file.
     */
    override fun presentation(): TargetPresentation {
        val kind = ByDeclarations.symbolKind(declaration?.kind)
        val icon = kind?.let { SYMBOL_KINDS.getIcon(it) } ?: file.fileType.icon
        val container = ByDeclarations.container(declaration?.qualifiedName, declaration?.name)
        return TargetPresentation.builder(name)
            .icon(icon)
            .let { if (container != null) it.containerText(container).locationText(container) else it.locationText(file.name) }
            .presentation()
    }

    override val usageHandler: UsageHandler = UsageHandler.createEmptyUsageHandler(name)

    override fun equals(other: Any?): Boolean =
        other is BySearchTarget && other.file == file && other.offset == offset

    override fun hashCode(): Int = 31 * file.hashCode() + offset

    /**
     * This target with what [server] says its name declares. Asked when an action wants the target,
     * on a background thread; a `by` without `by/declaration`, or one that does not answer within a
     * moment, leaves the target named by the name in its file, as before.
     */
    fun declared(server: LspClient, document: com.intellij.openapi.editor.Document): BySearchTarget {
        val declaration = ByDeclarations.at(server, file, document, offset, DECLARATION_TIMEOUT_MS).value ?: return this
        return BySearchTarget(project, file, offset, name, declaration)
    }

    companion object {
        /** The icons `by`'s descriptor shows symbol kinds with: the platform's default customizer. */
        private val SYMBOL_KINDS = LspSymbolKindCustomizer()

        /** A declaration is one lookup; the search goes on without it rather than wait on a busy server. */
        private const val DECLARATION_TIMEOUT_MS = 1_000

        /**
         * The name at [offset] in [psi] as a target, or `null` off a name. A caret just after a name
         * is on it, as it is for every other language's Find Usages.
         */
        fun at(psi: BasedPythonFile, file: VirtualFile, offset: Int): BySearchTarget? {
            val leaf = sequenceOf(offset, offset - 1).filter { it >= 0 }
                .mapNotNull { psi.findElementAt(it) }
                .firstOrNull { it.node.elementType == BasedPythonTokenTypes.IDENTIFIER }
                ?: return null
            return BySearchTarget(psi.project, file, leaf.textRange.startOffset, leaf.text)
        }
    }
}

/** Offers the name at a `.by` editor's caret as what Find Usages and Show Usages search for. */
internal class BySearchTargetsRule : UiDataRule {

    override fun uiDataSnapshot(sink: DataSink, snapshot: DataSnapshot) {
        val editor = snapshot[CommonDataKeys.EDITOR]?.takeIf { it !is EditorWindow } ?: return
        val project = editor.project?.takeIf { !it.isDefault } ?: return
        if (!BasedPythonSettings.getInstance(project).byFindReferences) return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val offset = editor.caretModel.offset
        sink.lazyValue(FindUsagesAction.SEARCH_TARGETS) {
            listOfNotNull(target(project, file, document, offset))
        }
    }

    private fun target(project: Project, file: VirtualFile, document: com.intellij.openapi.editor.Document, offset: Int): SearchTarget? {
        val psi = PsiDocumentManager.getInstance(project).getPsiFile(document) as? BasedPythonFile ?: return null
        val server = byServerFor(project, file) ?: return null
        val target = BySearchTarget.at(psi, file, offset) ?: return null
        return target.declared(server, document)
    }
}

/** Searches for a [BySearchTarget]'s usages: `by`'s references to it, its declaration not among them. */
internal class ByUsageSearcher : UsageSearcher {

    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<out Usage>? {
        val target = parameters.target as? BySearchTarget ?: return null
        return object : AbstractQuery<Usage>() {
            override fun processResults(consumer: Processor<in Usage>): Boolean = search(target, consumer)
        }
    }

    private fun search(target: BySearchTarget, consumer: Processor<in Usage>): Boolean {
        val server = byServerFor(target.project, target.file) ?: return true
        return usages(server, target, consumer)
    }

    /** What [server] answers for [target], each handed to [consumer] while it goes on accepting them. */
    internal fun usages(server: LspClient, target: BySearchTarget, consumer: Processor<in Usage>): Boolean {
        val params = ReadAction.computeBlocking<ReferenceParams?, RuntimeException> {
            val document = FileDocumentManager.getInstance().getDocument(target.file) ?: return@computeBlocking null
            ReferenceParams(
                server.getDocumentIdentifier(target.file).let { TextDocumentIdentifier(it.uri) },
                getLsp4jPosition(document, target.offset),
                ReferenceContext(false),
            )
        } ?: return true
        val locations = server.askBy("textDocument/references", TIMEOUT_MS) {
            it.textDocumentService.references(params)
        }.value.orEmpty()
        return ReadAction.computeBlocking<Boolean, RuntimeException> {
            val psiManager = PsiManager.getInstance(target.project)
            locations.all { location ->
                val file = server.descriptor.findFileByUri(location.uri) ?: return@all true
                val psi = psiManager.findFile(file) ?: return@all true
                val range = getRangeInDocument(psi.fileDocument, location.range) ?: return@all true
                consumer.process(PsiUsage.textUsage(psi, range))
            }
        }
    }

    private companion object {
        /** As long as the platform's own LSP find usages waits: a whole-workspace search. */
        const val TIMEOUT_MS = 60_000
    }
}
