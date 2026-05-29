package dev.basedpython.pycharm.structure

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * Entry point for the Structure View (registered as lang.psiStructureViewFactory in plugin.xml).
 */
class BasedPythonStructureViewFactory : PsiStructureViewFactory {

    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? {
        val file = psiFile as? BasedPythonFile ?: return null
        return object : TreeBasedStructureViewBuilder() {
            override fun createStructureViewModel(editor: Editor?): StructureViewModel =
                BasedPythonStructureViewModel(file, editor)
        }
    }
}
