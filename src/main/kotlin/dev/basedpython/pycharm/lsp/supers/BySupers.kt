package dev.basedpython.pycharm.lsp.supers

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByNamedDocumentSymbolParams
import dev.basedpython.pycharm.lsp.ext.ByNamedTypeHierarchyPrepareParams
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
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.TypeHierarchySupertypesParams
import org.eclipse.lsp4j.jsonrpc.messages.Either

/** Where Go to Super can go from the caret, or why it cannot go anywhere. */
internal sealed interface BySuperAnswer {

    /** One or more places to go: one is gone to, several are offered. */
    data class Targets(val chooserTitle: String, val targets: List<BySuperTarget>) : BySuperAnswer

    /** Nowhere, and what to tell the user about why. */
    data class Nowhere(val message: String) : BySuperAnswer
}

/** A place Go to Super can go, with what to call it in a chooser. */
internal data class BySuperTarget(
    /** The class, or `Class.member`. */
    val name: String,
    /** Where it is, for a chooser to say: the module, or the file. */
    val where: String?,
    val location: Location,
)

/** The definition the caret is in that Go to Super answers for, as `by`'s outline has it. */
internal sealed interface BySuperSubject {

    /** A class: Go to Super goes to its bases. */
    data class Class(val symbol: DocumentSymbol) : BySuperSubject

    /** A member of [owner]'s body: Go to Super goes to what it overrides. */
    data class Member(val symbol: DocumentSymbol, val owner: DocumentSymbol) : BySuperSubject
}

/**
 * Go to Super (Ctrl+U) in a `.by` file, asked of `by`.
 *
 * Everything that decides where to go is `by`'s. Which definition the caret is in comes from its
 * `textDocument/documentSymbol` outline, and a class's bases from its type hierarchy: the
 * `typeHierarchy/supertypes` of the class and of each of its bases in turn, listed in the class's
 * method resolution order as PyCharm lists a `.py` class's superclasses ([ancestors]). What a member
 * overrides is `by/superMembers`, the override checks' own answer.
 *
 * Every request names the text the caret's position was read in ([ByTextHash]), so each is answered
 * about that text: the outline's ranges, the class's position and the member's all mean the same
 * thing. That also covers pressing Ctrl+U in a file the platform has not yet sent `by` a `didOpen`
 * or the latest `didChange` for — `by` answers once the notification brings the text, where it
 * used to refuse the document, or answer about the text before the edit.
 */
internal object BySupers {

    /**
     * Where Go to Super goes from [offset] in [file], asking [server].
     *
     * Suspends on the server and gives up the moment the caller is cancelled. Reads the document
     * under a read action of its own.
     */
    suspend fun find(server: LspClient, file: VirtualFile, document: Document, offset: Int): BySuperAnswer {
        val (identifier, textHash, caret) = readAction {
            ByServerDocuments.ensureOpen(server, server.project, file)
            // the caret and the text it is in, read together
            Triple(server.getDocumentIdentifier(file), ByTextHash.of(document.immutableCharSequence), getLsp4jPosition(document, offset))
        }

        val symbols = when (
            val answer = server.awaitBy("textDocument/documentSymbol") {
                it.textDocumentService.documentSymbol(ByNamedDocumentSymbolParams(identifier, textHash))
            }
        ) {
            is ByAnswer.Answer -> answer.value
            ByAnswer.None -> emptyList()
            ByAnswer.Failed -> return nowhere("goto.super.noAnswer", "textDocument/documentSymbol")
        }

        return when (val subject = subjectAt(symbols, caret)) {
            null -> nowhere("goto.super.nothingHere")
            is BySuperSubject.Class -> bases(server, identifier, textHash, subject.symbol)
            is BySuperSubject.Member -> overridden(server, identifier, textHash, subject)
        }
    }

