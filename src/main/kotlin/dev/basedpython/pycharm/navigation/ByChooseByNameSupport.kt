package dev.basedpython.pycharm.navigation

import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.structure.IndentScanner

/**
 * Shared scanning logic for the "Go to Symbol" / "Go to Class" contributors.
 *
 * Enumerates every `.by` file in [scope], runs the flat [IndentScanner] over its
 * text, and produces [ByNavigationItem]s for the requested [IndentScanner.NodeKind]s.
 */
internal object ByChooseByNameSupport {

    /** Kinds shown in "Go to Symbol". */
    val SYMBOL_KINDS = setOf(
        IndentScanner.NodeKind.CLASS,
        IndentScanner.NodeKind.FUNCTION,
        IndentScanner.NodeKind.FIELD,
    )

    /** Kinds shown in "Go to Class". */
    val CLASS_KINDS = setOf(IndentScanner.NodeKind.CLASS)

    private fun byFiles(scope: GlobalSearchScope): Collection<VirtualFile> =
        ApplicationManager.getApplication().runReadAction<Collection<VirtualFile>> {
            FileTypeIndex.getFiles(BasedPythonFileType.INSTANCE, scope)
        }

    /** Collect all symbol names (de-duplicated) of [kinds] across `.by` files in [scope]. */
    fun collectNames(scope: GlobalSearchScope, kinds: Set<IndentScanner.NodeKind>): Set<String> {
        val names = LinkedHashSet<String>()
        for (file in byFiles(scope)) {
            if (!file.isValid) continue
            val text = readText(file) ?: continue
            for (node in IndentScanner.buildFlat(text)) {
                if (node.kind in kinds && node.name.isNotBlank()) names += node.name
            }
        }
        return names
    }

    /** Collect navigation items matching [name] of [kinds] across `.by` files in [scope]. */
    fun collectItems(
        project: Project,
        scope: GlobalSearchScope,
        name: String,
        kinds: Set<IndentScanner.NodeKind>,
    ): List<NavigationItem> {
        val items = ArrayList<NavigationItem>()
        for (file in byFiles(scope)) {
            if (!file.isValid) continue
            val text = readText(file) ?: continue
            for (node in IndentScanner.buildFlat(text)) {
                if (node.kind in kinds && node.name == name) {
                    items += ByNavigationItem(project, file, node.name, node.startOffset, node.kind)
                }
            }
        }
        return items
    }

    private fun readText(file: VirtualFile): CharSequence? =
        try {
            String(file.contentsToByteArray(), file.charset)
        } catch (_: Exception) {
            null
        }
}
