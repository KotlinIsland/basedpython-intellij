package dev.basedpython.pycharm.editor.copyright

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import com.maddyhome.idea.copyright.CopyrightProfile
import com.maddyhome.idea.copyright.psi.UpdateCopyright
import com.maddyhome.idea.copyright.psi.UpdateCopyrightsProvider
import com.maddyhome.idea.copyright.psi.UpdatePsiFileCopyright

/**
 * Lets the Copyright plugin write, update and remove the notice at the top of a `.by` file — *Update
 * Copyright*, the commit option and the on-save action — which it otherwise skips: it acts only on
 * file types something has registered an updater for.
 *
 * The notice is the comments at the top of the file, in `#` lines (the plugin builds them from
 * [dev.basedpython.pycharm.lang.BasedPythonCommenter]), above everything else — a module docstring
 * included — except a `#!` line, which has to stay the file's first line to mean anything.
 * Everything here is lexical: a `.by` tree is one leaf per token, with line breaks as whitespace, so
 * the header is the run of comment and whitespace leaves the file starts with.
 */
internal class ByUpdateCopyrightsProvider : UpdateCopyrightsProvider() {

    override fun createInstance(
        project: Project,
        module: Module?,
        file: VirtualFile,
        base: FileType,
        options: CopyrightProfile,
    ): UpdateCopyright = ByUpdateFileCopyright(project, module, file, options)
}

private class ByUpdateFileCopyright(
    project: Project,
    module: Module?,
    root: VirtualFile,
    options: CopyrightProfile,
) : UpdatePsiFileCopyright(project, module, root, options) {

    override fun scanFile() {
        var first: PsiElement? = file.firstChild
        if (first is PsiComment && first.text.startsWith("#!")) first = getNextSibling(first)
        // The header runs to the first leaf that is neither a comment nor whitespace; the notice
        // goes in front of that, or of the first comment in the run, as the profile says.
        var last = first
        var next = first
        while (next is PsiComment || next is PsiWhiteSpace) {
            next = getNextSibling(next)
            last = next
        }
        if (first == null) {
            checkComments(null, null, true)
            return
        }
        val comments = mutableListOf<PsiComment>()
        collectComments(first, last, comments)
        checkComments(last ?: first, true, comments)
    }
}
