package dev.basedpython.pycharm.lsp.supers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.NavigateAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.platform.lsp.util.navigateOrShowPopup
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByDeclarationUsages
import dev.basedpython.pycharm.lsp.vision.ByCodeVisionScope
import dev.basedpython.pycharm.lsp.vision.ByUsageCounts
import dev.basedpython.pycharm.lsp.vision.implementationsOf
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * The gutter's *is subclassed*, *is overridden* and *is implemented* icons (↓) in a `.by` file, from
 * the implementations count of `by/documentUsageCounts` ([ByUsageCounts]) — the request the
 * *Inheritors* code vision draws from, asked once per pass and shared.
 *
 * These were the platform's own LSP provider, which asks `textDocument/implementation` for every
 * method of a class with subclasses and `prepareTypeHierarchy` + `typeHierarchy/subtypes` for every
 * class, each edit once the document has been still for a second: on fastapi's `applications.py`,
 * ~240 `implementation` and ~150 `subtypes` requests per quiet edit. Everything an icon says is in
 * the count — whether there is anything, and whether the method is abstract, which makes what
 * overrides it implement it — so it costs nothing beyond the request the code vision makes anyway.
 * What subclasses or overrides is asked when the icon is clicked, as Ctrl+Alt+B would.
 *
 * The other direction, *overrides* and *implements* (↑), is [ByOverridingMarkers].
 */
class ByInheritorMarkers : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = BasedPythonBundle.message("inheritor.markers.name")

    override fun getIcon(): Icon = AllIcons.Gutter.OverridenMethod

    /** Nothing fast: every icon waits on `by`. */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile as? BasedPythonFile ?: return
        val project = file.project
        val virtualFile = file.originalFile.virtualFile ?: return
        // a stub or a library file opened from a navigation: what subclasses it is not the project's
        if (!ProjectFileIndex.getInstance(project).isInContent(virtualFile)) return
        val declarations = ByUsageCounts.getInstance(project).forFile(file, ByUsageCounts.Count.IMPLEMENTATIONS) ?: return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        result.addAll(markers(file, document, declarations, elements.toHashSet()))
    }

    internal companion object {

        /** [ByDeclarationUsages.kind] of a class. */
        private const val CLASS = "class"

        /**
         * An icon for each of [declarations] with implementations whose name is one of [elements],
         * on that name's token — a leaf, which the pass requires of a marker's element.
         */
        fun markers(
            file: PsiFile,
            document: Document,
            declarations: List<ByDeclarationUsages>,
            elements: Set<PsiElement>,
        ): List<LineMarkerInfo<PsiElement>> = declarations.mapNotNull { declaration ->
            val count = declaration.implementations?.takeIf { it > 0 } ?: return@mapNotNull null
            val start = declaration.selectionRange?.start ?: return@mapNotNull null
            val offset = getOffsetInDocument(document, start) ?: return@mapNotNull null
            val name = file.findElementAt(offset)?.takeIf { it in elements } ?: return@mapNotNull null
            marker(name, declaration, count)
        }

        private fun marker(name: PsiElement, declaration: ByDeclarationUsages, count: Int): LineMarkerInfo<PsiElement> {
            val (icon, tooltipKey, chooserKey) = when {
                declaration.kind == CLASS ->
                    Triple(AllIcons.Gutter.OverridenMethod, "inheritor.markers.subclassed", "code.vision.inheritors.chooser")
                declaration.abstract ->
                    Triple(AllIcons.Gutter.ImplementedMethod, "inheritor.markers.implemented", "code.vision.overrides.chooser")
                else ->
                    Triple(AllIcons.Gutter.OverridenMethod, "inheritor.markers.overridden", "code.vision.overrides.chooser")
            }
            val tooltip = BasedPythonBundle.message(tooltipKey, count)
            val label = declaration.name ?: name.text
            val info = LineMarkerInfo(
                name,
                name.textRange,
                icon,
                { tooltip },
                ToImplementations(BasedPythonBundle.message(chooserKey, label)),
                GutterIconRenderer.Alignment.RIGHT,
                { tooltip },
            )
            return NavigateAction.setNavigateAction(
                info,
                BasedPythonBundle.message("inheritor.markers.action"),
                IdeActions.ACTION_GOTO_IMPLEMENTATION,
            )
        }
    }

    /**
     * What subclasses the class or overrides the method whose name the icon is on, asked of `by`
     * when clicked, for the text as it is then, and gone to or offered as the *Inheritors* count's
     * click does.
     */
    private class ToImplementations(private val title: String) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(event: MouseEvent, name: PsiElement?) {
            if (name == null || !name.isValid) return
            val project = name.project
            val file = name.containingFile?.originalFile?.virtualFile ?: return
            val server = byServerFor(project, file) ?: return
            project.service<ByCodeVisionScope>().scope.launch {
                val places = withBackgroundProgress(project, BasedPythonBundle.message("code.vision.progress", name.text)) {
                    val asked = readAction {
                        if (!name.isValid) return@readAction null
                        val document = PsiDocumentManager.getInstance(project).getDocument(name.containingFile) ?: return@readAction null
                        server.getDocumentIdentifier(file) to getLsp4jPosition(document, name.textRange.startOffset)
                    } ?: return@withBackgroundProgress null
                    implementationsOf(server, file, asked.first, asked.second)
                }
                if (places.isNullOrEmpty()) return@launch
                withContext(Dispatchers.EDT) { navigateOrShowPopup(server, places, title, event) }
            }
        }
    }
}
