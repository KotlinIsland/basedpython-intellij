package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope

/**
 * Makes test-tree nodes navigable: resolves the `by_test://` URLs the parser attaches to suites and
 * tests back to the `.by` file and declaration they came from.
 *
 * pytest runs against the transpiled tree, so its node ids are paths relative to `by run`'s temp
 * directory naming `.py` files. Relative paths survive transpilation, so the same path under a
 * content root with a `.by` extension is the source — [ByTestLocations] does that rewrite, this
 * resolves it against the project.
 *
 * Every failure degrades to "not navigable" rather than throwing: a node the IDE cannot open is a
 * missing convenience, an exception during tree building is a broken run.
 */
object ByTestLocator : SMTestLocator {

    const val PROTOCOL: String = "by_test"

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val location = ByTestLocations.parse(path) ?: return emptyList()
        val file = ByTestSources.findSourceFile(project, location.file) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()

        val element: PsiElement = location.symbols
            .takeIf { it.isNotEmpty() }
            ?.let { ByTestLocations.declarationOffset(psiFile.text, it) }
            ?.let { psiFile.findElementAt(it) }
            ?: psiFile

        return listOf(PsiLocation.fromPsiElement(element))
    }
}
