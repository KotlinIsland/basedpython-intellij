package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.SuggestedNameInfo
import com.intellij.refactoring.rename.NameSuggestionProvider
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByRenameNameSuggestionsParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.ext.ByValidateRenameNameParams
import dev.basedpython.pycharm.lsp.runningByServer
import dev.basedpython.pycharm.util.BasedPythonBundle

/** What `by` says of a new name for the symbol a rename is about. */
internal sealed interface ByNameVerdict {

    /** `textDocument/rename` would take it. */
    data object Valid : ByNameVerdict

    /** `textDocument/rename` would refuse it, for [reason]. */
    data class Invalid(val reason: String) : ByNameVerdict

    /**
     * Nothing to go on: no `by` running, one from before `by/validateRenameName`, no answer, or
     * nothing to rename at the position. The rename itself is then what says whether the name will
     * do, as it did before the request existed.
     */
    data object Unknown : ByNameVerdict
}

/**
 * A rename's new name, as `by` judges it and suggests it — `by/validateRenameName` and
 * `by/renameNameSuggestions` — rather than by a list of keywords and an identifier pattern kept
 * here: which names a symbol may take is the rule `textDocument/rename` applies, and differs between
 * a python name and a django one.
 *
 * Both are asked about the symbol where the rename found it, in the text the rename was asked
 * about, which [ByTextHash] names: an in-place rename types the new name into the file, and the
 * symbol at that position is then whatever was typed, not the one being renamed.
 */
internal object ByRenameNames {

    /** Whether [newName] would do for the symbol at [offset] of [document]. Waits for `by`. */
    @RequiresBackgroundThread
    @RequiresReadLock
    fun validate(client: LspClient, project: Project, file: VirtualFile, document: Document, offset: Int, newName: String): ByNameVerdict {
        ByServerDocuments.ensureOpen(client, project, file)
        val params = ByValidateRenameNameParams(
            client.getDocumentIdentifier(file),
            getLsp4jPosition(document, offset),
            newName,
            ByTextHash.of(document.immutableCharSequence),
        )
        val answer = client.askBy("by/validateRenameName") { (it as ByServerExtensions).validateRenameName(params) }
        val verdict = (answer as? ByAnswer.Answer)?.value ?: return ByNameVerdict.Unknown
        return if (verdict.valid) {
            ByNameVerdict.Valid
        } else {
            ByNameVerdict.Invalid(verdict.message ?: BasedPythonBundle.message("rename.by.name.invalid", newName))
        }
    }

    /** The names `by` would give the symbol at [offset] of [document], best first. Waits for `by`. */
    @RequiresBackgroundThread
    @RequiresReadLock
    fun suggestions(client: LspClient, project: Project, file: VirtualFile, document: Document, offset: Int): List<String> {
        ByServerDocuments.ensureOpen(client, project, file)
        val params = ByRenameNameSuggestionsParams(
            client.getDocumentIdentifier(file),
            getLsp4jPosition(document, offset),
            ByTextHash.of(document.immutableCharSequence),
        )
        return client.askBy("by/renameNameSuggestions") { (it as ByServerExtensions).renameNameSuggestions(params) }
            .value.orEmpty()
    }

    /** [validate] for the name a [ByRenamedName] stands for, or [ByNameVerdict.Unknown] with no `by` to ask. */
    @RequiresBackgroundThread
    @RequiresReadLock
    fun validate(name: ByRenamedName, newName: String): ByNameVerdict {
        val file = name.containingFile.virtualFile ?: return ByNameVerdict.Unknown
        val client = runningByServer(name.project, file) ?: return ByNameVerdict.Unknown
        val document = name.containingFile.viewProvider.document ?: return ByNameVerdict.Unknown
        return validate(client, name.project, file, document, name.textOffset, newName)
    }
}

/**
 * The names the Rename dialog offers for a `.by` symbol, after the one it has: `by`'s suggestions
 * for it (see [ByRenameNames]).
 *
 * The dialog asks under a modal progress, off the EDT and in a read action, so `by` is waited for
 * there and the wait can be cancelled. A `by` without the request suggests nothing, and the dialog
 * offers the name the symbol has, as it did before.
 */
class ByRenameNameSuggestionProvider : NameSuggestionProvider {

    override fun getSuggestedNames(element: PsiElement, nameSuggestionContext: PsiElement?, result: MutableSet<String>): SuggestedNameInfo? {
        if (element !is ByRenamedName) return null
        val file = element.containingFile.virtualFile ?: return null
        val client = runningByServer(element.project, file) ?: return null
        val document = element.containingFile.viewProvider.document ?: return null
        result += ByRenameNames.suggestions(client, element.project, file, document, element.textOffset)
        return SuggestedNameInfo.NULL_INFO
    }
}
