package dev.basedpython.pycharm.navigation

import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.SymbolKind
import javax.swing.Icon

/**
 * A declaration `by` named, as the PSI element a platform chooser or tree wants: where its name is,
 * what to call it, and what to show beside it.
 *
 * A `.by` file's PSI has no declarations in it to hand over (see
 * [dev.basedpython.pycharm.lang.BasedPythonParserDefinition]), and the token at a declaration's name
 * would be shown as its text and file alone — `test_total` twice over for two classes' `test_total`.
 * This carries `by`'s name for it instead, `TestTotal::test_total` or `Circle.area`, and the module.
 *
 * Holds the file and an offset in it, nothing of the plugin's in any platform holder, and is made
 * fresh for each answer; a chooser keeps it for as long as it is open.
 */
internal class ByDeclarationElement(
    private val file: PsiFile,
    /** Where its name starts. */
    val offset: Int,
    private val name: String,
    private val location: String?,
    private val icon: Icon?,
) : FakePsiElement() {

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getLanguage(): Language = file.language

    override fun getName(): String = name

    override fun getLocationString(): String? = location

    override fun getIcon(open: Boolean): Icon? = icon

    override fun getTextOffset(): Int = offset

    override fun getNavigationElement(): PsiElement = file.findElementAt(offset) ?: file

    override fun isValid(): Boolean = file.isValid

    override fun canNavigate(): Boolean = file.isValid

    override fun navigate(requestFocus: Boolean) {
        val virtualFile = file.virtualFile ?: return
        OpenFileDescriptor(file.project, virtualFile, offset).navigate(requestFocus)
    }

    override fun toString(): String = "ByDeclarationElement($name)"

    companion object {
        /**
         * The icons `by`'s descriptor shows symbol kinds with: it keeps the platform's default
         * customizer, and a descriptor's customizer is only for the platform to call.
         */
        private val SYMBOL_KINDS = LspSymbolKindCustomizer()

        /**
         * The declaration whose name starts at [position] in the file [uri] names, as [client] maps
         * URIs, or null when that file is not there. Inside a read action.
         */
        fun at(
            client: LspClient,
            uri: String,
            position: Position,
            name: String,
            location: String?,
            kind: SymbolKind?,
        ): ByDeclarationElement? {
            val virtualFile = client.descriptor.findFileByUri(uri) ?: return null
            val file = PsiManager.getInstance(client.project).findFile(virtualFile) ?: return null
            val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
            val offset = getOffsetInDocument(document, position) ?: return null
            val icon = kind?.let { SYMBOL_KINDS.getIcon(it) }
            return ByDeclarationElement(file, offset, name, location, icon)
        }
    }
}
