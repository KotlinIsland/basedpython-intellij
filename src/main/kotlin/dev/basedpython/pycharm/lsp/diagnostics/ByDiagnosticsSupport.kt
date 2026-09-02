package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
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
 * diagnostic message be a `MarkupContent`, and lsp4j followed: in 2026.3 `Diagnostic.getMessage()`
 * returns `Either<String, MarkupContent>` where 2026.2 returned `String`. A direct call compiles
 * against one and is a `NoSuchMethodError` on the other, which is what every diagnostic in 2026.3
 * turned into. [LspDiagnosticsSupport.getMessage] keeps its `String` signature across both and is
 * where the platform absorbs the change, so it is the only spelling that works on the whole
 * 262–263 range this plugin claims.
 */
internal class ByDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun getTooltip(diagnostic: Diagnostic): String = ByCodeSpans.toHtml(getMessage(diagnostic))
}
