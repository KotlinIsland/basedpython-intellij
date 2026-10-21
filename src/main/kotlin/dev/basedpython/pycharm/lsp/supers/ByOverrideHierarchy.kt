package dev.basedpython.pycharm.lsp.supers

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByNamedDocumentSymbolParams
import dev.basedpython.pycharm.lsp.ext.ByNamedImplementationParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.BySuperMember
import dev.basedpython.pycharm.lsp.ext.BySuperMembersParams
import dev.basedpython.pycharm.lsp.isMethodNotFound
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.atomic.AtomicBoolean

/** A class's declaration of the member, as `by` names it. */
internal data class ByDeclared(
    /** The file it is in. Declarations are told apart by file and name, not by URI, which two answers may spell differently. */
    val file: VirtualFile,
    /** Where it is, as `by` gave it, for going there. */
    val uri: String,
    /** The member's name where it is declared. */
    val selectionRange: Range,
    /** The class that declares it; null when `by`'s outline of its file does not say. */
    val container: String?,
    /** The member's name. */
    val member: String,
    /** Where the class is, for the tree to say: its file. */
    val where: String?,
    /** The member is abstract here, so the class declares it without defining it. */
    val abstract: Boolean,
)

/** One class's declaration of the member in a method hierarchy, and the declarations below it. */
internal data class ByOverrideNode(
    val declared: ByDeclared,
    /** The member the hierarchy was opened on. */
    val isBase: Boolean,
    /** The members that override this one directly, by file and then in source order. */
    val children: List<ByOverrideNode>,
)

/** What a method hierarchy holds, or why it holds nothing. */
internal sealed interface ByOverrideHierarchyAnswer {

    /**
     * From the topmost member the base overrides, down through what each overrides, to the base, and
     * below it every member that overrides it. [base] is in [root]'s tree.
     */
    data class Tree(val root: ByOverrideNode, val base: ByOverrideNode) : ByOverrideHierarchyAnswer

    data class Nowhere(val message: String) : ByOverrideHierarchyAnswer
}

/**
 * The method hierarchy (Ctrl+Shift+H) of the class member at a place in a `.by` document, asked of
 * `by` — the override hierarchy, as Kotlin's is: the classes that declare the member, above and below
 * the one the caret is in.
 *
 * Everything that decides it is `by`'s. Which member the caret is in comes from its outline, as Go to
 * Super's does ([BySupers.subjectAt]). Up is `by/superMembers`, asked of the member and again of
 * what it overrides until nothing is left — each time the first of what it gives, the nearest along
 * the MRO, the line Java's hierarchy follows up too. Down is `textDocument/implementation`, which
 * gives every member that overrides the base, however far below it; each one's place in the tree is
 * under the member `by/superMembers` says it overrides directly, and its class is the class `by`'s
 * outline of its file declares it in.
 *
 * A class that declares no member of its own is not in the tree, as with the platform's "show only
 * classes implementing the method": what `by` answers about are declarations, not classes.
 *
 * Every request about a document names the text it is asked about ([ByTextHash]), as Go to Super's
 * do. Background only, in a read action — the hierarchy's own tree is built that way — and waits on
 * `by` there, cancellably.
 */
internal object ByOverrideHierarchy {

    /** How long one request waits: as long as `by` holds a request for text it has not been sent. */
    private const val TIMEOUT_MS = 10_000

    /** How far up to follow what a member overrides, against a server that answers in a circle. */
    private const val MAX_DEPTH = 64

