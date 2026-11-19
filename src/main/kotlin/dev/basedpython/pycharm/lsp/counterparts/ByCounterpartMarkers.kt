package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.platform.lsp.util.navigateOrShowPopup
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByCounterpartDeclaration
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterparts
import dev.basedpython.pycharm.lsp.isByServerFile
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.Location
import java.awt.event.MouseEvent
import java.net.URI
import javax.swing.Icon

/**
 * The gutter's stub icons: on each declaration of a runtime module that its stub declares too,
 * *Has stub in x.pyi*, and on each declaration of a stub that its runtime module declares,
 * *Stub for A.f in x.py*, going there on a click, or offering the places when there are several — the
 * overloads of a function, the branches of a `sys.version_info` check.
 *
 * All of it is `by/documentCounterparts` ([ByCounterparts]), one request per pass however many
 * declarations the document has, for whichever of `.by`, `.byi`, `.py` and `.pyi` the document is —
 * the four `by` is given. Registered for every language rather than for basedpython's: in PyCharm a
 * `.py` and a `.pyi` are the Python plugin's files, and without it a `.pyi` is a TextMate one, and a
 * stub is exactly as much a stub in each. Which files are asked about is decided by
 * [isByServerFile], the same test the server is given documents by, so a file of any other kind
 * costs one extension check.
 *
 * Not limited to the project's content, as [dev.basedpython.pycharm.lsp.supers.ByInheritorMarkers]
 * is: a library's or typeshed's stub opened by navigation is where *Stub for … in x.py* is most
 * worth having, and what a stub stands for does not depend on whose it is.
 *
 * PyCharm draws its own icon for this, `PyiRelatedItemLineMarkerProvider`, on a `.py` and a `.pyi`
 * that are both its files, from its own resolution. Where both are drawn on one name, its icon is
 * taken out of the gutter in favour of this one
 * ([dev.basedpython.pycharm.lsp.counterparts.python.ByPyiMarkerFilter]), so a declaration has one
 * icon, saying what `by` says.
 */
class ByCounterpartMarkers : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = BasedPythonBundle.message("counterpart.markers.name")

    override fun getIcon(): Icon = ICON

    /** Nothing fast: every icon waits on `by`. */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile ?: return
        // every language's pass comes here: the extension check first, before any service is touched
        if (file.originalFile.virtualFile?.isByServerFile() != true) return
        val counterparts = ByCounterparts.getInstance(file.project).forFile(file) ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        result.addAll(markers(file, document, counterparts, elements.toHashSet()))
    }

    internal companion object {

        /** PyCharm's icon for the same thing (`PyiRelatedItemLineMarkerProvider.ICON`), so it reads as the same thing. */
        val ICON: Icon get() = AllIcons.Gutter.Unique

        /**
         * An icon for each declaration of [counterparts] whose name starts in one of [elements], on
         * that name — the element being the leaf it starts in, which the pass requires of a marker's
         * element, and the range the name's own, which is narrower than the leaf in a file whose tree
         * has no tokens (a TextMate `.pyi`).
         */
        fun markers(
            file: PsiFile,
            document: Document,
            counterparts: ByDocumentCounterparts,
            elements: Set<PsiElement>,
        ): List<LineMarkerInfo<PsiElement>> = counterparts.declarations.orEmpty().mapNotNull { declaration ->
            val range = declaration.selectionRange ?: return@mapNotNull null
            val start = getOffsetInDocument(document, range.start) ?: return@mapNotNull null
            val end = getOffsetInDocument(document, range.end)?.takeIf { it > start } ?: return@mapNotNull null
            val leaf = file.findElementAt(start)?.takeIf { it in elements } ?: return@mapNotNull null
            marker(leaf, TextRange(start, end), counterparts.isStub, declaration)
        }

        private fun marker(
            leaf: PsiElement,
            range: TextRange,
            stub: Boolean,
            declaration: ByCounterpartDeclaration,
        ): LineMarkerInfo<PsiElement>? {
            val targets = declaration.targets.orEmpty().mapNotNull { target ->
                val uri = target.uri ?: return@mapNotNull null
                Location(uri, target.selectionRange ?: target.range ?: return@mapNotNull null)
            }
            if (targets.isEmpty()) return null
            val name = declaration.name ?: leaf.text
            val tooltip = tooltip(stub, name, targets)
            return ByCounterpartMarkerInfo(
                leaf,
                range,
                tooltip,
                ToCounterparts(
                    targets,
                    BasedPythonBundle.message(if (stub) "counterpart.markers.chooser.implementation" else "counterpart.markers.chooser.stub", name),
                ),
            )
        }

        /**
         * What the icon says, worded as PyCharm's own (*Has stub item in x.pyi*, *Stub for item in
         * x.py*) with the declaration named where PyCharm says "item": the files are the targets'
         * own, so a declaration whose counterpart was re-exported from another module says where it
         * is rather than where the module is.
         */
        fun tooltip(stub: Boolean, name: String, targets: List<Location>): String {
            val files = targets.map { fileName(it.uri) }.distinct().joinToString(", ")
            return if (stub) {
                BasedPythonBundle.message("counterpart.markers.stubFor", targets.size, name, files)
            } else {
                BasedPythonBundle.message("counterpart.markers.hasStub", targets.size, files)
            }
        }

        /** The last segment of [uri]'s path, unescaped: `x y.pyi` for `file:///p/x%20y.pyi`. */
        fun fileName(uri: String): String =
            (runCatching { URI(uri).path }.getOrNull() ?: uri).substringAfterLast('/')
    }

    /**
     * Goes to the counterpart, or offers the counterparts when there are several, as Go to
     * Implementation does — through the server that is running when it is clicked, so a restart in
     * between does not leave the icon holding a dead one.
     */
    private class ToCounterparts(
        private val targets: List<Location>,
        private val title: String,
    ) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(event: MouseEvent, element: PsiElement?) {
            if (element == null || !element.isValid) return
            val file = element.containingFile?.originalFile?.virtualFile ?: return
            val server = byServerFor(element.project, file) ?: return
            navigateOrShowPopup(server, targets, title, event)
        }
    }
}

/**
 * One of [ByCounterpartMarkers]' icons, as its own class so that what sits beside it in the gutter can
 * tell it apart ([dev.basedpython.pycharm.lsp.counterparts.python.ByPyiMarkerFilter]). Not mergeable:
 * a merged icon would be a chooser between this and whatever shares its line.
 */
internal class ByCounterpartMarkerInfo(
    leaf: PsiElement,
    range: TextRange,
    tooltip: String,
    navigation: GutterIconNavigationHandler<PsiElement>,
) : LineMarkerInfo<PsiElement>(
    leaf,
    range,
    ByCounterpartMarkers.ICON,
    { tooltip },
    navigation,
    GutterIconRenderer.Alignment.RIGHT,
    { tooltip },
)
