package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * How `by`'s Information and Hint diagnostics are drawn — `reveal_type`'s *Revealed type* is
 * Information — where the platform draws both as weak warnings.
 *
 * PyCharm's own ty integration (`PyLspToolCustomization.PyLspToolDiagnosticsSupport`, read from its
 * bytecode in PyCharm 263.7607) draws Information with a blue wave and Hint with a grey bold dotted
 * line, as fixed `TextAttributes` (`JBColor.BLUE`, `JBColor.GRAY`), a workaround for IJPL-196573,
 * the platform drawing both levels as weak warnings; no scheme key of the platform's is either look. These are keys instead, on basedpython's colour page under *Diagnostics*, defaulting to
 * PyCharm's look in Default and Darcula and the schemes made from them (`colorSchemes/diagnostics`),
 * and to the weak-warning wave in any other.
 */
object ByDiagnosticColors {

    /** An Information diagnostic: a blue wave by default. */
    @JvmField
    val INFORMATION: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BASEDPYTHON_DIAGNOSTIC_INFORMATION", CodeInsightColors.WEAK_WARNING_ATTRIBUTES)

    /** A Hint diagnostic with no tag: a grey bold dotted line by default. */
    @JvmField
    val HINT: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("BASEDPYTHON_DIAGNOSTIC_HINT", CodeInsightColors.WEAK_WARNING_ATTRIBUTES)
}
