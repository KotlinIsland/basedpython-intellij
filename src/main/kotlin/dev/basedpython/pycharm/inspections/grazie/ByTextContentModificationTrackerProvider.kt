package dev.basedpython.pycharm.inspections.grazie

import com.intellij.grazie.text.TextContentModificationTrackerProvider
import com.intellij.openapi.components.service
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.docs.render.ByDocstringSpanCache

/**
 * What the grammar checker caches a `.by` file's texts against: the PSI, as for every language, and
 * also `by`'s answer about which strings are docstrings ([ByDocstringSpanCache.modificationTracker]).
 *
 * Without it a file checked before `by` could answer — the server still starting, say — had its
 * docstrings extracted as nothing, and kept that until the file was next edited.
 */
internal class ByTextContentModificationTrackerProvider : TextContentModificationTrackerProvider {

    override fun getModificationTracker(psiElement: PsiElement): ModificationTracker =
        psiElement.project.service<ByDocstringSpanCache>().modificationTracker
}
