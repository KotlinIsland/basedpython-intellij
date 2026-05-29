package dev.basedpython.pycharm.editor.templates

import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

// ---------------------------------------------------------------------------
// Base helper — operates on raw text/offset, no PSI rewrite needed
// ---------------------------------------------------------------------------

/**
 * Resolves the expression that precedes the ".key" suffix by scanning
 * backwards from [dotOffset] until we hit a line-start or whitespace.
 */
private fun extractExpr(doc: Document, dotOffset: Int): String {
    val text = doc.charsSequence
    var start = dotOffset - 1
    while (start > 0) {
        val c = text[start - 1]
        if (c == '\n' || c == ' ' || c == '\t') break
        start--
    }
    return text.subSequence(start, dotOffset).toString()
}

private fun lineStartOffset(doc: Document, offset: Int): Int {
    val lineNum = doc.getLineNumber(offset)
    return doc.getLineStartOffset(lineNum)
}

// ---------------------------------------------------------------------------
// Abstract base for all our postfix templates
// ---------------------------------------------------------------------------

abstract class BasedPythonPostfixTemplate(
    name: String,
    example: String,
    provider: PostfixTemplateProvider
) : PostfixTemplate(name, ".$name", example, provider) {

    // We accept any element — actual filtering is done by the provider's
    // file-type check. The PSI tree is flat, so we skip element-level checks.
    override fun isApplicable(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean = true

    /**
     * The suffix that triggered this template, including the leading dot
     * (e.g. ".if"). Subclasses that need to know the key length may override.
     */
    protected open val triggerKey: String get() = ".$presentableName"

    /**
     * Build the replacement text given the expression that was before the dot.
     */
    abstract fun buildReplacement(expr: String): String

    /**
     * Return the caret offset relative to the START of [replacement], or -1
     * to place the caret at the end.
     */
    open fun caretOffsetIn(replacement: String, expr: String): Int = -1

    override fun expand(context: PsiElement, editor: Editor) {
        val doc = editor.document
        val caretOffset = editor.caretModel.offset

        // The trigger text is "$expr.$key" — locate the dot
        val keyWithDot = triggerKey
        val startOfKey = caretOffset - keyWithDot.length
        val dotOffset = startOfKey  // position of the '.'

        val expr = extractExpr(doc, dotOffset)
        val exprStart = dotOffset - expr.length

        val lineStart = lineStartOffset(doc, exprStart)
        val indent = doc.charsSequence.subSequence(lineStart, exprStart).toString()
            .takeWhile { it == ' ' || it == '\t' }

        val replacement = buildReplacement(expr)
        val fullReplacement = indent + replacement

        // Replace from line start to end of trigger
        doc.replaceString(lineStart, caretOffset, fullReplacement)

        val rel = caretOffsetIn(fullReplacement, expr)
        val newCaret = if (rel < 0) lineStart + fullReplacement.length else lineStart + rel
        editor.caretModel.moveToOffset(newCaret)
    }
}

// ---------------------------------------------------------------------------
// Concrete postfix templates
// ---------------------------------------------------------------------------

class IfPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("if", "expr.if → if expr:", p) {
    override val triggerKey = ".if"
    override fun buildReplacement(expr: String) = "if $expr:\n    "
    override fun caretOffsetIn(replacement: String, expr: String): Int = replacement.length
}

class ElsePostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("else", "expr.else → if not expr: … else: …", p) {
    override val triggerKey = ".else"
    override fun buildReplacement(expr: String) = "if $expr:\n    pass\nelse:\n    "
    override fun caretOffsetIn(replacement: String, expr: String): Int = replacement.length
}

class ForPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("for", "expr.for → for x in expr:", p) {
    override val triggerKey = ".for"
    override fun buildReplacement(expr: String) = "for x in $expr:\n    "
    override fun caretOffsetIn(replacement: String, expr: String): Int = replacement.length
}

class WhilePostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("while", "expr.while → while expr:", p) {
    override val triggerKey = ".while"
    override fun buildReplacement(expr: String) = "while $expr:\n    "
    override fun caretOffsetIn(replacement: String, expr: String): Int = replacement.length
}

class NotPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("not", "expr.not → not expr", p) {
    override val triggerKey = ".not"
    override fun buildReplacement(expr: String) = "not $expr"
}

class ReturnPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("return", "expr.return → return expr", p) {
    override val triggerKey = ".return"
    override fun buildReplacement(expr: String) = "return $expr"
}

class RetPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("ret", "expr.ret → return expr", p) {
    override val triggerKey = ".ret"
    override fun buildReplacement(expr: String) = "return $expr"
}

class PrintPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("print", "expr.print → print(expr)", p) {
    override val triggerKey = ".print"
    override fun buildReplacement(expr: String) = "print($expr)"
}

class LenPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("len", "expr.len → len(expr)", p) {
    override val triggerKey = ".len"
    override fun buildReplacement(expr: String) = "len($expr)"
}

class VarPostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("var", "expr.var → name = expr", p) {
    override val triggerKey = ".var"
    override fun buildReplacement(expr: String) = "name = $expr"
    // Place caret at start of "name" so user can overwrite it
    override fun caretOffsetIn(replacement: String, expr: String): Int = 0
}

class NonePostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("none", "expr.none → expr is None", p) {
    override val triggerKey = ".none"
    override fun buildReplacement(expr: String) = "$expr is None"
}

class NotNonePostfixTemplate(p: PostfixTemplateProvider) :
    BasedPythonPostfixTemplate("notnone", "expr.notnone → expr is not None", p) {
    override val triggerKey = ".notnone"
    override fun buildReplacement(expr: String) = "$expr is not None"
}

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

class BasedPythonPostfixTemplateProvider : PostfixTemplateProvider {

    private val templateSet: Set<PostfixTemplate> by lazy {
        setOf(
            IfPostfixTemplate(this),
            ElsePostfixTemplate(this),
            ForPostfixTemplate(this),
            WhilePostfixTemplate(this),
            NotPostfixTemplate(this),
            ReturnPostfixTemplate(this),
            RetPostfixTemplate(this),
            PrintPostfixTemplate(this),
            LenPostfixTemplate(this),
            VarPostfixTemplate(this),
            NonePostfixTemplate(this),
            NotNonePostfixTemplate(this),
        )
    }

    override fun getTemplates(): Set<PostfixTemplate> = templateSet

    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'

    override fun preExpand(file: PsiFile, editor: Editor) {}

    override fun afterExpand(file: PsiFile, editor: Editor) {}

    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile
}
