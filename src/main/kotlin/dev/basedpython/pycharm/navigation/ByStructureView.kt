package dev.basedpython.pycharm.navigation

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.FileEditorPositionListener
import com.intellij.ide.structureView.ModelListener
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.Filter
import com.intellij.ide.util.treeView.smartTree.Grouper
import com.intellij.ide.util.treeView.smartTree.NodeProvider
import com.intellij.ide.util.treeView.smartTree.ProvidingTreeModel
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.Language
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.ext.ByInheritedMember
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind

/**
 * The Structure view and File Structure popup for `.by` files: the platform's LSP structure — `by`'s
 * document symbols, each with the signature or bases `by` gives as its `detail` and a property as a
 * property — with what PyCharm offers for `.py` on top: an *Alphabetical* sorter and a *Show Fields*
 * filter, which hides the attributes of classes (`name: str`, `self.radius = radius`).
 *
 * The structure itself stays the platform's, asked for the same symbols at the same moments; this
 * only wraps the model it builds, and reads each node's kind off the `DocumentSymbol` the platform's
 * nodes carry as their value. *Show Inherited* adds each class's inherited members, from `by`
 * ([ByInheritedMembersProvider]).
 */
class ByStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val lsp = LanguageStructureViewBuilder.getInstance().allForLanguage(Language.ANY)
            .firstNotNullOfOrNull { factory -> factory.takeIf { it !is ByStructureViewFactory }?.getStructureViewBuilder(psiFile) }
            as? TreeBasedStructureViewBuilder ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                ByStructureViewModel(lsp.createStructureViewModel(editor), psiFile)

            override fun isRootNodeShown(): Boolean = lsp.isRootNodeShown
        }
    }
}

/** [delegate], the platform's model of `by`'s symbols, with a sorter and a filter of its own. */
internal class ByStructureViewModel(private val delegate: StructureViewModel, file: PsiFile) :
    StructureViewModel by delegate, StructureViewModel.ElementInfoProvider, ProvidingTreeModel {

    private val inherited = ByInheritedMembersProvider(file)

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun getNodeProviders(): Collection<NodeProvider<*>> = listOf(inherited)

    /** Whether a provider's toggle is on is the view's to say; this model, used bare, adds nothing. */
    override fun isEnabled(provider: NodeProvider<*>): Boolean = false

    override fun getFilters(): Array<Filter> = arrayOf(ByFieldsFilter)

    override fun getGroupers(): Array<Grouper> = delegate.groupers

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement?): Boolean =
        (delegate as? StructureViewModel.ElementInfoProvider)?.isAlwaysShowsPlus(element) ?: false

    override fun isAlwaysLeaf(element: StructureViewTreeElement?): Boolean =
        (delegate as? StructureViewModel.ElementInfoProvider)?.isAlwaysLeaf(element) ?: false

    override fun addEditorPositionListener(listener: FileEditorPositionListener) = delegate.addEditorPositionListener(listener)

    override fun removeEditorPositionListener(listener: FileEditorPositionListener) = delegate.removeEditorPositionListener(listener)

    override fun addModelListener(modelListener: ModelListener) = delegate.addModelListener(modelListener)

    override fun removeModelListener(modelListener: ModelListener) = delegate.removeModelListener(modelListener)
}

/**
 * *Show Fields*: on, the attributes of classes are shown, as they are by default; off, they are
 * hidden — as PyCharm's filter of the same name does for `.py`. A field is what `by` outlines as one:
 * an attribute a class body declares or a method assigns on `self`. A module's variables are not.
 */
internal object ByFieldsFilter : Filter {

    override fun isVisible(treeNode: TreeElement): Boolean {
        val kind = when (val value = (treeNode as? StructureViewTreeElement)?.value) {
            is DocumentSymbol -> value.kind
            is ByInheritedMember -> value.kind
            else -> return true
        }
        return kind != SymbolKind.Field
    }

    override fun isReverted(): Boolean = true

    override fun getPresentation(): ActionPresentation =
        ActionPresentationData(BasedPythonBundle.message("structure.filter.fields"), null, AllIcons.Nodes.Field)

    override fun getName(): String = "BASEDPYTHON_SHOW_FIELDS"
}