    fun build(client: LspClient, file: VirtualFile, document: Document, offset: Int): ByOverrideHierarchyAnswer {
        ByServerDocuments.ensureOpen(client, client.project, file)
        val uri = client.descriptor.getFileUri(file)
        val textHash = ByTextHash.of(document.immutableCharSequence)

        val symbols = when (val answer = documentSymbols(client, uri, textHash)) {
            is ByAnswer.Answer -> answer.value
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return nowhere("goto.super.noAnswer", "textDocument/documentSymbol")
        }
        val subject = BySupers.subjectAt(symbols, getLsp4jPosition(document, offset)) as? BySuperSubject.Member
            ?: return nowhere("method.hierarchy.nothingHere")
        val member = subject.symbol.name
        val base = ByDeclared(file, uri, subject.symbol.selectionRange, subject.owner.name, member, file.name, abstract = false)

        // up: each member's nearest overridden one, until there is none
        val supers = mutableListOf<ByDeclared>()
        var at = base
        while (supers.size < MAX_DEPTH) {
            val overridden = when (val answer = superMembers(client, at.uri, at.selectionRange.start)) {
                is Asked.Answered -> answer.members
                Asked.Unsupported -> return nowhere("method.hierarchy.unsupported", "by/superMembers")
                Asked.Failed -> return nowhere("goto.super.noAnswer", "by/superMembers")
            }
            val nearest = overridden.firstNotNullOfOrNull { declared(client, it, member) } ?: break
            if (nearest.place == at.place || supers.any { it.place == nearest.place }) break
            supers += nearest
            at = nearest
        }

        // down: every member overriding the base, each under what it overrides directly
        val locations = when (val answer = implementations(client, uri, base.selectionRange.start, textHash)) {
            is ByAnswer.Answer -> answer.value
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return nowhere("goto.super.noAnswer", "textDocument/implementation")
        }
        val containers = containers(client, locations)
        val implementations = locations.mapNotNull { location ->
            val implementationFile = client.descriptor.findFileByUri(location.uri) ?: return@mapNotNull null
            ByDeclared(
                implementationFile, location.uri, location.range, containers[location], member, implementationFile.name,
                abstract = false,
            )
        }.filter { it.place != base.place }
        val parents = mutableMapOf<ByDeclared, ByDeclared>()
        for (implementation in implementations) {
            val overridden = when (val answer = superMembers(client, implementation.uri, implementation.selectionRange.start)) {
                is Asked.Answered -> answer.members
                Asked.Unsupported -> return nowhere("method.hierarchy.unsupported", "by/superMembers")
                Asked.Failed -> return nowhere("goto.super.noAnswer", "by/superMembers")
            }
            val places = overridden.mapNotNull { declared(client, it, member)?.place }
            parents[implementation] = places.firstNotNullOfOrNull { place ->
                (implementations + base).firstOrNull { it.place == place }
            } ?: base
        }
        return assemble(base, supers, parents)
    }

    /**
     * The tree: [supers], nearest first, above [base], each the only child of the one above it, and
     * below [base] every declaration [parents] maps, under the declaration it maps it to.
     *
     * `by` says each of [parents]' keys overrides the base, however far below, so one whose direct
     * parent is not in the tree — none of what it overrides directly is among them — hangs from the
     * base.
     */
    @VisibleForTesting
    fun assemble(base: ByDeclared, supers: List<ByDeclared>, parents: Map<ByDeclared, ByDeclared>): ByOverrideHierarchyAnswer.Tree {
        val order = compareBy<ByDeclared>({ it.file.path }, { it.selectionRange.start.line }, { it.selectionRange.start.character })
        fun below(parent: ByDeclared): List<ByOverrideNode> =
            parents.filterValues { it.place == parent.place }.keys
                .sortedWith(order)
                .map { ByOverrideNode(it, isBase = false, children = below(it)) }

        val baseNode = ByOverrideNode(base, isBase = true, children = below(base))
        val root = supers.fold(baseNode) { child, declared -> ByOverrideNode(declared, isBase = false, children = listOf(child)) }
        return ByOverrideHierarchyAnswer.Tree(root, baseNode)
    }

    /** What tells declarations apart: their file and their name. */
    private val ByDeclared.place: Pair<VirtualFile, Range> get() = file to selectionRange

    /** [overridden] as a declaration, or null when its file is not there. */
    private fun declared(client: LspClient, overridden: BySuperMember, member: String): ByDeclared? {
        val uri = overridden.uri ?: return null
        val range = overridden.selectionRange ?: return null
        val file = client.descriptor.findFileByUri(uri) ?: return null
        return ByDeclared(
            file, uri, range, overridden.containerName, overridden.name ?: member,
            BySupers.memberTarget(overridden)?.where, overridden.abstract,
        )
    }

