package dev.basedpython.pycharm.editor.djangotemplate

import com.intellij.ide.structureView.ModelListener
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.Language
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.navigation.ByStructureViewFactory

/**
 * The Structure view of a django template `by` serves: the template's `{% block %}`s, as `by`'s
 * `textDocument/documentSymbol` outlines them, nested as written, and after them the file's own
 * outline — the HTML tree — in one tree.
 *
 * What a template's blocks are stays `by`'s: they are the platform's LSP structure, asked as for a
 * `.by` file, and the HTML is HTML's own builder; this only puts the two models' top rows under one
 * root. HTML's builder would win otherwise, the platform asking only the first factory registered for
 * a file's language, so this one is registered first and hands every file that is not a template to
 * the next factory for its language, unchanged.
 *
 * One tree rather than a tab each: the platform's tabbed structure view (`StructureViewComposite`) is
 * internal API.
 */
class ByTemplateStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val own = LanguageStructureViewBuilder.getInstance().allForLanguage(psiFile.language)
            .firstNotNullOfOrNull { factory -> factory.takeIf { it !is ByTemplateStructureViewFactory }?.getStructureViewBuilder(psiFile) }
        if (!ByDjangoTemplateEditing.handles(psiFile)) return own
        val blocks = LanguageStructureViewBuilder.getInstance().allForLanguage(Language.ANY)
            .firstNotNullOfOrNull { factory ->
                factory.takeIf { it !is ByTemplateStructureViewFactory && it !is ByStructureViewFactory }?.getStructureViewBuilder(psiFile)
            } as? TreeBasedStructureViewBuilder ?: return own
        val file = own as? TreeBasedStructureViewBuilder
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                ByTemplateStructureModel(psiFile, editor, blocks.createStructureViewModel(editor), file?.createStructureViewModel(editor))

            override fun isRootNodeShown(): Boolean = false
        }
    }
}

/**
 * [blocks]' top rows, then [file]'s, under one root. Each row stays its own model's, opening and
 * expanding as that model has it; the caret is followed through the HTML rows, which are PSI.
 */
internal class ByTemplateStructureModel(
    psiFile: PsiFile,
    editor: Editor?,
    private val blocks: StructureViewModel,
    private val file: StructureViewModel?,
) : StructureViewModelBase(psiFile, editor, Root(psiFile, blocks, file)) {

    private class Root(private val psiFile: PsiFile, private val blocks: StructureViewModel, private val file: StructureViewModel?) :
        StructureViewTreeElement {
        override fun getValue(): Any = psiFile

        override fun getPresentation(): ItemPresentation = file?.root?.presentation ?: blocks.root.presentation

        /** A fresh array: each model's own is typed as its element class, which the other's rows are not. */
        override fun getChildren(): Array<TreeElement> =
            (blocks.root.children.asList() + file?.root?.children.orEmpty().asList()).toTypedArray()

        override fun navigate(requestFocus: Boolean) = psiFile.navigate(requestFocus)

        override fun canNavigate(): Boolean = psiFile.canNavigate()

        override fun canNavigateToSource(): Boolean = psiFile.canNavigateToSource()
    }

    /** Either model's change is this one's: `by`'s symbols arrive after the view opens. */
    override fun addModelListener(modelListener: ModelListener) {
        super.addModelListener(modelListener)
        blocks.addModelListener(modelListener)
        file?.addModelListener(modelListener)
    }

    override fun removeModelListener(modelListener: ModelListener) {
        super.removeModelListener(modelListener)
        blocks.removeModelListener(modelListener)
        file?.removeModelListener(modelListener)
    }

    override fun dispose() {
        super.dispose()
        blocks.dispose()
        file?.dispose()
    }
}
