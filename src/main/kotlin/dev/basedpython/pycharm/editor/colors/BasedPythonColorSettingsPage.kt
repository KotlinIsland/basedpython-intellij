package dev.basedpython.pycharm.editor.colors

import com.intellij.codeHighlighting.RainbowHighlighter
import com.intellij.lang.Language
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.RainbowColorSettingsPage
import dev.basedpython.pycharm.editor.highlight.ByStringMarginColors
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonColors
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.BasedPythonSyntaxHighlighter
import dev.basedpython.pycharm.lsp.inlay.ByInlayColors
import dev.basedpython.pycharm.lsp.semantic.BasedPythonSemanticTokensMapping
import javax.swing.Icon

/**
 * *Settings | Editor | Color Scheme | basedpython*: a key for everything the lexer and `by`'s
 * semantic tokens colour ([BasedPythonSemanticTokensMapping]), laid out as Python's page is.
 *
 * A [RainbowColorSettingsPage], which is what puts the *Semantic highlighting* checkbox on this page
 * for basedpython alone — without it a `.by` file only followed *Language Defaults*. Parameters and
 * locals are what it colours ([dev.basedpython.pycharm.lsp.semantic.BasedPythonRainbowVisitor]), as
 * Python's are.
 */
class BasedPythonColorSettingsPage : RainbowColorSettingsPage {

    override fun getDisplayName(): String = "basedpython"

    override fun getIcon(): Icon = BasedPythonFileType.INSTANCE.icon

    override fun getHighlighter(): SyntaxHighlighter = BasedPythonSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = COLORS

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> = TAGS

    override fun getDemoText(): String = DEMO_TEXT

    override fun isRainbowType(type: TextAttributesKey): Boolean =
        type == BasedPythonHighlightKeys.PARAMETER || type == BasedPythonHighlightKeys.LOCAL_VARIABLE

    override fun getLanguage(): Language = BasedPythonLanguage