    /** The bases of [symbol], a class, from `by`'s type hierarchy. */
    private suspend fun bases(
        server: LspClient,
        document: TextDocumentIdentifier,
        textHash: String,
        symbol: DocumentSymbol,
    ): BySuperAnswer {
        val prepareParams = ByNamedTypeHierarchyPrepareParams(document, symbol.selectionRange.start, textHash)
        val item = when (
            val answer = server.awaitBy("textDocument/prepareTypeHierarchy") {
                it.textDocumentService.prepareTypeHierarchy(prepareParams)
            }
        ) {
            is ByAnswer.Answer -> answer.value.firstOrNull() ?: return nowhere("goto.super.class.unknown", symbol.name)
            ByAnswer.None -> return nowhere("goto.super.class.unknown", symbol.name)
            ByAnswer.Failed -> return nowhere("goto.super.noAnswer", "textDocument/prepareTypeHierarchy")
        }
        val ancestors = ancestors(item) { of ->
            when (val answer = server.awaitBy("typeHierarchy/supertypes") { it.textDocumentService.typeHierarchySupertypes(TypeHierarchySupertypesParams(of)) }) {
                is ByAnswer.Answer -> answer.value
                ByAnswer.None -> emptyList()
                ByAnswer.Failed -> null
            }
        } ?: return nowhere("goto.super.noAnswer", "typeHierarchy/supertypes")
        if (ancestors.isEmpty()) return nowhere("goto.super.class.none", item.name)
        return BySuperAnswer.Targets(
            BasedPythonBundle.message("goto.super.class.chooser", item.name),
            ancestors.map(::classTarget),
        )
    }

    /**
     * Every class [item] inherits from, in its method resolution order without itself — `C`, `B`,
     * `A`, `M`, `object` for `class D(C)` with `class C(B, M)` and `class B(A)` — as PyCharm lists a
     * `.py` class's superclasses. Each class's direct bases are `by`'s `typeHierarchy/supertypes`,
     * asked by [supertypes] once per class; the order is C3's merge of them, the linearisation Python
     * itself uses. A hierarchy C3 cannot linearise, which Python refuses to create, is listed in the
     * order the classes were first reached. Null when `by` did not answer for some class.
     */
    suspend fun ancestors(
        item: TypeHierarchyItem,
        supertypes: suspend (TypeHierarchyItem) -> List<TypeHierarchyItem>?,
    ): List<TypeHierarchyItem>? {
        val bases = HashMap<HierarchyKey, List<HierarchyKey>>()
        val items = LinkedHashMap<HierarchyKey, TypeHierarchyItem>()
        val pending = ArrayDeque(listOf(item))
        items[HierarchyKey.of(item)] = item
        while (pending.isNotEmpty() && items.size <= MAX_ANCESTORS) {
            val next = pending.removeFirst()
            val key = HierarchyKey.of(next)
            if (key in bases) continue
            val direct = supertypes(next) ?: return null
            bases[key] = direct.map { base -> HierarchyKey.of(base).also { if (items.putIfAbsent(it, base) == null) pending.addLast(base) } }
        }
        val linearised = HashMap<HierarchyKey, List<HierarchyKey>?>()
        fun mro(key: HierarchyKey, visiting: Set<HierarchyKey>): List<HierarchyKey>? {
            if (key in linearised) return linearised[key]
            if (key in visiting) return null
            val direct = bases[key].orEmpty()
            val sequences = direct.map { mro(it, visiting + key) ?: return null }.map { it.toMutableList() } + listOf(direct.toMutableList())
            val result = mutableListOf(key)
            while (sequences.any { it.isNotEmpty() }) {
                val head = sequences.firstNotNullOfOrNull { sequence ->
                    sequence.firstOrNull()?.takeIf { candidate -> sequences.none { candidate in it.drop(1) } }
                } ?: return null.also { linearised[key] = null }
                result += head
                for (sequence in sequences) sequence.remove(head)
            }
            return result.also { linearised[key] = it }
        }
        val order = mro(HierarchyKey.of(item), emptySet())?.drop(1) ?: items.keys.drop(1)
        return order.mapNotNull { items[it] }
    }

    /** One class of a hierarchy, as the same class however `by` reached it. */
    private data class HierarchyKey(val uri: String, val line: Int, val character: Int, val name: String) {
        companion object {
            fun of(item: TypeHierarchyItem) =
                HierarchyKey(item.uri, item.selectionRange.start.line, item.selectionRange.start.character, item.name)
        }
    }

    /** A bound on the classes walked, against a hierarchy that is not one. */
    private const val MAX_ANCESTORS = 200

