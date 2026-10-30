package dev.basedpython.pycharm.navigation

import com.intellij.codeInsight.navigation.GotoTargetPresentationProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.runningByServer

/**
 * A `.by` declaration in a Go to target popup — *Choose Implementation of area*, and every other
 * chooser the platform's navigation opens — drawn as PyCharm draws a `.py` one: its name, the kind
 * of declaration it is, and what declares it, `area (pkg.shapes.Square)`, from `by/declaration` at
 * the target.
 *
 * Without it the platform's LSP navigation names a target by its file: Go to Implementation on
 * `Shape.area` offered `area (shapes.by)` three times over, with the file's icon, so which row was
 * `Square`'s and which `Circle`'s could only be told by trying (measured in PyCharm 263.7649, where
 * the same popup for `.py` reads `area` in `Square in pkg.pyshapes`, with a method's icon).
 *
 * The platform asks this on a background thread under the popup's progress, one target at a time; a
 * `by` that does not answer within a moment, or one from before the request, leaves the target as
 * the platform draws it.
 *
 * `gotoTargetPresentationProvider` rather than `gotoTargetRendererProvider`, which PyCharm uses for
 * `.py`: that one hands the platform a cell renderer, and the platform's path to it is deprecated for
 * removal in favour of this.
 */
class ByGotoTargetPresentationProvider : GotoTargetPresentationProvider {

    override fun getTargetPresentation(element: PsiElement, differentNames: Boolean): TargetPresentation? {
        if (element is PsiFile) return null
        val file = element.containingFile as? BasedPythonFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        return presentation(element, runningByServer(file.project, virtualFile) ?: return null)
    }

    /** [element], a target in a `.by` file, as [server] names what is declared where it is. */
    internal fun presentation(element: PsiElement, server: LspClient): TargetPresentation? {
        val file = element.containingFile as? BasedPythonFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        val document = file.viewProvider.document ?: return null
        val offset = element.textOffset
        if (offset < 0 || offset > document.textLength) return null
        val declaration = ByDeclarations.at(server, virtualFile, document, offset, DECLARATION_TIMEOUT_MS).value ?: return null
        val name = declaration.name ?: return null
        val icon = ByDeclarations.symbolKind(declaration.kind)?.let(SYMBOL_KINDS::getIcon) ?: virtualFile.fileType.icon
        val container = ByDeclarations.container(declaration.qualifiedName, name)
        return TargetPresentation.builder(name)
            .icon(icon)
            .containerText(container ?: virtualFile.name)
            .presentation()
    }

    private companion object {
        /** The icons `by`'s descriptor shows symbol kinds with: the platform's default customizer. */
        val SYMBOL_KINDS = LspSymbolKindCustomizer()

        /** One lookup per target; the popup is waiting on every one of them. */
        const val DECLARATION_TIMEOUT_MS = 1_000
    }
}
