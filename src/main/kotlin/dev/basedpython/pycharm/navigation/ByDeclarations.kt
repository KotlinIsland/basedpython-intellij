package dev.basedpython.pycharm.navigation

import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.ext.ByDeclaration
import dev.basedpython.pycharm.lsp.ext.ByDeclarationParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * `by/declaration`: what the name at an offset declares or refers to.
 *
 * Shared by Copy Reference ([ByCopyReferenceAction]) and Find Usages' target
 * ([dev.basedpython.pycharm.lsp.usages.BySearchTarget]), which both need the declaration's kind and
 * dotted name and have no PSI to read them from.
 */
internal object ByDeclarations {

    /**
     * Asks [client] about [offset] in [file]. Background only, like every request; a `by` from before
     * the request answers `MethodNotFound`, which is [ByAnswer.Failed], and callers do as they did
     * without it.
     */
    fun at(client: LspClient, file: VirtualFile, document: Document, offset: Int, timeoutMs: Int): ByAnswer<ByDeclaration> {
        val params = params(client, file, document, offset)
        return client.askBy("by/declaration", timeoutMs) { (it as ByServerExtensions).declaration(params) }
    }

    /** [at] for a coroutine, such as one under a modal progress on the EDT. */
    suspend fun await(client: LspClient, file: VirtualFile, document: Document, offset: Int, timeoutMs: Int): ByAnswer<ByDeclaration> {
        val params = readAction { params(client, file, document, offset) }
        return client.awaitBy("by/declaration", timeoutMs.toLong()) { (it as ByServerExtensions).declaration(params) }
    }

    private fun params(client: LspClient, file: VirtualFile, document: Document, offset: Int) =
        ByDeclarationParams(TextDocumentIdentifier(client.getDocumentIdentifier(file).uri), getLsp4jPosition(document, offset))

    /**
     * The LSP symbol kind closest to a kind `by` names (`ty_ide::declaration::DeclaredKind`), for the
     * icon the platform draws it with. `null` for a kind this does not know, drawn without one.
     */
    fun symbolKind(kind: String?): SymbolKind? = when (kind) {
        "module" -> SymbolKind.Module
        "class" -> SymbolKind.Class
        "function" -> SymbolKind.Function
        "method" -> SymbolKind.Method
        "property" -> SymbolKind.Property
        "attribute" -> SymbolKind.Field
        "variable" -> SymbolKind.Variable
        "parameter" -> SymbolKind.Variable
        "type parameter" -> SymbolKind.TypeParameter
        "type alias" -> SymbolKind.Class
        else -> null
    }

    /** What a qualified name is declared in: `shapes.base.Circle` for `shapes.base.Circle.area`. */
    fun container(qualifiedName: String?, name: String?): String? {
        if (qualifiedName == null || name == null) return null
        if (!qualifiedName.endsWith(".$name")) return null
        return qualifiedName.removeSuffix(".$name").takeIf { it.isNotEmpty() }
    }
}

/**
 * The name at a `.by` editor's caret as `by` declared it, for Copy Reference: an element whose text
 * range is the name at the caret, so the platform highlights that name as it highlights the
 * reference it copied in a `.py`, and whose dotted name [ByQualifiedNameProvider] reads back.
 *
 * Holds the file and two offsets, is made afresh for each copy, and nothing keeps it.
 */
internal class ByNamedDeclaration(
    private val file: PsiFile,
    private val nameRange: TextRange,
    private val name: String?,
    /** `null` for a name local to a function, which Copy Reference copies as `path:line` instead. */
    val qualifiedName: String?,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getName(): String? = name

    override fun getTextRange(): TextRange = nameRange

    override fun getTextOffset(): Int = nameRange.startOffset

    override fun getTextLength(): Int = nameRange.length

    override fun isValid(): Boolean = file.isValid

    override fun toString(): String = "ByNamedDeclaration($qualifiedName)"

    companion object {
        /** [declaration] as an element at the name it was asked about in [file], or `null` without one. */
        fun of(file: PsiFile, document: Document, declaration: ByDeclaration): ByNamedDeclaration? {
            val origin = declaration.originSelectionRange ?: return null
            val range = getRangeInDocument(document, origin) ?: return null
            return ByNamedDeclaration(file, range, declaration.name, declaration.qualifiedName)
        }
    }
}
