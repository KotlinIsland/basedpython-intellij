package dev.basedpython.pycharm.structure

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Filter
import com.intellij.ide.util.treeView.smartTree.Grouper
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.openapi.editor.Editor
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * Structure view model for BasedPython files.
 * Supports alphabetical sorting and narrows-to-editor-selection.
 */
class BasedPythonStructureViewModel(
    file: BasedPythonFile,
    editor: Editor?,
) : StructureViewModelBase(file, editor, BasedPythonStructureViewElement.forFile(file)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)
    override fun getGroupers(): Array<Grouper> = Grouper.EMPTY_ARRAY
    override fun getFilters(): Array<Filter> = Filter.EMPTY_ARRAY

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean = false
    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean {
        val v = element.value
        if (v is IndentScanner.ScopeNode) {
            return v.kind == IndentScanner.NodeKind.FIELD ||
                v.kind == IndentScanner.NodeKind.IMPORT_BLOCK ||
                v.kind == IndentScanner.NodeKind.REGION
        }
        return false
    }

    override fun shouldEnterElement(element: Any?): Boolean = true
}
