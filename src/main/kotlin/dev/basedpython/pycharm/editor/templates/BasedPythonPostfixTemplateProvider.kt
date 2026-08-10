package dev.basedpython.pycharm.editor.templates

import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * A basedpython postfix template.
 *
 * Two things about the platform contract that the first version of this file got wrong, and that
 * the shape below exists to get right:
 *
 * 1. The four-argument `PostfixTemplate` constructor is `(id, name, example, provider)` and derives
 *    the trigger key as `"." + name`. Passing `".print"` as the *name* therefore registered the key
 *    `..print`, so the template only fired after a second dot.
 * 2. `PostfixLiveTemplate` deletes the key — dot included — *before* calling [expand]. The caret is
 *    already sitting at the end of the expression, so there is nothing left to subtract.
 *
 * @param name the trigger, without its dot: `print` registers `.print`
 * @param statement true for templates that expand to a statement (`if`, `for`, …). Those are only
 *   offered when the expression is the first thing on its line, because rewriting `x = foo.if` into
 *   `x = if foo:` would just produce a syntax error.
 * @param body renders the replacement from the expression text; may span lines and may contain one
 *   [CARET_MARKER].
 */
private class BasedPythonPostfixTemplate(
    name: String,
    example: String,
    provider: PostfixTemplateProvider,
    private val statement: Boolean = false,
    private val body: (String) -> String,
) : PostfixTemplate("basedpython.$name", name, example, provider) {

    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean {
        val expansion = postfixExpansion(copyDocument.charsSequence, newOffset, body) ?: return false
        if (!statement) return true
        val text = copyDocument.charsSequence
        var i = expansion.startOffset
        while (i > 0 && text[i - 1] != '\n') {
            if (text[i - 1] != ' ' && text[i - 1] != '\t') return false
            i--
        }
        return true
    }

    override fun expand(context: PsiElement, editor: Editor) {
        val doc = editor.document
        val expansion = postfixExpansion(doc.charsSequence, editor.caretModel.offset, body) ?: return
        doc.replaceString(expansion.startOffset, expansion.endOffset, expansion.text)
        editor.caretModel.moveToOffset(expansion.caretOffset)
    }
}

class BasedPythonPostfixTemplateProvider : PostfixTemplateProvider {

    private val templateSet: Set<PostfixTemplate> by lazy {
        setOf(
            statement("if", "expr.if → if expr:") { "if $it:\n    $CARET_MARKER" },
            statement("else", "expr.else → if expr: … else: …") {
                "if $it:\n    pass\nelse:\n    $CARET_MARKER"
            },
            statement("for", "expr.for → for x in expr:") { "for x in $it:\n    $CARET_MARKER" },
            statement("while", "expr.while → while expr:") { "while $it:\n    $CARET_MARKER" },
            statement("var", "expr.var → name = expr") { "${CARET_MARKER}name = $it" },
            expression("not", "expr.not → not expr") { "not $it" },
            expression("return", "expr.return → return expr") { "return $it" },
            expression("ret", "expr.ret → return expr") { "return $it" },
            expression("print", "expr.print → print(expr)") { "print($it)" },
            expression("len", "expr.len → len(expr)") { "len($it)" },
            expression("none", "expr.none → expr is None") { "$it is None" },
            expression("notnone", "expr.notnone → expr is not None") { "$it is not None" },
        )
    }

    private fun expression(name: String, example: String, body: (String) -> String): PostfixTemplate =
        BasedPythonPostfixTemplate(name, example, this, statement = false, body = body)

    private fun statement(name: String, example: String, body: (String) -> String): PostfixTemplate =
        BasedPythonPostfixTemplate(name, example, this, statement = true, body = body)

    override fun getTemplates(): Set<PostfixTemplate> = templateSet

    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'

    override fun preExpand(file: PsiFile, editor: Editor) {}

    override fun afterExpand(file: PsiFile, editor: Editor) {}

    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}
