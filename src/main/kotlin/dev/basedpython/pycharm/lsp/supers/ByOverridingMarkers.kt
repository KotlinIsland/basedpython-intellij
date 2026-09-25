package dev.basedpython.pycharm.lsp.supers

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.daemon.NavigateAction
import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.awt.RelativePoint
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.ext.ByOverridingMember
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * The gutter's *overrides* and *implements* icons (↑) in a `.by` file: on each class member that
 * overrides a superclass member, as Java, Kotlin and Python draw them, going to what it overrides.
 *
 * The other direction — *is overridden*, *is implemented*, *is subclassed* (↓) — is the platform's
 * own LSP provider, switched on for `.by` files by `ByInheritanceMarkers` in `LspServers.kt`; in
 * 263.5153 it draws nothing upward. This draws only the upward icons, so a member that both
 * overrides and is overridden carries one of each, side by side on the right as the platform and
 * Python place theirs.
 *
 * What a member overrides is `by`'s — `by/documentSuperMembers`, one request per revision of the
 * document, kept by [ByOverridingMembers]. Which icon is Java's rule over `by`'s facts: *implements*
 * when everything the member overrides is abstract and the member itself is not, *overrides*
 * otherwise. A `by` without the request draws nothing, and says nothing: these are ambient.
 *
 * Clicking the icon is Go to Super from the member's name: `by/superMembers` asked then, for the
 * document as it is by then, with the same chooser when there are several, and the gutter's context
 * menu offers Go to Super with its shortcut, as Java's does.
 */
class ByOverridingMarkers : LineMarkerProviderDescriptor(), DumbAware {

    override fun getName(): String = BasedPythonBundle.message("overriding.markers.name")

    override fun getIcon(): Icon = AllIcons.Gutter.OverridingMethod

    /** Nothing fast: every icon waits on `by`. */
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val file = elements.firstOrNull()?.containingFile as? BasedPythonFile ?: return
        val answer = ByOverridingMembers.getInstance(file.project).forFile(file) ?: return
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        result.addAll(markers(file, document, answer.members, elements.toHashSet()))
    }

    internal companion object {

        /**
         * An icon for each of [members] whose name is one of [elements], on that name's token — a
         * leaf, which the pass requires of a marker's element.
         */
        fun markers(
            file: PsiFile,
            document: Document,
            members: List<ByOverridingMember>,
            elements: Set<PsiElement>,
        ): List<LineMarkerInfo<PsiElement>> = members.mapNotNull { member ->
            val start = member.selectionRange?.start ?: return@mapNotNull null
            val offset = getOffsetInDocument(document, start) ?: return@mapNotNull null
            val name = file.findElementAt(offset)?.takeIf { it in elements } ?: return@mapNotNull null
            marker(name, member)
        }

        private fun marker(name: PsiElement, member: ByOverridingMember): LineMarkerInfo<PsiElement>? {
            val overridden = member.superMembers.mapNotNull(BySupers::memberTarget)
            if (overridden.isEmpty()) return null
            val implements = !member.abstract && member.superMembers.all { it.abstract }
            val tooltip = BasedPythonBundle.message(
                if (implements) "overriding.markers.implements" else "overriding.markers.overrides",
                overridden.joinToString(", ") { it.name },
            )
            val label = listOfNotNull(member.containerName, member.name).joinToString(".")
            val info = LineMarkerInfo(
                name,
                name.textRange,
                if (implements) AllIcons.Gutter.ImplementingMethod else AllIcons.Gutter.OverridingMethod,
                { tooltip },
                GoToSuper(label),
                GutterIconRenderer.Alignment.RIGHT,
                { tooltip },
            )
            return NavigateAction.setNavigateAction(
                info,
                BasedPythonBundle.message("overriding.markers.action"),
                IdeActions.ACTION_GOTO_SUPER,
            )
        }
    }

    /**
     * Go to Super from the member whose name the icon is on, [member] being `Class.member`: asked of
     * `by` when clicked, for the text as it is then, and gone to or offered as Ctrl+U does — in the
     * background, as Ctrl+U asks, so that a `didOpen` the request waits for can go out (see
     * [ByGotoSuperHandler]).
     */
    private class GoToSuper(private val member: String) : GutterIconNavigationHandler<PsiElement> {
        override fun navigate(event: MouseEvent, name: PsiElement?) {
            if (name == null || !name.isValid) return
            val project = name.project
            val file = name.containingFile?.originalFile?.virtualFile ?: return
            val editor = DataManager.getInstance().getDataContext(event.component).getData(CommonDataKeys.EDITOR) ?: return
            val server = byServerFor(project, file)
                ?: return nowhere(event, BasedPythonBundle.message("goto.super.noServer"))
            val find: suspend (Document, Int) -> BySuperAnswer = { document, _ ->
                val asked = readAction {
                    if (!name.isValid) return@readAction null
                    ByServerDocuments.ensureOpen(server, project, file)
                    // the name and the text it is in, read together
                    Triple(
                        server.getDocumentIdentifier(file),
                        ByTextHash.of(document.immutableCharSequence),
                        getLsp4jPosition(document, name.textRange.startOffset),
                    )
                }
                if (asked == null) {
                    BySuperAnswer.Nowhere(BasedPythonBundle.message("goto.super.member.unknown", member))
                } else {
                    BySupers.overridden(server, asked.first, asked.second, asked.third, member)
                }
            }
            ByGotoSuperHandler().ask(project, editor, find) { answer ->
                when (answer) {
                    is BySuperAnswer.Nowhere -> nowhere(event, answer.message)
                    is BySuperAnswer.Targets -> BySuperChooser.go(server, answer) { it.show(RelativePoint(event)) }
                }
            }
        }

        /** Says why there is nowhere to go, by the icon clicked. */
        private fun nowhere(event: MouseEvent, message: String) {
            JBPopupFactory.getInstance()
                .createHtmlTextBalloonBuilder(StringUtil.escapeXmlEntities(message), MessageType.INFO, null)
                .setFadeoutTime(FADEOUT_MS)
                .createBalloon()
                .show(RelativePoint(event), Balloon.Position.above)
        }

        private companion object {
            const val FADEOUT_MS = 4_000L
        }
    }
}
