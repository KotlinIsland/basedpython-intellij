package dev.basedpython.pycharm.editor.djangotemplate

import com.intellij.codeInsight.generation.CommenterWithLineSuffix
import com.intellij.lang.Commenter
import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.editor.Editor
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import com.intellij.psi.templateLanguages.MultipleLangCommentProvider

/**
 * *Comment with Line Comment* and *Block Comment* in a django template write django's comments:
 * `{# … #}` around each line, `{% comment %}` … `{% endcomment %}` around a selection.
 *
 * Without this a template is plain HTML to the editor and gets `<!-- -->`, which django renders
 * straight into the page — the markup it hides is still sent, and a `{{ secret }}` inside it is
 * still evaluated. A django comment is removed before rendering.
 *
 * A [MultipleLangCommentProvider] rather than a commenter for HTML: the platform asks these before a
 * language's own commenter (`CommentByBlockCommentHandler.getCommenter`, checked in 263), and only
 * where [canProcess] says so, so HTML outside a template is untouched. A line wholly inside an
 * embedded language — a `<script>` or `<style>` body — keeps that language's comment, as it does in
 * PyCharm Pro's Django templates.
 */
class ByDjangoTemplateCommenter : MultipleLangCommentProvider {

    override fun canProcess(file: PsiFile, viewProvider: FileViewProvider): Boolean =
        ByDjangoTemplateEditing.handles(file, viewProvider)

    override fun getLineCommenter(
        file: PsiFile,
        editor: Editor,
        lineStartLanguage: Language,
        lineEndLanguage: Language,
    ): Commenter {
        val base = file.viewProvider.baseLanguage
        if (lineStartLanguage == lineEndLanguage && !lineStartLanguage.isKindOf(base)) {
            LanguageCommenters.INSTANCE.forLanguage(lineStartLanguage)?.let { return it }
        }
        return DjangoCommenter
    }

    /** Django's two comment forms. `{# #}` cannot span lines, which is why a block takes the tag form. */
    object DjangoCommenter : CommenterWithLineSuffix {
        override fun getLineCommentPrefix(): String = "{# "

        override fun getLineCommentSuffix(): String = " #}"

        override fun getBlockCommentPrefix(): String = "{% comment %}"

        override fun getBlockCommentSuffix(): String = "{% endcomment %}"

        override fun getCommentedBlockCommentPrefix(): String? = null

        override fun getCommentedBlockCommentSuffix(): String? = null
    }
}