    companion object {
        private val DESCRIPTORS: Array<AttributesDescriptor> = arrayOf(
            AttributesDescriptor("Keyword", BasedPythonColors.KEYWORD),
            AttributesDescriptor("Number", BasedPythonColors.NUMBER),
            AttributesDescriptor("Comment", BasedPythonColors.COMMENT),
            AttributesDescriptor("Identifier", BasedPythonColors.IDENTIFIER),
            AttributesDescriptor("Bad character", BasedPythonColors.BAD_CHARACTER),
            AttributesDescriptor("Strings//String", BasedPythonColors.STRING),
            AttributesDescriptor("Strings//Docstring", BasedPythonHighlightKeys.DOCSTRING),
            AttributesDescriptor("Strings//Escape sequence", BasedPythonHighlightKeys.STRING_ESCAPE),
            AttributesDescriptor("Strings//F-string braces", BasedPythonHighlightKeys.FSTRING_BRACES),
            AttributesDescriptor("Strings//F-string conversion", BasedPythonHighlightKeys.FSTRING_CONVERSION),
            AttributesDescriptor("Strings//F-string format spec start", BasedPythonHighlightKeys.FSTRING_FORMAT_START),
            AttributesDescriptor("Braces and Operators//Operator", BasedPythonColors.OPERATOR),
            AttributesDescriptor("Braces and Operators//Parentheses", BasedPythonColors.PARENTHESES),
            AttributesDescriptor("Braces and Operators//Brackets", BasedPythonColors.BRACKETS),
            AttributesDescriptor("Braces and Operators//Braces", BasedPythonColors.BRACES),
            AttributesDescriptor("Braces and Operators//Comma", BasedPythonColors.COMMA),
            AttributesDescriptor("Braces and Operators//Semicolon", BasedPythonColors.SEMICOLON),
            AttributesDescriptor("Braces and Operators//Dot", BasedPythonColors.DOT),
            AttributesDescriptor("Functions//Function definition", BasedPythonHighlightKeys.FUNCTION_DECLARATION),
            AttributesDescriptor("Functions//Nested function definition", BasedPythonHighlightKeys.NESTED_FUNCTION_DECLARATION),
            AttributesDescriptor("Functions//Function call", BasedPythonHighlightKeys.FUNCTION_CALL),
            AttributesDescriptor("Functions//Method call", BasedPythonHighlightKeys.METHOD_CALL),
            AttributesDescriptor("Classes//Class definition", BasedPythonHighlightKeys.CLASS_DECLARATION),
            AttributesDescriptor("Classes//Class reference", BasedPythonHighlightKeys.CLASS_REFERENCE),
            AttributesDescriptor("Parameters//Parameter", BasedPythonHighlightKeys.PARAMETER),
            AttributesDescriptor("Parameters//Self / cls", BasedPythonHighlightKeys.SELF_PARAMETER),
            AttributesDescriptor("Parameters//Keyword argument", BasedPythonHighlightKeys.KEYWORD_ARGUMENT),
            AttributesDescriptor("Variables//Local variable", BasedPythonHighlightKeys.LOCAL_VARIABLE),
            AttributesDescriptor("Variables//Constant", BasedPythonSemanticTokensMapping.CONSTANT),
            AttributesDescriptor("Variables//Property", BasedPythonSemanticTokensMapping.PROPERTY),
            AttributesDescriptor("Variables//Static property", BasedPythonSemanticTokensMapping.STATIC_PROPERTY),
            AttributesDescriptor("Special names//Definition", BasedPythonHighlightKeys.DUNDER_DEFINITION),
            AttributesDescriptor("Special names//Usage", BasedPythonHighlightKeys.DUNDER_USAGE),
            AttributesDescriptor("Builtins//Built-in name", BasedPythonHighlightKeys.BUILTIN_NAME),
            AttributesDescriptor("Builtins//None, True, False", BasedPythonHighlightKeys.BUILTIN_CONSTANT),
            AttributesDescriptor("Types//Type annotation", BasedPythonHighlightKeys.ANNOTATION),
            AttributesDescriptor("Types//Type parameter", BasedPythonHighlightKeys.TYPE_PARAMETER),
            AttributesDescriptor("Types//Type name", BasedPythonHighlightKeys.TYPE_NAME),
            AttributesDescriptor("Module", BasedPythonHighlightKeys.NAMESPACE),
            AttributesDescriptor("Decorator", BasedPythonHighlightKeys.DECORATOR),
            // Inlay hints from `by`. Undefined in a scheme, the colour is derived from that
            // scheme's own text and background instead — see ByInlayColors.
            AttributesDescriptor("Inlay hint", ByInlayColors.HINT),
        )

        /**
         * The trim margin drawn down a multiline string. A colour rather than text attributes:
         * it is a line beside the text, not a way of drawing text. Undefined, it is the scheme's
         * own indent-guide colour — the margin shares that column with a real indent guide, and
         * ByStringMarginColors argues why it should not fight it for it.
         */
        private val COLORS: Array<ColorDescriptor> = arrayOf(
            ColorDescriptor(
                "Multiline string trim margin",
                ByStringMarginColors.MARGIN,
                ColorDescriptor.Kind.FOREGROUND,
            ),
        )

        /** What the demo's tags stand for: the keys `by`'s tokens bring, which the lexer cannot show. */
        private val TAGS: Map<String, TextAttributesKey> = mapOf(
            "doc" to BasedPythonHighlightKeys.DOCSTRING,
            "funcDef" to BasedPythonHighlightKeys.FUNCTION_DECLARATION,
            "nestedFuncDef" to BasedPythonHighlightKeys.NESTED_FUNCTION_DECLARATION,
            "call" to BasedPythonHighlightKeys.FUNCTION_CALL,
            "mcall" to BasedPythonHighlightKeys.METHOD_CALL,
            "classDef" to BasedPythonHighlightKeys.CLASS_DECLARATION,
            "classRef" to BasedPythonHighlightKeys.CLASS_REFERENCE,
            "param" to BasedPythonHighlightKeys.PARAMETER,
            "self" to BasedPythonHighlightKeys.SELF_PARAMETER,
            "kwarg" to BasedPythonHighlightKeys.KEYWORD_ARGUMENT,
            "localVar" to BasedPythonHighlightKeys.LOCAL_VARIABLE,
            "const" to BasedPythonSemanticTokensMapping.CONSTANT,
            "property" to BasedPythonSemanticTokensMapping.PROPERTY,
            "predefined" to BasedPythonHighlightKeys.DUNDER_DEFINITION,
            "predefinedUsage" to BasedPythonHighlightKeys.DUNDER_USAGE,
            "builtin" to BasedPythonHighlightKeys.BUILTIN_NAME,
            "builtinConst" to BasedPythonHighlightKeys.BUILTIN_CONSTANT,
            "annotation" to BasedPythonHighlightKeys.ANNOTATION,
            "typeParam" to BasedPythonHighlightKeys.TYPE_PARAMETER,
            "module" to BasedPythonHighlightKeys.NAMESPACE,
            "decorator" to BasedPythonHighlightKeys.DECORATOR,
        ) + RainbowHighlighter.createRainbowHLM()

        private val DEMO_TEXT: String = """
            # basedpython demo

            import <module>math</module>
            from <module>dataclasses</module> import <call>field</call>

            <const>MAX_SIZE</const> = 10

            protocol <classDef>Greeter</classDef>:
                def <funcDef>greet</funcDef>(<self>self</self>) -> <builtin>str</builtin>: ...

            enum class <classDef>Color</classDef>:
                RED = 1
                GREEN = 2

            data class <classDef>Point</classDef>[<typeParam>T</typeParam>]:
                ${'$'}x: <annotation>T</annotation>
                ${'$'}y: <annotation>T</annotation>

                @<decorator>staticmethod</decorator>
                def <funcDef>origin</funcDef>() -> <classRef>Point</classRef>[<builtin>int</builtin>]:
                    <doc>"Return the origin point."</doc>
                    return <classRef>Point</classRef>(<kwarg>x</kwarg>=0, <kwarg>y</kwarg>=0)

            class def <classDef>Greeter_en</classDef>(<classRef>Greeter</classRef>):
                <doc>"English greeter."</doc>
                private ${'$'}_name: <builtin>str</builtin>

                def <predefined>__init__</predefined>(<self>self</self>, <param>name</param>: <builtin>str</builtin>) -> <builtinConst>None</builtinConst>:
                    <self>self</self>.<property>_name</property> = <param>name</param>

                override
                def <funcDef>greet</funcDef>(<self>self</self>) -> <builtin>str</builtin>:
                    def <nestedFuncDef>shout</nestedFuncDef>(<param>text</param>: <builtin>str</builtin>) -> <builtin>str</builtin>:
                        return <param>text</param>.<mcall>upper</mcall>()

                    <localVar>greeting</localVar> = f"Hello, {<self>self</self>.<property>_name</property>!r:>10}!"
                    <call>print</call>(<self>self</self>.<predefinedUsage>__class__</predefinedUsage>, <module>math</module>.pi)
                    return <call>shout</call>(<localVar>greeting</localVar>)

            let pi: <builtin>float</builtin> = 3.14159
            let maybe_name: <builtin>str</builtin>? = <builtinConst>None</builtinConst>
            let resolved: <builtin>str</builtin> = maybe_name ?? "anon"
        """.trimIndent()
    }
}
