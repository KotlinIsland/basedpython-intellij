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
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.refactoring.suggested.SignatureChangePresentationModel
import com.intellij.refactoring.suggested.SignaturePresentationBuilder
import com.intellij.refactoring.suggested.SuggestedChangeSignatureData
import com.intellij.refactoring.suggested.SuggestedRefactoringAvailability
import com.intellij.refactoring.suggested.SuggestedRefactoringData
import com.intellij.refactoring.suggested.SuggestedRefactoringExecution
import com.intellij.refactoring.suggested.SuggestedRefactoringProvider
import com.intellij.refactoring.suggested.SuggestedRefactoringState
import com.intellij.refactoring.suggested.SuggestedRefactoringStateChanges
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport
import com.intellij.refactoring.suggested.SuggestedRefactoringUI
import com.intellij.refactoring.suggested.SuggestedRenameData
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import dev.basedpython.pycharm.lang.BasedPythonCodeFragment
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByAnnotationEdit
import dev.basedpython.pycharm.lsp.ext.ByChangeSignatureParams
import dev.basedpython.pycharm.lsp.ext.ByChangedParameter
import dev.basedpython.pycharm.lsp.ext.ByNamedRenameParams
import dev.basedpython.pycharm.lsp.ext.BySignature
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import dev.basedpython.pycharm.lsp.runningByServer
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.TextDocumentIdentifier

/**
 * Suggested refactoring for a `.by` file: after a `def`'s signature, or the name a `class` declares,
 * is edited in place, the gutter's R offers to bring what uses it along — a rename through `by`'s
 * rename, or Change Signature through `by/changeSignature`.
 *
 * ## What is a declaration
 *
 * The platform tracks a declaration from the token being edited, which in a `.by` file is all the
 * tree there is. Whether that token is a name a definition declares, or is in a `def`'s header, is
 * `by`'s parse, from its outline (see [ByOutlines]): the platform asks first on the EDT as the edit
 * begins, where the outline of the text before it is read without waiting, and then after each
 * keystroke off the EDT, where the outline of the new text is waited for. A name a statement assigns
 * is not offered: a second assignment binds the same variable, and editing it is as often a new
 * variable as the old one renamed.
 *
 * A `def`'s signature is its header from the name to the end of its return annotation, read from
 * the name's tokens ([ByDefHeader]), so that it can be read again wherever the name is, which the
 * platform does on the EDT around the refactoring, before `by` has outlined the text it just wrote.
 *
 * ## What is offered
 *
 * A changed name alone, of a function or class or of one parameter, is a rename: the platform puts
 * the old name back and runs its own Rename on [ByRenamedName], whose processor,
 * [ByRenamedNameProcessor], makes `by`'s `textDocument/rename` — so what is renamed is exactly what
 * Shift+F6 renames. Parameters added, removed or reordered, moved between `*` and `/`, or stripped of
 * a default — what calls have to follow — are Change Signature ([ByChangeSignatureExecution]). An
 * annotation, a default value or the return annotation changed alone changes no call, and nothing is
 * offered.
 */
class BySuggestedRefactoringSupport : SuggestedRefactoringSupport {

    override fun isAnchor(psiElement: PsiElement): Boolean =
        declaredName(psiElement) != null || defNameAround(psiElement) != null

    override fun signatureRange(anchor: PsiElement): TextRange? = header(anchor)?.range ?: anchor.textRange

    override fun importsRange(psiFile: PsiFile): TextRange? = null

    override fun nameRange(anchor: PsiElement): TextRange? = when (anchor) {
        is ByRenamedName -> anchor.textRange
        else -> header(anchor)?.name ?: anchor.textRange
    }

    /** A `.by` file's tree has no error elements; a header that is not whole has no signature. */
    override fun hasSyntaxError(anchor: PsiElement): Boolean = false

    override fun isIdentifierStart(c: Char): Boolean = c == '_' || Character.isLetter(c)

    override fun isIdentifierPart(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)

    override val stateChanges: SuggestedRefactoringStateChanges = BySignatureStateChanges(this)

    override val availability: SuggestedRefactoringAvailability = BySignatureAvailability(this)

    override val ui: SuggestedRefactoringUI = BySignatureUI

    override val execution: SuggestedRefactoringExecution = ByChangeSignatureExecution(this)

