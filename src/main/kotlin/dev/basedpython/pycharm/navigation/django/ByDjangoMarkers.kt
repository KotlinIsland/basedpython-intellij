package dev.basedpython.pycharm.navigation.django

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.platform.lsp.util.navigateOrShowPopup
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.editor.djangotemplate.ByDjangoTemplateEditing
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.navigation.endpoints.ByEndpoints
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.Location
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * The gutter's django icons: on a view, the `path()`s routing to it and the templates it renders; on
 * a `path()`, its view, with the whole path it serves; on a model, its admin classes, and on an admin
 * class, its models; at the top of a template, the views rendering it. Each goes there on a click, or
 * offers the places when there are several. [ByDjangoLinks] says which; this only draws them.
 *
 * Every icon waits on `by` — `by/endpoints` and `by/djangoStructure`, each asked once per change of
 * the project's code however many files are open — so all of them are slow markers. Registered for
 * basedpython, and for XML and plain text, which a template comes in as.
 */
class ByDjangoMarkers : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = BasedPythonBundle.message("django.markers.name")

    override fun getIcon(): Icon = AllIcons.Gutter.ImplementedMethod

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        if (file !is BasedPythonFile && !ByDjangoTemplateEditing.handles(file)) return
        val project = file.project
        val virtualFile = file.originalFile.virtualFile ?: return
        val uri = byServerFor(project, virtualFile)?.getDocumentIdentifier(virtualFile)?.uri ?: return
        val structure = ByDjangoStructureService.getInstance(project).fresh()
        // routes are django's or FastAPI's, but a project neither serves asks for none
        val endpoints = if (structure != null || file is BasedPythonFile) ByEndpoints.getInstance(project).endpoints() else emptyList()
        val links = ByDjangoLinks.forFile(uri, endpoints, structure)
        if (links.isEmpty()) return
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val wanted = elements.toHashSet()
        for (link in links) {
            val offset = getOffsetInDocument(document, link.at) ?: continue
            val leaf = leafAt(file, offset)?.takeIf { it in wanted } ?: continue
            result += marker(leaf, link)
        }
    }

    /** The leaf an icon at [offset] hangs on: a template's first may be whitespace, which the pass still visits. */
    private fun leafAt(file: PsiFile, offset: Int): PsiElement? = file.findElementAt(offset)

    private fun marker(leaf: PsiElement, link: ByDjangoLink): LineMarkerInfo<PsiElement> {
        val tooltip = tooltip(link)
        return LineMarkerInfo(
            leaf,
            TextRange.from(leaf.textRange.startOffset, 0).takeIf { leaf.textLength == 0 } ?: leaf.textRange,
            icon(link.kind),
            { tooltip },
            ToTargets(link.targets, title(link)),
            GutterIconRenderer.Alignment.LEFT,
            { tooltip },
        )
    }

    private class ToTargets(private val targets: List<Location>, private val title: String) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(event: MouseEvent, element: PsiElement?) {
            if (element == null || !element.isValid) return
            val file = element.containingFile?.originalFile?.virtualFile ?: return
            val server = byServerFor(element.project, file) ?: return
            navigateOrShowPopup(server, targets, title, event)
        }
    }

    internal companion object {

        fun icon(kind: ByDjangoLinkKind): Icon = when (kind) {
            ByDjangoLinkKind.ROUTES, ByDjangoLinkKind.VIEW -> AllIcons.Nodes.PpWeb
            ByDjangoLinkKind.ADMIN, ByDjangoLinkKind.MODEL -> AllIcons.Nodes.DataTables
            ByDjangoLinkKind.TEMPLATES, ByDjangoLinkKind.RENDERED_BY -> AllIcons.FileTypes.Html
        }

        /** `Routed at /polls/, /p/` — the names, at most three, then how many more. */
        fun tooltip(link: ByDjangoLink): String {
            val shown = link.names.distinct()
            val names = shown.take(3).joinToString(", ") + if (shown.size > 3) BasedPythonBundle.message("django.markers.more", shown.size - 3) else ""
            return BasedPythonBundle.message("django.markers.${key(link.kind)}", names)
        }

        fun title(link: ByDjangoLink): String = BasedPythonBundle.message("django.markers.${key(link.kind)}.title")

        private fun key(kind: ByDjangoLinkKind): String = when (kind) {
            ByDjangoLinkKind.ROUTES -> "routes"
            ByDjangoLinkKind.VIEW -> "view"
            ByDjangoLinkKind.ADMIN -> "admin"
            ByDjangoLinkKind.MODEL -> "model"
            ByDjangoLinkKind.TEMPLATES -> "templates"
            ByDjangoLinkKind.RENDERED_BY -> "renderedBy"
        }
    }
}
