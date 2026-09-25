package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.refactoring.util.MoveRenameUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import dev.basedpython.pycharm.format.ByCleanup
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Path

/**
 * One edit `by` asked for to an `import` that names a module about to move: replace the text in this
 * usage's range with [newText].
 *
 * A usage like any other refactoring's, so that it shows in the platform's usage preview under the
 * file it is in, can be excluded there, and reaches the processor's rename or move with the rest.
 * It is not a non-code usage, which the platform would both label as an occurrence in a comment or
 * string and force a preview for; and it has no reference to rebind, because what the import should
 * say is `by`'s answer, not something the IDE works out. So the processors apply it themselves, with
 * [apply], in the same command as the move.
 */
class ByImportEdit(
    file: PsiFile,
    range: TextRange,
    referencedElement: PsiElement,
    val newText: String,
) : MoveRenameUsageInfo(file, null, range.startOffset, range.endOffset, referencedElement, false) {

    companion object {

        /**
         * [edit], as usages of [moved] in the files it names.
         *
         * A file the IDE cannot find, or cannot show as a document, is left out: there is nothing to
         * apply its edits to. Offsets are read against each file's document as it is now, which is
         * the text the server was just asked about.
         */
        @RequiresReadLock
        fun of(
            project: Project,
            edit: WorkspaceEdit,
            moved: PsiElement,
            fileOf: (uri: String) -> VirtualFile? = ByWorkspaceEditFiles::fileOf,
        ): List<ByImportEdit> {
            val psiManager = PsiManager.getInstance(project)
            val documents = FileDocumentManager.getInstance()
            return ByWorkspaceEditFiles.uris(edit).flatMap { uri ->
                val file = fileOf(uri) ?: return@flatMap emptyList()
                val psiFile = psiManager.findFile(file) ?: return@flatMap emptyList()
                val document = documents.getDocument(file) ?: return@flatMap emptyList()
                ByCleanup.editsFor(edit, uri).edits.mapNotNull { text ->
                    val range = getRangeInDocument(document, text.range) ?: return@mapNotNull null
                    ByImportEdit(psiFile, range, moved, text.newText)
                }
            }
        }

        /**
         * Applies [edits] to their documents, last in each document first so that each range still
         * means the text it was found at, and commits them, so that whatever the refactoring does
         * next with the PSI of those files sees the new text.
         *
         * Must run inside the refactoring's command and write action, which is what makes the edits
         * and the move one step to undo.
         */
        @RequiresWriteLock
        fun apply(project: Project, edits: List<ByImportEdit>) {
            val byDocument = LinkedHashMap<Document, MutableList<Pair<TextRange, String>>>()
            for (edit in edits) {
                val file = edit.virtualFile ?: continue
                val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
                val segment = edit.segment ?: continue
                byDocument.getOrPut(document) { ArrayList() } += TextRange.create(segment) to edit.newText
            }
            val psiDocuments = PsiDocumentManager.getInstance(project)
            for ((document, replacements) in byDocument) {
                for ((range, text) in replacements.sortedByDescending { it.first.startOffset }) {
                    document.replaceString(range.startOffset, range.endOffset, text)
                }
                psiDocuments.commitDocument(document)
            }
        }
    }
}

/**
 * The imports of [element] could not be worked out, and the refactoring would leave them naming a
 * module that is no longer there.
 *
 * Reported as the platform reports any rename that cannot keep every usage working: in the conflicts
 * dialog, where the user decides whether to go ahead anyway. [description] says why.
 */
class ByImportsUnknown(element: PsiElement, private val description: String) :
    UnresolvableCollisionUsageInfo(element, element) {
    override fun getDescription(): String = description
}

/** What a user is told when the imports of [name] cannot be updated, for each reason that happens. */
internal fun importsUnknownMessage(answer: ByImportRewrites, name: String): String? = when (answer) {
    ByImportRewrites.NoServer -> BasedPythonBundle.message("refactoring.by.imports.noServer", name)
    ByImportRewrites.NotSupported -> BasedPythonBundle.message("refactoring.by.imports.notSupported", name)
    ByImportRewrites.Failed -> BasedPythonBundle.message("refactoring.by.imports.failed", name)
    is ByImportRewrites.Edits, ByImportRewrites.NoneNeeded -> null
}

/** The files a [WorkspaceEdit] names. */
object ByWorkspaceEditFiles {

    /** Every document URI [edit] names, in either of the two shapes a workspace edit can take. */
    fun uris(edit: WorkspaceEdit): Set<String> =
        edit.changes?.keys.orEmpty() +
            edit.documentChanges.orEmpty()
                .mapNotNull { change -> change.takeIf { it.isLeft }?.left?.textDocument?.uri }

    /** The file a `file:` URI names, if the IDE has it. */
    fun fileOf(uri: String): VirtualFile? = runCatching {
        LocalFileSystem.getInstance().findFileByNioFile(Path.of(java.net.URI.create(uri)))
    }.getOrNull()
}

/** [usages] without the ones that are `by`'s import edits, and those edits. */
internal fun splitImportEdits(usages: Array<out UsageInfo>): Pair<List<ByImportEdit>, Array<UsageInfo>> {
    val (ours, rest) = usages.partition { it is ByImportEdit }
    return ours.map { it as ByImportEdit } to rest.toTypedArray()
}
