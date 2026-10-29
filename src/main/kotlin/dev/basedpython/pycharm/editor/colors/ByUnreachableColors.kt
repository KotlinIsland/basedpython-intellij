package dev.basedpython.pycharm.editor.colors

import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.ColorUtil
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonColors
import dev.basedpython.pycharm.lsp.semantic.BasedPythonSemanticTokensMapping
import java.awt.Color

/**
 * The colours of code `by` reports unreachable: each kind of token in its own colour, faded, the way
 * VS Code fades code a server tags `Unnecessary`, rather than all one grey.
 *
 * Every key a token can be coloured with has an *unreachable* variant ([variant]), which `by`'s
 * semantic tokens are mapped to when they carry its `unreachable` modifier. A variant falls back to
 * [CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES], the grey unused code has, in a scheme that says
 * nothing about it; the schemes bundled with the IDE and this plugin's own say: there each variant is
 * its token's colour blended toward the editor background ([defaultsFor]), shipped as each scheme's
 * additional text attributes and regenerated from the schemes themselves (`ByUnreachableColorsTest`).
 * The variants are on basedpython's colour settings page under *Unreachable code*.
 *
 * The fade is VS Code's `editorUnnecessaryCode.opacity`: `#000a` for dark themes and `#0007` for
 * light ones (`src/vs/editor/common/core/editorColorRegistry.ts`), the text drawn at 0xaa/0xff
 * and 0x77/0xff of its colour over the background.
 */
object ByUnreachableColors {

    /** The keys a token of `by`'s can be coloured with, lexer and semantic alike. */
    val BASES: List<TextAttributesKey> = listOf(
        BasedPythonColors.KEYWORD,
        BasedPythonColors.NUMBER,
        BasedPythonColors.COMMENT,
        BasedPythonColors.IDENTIFIER,
        BasedPythonColors.STRING,
        BasedPythonHighlightKeys.DOCSTRING,
        BasedPythonHighlightKeys.STRING_ESCAPE,
        BasedPythonHighlightKeys.FSTRING_BRACES,
        BasedPythonHighlightKeys.FSTRING_CONVERSION,
        BasedPythonHighlightKeys.FSTRING_FORMAT_START,
        BasedPythonColors.OPERATOR,
        BasedPythonColors.PARENTHESES,
        BasedPythonColors.BRACKETS,
        BasedPythonColors.BRACES,
        BasedPythonColors.COMMA,
        BasedPythonColors.SEMICOLON,
        BasedPythonColors.DOT,
        BasedPythonHighlightKeys.FUNCTION_DECLARATION,
        BasedPythonHighlightKeys.NESTED_FUNCTION_DECLARATION,
        BasedPythonHighlightKeys.FUNCTION_CALL,
        BasedPythonHighlightKeys.METHOD_CALL,
        BasedPythonHighlightKeys.CLASS_DECLARATION,
        BasedPythonHighlightKeys.CLASS_REFERENCE,
        BasedPythonHighlightKeys.PARAMETER,
        BasedPythonHighlightKeys.SELF_PARAMETER,
        BasedPythonHighlightKeys.KEYWORD_ARGUMENT,
        BasedPythonHighlightKeys.LOCAL_VARIABLE,
        BasedPythonSemanticTokensMapping.CONSTANT,
        BasedPythonSemanticTokensMapping.PROPERTY,
        BasedPythonSemanticTokensMapping.STATIC_PROPERTY,
        BasedPythonHighlightKeys.DUNDER_DEFINITION,
        BasedPythonHighlightKeys.DUNDER_USAGE,
        BasedPythonHighlightKeys.BUILTIN_NAME,
        BasedPythonHighlightKeys.BUILTIN_CONSTANT,
        BasedPythonHighlightKeys.ANNOTATION,
        BasedPythonHighlightKeys.TYPE_PARAMETER,
        BasedPythonHighlightKeys.TYPE_NAME,
        BasedPythonHighlightKeys.NAMESPACE,
        BasedPythonHighlightKeys.DECORATOR,
    )

    private val VARIANTS: Map<TextAttributesKey, TextAttributesKey> = BASES.associateWith { base ->
        TextAttributesKey.createTextAttributesKey(
            "BASEDPYTHON_UNREACHABLE_" + base.externalName.removePrefix("BASEDPYTHON_"),
            CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
        )
    }

    /** [base]'s unreachable variant; unused code's grey for a key that has none. */
    fun variant(base: TextAttributesKey?): TextAttributesKey =
        base?.let { VARIANTS[it] } ?: CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES

    /** Every variant, by the key it fades. */
    val variants: Map<TextAttributesKey, TextAttributesKey> get() = VARIANTS

    /** VS Code's `editorUnnecessaryCode.opacity` for a dark theme, `#000a`. */
    const val DARK_OPACITY: Double = 0xaa / 255.0

    /** And for a light one, `#0007`. */
    const val LIGHT_OPACITY: Double = 0x77 / 255.0

    /**
     * Each variant's attributes in [scheme]: its base's, as [scheme] resolves them, with the
     * foreground drawn at VS Code's opacity over the editor background. A base with no foreground
     * of its own in [scheme] has its text in the scheme's default foreground, so that is what fades.
     */
    fun defaultsFor(scheme: EditorColorsScheme): Map<TextAttributesKey, TextAttributes> {
        val background = scheme.defaultBackground
        val opacity = if (ColorUtil.isDark(background)) DARK_OPACITY else LIGHT_OPACITY
        return VARIANTS.entries.associate { (base, variant) ->
            val attributes = scheme.getAttributes(base)
            val foreground = attributes?.foregroundColor ?: scheme.defaultForeground
            variant to TextAttributes(fade(foreground, background, opacity), null, null, null, attributes?.fontType ?: 0)
        }
    }

    /** [foreground] at [opacity] over [background], as one opaque colour. */
    fun fade(foreground: Color, background: Color, opacity: Double): Color {
        fun mix(front: Int, back: Int) = Math.round(front * opacity + back * (1 - opacity)).toInt()
        return Color(mix(foreground.red, background.red), mix(foreground.green, background.green), mix(foreground.blue, background.blue))
    }
}
