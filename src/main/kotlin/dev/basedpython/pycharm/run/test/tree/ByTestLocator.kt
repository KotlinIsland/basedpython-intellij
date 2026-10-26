package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.FileUrlProvider
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope

/**
 * Makes test-tree nodes navigable: resolves the `by_test://` URLs the parser attaches to suites and
 * tests back to the source file and declaration they came from, and the `file://` URLs `by`'s test
 * reporter writes.
 *
 * A `by_test://` URL carries the node id pytest reported. Which file that names, and where the test is
 * declared in it, is `by`'s answer — see [ByTestSources.locate]. A `file://` URL is the reporter's own
 * answer, the `.by` file and the line of the `def` or `class`, and [FileUrlProvider] reads it as it
 * reads any test framework's.
 *
 * Every failure degrades to "not navigable" rather than throwing: a node the IDE cannot open is a
 * missing convenience, an exception during tree building is a broken run.
 */
object ByTestLocator : SMTestLocator {

    const val PROTOCOL: String = "by_test"

    /** The protocol of the locations `by`'s test reporter writes, `file://<path>:<line>`. */
    private const val FILE_PROTOCOL: String = "file"

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        if (protocol == FILE_PROTOCOL) return FileUrlProvider.INSTANCE.getLocation(protocol, path, project, scope)
        if (protocol != PROTOCOL) return emptyList()
        // The URL does not say which kind of run reported it, so a staged file is looked for first.
        val place = ByTestSources.locate(project, path, transpiled = null) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(place.file) ?: return emptyList()
        val element: PsiElement = psiFile.findElementAt(place.offset)?.takeIf { place.offset > 0 } ?: psiFile
        return listOf(PsiLocation.fromPsiElement(element))
    }
}
