package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.ElementPattern
import com.intellij.patterns.PlatformPatterns
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.ElementDescriptionLocation
import com.intellij.psi.ElementDescriptionProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.refactoring.rename.naming.NameSuggester
import com.intellij.refactoring.util.RefactoringDescriptionLocation
import com.intellij.usageView.UsageInfo
import com.intellij.usageView.UsageViewShortNameLocation
import com.intellij.usageView.UsageViewTypeLocation
import com.intellij.util.ProcessingContext
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByRenameOffer
import dev.basedpython.pycharm.lsp.ext.ByRenameOffersParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.RenameFile

/**
 * The renames `by` offers beside a rename — overriding methods, their parameters, a module named
 * after the class — shown and applied the way the platform's automatic renamers are: once the
 * rename has found its uses, one dialog per kind lists each offer with a checkbox, and the ones kept
 * are renamed with the rest, in the rename's one command.
 *
 * `by/renameOffers` is asked beside `textDocument/rename` ([ByRenamedNameProcessor]) and answers
 * each offer as an edit of its own. Each becomes a [ByRenameOfferElement], an extra element of the
 * platform's rename: a [ByRenameOffersFactory] of its kind hands them to the platform, the platform
 * asks which to keep, and [ByRenameOfferProcessor] finds and makes each kept one's edits — and
 * renames the module's file through the IDE's own file rename, so the VFS, open editors and VCS
 * follow it.
 */
internal object ByRenameOffers {

    /**
     * What `by` offers beside renaming the symbol at [offset] of [document] to [newName]. Empty for a
     * `by` without the request, as for one with nothing to offer: the rename goes ahead alone either
     * way, as it did before.
     */
    @RequiresBackgroundThread
    @RequiresReadLock
    fun ask(client: LspClient, project: Project, file: VirtualFile, document: Document, offset: Int, newName: String): List<ByRenameOffer> {
        ByServerDocuments.ensureOpen(client, project, file)
        val params = ByRenameOffersParams(
            client.getDocumentIdentifier(file),
            getLsp4jPosition(document, offset),
            newName,
            ByTextHash.of(document.immutableCharSequence),
        )
        return client.askBy("by/renameOffers") { (it as ByServerExtensions).renameOffers(params) }.value.orEmpty()
            .filter { it.edit != null && ByOfferKind.of(it.id) != null }
    }

    /**
     * [offers] as elements of the rename, each standing where its first edit is — what the platform's
     * dialog previews — with the name it renames and the name it gives. An offer whose files the IDE
     * does not have is left out: there is nothing to apply it to.
     */
    @RequiresReadLock
    fun elementsOf(
        project: Project,
        offers: List<ByRenameOffer>,
        fileOf: (uri: String) -> VirtualFile? = ByWorkspaceEditFiles::fileOf,
    ): List<ByRenameOfferElement> = offers.mapNotNull { offer -> elementOf(project, offer, fileOf) }

    private fun elementOf(project: Project, offer: ByRenameOffer, fileOf: (String) -> VirtualFile?): ByRenameOfferElement? {
        val edit = offer.edit ?: return null
        val psiManager = PsiManager.getInstance(project)
        val fileRename = edit.documentChanges.orEmpty().mapNotNull { change -> (change.right as? RenameFile) }.firstOrNull()
        if (fileRename != null) {
            val module = fileOf(fileRename.oldUri)?.let(psiManager::findFile) ?: return null
            val newName = fileOf(fileRename.newUri)?.name ?: fileRename.newUri.substringAfterLast('/')
            return ByRenameOfferElement(module, TextRange.EMPTY_RANGE, offer, module.name, newName, fileRename)
        }
        for (uri in ByWorkspaceEditFiles.uris(edit)) {
            val file = fileOf(uri) ?: continue
            val psiFile = psiManager.findFile(file) ?: continue
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            val first = edit.changes?.get(uri)?.firstOrNull() ?: continue
            val range = getRangeInDocument(document, first.range) ?: continue
            return ByRenameOfferElement(psiFile, range, offer, document.getText(range), first.newText, null)
        }
        return null
    }
}

/** The three kinds `by` offers, by the prefix of an offer's id. */
internal enum class ByOfferKind(val prefix: String, private val entityKey: String, private val descriptionKey: String) {
    Override("override:", "rename.by.offers.override.entity", "rename.by.offers.override.description"),
    Parameter("parameter:", "rename.by.offers.parameter.entity", "rename.by.offers.parameter.description"),
    Module("module:", "rename.by.offers.module.entity", "rename.by.offers.module.description"),
    ;

    val entityName: String get() = BasedPythonBundle.message(entityKey)
    val dialogDescription: String get() = BasedPythonBundle.message(descriptionKey)

    companion object {
        fun of(id: String?): ByOfferKind? = entries.firstOrNull { id?.startsWith(it.prefix) == true }
    }
}

/**
 * One rename `by` offers, as an element the platform's rename renames: [getName] is the name it
 * renames (`speak`, `loud`, `animal.by`) and [newName] the one it gives; it stands at its first edit,
 * or for a module at the module's file. Lives as long as the rename it was found for.
 */
