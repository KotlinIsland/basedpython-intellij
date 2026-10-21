package dev.basedpython.pycharm.lsp.outline

import com.intellij.codeInsight.daemon.impl.focusMode.FocusModeProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Segment
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.util.FileContentUtilCore
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByNamedDocumentSymbolParams
import dev.basedpython.pycharm.lsp.runningByServer
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.SymbolInformation
import org.jetbrains.annotations.TestOnly
import java.util.Collections

/**
 * Focus mode in `.by` (*View | Appearance | Focus Mode*): everything but the definition the caret
 * is in is dimmed.
 *
 * The zones are the definitions `by` reports in `textDocument/documentSymbol` — the same outline as
 * the Structure view — as Kotlin's are its declarations: each top-level class and function, and each
 * function of a class, from its first decorator to the end of its body. Nothing here reads the
 * file's syntax; a `.by` tree is one leaf per token and has no definitions to find.
 *
 * The platform asks once per PSI revision and caches the answer against the file, so it is asked
 * and waited for here, inside the background pass that wants it. A file asked about while `by`
 * could not answer gets no zones — the whole file in focus — and is re-parsed when a server comes
 * up, which is what makes the platform ask again ([ByFocusZones]).
 */
internal class ByFocusModeProvider : FocusModeProvider {

    override fun calcFocusZones(psiFile: PsiFile): List<Segment> = ByFocusZones.getInstance(psiFile.project).of(psiFile)
}

/** The focus zones of each `.by` file, as `by`'s document symbols draw them. */
@Service(Service.Level.PROJECT)
internal class ByFocusZones(private val project: Project) : Disposable {

    /** Files given no zones because no server answered; weakly held. */
    private val servedNothing: MutableSet<VirtualFile> =
        Collections.newSetFromMap(ContainerUtil.createConcurrentWeakMap())

    private var recorded: Map<VirtualFile, List<Segment>> = emptyMap()

    init {
        project.messageBus.connect(this).subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    if (serverName != BY_SERVER) return
                    val files = servedNothing.toList().filter { it.isValid }
                    servedNothing.clear()
                    if (files.isEmpty() || !EditorSettingsExternalizable.getInstance().isFocusMode) return
                    // The platform caches zones against the PSI; only a new PSI makes it ask again.
                    ApplicationManager.getApplication().invokeLater(
                        { FileContentUtilCore.reparseFiles(files) },
                        project.disposed,
                    )
                }
            },
        )
    }

    fun of(file: PsiFile): List<Segment> {
        val virtualFile = file.originalFile.virtualFile ?: return emptyList()
        recorded[virtualFile]?.let { return it }
        val symbols = ask(file, virtualFile) ?: return emptyList<Segment>().also { servedNothing.add(virtualFile) }
        return zones(symbols, file.text)
    }

    @TestOnly
    fun remember(file: VirtualFile, zones: List<Segment>) {
        recorded = recorded + (file to zones)
    }

    private fun ask(file: PsiFile, virtualFile: VirtualFile): List<Either<SymbolInformation, DocumentSymbol>>? {
        val server = runningByServer(project, virtualFile) ?: return null
        ByServerDocuments.ensureOpen(server, project, virtualFile)
        val params = ByNamedDocumentSymbolParams(server.getDocumentIdentifier(virtualFile), ByTextHash.of(file.text))
        return server.askBy("textDocument/documentSymbol", TIMEOUT_MS) {
            it.textDocumentService.documentSymbol(params)
        }.value ?: emptyList()
    }

    override fun dispose() {
        servedNothing.clear()
    }

    companion object {
        /** The name [dev.basedpython.pycharm.lsp.ByLspServerDescriptor] broadcasts under. */
        private const val BY_SERVER = "by"

        /** A parse, so an answer that has not come in this long is a server busy elsewhere. */
        private const val TIMEOUT_MS = 2_000

        private val DEFINITIONS = setOf(SymbolKind.Class, SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)
        private val MEMBERS = setOf(SymbolKind.Function, SymbolKind.Method, SymbolKind.Constructor)

        fun getInstance(project: Project): ByFocusZones = project.service()

        /**
         * The zones [symbols] draw in [text]: each top-level class and function, and each function of a
         * class, inner zones before the zone around them, in source order.
         */
        fun zones(symbols: List<Either<SymbolInformation, DocumentSymbol>>, text: CharSequence): List<Segment> {
            val lineStarts = buildList { add(0); text.forEachIndexed { i, c -> if (c == '\n') add(i + 1) } }
            fun range(symbol: DocumentSymbol): TextRange? {
                val start = lineStarts.getOrNull(symbol.range.start.line)?.plus(symbol.range.start.character) ?: return null
                val end = lineStarts.getOrNull(symbol.range.end.line)?.plus(symbol.range.end.character) ?: return null
                return TextRange(start, end.coerceAtMost(text.length)).takeIf { start <= it.endOffset }
            }
            val zones = mutableListOf<Segment>()
            for (symbol in symbols.mapNotNull { it.right }.filter { it.kind in DEFINITIONS }.sortedBy { range(it)?.startOffset }) {
                if (symbol.kind == SymbolKind.Class) {
                    symbol.children.orEmpty().filter { it.kind in MEMBERS }.sortedBy { range(it)?.startOffset }
                        .forEach { member -> range(member)?.let(zones::add) }
                }
                range(symbol)?.let(zones::add)
            }
            return zones
        }
    }
}
