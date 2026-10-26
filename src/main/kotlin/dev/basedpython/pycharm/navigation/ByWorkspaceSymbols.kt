package dev.basedpython.pycharm.navigation

import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.GotoClassContributor
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer
import com.intellij.platform.lsp.api.customization.LspWorkspaceSymbolSupport
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.PathUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import javax.swing.Icon

/**
 * Go to Class, Go to Symbol and Search Everywhere's entries for `by`'s workspace symbols, placed in
 * what declares them — `area  shapes.base.Circle`, `area  shapes.base.Shape` — as PyCharm places a
 * `.py` symbol (`area(self) (Circle in shapes.base)`), instead of only the file's name, which said
 * nothing about which of three `area`s each was. The place is the symbol's `containerName`, its
 * module and the classes it is declared in.
 *
 * A dotted query, `Shape.desc`, is `by`'s to match, and it does; the platform then matches each
 * symbol against the query itself, and a name alone, `describe`, never matches `Shape.desc`. So each
 * symbol has a qualified name, its container and its name, which [ByQualifiedSymbolNames] hands the
 * platform's matcher as Python's contributors hand theirs.
 */
internal object ByWorkspaceSymbols : LspWorkspaceSymbolSupport() {

    override fun createNavigationItem(lspClient: LspClient, workspaceSymbol: WorkspaceSymbol): NavigationItem? {
        val location = workspaceSymbol.location
        val uri = location.left?.uri ?: location.right?.uri ?: return null
        return ByWorkspaceSymbolItem(
            lspClient,
            workspaceSymbol.name,
            workspaceSymbol.containerName?.takeIf { it.isNotEmpty() },
            workspaceSymbol.kind,
            uri,
            location.left?.range,
        )
    }
}

/** One of `by`'s workspace symbols. Equal to another for the same symbol. */
internal data class ByWorkspaceSymbolItem(
    private val client: LspClient,
    private val name: String,
    /** `shapes.base.Circle` for `Circle.area`; `null` when `by` gives none. */
    val container: String?,
    private val kind: SymbolKind?,
    private val uri: String,
    private val range: Range?,
) : NavigationItem {

    /** The container and the name, `shapes.base.Circle.area`, for the platform's matcher. */
    val qualifiedName: String get() = container?.let { "$it.$name" } ?: name

    override fun getName(): String = name

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = name

        override fun getLocationString(): String = container ?: PathUtil.getFileName(uri)

        override fun getIcon(unused: Boolean): Icon? = kind?.let { SYMBOL_KINDS.getIcon(it) }
    }

    override fun navigate(requestFocus: Boolean) {
        val file = client.descriptor.findFileByUri(uri) ?: return
        val offset = range?.let { range ->
            FileDocumentManager.getInstance().getDocument(file)?.let { getOffsetInDocument(it, range.start) }
        } ?: 0
        OpenFileDescriptor(client.project, file, offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    private companion object {
        /**
         * The icons `by`'s descriptor shows symbol kinds with: it keeps the platform's default
         * customizer, and a descriptor's customizer is only for the platform to call.
         */
        val SYMBOL_KINDS = LspSymbolKindCustomizer()
    }
}

/**
 * The qualified names of `by`'s symbols, for Go to Class and Go to Symbol to match a dotted query
 * against: a contributor of no symbols of its own, which the platform asks each item's qualified
 * name of and which says `.` separates its parts.
 */
internal class ByQualifiedSymbolNames : GotoClassContributor, ChooseByNameContributorEx, DumbAware {

    override fun getQualifiedName(item: NavigationItem): String? = (item as? ByWorkspaceSymbolItem)?.qualifiedName

    override fun getQualifiedNameSeparator(): String = "."

    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) = Unit

    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) = Unit
}
