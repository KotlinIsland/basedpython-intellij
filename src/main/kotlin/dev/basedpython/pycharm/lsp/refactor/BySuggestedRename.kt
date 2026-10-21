package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.refactoring.suggested.SuggestedRefactoringAvailability
import com.intellij.refactoring.suggested.SuggestedRefactoringData
import com.intellij.refactoring.suggested.SuggestedRefactoringExecution
import com.intellij.refactoring.suggested.SuggestedRefactoringProvider
import com.intellij.refactoring.suggested.SuggestedRefactoringState
import com.intellij.refactoring.suggested.SuggestedRefactoringStateChanges
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport
import com.intellij.refactoring.suggested.SuggestedRefactoringUI
import com.intellij.refactoring.suggested.SuggestedRenameData
import com.intellij.usageView.UsageInfo
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByNamedRenameParams
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import dev.basedpython.pycharm.lsp.runningByServer
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Suggested refactoring for a `.by` file, rename only: after the name a `def` or `class` declares,
 * or one of a `def`'s parameters, is edited in place, the gutter's R offers to rename what uses it
 * to match, through `by`'s rename.
 *
 * ## What is a declaration
 *
 * The platform tracks a declaration from the token being edited, which in a `.by` file is all the
 * tree there is. Whether that token is a name a definition declares is `by`'s parse, from its
 * outline (see [ByOutlines]): the platform asks first on the EDT as the edit begins, where the
 * outline of the text before it is read without waiting, and then after each keystroke off the
 * EDT, where the outline of the new text is waited for. A name a statement assigns is not offered:
 * a second assignment binds the same variable, and editing it is as often a new variable as the
 * old one renamed.
 *
 * ## The rename
 *
 * The platform's suggested rename puts the old name back and runs its own Rename on the declaration
 * it is handed. That is [ByRenamedName], which stands for the name `by` declared, and its
 * processor, [ByRenamedNameProcessor], asks `by`'s `textDocument/rename` for the edit, every use
 * across the project included, and makes it — the platform finds no references of its own. So what
 * is renamed, and what is left alone, is exactly what Shift+F6 does.
 *
 * ## Not done: Change Signature
 *
 * Adding, removing or reordering a `def`'s parameters is not offered for its calls: `by` has no
 * request that rewrites call sites for a changed signature.
 */
class BySuggestedRefactoringSupport : SuggestedRefactoringSupport {

    override fun isAnchor(psiElement: PsiElement): Boolean = declaredName(psiElement) != null

    override fun signatureRange(anchor: PsiElement): TextRange? = nameRange(anchor)

    override fun importsRange(psiFile: PsiFile): TextRange? = null

    override fun nameRange(anchor: PsiElement): TextRange? = when (anchor) {
        is ByRenamedName -> anchor.textRange
        else -> anchor.textRange
    }

    /** A `.by` file's tree has no error elements; whether the name is one is [isAnchor]'s answer. */
    override fun hasSyntaxError(anchor: PsiElement): Boolean = false

    override fun isIdentifierStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

    override fun isIdentifierPart(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)

    override val stateChanges: SuggestedRefactoringStateChanges = ByNameStateChanges(this)

    override val availability: SuggestedRefactoringAvailability = ByRenameAvailability(this)

    override val ui: SuggestedRefactoringUI = SuggestedRefactoringUI.RenameOnly

    override val execution: SuggestedRefactoringExecution = SuggestedRefactoringExecution.RenameOnly(this)

    internal companion object {

        /**
         * The range of [element] when it is a token that is the name a definition declares, as `by`
         * last outlined the text it is in; `null` when it is not, or there is no outline of that
         * text to say. Off the EDT the outline is waited for; on it, only an outline already in hand
         * is read.
         */
        fun declaredName(element: PsiElement): TextRange? {
            if (element.firstChild != null) return null
            val file = element.containingFile as? BasedPythonFile ?: return null
            val document = file.viewProvider.document ?: return null
            // a token of a tree not yet brought up to the text is at offsets the outline does not use
            if (!PsiDocumentManager.getInstance(file.project).isCommitted(document)) return null
            val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return null
            val range = element.textRange
            return range.takeIf { declares(outline, it) }
        }

        private fun declares(outline: ByOutline, range: TextRange): Boolean {
            var found = false
            outline.walk { statement, _ ->
                if (statement.name == range || range in statement.parameters) found = true
            }
            return found
        }
    }
}

/** A declaration's signature is its name: the text of the token. */
private class ByNameStateChanges(support: SuggestedRefactoringSupport) : SuggestedRefactoringStateChanges(support) {

    override fun signature(anchor: PsiElement, prevState: SuggestedRefactoringState?): SuggestedRefactoringSupport.Signature? {
        // a state that names this support is about to be held by the platform
        anchor.project.service<BySuggestedRenameReset>()
        val name = anchor.text?.takeIf { it.isNotEmpty() } ?: return null
        return SuggestedRefactoringSupport.Signature.create(name, null, emptyList(), null)
    }

    override fun parameterMarkerRanges(anchor: PsiElement): List<TextRange?> = emptyList()
}