internal class ByRenameOfferElement(
    private val file: PsiFile,
    private val range: TextRange,
    val offer: ByRenameOffer,
    private val oldName: String,
    val newName: String,
    /** The module's own move, for a module offer. */
    val fileRename: RenameFile?,
) : FakePsiElement() {

    val kind: ByOfferKind get() = ByOfferKind.of(offer.id)!!

    override fun getParent(): PsiElement = file

    override fun getContainingFile(): PsiFile = file

    override fun getNavigationElement(): PsiElement = this

    override fun getTextRange(): TextRange = range

    override fun getTextOffset(): Int = range.startOffset

    override fun getName(): String = oldName

    override fun getText(): String = oldName

    override fun isValid(): Boolean = file.isValid

    override fun isWritable(): Boolean = file.isWritable

    override fun toString(): String = "ByRenameOfferElement(${offer.id})"
}

/**
 * Hands the platform's rename the offers of one kind that `by` made for the name being renamed, as
 * an [AutomaticRenamer] — one dialog per kind, titled with `by`'s label, as PyCharm offers to rename
 * a method's overrides. No checkbox in the Rename dialog ([getOptionName] is null): whether there is
 * anything to offer is only known once `by` has been asked, and the dialog that lists the offers is
 * where each is chosen.
 */
abstract class ByRenameOffersFactory internal constructor(private val kind: ByOfferKind) : AutomaticRenamerFactory {

    override fun isApplicable(element: PsiElement): Boolean = offersOf(element).isNotEmpty()

    override fun getOptionName(): String? = null

    override fun isEnabled(): Boolean = true

    override fun setEnabled(enabled: Boolean) = Unit

    override fun createRenamer(element: PsiElement, newName: String, usages: Collection<UsageInfo>): AutomaticRenamer {
        val offers = offersOf(element)
        val elements = ByRenameOffers.elementsOf(element.project, offers)
        return ByRenameOffersRenamer(kind, offers.first().label, elements, (element as? ByRenamedName)?.name.orEmpty(), newName)
    }

    private fun offersOf(element: PsiElement): List<ByRenameOffer> =
        (element as? ByRenamedName)?.offers.orEmpty().filter { ByOfferKind.of(it.id) == kind }
}

class ByRenameOverridesFactory : ByRenameOffersFactory(ByOfferKind.Override)
class ByRenameParametersFactory : ByRenameOffersFactory(ByOfferKind.Parameter)
class ByRenameModuleFactory : ByRenameOffersFactory(ByOfferKind.Module)

/** The offers of one kind, each to the name `by` gives it; the names are `by`'s and are not edited. */
internal class ByRenameOffersRenamer(
    private val kind: ByOfferKind,
    private val label: String,
    elements: List<ByRenameOfferElement>,
    oldName: String,
    newName: String,
) : AutomaticRenamer() {

    init {
        myElements.addAll(elements)
        suggestAllNames(oldName, newName)
    }

    override fun suggestNameForElement(element: com.intellij.psi.PsiNamedElement, suggester: NameSuggester, newClassName: String, oldClassName: String): String =
        (element as ByRenameOfferElement).newName

    override fun allowChangeSuggestedName(): Boolean = false

    /** `by` offers these as renames that go with this one; the dialog is where one is turned down. */
    override fun isSelectedByDefault(): Boolean = true

    override fun getDialogTitle(): String = label

    override fun getDialogDescription(): String = kind.dialogDescription

    override fun entityName(): String = kind.entityName
}

/**
 * Finds and makes one offer's edits. Its uses are `by`'s edits, as a [ByRenamedName]'s are, so that
 * the usage preview shows them under their files and the rename applies them in its command; a
 * module offer then renames the module's file as the IDE renames any file, after the imports that
 * name it have been rewritten.
 */
class ByRenameOfferProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = element is ByRenameOfferElement

    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> =
        emptyList()

    override fun getElementToSearchInStringsAndComments(element: PsiElement): PsiElement? = null

    override fun findCollisions(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<out PsiElement, String>,
        result: MutableList<UsageInfo>,
    ) {
        if (element !is ByRenameOfferElement) return
        val edit = element.offer.edit ?: return
        result += ByImportEdit.of(element.project, edit, element)
    }

    override fun renameElement(element: PsiElement, newName: String, usages: Array<out UsageInfo>, listener: RefactoringElementListener?) {
        if (element !is ByRenameOfferElement) return
        val (edits, _) = splitImportEdits(usages)
        ByImportEdit.apply(element.project, edits)
        element.fileRename?.let { moved ->
            val module = element.containingFile
            // by puts the module beside the one it renames; a move anywhere else is not a rename
            val target = ByWorkspaceEditFiles.fileOf(moved.newUri.substringBeforeLast('/'))
            if (module.isValid && (target == null || target == module.virtualFile.parent)) module.name = element.newName
        }
        listener?.elementRenamed(element)
    }
}

/** What the platform's dialogs and usage view call an offer: `by`'s description of it, and its kind. */
class ByRenameOfferDescriptionProvider : ElementDescriptionProvider {
    override fun getElementDescription(element: PsiElement, location: ElementDescriptionLocation): String? {
        if (element !is ByRenameOfferElement) return null
        return when (location) {
            is UsageViewTypeLocation -> element.kind.entityName
            is UsageViewShortNameLocation -> element.name
            // the platform's renaming dialog puts this inside its own <html>
            is RefactoringDescriptionLocation -> StringUtil.escapeXmlEntities(element.offer.description)
            else -> element.offer.description
        }
    }
}

/** An offer's new name is `by`'s and is not typed, so there is nothing to check: `animal.by` to `beast.by` is not an identifier. */
class ByRenameOfferNameValidator : RenameInputValidator {
    override fun getPattern(): ElementPattern<out PsiElement> = PlatformPatterns.psiElement(ByRenameOfferElement::class.java)

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext): Boolean =
        newName == (element as? ByRenameOfferElement)?.newName
}
