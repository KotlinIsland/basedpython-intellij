package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.psi.PsiDocumentManager
import dev.basedpython.pycharm.inspections.rules.ByRuleInspection
import dev.basedpython.pycharm.inspections.rules.ByRuleInspections
import dev.basedpython.pycharm.inspections.rules.ByRuleLevels

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.openapi.util.TextRange
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import dev.basedpython.pycharm.inspections.explain.ExplainRuleFix
import dev.basedpython.pycharm.markup.ByCodeSpans
import org.eclipse.lsp4j.Diagnostic

/**
 * Renders a server diagnostic's tooltip as the HTML a tooltip actually is.
 *
 * The platform passes the message straight through to `AnnotationBuilder.tooltip`, which takes
 * HTML — so a message from `by` arrives with its markdown backticks intact and its `<`s read as
 * tags. ``Object of type `<class 'int'>` is not callable`` reached the user as *Object of type ``
 * `` is not callable*: backticks shown, the type itself eaten by Swing's HTML parser.
 *
 * [ByCodeSpans] escapes it and gives the code spans a `<code>` each. Only the tooltip: the
 * *message* stays exactly as the server wrote it, because that is the plain-text side of the
 * diagnostic — the Problems view, the error stripe, "Copy problem description" — where backticks
 * are how a type is quoted and HTML would be the wrong thing entirely.
 *
 * The text comes from [getMessage], never from `diagnostic.message` directly. LSP 3.18 lets a
 * diagnostic message be a `MarkupContent`, and lsp4j followed: `Diagnostic.getMessage()` returns
 * `Either<String, MarkupContent>`. [LspDiagnosticsSupport.getMessage] is where the platform turns
 * either into the `String` a tooltip is built from.
 */
internal class ByDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun getTooltip(diagnostic: Diagnostic): String = ByCodeSpans.toHtml(getMessage(diagnostic))

    /**
     * Every diagnostic that names its rule offers to explain it; see [ExplainRuleFix]. A code that is
     * no rule of `by`'s, as `stale-api-lock` on an `api.lock`, has nothing to explain and is drawn as
     * the platform draws it. And it is drawn
     * as its rule's inspection says ([ByRuleLevels]): not at all where the profile switches the rule
     * off for the file, at the profile's severity where it gives one, and as `by` reported it where
     * the profile leaves the rule as it comes. The explanation carries the inspection's key, so
     * Alt+Enter offers its settings and switching it off beside it.
     */
    override fun createAnnotation(
        holder: AnnotationHolder,
        diagnostic: Diagnostic,
        textRange: TextRange,
        quickFixes: List<IntentionAction>,
    ) {
        val code = ExplainRuleFix.codeOf(diagnostic)?.takeIf { ByRuleInspections.getInstance().mayBeRule(it) }
            ?: return super.createAnnotation(holder, diagnostic, textRange, quickFixes)
        val file = holder.currentAnnotationSession.file
        val drawn = ByRuleLevels.forDiagnostic(file.project, code, file)
        if (drawn == ByRuleLevels.Drawn.Hidden) return
        val severity = (drawn as? ByRuleLevels.Drawn.At)?.severity ?: getHighlightSeverity(diagnostic) ?: return
        val key = (drawn as? ByRuleLevels.Drawn.At)?.key ?: HighlightDisplayKey.find(ByRuleInspection.shortName(code))
        var builder = holder.newAnnotation(severity, getMessage(diagnostic)).tooltip(getTooltip(diagnostic)).range(textRange)
        if (textRange.isEmpty) {
            val document = PsiDocumentManager.getInstance(file.project).getDocument(file)
            if (document != null && textRange.startOffset == document.getLineEndOffset(document.getLineNumber(textRange.startOffset))) {
                builder = builder.afterEndOfLine()
            }
        }
        getSpecialHighlightType(diagnostic)?.let { builder = builder.highlightType(it) }
        for (fix in quickFixes) builder = builder.withFix(fix)
        val explain = builder.newFix(ExplainRuleFix(code))
        builder = (if (key != null) explain.key(key) else explain).registerFix()
        builder.create()
    }
}