/** A renamed declaration is offered as the rename of [ByRenamedName]. */
private class ByRenameAvailability(support: SuggestedRefactoringSupport) : SuggestedRefactoringAvailability(support) {

    override fun detectAvailableRefactoring(state: SuggestedRefactoringState): SuggestedRefactoringData? {
        val anchor = state.anchor
        val file = anchor.containingFile as? BasedPythonFile ?: return null
        val document = file.viewProvider.document ?: return null
        return SuggestedRenameData(ByRenamedName(file, document, anchor.textRange), state.oldSignature.name)
    }
}

/**
 * The name a `.by` definition declares, as the declaration the platform renames: a token's range,
 * followed through edits by a marker.
 *
 * Made by [ByRenameAvailability] for the suggestion on screen and dropped with it; nothing keeps
 * one. [setName] is how the platform puts the old name back before it renames: it rewrites the
 * name's text, and nothing else.
 */
internal class ByRenamedName(private val file: PsiFile, private val document: Document, range: TextRange) : FakePsiElement() {

    private val marker: RangeMarker = document.createRangeMarker(range).apply {
        isGreedyToLeft = true
        isGreedyToRight = true
    }

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getTextRange(): TextRange = marker.textRange

    override fun getTextOffset(): Int = marker.startOffset

    override fun getName(): String = document.immutableCharSequence.subSequence(marker.startOffset, marker.endOffset).toString()

    override fun getText(): String = name

    override fun setName(name: String): PsiElement {
        document.replaceString(marker.startOffset, marker.endOffset, name)
        PsiDocumentManager.getInstance(file.project).commitDocument(document)
        return this
    }

    override fun isValid(): Boolean = file.isValid && marker.isValid

    override fun isWritable(): Boolean = file.isWritable

    override fun toString(): String = "ByRenamedName(${marker.textRange})"
}

/**
 * The Rename the platform runs for a suggested rename of a [ByRenamedName], made by `by`.
 *
 * The platform finds no references to the name, since nothing in a `.by` file's tree refers to
 * anything; [findCollisions], which it calls off the EDT under the rename's progress with the new
 * name, asks `by`'s `textDocument/rename` instead, and each edit `by` answers with becomes a usage
 * that [renameElement] makes — the declaration's own name among them.
 */
class ByRenamedNameProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = element is ByRenamedName

    override fun findReferences(
        element: PsiElement,
        searchScope: SearchScope,
        searchInCommentsAndStrings: Boolean,
    ): Collection<PsiReference> = emptyList()

    /** Comments and strings are `by`'s to rename or leave, not a text search's. */
    override fun getElementToSearchInStringsAndComments(element: PsiElement): PsiElement? = null

    override fun findCollisions(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<out PsiElement, String>,
        result: MutableList<UsageInfo>,
    ) {
        if (element !is ByRenamedName) return
        val project = element.project
        val file = element.containingFile.virtualFile
        val server = file?.let { runningByServer(project, it) }
            ?: return run { result += ByRenameUnknown(element, BasedPythonBundle.message("rename.by.suggested.noServer", element.name)) }
        val document = element.containingFile.viewProvider.document ?: return
        ByServerDocuments.ensureOpen(server, project, file)
        val params = ByNamedRenameParams(
            TextDocumentIdentifier(server.getDocumentIdentifier(file).uri),
            getLsp4jPosition(document, element.textOffset),
            newName,
            ByTextHash.of(document.immutableCharSequence),
        )
        when (val answer = server.askBy("textDocument/rename") { it.textDocumentService.rename(params) }) {
            is ByAnswer.Answer -> result += ByImportEdit.of(project, answer.value, element)
            ByAnswer.None, ByAnswer.Failed ->
                result += ByRenameUnknown(element, BasedPythonBundle.message("rename.by.suggested.failed", element.name, newName))
        }
    }

    /** `by`'s edits, the declaration's own name among them, in the rename's command. */
    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        val (edits, _) = splitImportEdits(usages)
        ByImportEdit.apply(element.project, edits)
        listener?.elementRenamed(element)
    }
}

/**
 * `by` could not say what to rename, and the rename would change the declaration and nothing that
 * uses it. Reported in the platform's conflicts dialog, where the user decides whether to go ahead.
 */
private class ByRenameUnknown(element: PsiElement, private val description: String) :
    UnresolvableCollisionUsageInfo(element, element) {
    override fun getDescription(): String = description
}

/**
 * Lets go of the suggestion the platform is holding when the plugin unloads.
 *
 * The platform keeps the state of the declaration being edited, which names
 * [BySuggestedRefactoringSupport], until the edit is done with, and nothing about an unload ends
 * it. One left there would keep the plugin's classes loaded, so the suggestion is dropped with this
 * service, which the platform disposes as the plugin goes. Asked for whenever such a state is
 * made, so that it exists by then.
 */
@Service(Service.Level.PROJECT)
internal class BySuggestedRenameReset(private val project: Project) : Disposable {
    override fun dispose() {
        // an unload runs on the EDT; a project closing takes the provider with it anyway
        if (project.isDisposed || !ApplicationManager.getApplication().isDispatchThread) return
        project.serviceIfCreated<SuggestedRefactoringProvider>()?.reset()
    }
}
