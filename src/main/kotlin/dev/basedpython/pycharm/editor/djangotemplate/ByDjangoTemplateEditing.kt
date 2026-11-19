package dev.basedpython.pycharm.editor.djangotemplate

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.basedpython.pycharm.lsp.ByTemplateFiles

/**
 * Which files the template editing here — `{# #}` comments, closing a typed `{%`, a line breakpoint
 * in the gutter — applies to.
 *
 * A django template `by` serves is opened as whatever its extension says: HTML, XML or plain text,
 * with no template language of its own, so nothing in the editor knows a `{%` from any other text.
 * Registering a template language would fix that wholesale, and would also put this plugin in a
 * fight with PyCharm Pro's own Django template language, which owns the same files when it is
 * there. So the handlers stay file-scoped instead: a file [ByTemplateFiles] calls a template, and
 * only while it is still the plain file type — once anything has given it a template language, the
 * file's base language is that language and these step aside.
 */
internal object ByDjangoTemplateEditing {

    /** The base languages a template is opened as when nothing claims it: `.html`, `.xml`, `.txt` and the rest. */
    private val PLAIN_LANGUAGES: Set<String> = setOf("HTML", "XHTML", "XML", "TEXT")

    fun handles(file: PsiFile): Boolean = handles(file, file.viewProvider)

    /** The same question asked of a [VirtualFile], for callers that have no PSI in hand — the breakpoint gutter. */
    fun handles(project: Project, file: VirtualFile): Boolean {
        if (!ByTemplateFiles.isTemplate(file)) return false
        val viewProvider = PsiManager.getInstance(project).findViewProvider(file) ?: return false
        return viewProvider.baseLanguage.id in PLAIN_LANGUAGES
    }

    fun handles(file: PsiFile, viewProvider: FileViewProvider): Boolean {
        if (viewProvider.baseLanguage.id !in PLAIN_LANGUAGES) return false
        val virtualFile = file.originalFile.virtualFile ?: viewProvider.virtualFile
        return ByTemplateFiles.isTemplate(virtualFile)
    }
}
