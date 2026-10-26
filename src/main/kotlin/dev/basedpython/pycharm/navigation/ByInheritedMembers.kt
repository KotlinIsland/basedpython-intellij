package dev.basedpython.pycharm.navigation

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.ActionShortcutProvider
import com.intellij.ide.util.FileStructureNodeProvider
import com.intellij.ide.util.treeView.smartTree.ActionPresentation
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ColoredItemPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.customization.LspSymbolKindCustomizer
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByInheritedMember
import dev.basedpython.pycharm.lsp.ext.ByInheritingClass
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.BySyntaxOutlineParams
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import dev.basedpython.pycharm.lsp.runningByServer
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.jetbrains.annotations.TestOnly
import javax.swing.Icon

/**
 * What `by/documentInheritedMembers` says each class of a `.by` document inherits, kept against what
 * it is about: the document's text and the project's PSI, since a member added to a superclass in
 * another file is one more inherited member.
 *
 * Asked off the EDT, as the structure view builds its nodes; on it, only an answer already had is
 * given. A `by` without the request, or one that does not answer, gives no inherited members.
 */
@Service(Service.Level.PROJECT)
internal class ByInheritedMembers(private val project: Project) {

    private data class About(val textHash: String, val projectModificationCount: Long)

    private class Answer(val about: About, val classes: List<ByInheritingClass>)

    /** Weakly keyed: a file closed and collected takes its answer with it. */
    private val answers: MutableMap<VirtualFile, Answer> = ContainerUtil.createConcurrentWeakMap()

    /** The classes of [file] with what each inherits, or null when there is no answer to give. */
    fun forFile(file: PsiFile): List<ByInheritingClass>? {
        val virtualFile = file.originalFile.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file.originalFile) ?: return null
        val text = document.immutableCharSequence
        val about = About(ByTextHash.of(text), PsiModificationTracker.getInstance(project).modificationCount)
        answers[virtualFile]?.takeIf { it.about == about }?.let { return it.classes }
        if (ApplicationManager.getApplication().isDispatchThread) return null
        val server = runningByServer(project, virtualFile) ?: return null
        ByServerDocuments.ensureOpen(server, project, virtualFile)
        val params = BySyntaxOutlineParams(TextDocumentIdentifier(server.getDocumentIdentifier(virtualFile).uri), about.textHash)
        val classes = server.askBy("by/documentInheritedMembers", TIMEOUT_MS) {
            (it as ByServerExtensions).documentInheritedMembers(params)
        }.value ?: return null
        answers[virtualFile] = Answer(about, classes)
        return classes
    }

    /** Puts in an answer as though `by` had just given it for [file] as it is now. */
    @TestOnly
    fun remember(file: PsiFile, classes: List<ByInheritingClass>) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val about = About(ByTextHash.of(document.immutableCharSequence), PsiModificationTracker.getInstance(project).modificationCount)
        answers[file.virtualFile] = Answer(about, classes)
    }

    companion object {
        fun getInstance(project: Project): ByInheritedMembers = project.service()

        /** It reads every file the classes inherit from. */
        private const val TIMEOUT_MS = 10_000
    }
}

/**
 * The Structure view's and File Structure popup's *Show Inherited* toggle (Ctrl+F12 twice in the
 * popup, as it is for Java and Python): under each class, after what it declares, the members it
 * inherits, greyed as PyCharm greys a `.py` class's, each opening where its superclass declares it.
 * What a class inherits is `by`'s ([ByInheritedMembers]); a class is matched to its answer by the
 * name's range, which the platform's node carries in its `DocumentSymbol`.
 *
 * A node provider rather than a filter over nodes always built: the platform asks a provider only
 * while its toggle is on, so `by` is not asked to read the superclasses of every class for a toggle
 * left off.
 */
internal class ByInheritedMembersProvider(private val file: PsiFile) :
    FileStructureNodeProvider<ByInheritedElement>, ActionShortcutProvider {

    override fun provideNodes(node: TreeElement): Collection<ByInheritedElement> {
        val symbol = (node as? StructureViewTreeElement)?.value as? DocumentSymbol ?: return emptyList()
        if (symbol.kind != SymbolKind.Class) return emptyList()
        val classes = ByInheritedMembers.getInstance(file.project).forFile(file) ?: return emptyList()
        val owner = classes.firstOrNull { it.selectionRange?.start == symbol.selectionRange?.start } ?: return emptyList()
        return owner.members.map { ByInheritedElement(file.project, it) }
    }

    override fun getCheckBoxText(): String = BasedPythonBundle.message("structure.inherited.checkbox")

    /** The popup's own shortcut, Ctrl+F12, given by [getActionIdForShortcut]. */
    override fun getShortcut(): Array<Shortcut> = emptyArray()

    override fun getActionIdForShortcut(): String = IdeActions.ACTION_FILE_STRUCTURE_POPUP

    override fun getPresentation(): ActionPresentation =
        ActionPresentationData(BasedPythonBundle.message("structure.inherited.action"), null, AllIcons.Hierarchy.Supertypes)

    /** The platform's own toggle's, so the state is shared with the other languages'. */
    override fun getName(): String = "SHOW_INHERITED"
}

/** An inherited member in the structure: its name and signature, greyed, opening where it is declared. */
internal data class ByInheritedElement(private val project: Project, val member: ByInheritedMember) : StructureViewTreeElement {

    override fun getValue(): Any = member

    override fun getChildren(): Array<TreeElement> = TreeElement.EMPTY_ARRAY

    override fun getPresentation(): ItemPresentation = object : ColoredItemPresentation {
        override fun getPresentableText(): String? = member.name
        override fun getLocationString(): String? = member.detail
        override fun getIcon(unused: Boolean): Icon? = member.kind?.let(SYMBOL_KINDS::getIcon)
        override fun getTextAttributesKey(): TextAttributesKey = CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES
    }

    override fun navigate(requestFocus: Boolean) {
        val file = member.uri?.let(ByWorkspaceEditFiles::fileOf) ?: return
        val document = FileDocumentManager.getInstance().getDocument(file)
        val offset = document?.let { doc -> (member.selectionRange ?: member.range)?.start?.let { getOffsetInDocument(doc, it) } } ?: 0
        OpenFileDescriptor(project, file, offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = member.uri != null

    override fun canNavigateToSource(): Boolean = canNavigate()

    private companion object {
        /** The icons `by`'s descriptor shows symbol kinds with: the platform's default customizer. */
        val SYMBOL_KINDS = LspSymbolKindCustomizer()
    }
}
