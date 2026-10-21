package dev.basedpython.pycharm.lsp.imports

import com.intellij.codeInsight.daemon.ReferenceImporter
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.ByTemplateFiles
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import java.util.function.BooleanSupplier

/**
 * *Add unambiguous imports on the fly* for the files `by` serves: an import `by` says is the only
 * one that resolves an unresolved name is added without asking. See [ByAutoImports].
 *
 * The platform decides when — the auto-import pass after highlighting, never for a problem the caret
 * is in or right after, never while a modal dialog is up, only in a file it may change silently —
 * and asks this, in the background under a read action, for each problem it has drawn. What comes
 * back runs later on the EDT, so it carries the edit already worked out, and applies it only if the
 * document has not changed since.
 *
 * Switched by *Settings | Editor | General | Auto Import | basedpython*, off until switched on, as
 * Java's and Kotlin's are.
 */
internal class ByReferenceImporter : ReferenceImporter {

    override fun isAddUnambiguousImportsOnTheFlyEnabled(psiFile: PsiFile): Boolean {
        if (!BasedPythonAppSettings.getInstance().addUnambiguousImportsOnTheFly) return false
        val file = psiFile.virtualFile ?: return false
        // a template is checked by `by` as the template it is, and imports nothing
        return !ByTemplateFiles.isTemplate(file) && byServerFor(psiFile.project, file) != null
    }

    override fun computeAutoImportAtOffset(
        editor: Editor,
        psiFile: PsiFile,
        offset: Int,
        allowCaretNearReference: Boolean,
    ): BooleanSupplier? {
        val file = psiFile.virtualFile ?: return null
        if (ByTemplateFiles.isTemplate(file)) return null
        val project = psiFile.project
        val server = byServerFor(project, file) ?: return null
        val document = editor.document
        val stamp = document.modificationStamp
        val caret = if (allowCaretNearReference) null else editor.caretModel.offset
        val import = ByAutoImports.find(server, file, document, offset, caret) ?: return null
        return BooleanSupplier { ByAutoImports.apply(project, document, stamp, import) }
    }
}