    /**
     * What [subject], a class member of the text [textHash] names, overrides, from `by/superMembers`.
     *
     * A `by` that does not know the request is said to be one, rather than read as a member that
     * overrides nothing or answered some other way: nothing else can say what a member overrides.
     */
    suspend fun overridden(
        server: LspClient,
        document: TextDocumentIdentifier,
        textHash: String,
        subject: BySuperSubject.Member,
    ): BySuperAnswer =
        overridden(server, document, textHash, subject.symbol.selectionRange.start, "${subject.owner.name}.${subject.symbol.name}")

    /**
     * What the class member declared at [name], a position on its name in the text [textHash]
     * names, overrides: the same question, asked from where the member's name is rather than from
     * `by`'s outline. [member] is what to call it, `Class.member`.
     */
    suspend fun overridden(
        server: LspClient,
        document: TextDocumentIdentifier,
        textHash: String,
        name: Position,
        member: String,
    ): BySuperAnswer {
        val params = BySuperMembersParams(document, name, textHash)
        var unknownRequest = false
        val answer = awaitingAgain<List<BySuperMember>>("by/superMembers", LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong()) { sent ->
            try {
                server.sendRequest { (it as ByServerExtensions).superMembers(params).also(sent) }
            } catch (e: Exception) {
                if (isMethodNotFound(e)) unknownRequest = true
                throw e
            }
        }
        val members = when (answer) {
            is ByAnswer.Answer -> answer.value
            ByAnswer.None -> return nowhere("goto.super.member.unknown", member)
            ByAnswer.Failed ->
                return if (unknownRequest) {
                    nowhere("goto.super.member.unsupported", member)
                } else {
                    nowhere("goto.super.noAnswer", "by/superMembers")
                }
        }
        val targets = members.mapNotNull(::memberTarget)
        if (targets.isEmpty()) return nowhere("goto.super.member.none", member)
        return BySuperAnswer.Targets(BasedPythonBundle.message("goto.super.member.chooser", member), targets)
    }

    /** Where an overridden member is: `Class.member`, and the file that declares it. */
    fun memberTarget(member: BySuperMember): BySuperTarget? {
        val uri = member.uri ?: return null
        val range = member.selectionRange ?: return null
        val file = (runCatching { java.net.URI(uri).path }.getOrNull() ?: uri).substringAfterLast('/')
        return BySuperTarget(
            "${member.containerName}.${member.name}",
            if (member.synthesized) BasedPythonBundle.message("goto.super.member.synthesized", file) else file,
            Location(uri, range),
        )
    }

    /** Where a base class is: its name, where the platform opens it. */
    fun classTarget(item: TypeHierarchyItem): BySuperTarget =
        BySuperTarget(item.name, item.detail, Location(item.uri, item.selectionRange))

    /**
     * The class or class member the caret at [caret] is in: the innermost symbol around it that is
     * either a class or one of a class's own members. `null` when the caret is in neither — at the
     * top level of a module, or in a function no class holds.
     *
     * A caret inside a method's body is in the method, as in any IDE's Go to Super; one inside a
     * class nested in a method is in that nested class. `by` answers the outline hierarchically; the
     * deprecated flat shape carries no nesting to find a member's class in, so it answers nothing.
     */
    fun subjectAt(symbols: List<Either<SymbolInformation, DocumentSymbol>>, caret: Position): BySuperSubject? {
        var subject: BySuperSubject? = null
        var parent: DocumentSymbol? = null
        var level: List<DocumentSymbol> = symbols.mapNotNull { if (it.isRight) it.right else null }
        while (true) {
            val around = level.firstOrNull { contains(it.range, caret) } ?: return subject
            when {
                around.kind == SymbolKind.Class -> subject = BySuperSubject.Class(around)
                parent?.kind == SymbolKind.Class -> subject = BySuperSubject.Member(around, parent)
            }
            parent = around
            level = around.children.orEmpty()
        }
    }

    private fun contains(range: Range, position: Position): Boolean =
        !before(position, range.start) && !before(range.end, position)

    private fun before(a: Position, b: Position): Boolean =
        a.line < b.line || (a.line == b.line && a.character < b.character)

    private fun nowhere(key: String, vararg params: Any): BySuperAnswer.Nowhere =
        BySuperAnswer.Nowhere(BasedPythonBundle.message(key, *params))
}