    private sealed interface Asked {
        data class Answered(val members: List<BySuperMember>) : Asked
        data object Unsupported : Asked
        data object Failed : Asked
    }

    /** What the member whose name is at [position] in [uri] overrides directly, in MRO order. */
    private fun superMembers(client: LspClient, uri: String, position: Position): Asked {
        val file = client.descriptor.findFileByUri(uri) ?: return Asked.Answered(emptyList())
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return Asked.Answered(emptyList())
        ByServerDocuments.ensureOpen(client, client.project, file)
        val params = BySuperMembersParams(TextDocumentIdentifier(uri), position, ByTextHash.of(document.immutableCharSequence))
        val unsupported = AtomicBoolean()
        val answer = client.askBy("by/superMembers", TIMEOUT_MS) { server ->
            (server as ByServerExtensions).superMembers(params).whenComplete { _, error ->
                if (error != null && isMethodNotFound(error)) unsupported.set(true)
            }
        }
        return when (answer) {
            is ByAnswer.Answer -> Asked.Answered(answer.value)
            // no class member declared there, which is nothing to go up from
            ByAnswer.None -> Asked.Answered(emptyList())
            ByAnswer.Failed -> if (unsupported.get()) Asked.Unsupported else Asked.Failed
        }
    }

    private fun documentSymbols(client: LspClient, uri: String, textHash: String): ByAnswer<List<Either<SymbolInformation, DocumentSymbol>>> =
        client.askBy("textDocument/documentSymbol", TIMEOUT_MS) {
            it.textDocumentService.documentSymbol(ByNamedDocumentSymbolParams(TextDocumentIdentifier(uri), textHash))
        }

    private fun implementations(client: LspClient, uri: String, position: Position, textHash: String): ByAnswer<List<Location>> {
        val params = ByNamedImplementationParams(TextDocumentIdentifier(uri), position, textHash)
        return when (val answer = client.askBy("textDocument/implementation", TIMEOUT_MS) { it.textDocumentService.implementation(params) }) {
            is ByAnswer.Answer -> ByAnswer.Answer(
                if (answer.value.isLeft) {
                    answer.value.left
                } else {
                    answer.value.right.map { Location(it.targetUri, it.targetSelectionRange) }
                },
            )
            ByAnswer.None -> ByAnswer.None
            ByAnswer.Failed -> ByAnswer.Failed
        }
    }

    /**
     * The class each of [members] is declared in, from `by`'s outline of its file: the class one of
     * whose children is named where the member is. One outline per file.
     */
    private fun containers(client: LspClient, members: List<Location>): Map<Location, String> {
        val found = mutableMapOf<Location, String>()
        for ((uri, inFile) in members.groupBy { it.uri }) {
            val file = client.descriptor.findFileByUri(uri) ?: continue
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            ByServerDocuments.ensureOpen(client, client.project, file)
            val symbols = documentSymbols(client, uri, ByTextHash.of(document.immutableCharSequence)).value.orEmpty()
            val classes = symbols.mapNotNull { if (it.isRight) it.right else null }.flatMap(::classes)
            for (member in inFile) {
                val owner = classes.firstOrNull { owner -> owner.children.orEmpty().any { it.selectionRange == member.range } }
                if (owner != null) found[member] = owner.name
            }
        }
        return found
    }

    /** [symbol] and every class nested in it, if it is one. */
    private fun classes(symbol: DocumentSymbol): List<DocumentSymbol> =
        (if (symbol.kind == SymbolKind.Class) listOf(symbol) else emptyList()) +
            symbol.children.orEmpty().flatMap(::classes)

    private fun nowhere(key: String, vararg params: Any): ByOverrideHierarchyAnswer.Nowhere =
        ByOverrideHierarchyAnswer.Nowhere(BasedPythonBundle.message(key, *params))
}