    internal companion object {

        /**
         * The range of [element] when it is a token that is the name a definition declares, as `by`
         * last outlined the text it is in; `null` when it is not, or there is no outline of that
         * text to say. Off the EDT the outline is waited for; on it, only an outline already in hand
         * is read.
         */
        fun declaredName(element: PsiElement): TextRange? {
            val outline = outlineOf(element) ?: return null
            val range = element.textRange
            return range.takeIf { declares(outline, it) }
        }

        /**
         * The name of the `def` whose header — from its name to the `:` opening its body — holds
         * [element], a token, as `by` last outlined the text; read as [declaredName] reads it.
         */
        fun defNameAround(element: PsiElement): TextRange? {
            val outline = outlineOf(element) ?: return null
            val text = element.containingFile.viewProvider.document?.immutableCharSequence ?: return null
            val range = element.textRange
            var found: TextRange? = null
            outline.walk { statement, _ ->
                val name = statement.name
                val clause = statement.clauses.firstOrNull()
                val keyword = clause?.keyword
                val colon = clause?.colon
                if (name != null && keyword != null && colon != null && text.subSequence(keyword.startOffset, keyword.endOffset).endsWith("def") &&
                    name.startOffset <= range.startOffset && range.endOffset <= colon.startOffset
                ) {
                    found = name
                }
            }
            return found
        }

        /**
         * The header of the `def` [anchor] is the name of — a token after the keyword `def`, as the
         * tokens say — or, as `by`'s outline says, is in the header of; null for any other anchor,
         * and for a header that is not whole.
         */
        fun header(anchor: PsiElement): ByDefHeader? {
            val file = anchor.containingFile as? BasedPythonFile ?: return null
            val text = file.viewProvider.document?.immutableCharSequence ?: return null
            val before = PsiTreeUtil.skipWhitespacesAndCommentsBackward(anchor)
            val name = if (before?.node?.elementType == BasedPythonTokenTypes.KEYWORD && before.text == "def") {
                anchor.textRange
            } else {
                defNameAround(anchor) ?: return null
            }
            return ByDefHeader.read(text, name)
        }

        private fun outlineOf(element: PsiElement): ByOutline? {
            if (element.firstChild != null) return null
            val file = element.containingFile as? BasedPythonFile ?: return null
            val document = file.viewProvider.document ?: return null
            // a token of a tree not yet brought up to the text is at offsets the outline does not use
            if (!PsiDocumentManager.getInstance(file.project).isCommitted(document)) return null
            return ByOutlines.getInstance(file.project).forFile(file)
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

/**
 * How a parameter is passed, as far as the header's tokens say: what a call has to follow when it
 * changes. Its annotation and its default's value are not here, since no call follows them.
 */
internal data class ByParameterShape(val stars: String, val section: Int, val hasDefault: Boolean) :
    SuggestedRefactoringSupport.ParameterAdditionalData {
    override fun toString(): String = "$stars§$section${if (hasDefault) "=" else ""}"
}

/**
 * A `def`'s signature: its name, its parameters each with what follows its name as [type] and its
 * [ByParameterShape], and its return annotation; a class's, its name.
 */
private class BySignatureStateChanges(support: SuggestedRefactoringSupport) : SuggestedRefactoringStateChanges(support) {

    override fun signature(anchor: PsiElement, prevState: SuggestedRefactoringState?): SuggestedRefactoringSupport.Signature? {
        // a state that names this support is about to be held by the platform
        anchor.project.service<BySuggestedRenameReset>()
        val header = BySuggestedRefactoringSupport.header(anchor)
        if (header == null) {
            // a def's header being edited into something that is not one yet
            if (BySuggestedRefactoringSupport.defNameAround(anchor) != null) return null
            val name = anchor.text?.takeIf { it.isNotEmpty() } ?: return null
            return SuggestedRefactoringSupport.Signature.create(name, null, emptyList(), null)
        }
        val text = anchor.containingFile.viewProvider.document?.immutableCharSequence ?: return null
        val parameters = header.parameters.map {
            SuggestedRefactoringSupport.Parameter(
                Any(),
                text.subSequence(it.name.startOffset, it.name.endOffset).toString(),
                it.rest,
                ByParameterShape(it.stars, it.section, it.hasDefault),
            )
        }
        val name = text.subSequence(header.name.startOffset, header.name.endOffset).toString()
        val signature = SuggestedRefactoringSupport.Signature.create(name, header.returns, parameters, null) ?: return null
        return if (prevState == null) signature else matchParametersWithPrevState(signature, anchor, prevState)
    }

    /** The `,` or `)` after each parameter, which retyping its name, annotation or default leaves where it was. */
    override fun parameterMarkerRanges(anchor: PsiElement): List<TextRange?> =
        BySuggestedRefactoringSupport.header(anchor)?.parameters?.map { it.after }.orEmpty()
}

/** Which refactoring an edit of a signature asks for; see [BySuggestedRefactoringSupport]. */
private class BySignatureAvailability(support: SuggestedRefactoringSupport) : SuggestedRefactoringAvailability(support) {

    override fun detectAvailableRefactoring(state: SuggestedRefactoringState): SuggestedRefactoringData? {
        val anchor = state.anchor
        val file = anchor.containingFile as? BasedPythonFile ?: return null
        val document = file.viewProvider.document ?: return null
        val old = state.oldSignature
        val new = state.newSignature
        val header = BySuggestedRefactoringSupport.header(anchor)
            ?: return SuggestedRenameData(ByRenamedName(file, document, anchor.textRange), old.name).takeIf { old.name != new.name }
        if (callsFollow(old, new)) {
            return SuggestedChangeSignatureData.create(state, BasedPythonBundle.message("refactoring.by.signature.suggested.calls"))
        }
        val renamed = old.parameters.zip(new.parameters).withIndex().filter { (_, pair) -> pair.first.name != pair.second.name }
        return when {
            renamed.isEmpty() && old.name != new.name -> SuggestedRenameData(ByRenamedName(file, document, header.name), old.name)
            renamed.size == 1 && old.name == new.name -> {
                val (index, pair) = renamed.single()
                SuggestedRenameData(ByRenamedName(file, document, header.parameters[index].name), pair.first.name)
            }
            renamed.isEmpty() -> null
            else -> SuggestedChangeSignatureData.create(state, BasedPythonBundle.message("refactoring.by.signature.suggested.calls"))
        }
    }

    /**
     * Whether calls have to change: a parameter added, removed or moved, passed another way, or
     * no longer defaulted. Not for a name, an annotation, a default's value or the return annotation.
     */
    private fun callsFollow(old: SuggestedRefactoringSupport.Signature, new: SuggestedRefactoringSupport.Signature): Boolean {
        if (old.parameters.map { it.id } != new.parameters.map { it.id }) return true
        return old.parameters.zip(new.parameters).any { (before, after) ->
            val was = before.additionalData as? ByParameterShape
            val now = after.additionalData as? ByParameterShape
            was == null || now == null || was.stars != now.stars || was.section != now.section || (was.hasDefault && !now.hasDefault)
        }
    }
}

/**
 * The suggestion's popup: the signature before and after, each parameter as written, and a field
 * for what each call passes a new parameter that has no default — the parameters `by` cannot leave
 * out of a call.
 */
internal object BySignatureUI : SuggestedRefactoringUI() {

    override fun createSignaturePresentationBuilder(
        signature: SuggestedRefactoringSupport.Signature,
        otherSignature: SuggestedRefactoringSupport.Signature,
        isOldSignature: Boolean,
    ): SignaturePresentationBuilder = object : SignaturePresentationBuilder(signature, otherSignature, isOldSignature) {
        override fun buildPresentation() {
            fragments += leaf(signature.name, otherSignature.name)
            buildParameterList { fragments, parameter, corresponding ->
                fragments += leaf(written(parameter), corresponding?.let(::written))
            }
            signature.type?.let {
                fragments += SignatureChangePresentationModel.TextFragment.Leaf(" -> ")
                fragments += leaf(it, otherSignature.type)
            }
        }
    }

    override fun extractNewParameterData(data: SuggestedChangeSignatureData): List<NewParameterData> =
        valuesAsked(data).map { parameter ->
            NewParameterData(parameter.name, valueFragment(data.declaration, parameter.name), offerToUseAnyVariable = false)
        }

    override fun extractValue(fragment: PsiCodeFragment): SuggestedRefactoringExecution.NewParameterValue.Expression? =
        fragment.takeIf { it.text.isNotBlank() }?.let { SuggestedRefactoringExecution.NewParameterValue.Expression(it) }

    /** The new parameters each call has to be given something for: no default, and not `*args` or `**kwargs`. */
    fun valuesAsked(data: SuggestedChangeSignatureData): List<SuggestedRefactoringSupport.Parameter> =
        data.newSignature.parameters.filter { parameter ->
            val shape = parameter.additionalData as? ByParameterShape
            data.oldSignature.parameterById(parameter.id) == null && shape != null && shape.stars.isEmpty() && !shape.hasDefault
        }

    /** [parameter] as the header writes it. */
    private fun written(parameter: SuggestedRefactoringSupport.Parameter): String =
        ((parameter.additionalData as? ByParameterShape)?.stars.orEmpty()) + parameter.name + parameter.type

    /** A basedpython expression typed in the popup, in the context of the declaration. */
    private fun valueFragment(context: PsiElement, name: String): PsiCodeFragment =
        BasedPythonCodeFragment.create(context.project, "$name.by", "", context)
}

/**
 * Change Signature for a suggestion: `by/prepareChangeSignature` reads the new header while it is in
 * place — each parameter's kind, default and annotation as `by` parses them — and, with the old one
 * put back by the platform, the old header, and `by/changeSignature` rewrites the function and every
 * call of it from the old list to the new, each new parameter passed what was typed for it. A
 * refusal is shown, with the file and line `by` names, and the header is left as it was typed.
 *
 * The platform asks on the EDT, inside its command, so each request goes under a modal progress, as
 * the Change Signature dialog's do.
 */
internal class ByChangeSignatureExecution(support: SuggestedRefactoringSupport) : SuggestedRefactoringExecution(support) {

    /** The new header as `by` reads it, and where the function's name is; or why there is none. */
    sealed interface Prepared {
        data class Ready(val offset: Int, val signature: BySignature) : Prepared

        data class Unanswered(val message: String) : Prepared
    }

    override fun prepareChangeSignature(data: SuggestedChangeSignatureData): Any {
        val offset = refactoringSupport.nameRange(data.declaration)?.startOffset ?: data.declaration.textOffset
        val site = site(data.declaration.containingFile, offset) ?: return Prepared.Unanswered(BasedPythonBundle.message("refactoring.by.noServer"))
        return when (val asked = askSignature(site)) {
            is ByAsked.Answer -> Prepared.Ready(offset, asked.value)
            else -> Prepared.Unanswered(unanswered(asked))
        }
    }

    override fun performChangeSignature(data: SuggestedChangeSignatureData, newParameterValues: List<NewParameterValue>, preparedData: Any?) {
        val file = data.declaration.containingFile
        val project = file.project
        val prepared = preparedData as? Prepared.Ready
            ?: return CommonRefactoringUtil.showErrorMessage(TITLE, (preparedData as? Prepared.Unanswered)?.message.orEmpty(), null, project)
        val site = site(file, prepared.offset) ?: return
        val old = when (val asked = askSignature(site)) {
            is ByAsked.Answer -> asked.value
            else -> return CommonRefactoringUtil.showErrorMessage(TITLE, unanswered(asked), null, project)
        }
        val values = BySignatureUI.valuesAsked(data).map { it.name }
            .zip(newParameterValues) { name, value -> name to (value as? NewParameterValue.Expression)?.expression?.text?.takeIf { it.isNotBlank() } }
            .toMap()
        val params = changeSignatureParams(site, data, old, prepared.signature, values)
        val edited = ByProjectRefactorings.askEdit(site, TITLE, "by/changeSignature") { it.changeSignature(params) }
        ByProjectRefactorings.processor(project, TITLE, file, edited)?.run()
    }

    /** Where the function's name is, for `by`; null when no `by` runs for the file, which the user has been told. */
    private fun site(file: PsiFile, offset: Int): ByRefactoringSite? =
        file.virtualFile?.let { ByProjectRefactorings.site(file.project, null, it, offset, TITLE) }

    private fun askSignature(site: ByRefactoringSite): ByAsked<BySignature> =
        ByProjectRefactorings.ask(site, TITLE, "by/prepareChangeSignature") { it.prepareChangeSignature(site.params) }

    private fun unanswered(asked: ByAsked<BySignature>): String =
        ByProjectRefactorings.unanswered(asked, "by/prepareChangeSignature", BasedPythonBundle.message("refactoring.by.signature.noFunction")).orEmpty()

    companion object {
        private val TITLE: String get() = RefactoringBundle.message("changeSignature.refactoring.name")

        /**
         * `by/changeSignature`'s request taking the function from [old] to [new], `by`'s readings of
         * the header before and after the edit: each new parameter with the one it was — the
         * platform's record of which parameter each is, followed through renames — its kind, default
         * and annotation as `by` read them, the annotation sent only where it changed, and for a new
         * one the value [values] has for it; the name and return annotation only where they changed.
         */
        fun changeSignatureParams(
            site: ByRefactoringSite,
            data: SuggestedChangeSignatureData,
            old: BySignature,
            new: BySignature,
            values: Map<String, String?>,
        ): ByChangeSignatureParams {
            val parameters = new.parameters.orEmpty().mapNotNull { parameter ->
                val name = parameter.name ?: return@mapNotNull null
                val kind = parameter.kind ?: return@mapNotNull null
                val was = data.newSignature.parameterByName(name)?.let { data.oldSignature.parameterById(it.id) }
                    ?.let { tracked -> old.parameters.orEmpty().firstOrNull { it.name == tracked.name } }
                ByChangedParameter(
                    name = name,
                    oldName = was?.name,
                    kind = kind,
                    default = parameter.default,
                    value = if (was == null) values[name] else null,
                    annotation = annotationEdit(was?.annotation, parameter.annotation),
                )
            }
            val name = data.newSignature.name.takeIf { it != data.oldSignature.name }
            val returns = annotationEdit(old.returns, new.returns)
            return ByChangeSignatureParams(site.identifier, site.position, parameters, name, returns)
        }

        /** [now] against [was]: unchanged is nothing to send, gone is a removal. */
        private fun annotationEdit(was: String?, now: String?): ByAnnotationEdit? = when {
            was == now -> null
            now == null -> ByAnnotationEdit.Remove
            else -> ByAnnotationEdit.Set(now)
        }
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
