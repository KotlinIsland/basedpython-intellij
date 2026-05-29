package dev.basedpython.pycharm.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Detects mutable default arguments in `def` parameter lists:
 *   def f(x=[], y={}, z=set())
 *
 * BasedPython auto-rewrites these, so severity is WEAK_WARNING.
 * The quick-fix just explains the rewrite behaviour — no text mutation is needed.
 */
class MutableDefaultArgInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "BasedPython"
    override fun getDisplayName(): String = "Mutable default argument"
    override fun getShortName(): String = "BasedPythonMutableDefaultArg"

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor> {
        if (file !is BasedPythonFile) return ProblemDescriptor.EMPTY_ARRAY

        val text = file.text
        val lexer = BasedPythonLexer()
        lexer.start(text, 0, text.length, 0)

        val problems = mutableListOf<ProblemDescriptor>()

        // State machine: watch for `def` keyword → param list context → `=` followed by mutable literal
        var inDef = false
        var parenDepth = 0
        var afterEquals = false

        while (lexer.tokenType != null) {
            val type = lexer.tokenType
            val start = lexer.tokenStart
            val end = lexer.tokenEnd
            val tokenText = text.substring(start, end)

            when {
                type == BasedPythonTokenTypes.KEYWORD && tokenText == "def" -> {
                    inDef = true
                    parenDepth = 0
                    afterEquals = false
                }
                inDef && type == BasedPythonTokenTypes.LPAREN -> {
                    parenDepth++
                    afterEquals = false
                }
                inDef && parenDepth > 0 && type == BasedPythonTokenTypes.RPAREN -> {
                    parenDepth--
                    afterEquals = false
                    if (parenDepth == 0) inDef = false
                }
                inDef && parenDepth > 0 && type == BasedPythonTokenTypes.OPERATOR && tokenText == "=" -> {
                    afterEquals = true
                }
                inDef && parenDepth > 0 && afterEquals -> {
                    val mutableKind = when {
                        type == BasedPythonTokenTypes.LBRACKET -> "list"
                        type == BasedPythonTokenTypes.LBRACE -> "dict or set literal"
                        type == BasedPythonTokenTypes.IDENTIFIER && tokenText == "set" -> checkIfSetCall(text, end)
                        type == BasedPythonTokenTypes.IDENTIFIER && tokenText == "dict" -> checkIfCall(text, end, "dict")
                        type == BasedPythonTokenTypes.IDENTIFIER && tokenText == "list" -> checkIfCall(text, end, "list")
                        else -> null
                    }
                    if (mutableKind != null) {
                        val element = file.findElementAt(start) ?: run { afterEquals = false; lexer.advance(); continue }
                        val descriptor = manager.createProblemDescriptor(
                            element,
                            "Mutable default argument ($mutableKind) — basedpython auto-rewrites this to a factory",
                            MutableDefaultArgFix(),
                            ProblemHighlightType.WEAK_WARNING,
                            isOnTheFly
                        )
                        problems += descriptor
                    }
                    afterEquals = false
                }
                inDef && parenDepth > 0 && type == BasedPythonTokenTypes.COMMA -> {
                    afterEquals = false
                }
                type == BasedPythonTokenTypes.NEWLINE || (type == BasedPythonTokenTypes.COLON && parenDepth == 0) -> {
                    if (inDef && parenDepth == 0) inDef = false
                }
            }
            lexer.advance()
        }

        return problems.toTypedArray()
    }

    private fun checkIfSetCall(text: String, afterName: Int): String? {
        val rest = text.substring(afterName).trimStart()
        return if (rest.startsWith("()")) "set()" else null
    }

    private fun checkIfCall(text: String, afterName: Int, name: String): String? {
        val rest = text.substring(afterName).trimStart()
        return if (rest.startsWith("()")) "$name()" else null
    }

    private class MutableDefaultArgFix : LocalQuickFix {
        override fun getFamilyName(): String = "Acknowledge: basedpython will wrap in a factory"
        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            // No-op: basedpython auto-rewrites mutable defaults; this fix is informational.
        }
    }
}
