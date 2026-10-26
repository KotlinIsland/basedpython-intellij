package dev.basedpython.pycharm.lsp.semantic

import com.intellij.openapi.editor.colors.TextAttributesKey
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonColors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [BasedPythonSemanticTokensMapping.keyFor]. No platform fixture is required —
 * [TextAttributesKey.createTextAttributesKey] works in a plain JUnit run.
 */
class BasedPythonSemanticTokensMappingTest {

    private fun key(type: String, vararg mods: String): TextAttributesKey? =
        BasedPythonSemanticTokensMapping.keyFor(type, mods.toList())

    // region: type-name family

    @Test
    fun `namespace maps to NAMESPACE`() {
        assertSame(BasedPythonHighlightKeys.NAMESPACE, key("namespace"))
    }

    @Test
    fun `typeParameter maps to TYPE_PARAMETER`() {
        assertSame(BasedPythonHighlightKeys.TYPE_PARAMETER, key("typeParameter"))
    }

    @Test
    fun `a class where it is defined is a class declaration, and anywhere else a reference`() {
        for (type in listOf("class", "enum", "interface", "struct", "type")) {
            assertSame(BasedPythonHighlightKeys.CLASS_DECLARATION, key(type, "definition"), type)
            assertSame(BasedPythonHighlightKeys.CLASS_REFERENCE, key(type), type)
        }
    }

    @Test
    fun `a class in a type expression is a type annotation, unless it is a builtin`() {
        assertSame(BasedPythonHighlightKeys.ANNOTATION, key("class", "annotation"))
        assertSame(BasedPythonHighlightKeys.BUILTIN_NAME, key("class", "annotation", "defaultLibrary"))
    }

    // endregion

    // region: parameter & variables

    @Test
    fun `parameter maps to PARAMETER`() {
        assertSame(BasedPythonHighlightKeys.PARAMETER, key("parameter"))
        assertSame(BasedPythonHighlightKeys.PARAMETER, key("parameter", "definition", "functionScope"))
    }

    @Test
    fun `the name a keyword argument is passed by is a keyword argument`() {
        assertSame(BasedPythonHighlightKeys.KEYWORD_ARGUMENT, key("parameter", "keywordArgument"))
    }

    @Test
    fun `self and cls are the self parameter`() {
        assertSame(BasedPythonHighlightKeys.SELF_PARAMETER, key("selfParameter"))
        assertSame(BasedPythonHighlightKeys.SELF_PARAMETER, key("clsParameter", "functionScope"))
    }

    @Test
    fun `a variable bound in a function is a local`() {
        assertSame(BasedPythonHighlightKeys.LOCAL_VARIABLE, key("variable", "functionScope"))
        assertSame(BasedPythonHighlightKeys.LOCAL_VARIABLE, key("variable", "definition", "functionScope"))
    }

    @Test
    fun `variable without modifiers maps to IDENTIFIER`() {
        assertSame(BasedPythonColors.IDENTIFIER, key("variable"))
    }

    @Test
    fun `variable with readonly maps to CONSTANT`() {
        assertSame(BasedPythonSemanticTokensMapping.CONSTANT, key("variable", "readonly"))
    }

    @Test
    fun `variable with static but not readonly stays IDENTIFIER`() {
        assertSame(BasedPythonColors.IDENTIFIER, key("variable", "static"))
    }

    @Test
    fun `variable with readonly among multiple modifiers maps to CONSTANT`() {
        assertSame(
            BasedPythonSemanticTokensMapping.CONSTANT,
            key("variable", "declaration", "readonly", "static")
        )
    }

    // endregion

    // region: property / field family

    @Test
    fun `property without modifiers maps to PROPERTY`() {
        assertSame(BasedPythonSemanticTokensMapping.PROPERTY, key("property"))
    }

    @Test
    fun `property with static maps to STATIC_PROPERTY`() {
        assertSame(BasedPythonSemanticTokensMapping.STATIC_PROPERTY, key("property", "static"))
    }

    @Test
    fun `enumMember maps to CONSTANT`() {
        assertSame(BasedPythonSemanticTokensMapping.CONSTANT, key("enumMember"))
    }

    // endregion

    // region: callables

    @Test
    fun `a function where it is defined is a declaration, and anywhere else a call`() {
        assertSame(BasedPythonHighlightKeys.FUNCTION_DECLARATION, key("function", "definition"))
        assertSame(BasedPythonHighlightKeys.FUNCTION_CALL, key("function"))
    }

    @Test
    fun `a function defined inside another is a nested function definition`() {
        assertSame(BasedPythonHighlightKeys.NESTED_FUNCTION_DECLARATION, key("function", "definition", "functionScope"))
    }

    @Test
    fun `a method where it is defined is a declaration, and anywhere else a method call`() {
        assertSame(BasedPythonHighlightKeys.FUNCTION_DECLARATION, key("method", "definition"))
        assertSame(BasedPythonHighlightKeys.FUNCTION_DECLARATION, key("method", "definition", "async"))
        assertSame(BasedPythonHighlightKeys.METHOD_CALL, key("method"))
    }

    @Test
    fun `a builtin is a builtin name whatever it is`() {
        for (type in listOf("function", "method", "class", "variable")) {
            assertSame(BasedPythonHighlightKeys.BUILTIN_NAME, key(type, "defaultLibrary"), type)
        }
    }

    @Test
    fun `a dunder is a special name, defined or used, ahead of what it names`() {
        assertSame(BasedPythonHighlightKeys.DUNDER_DEFINITION, key("method", "definition", "dunder"))
        assertSame(BasedPythonHighlightKeys.DUNDER_USAGE, key("variable", "dunder"))
        assertSame(BasedPythonHighlightKeys.DUNDER_USAGE, key("property", "dunder"))
    }

    @Test
    fun `None, True and False are builtin constants`() {
        assertSame(BasedPythonHighlightKeys.BUILTIN_CONSTANT, key("builtinConstant"))
    }

    // endregion

    // region: decorators / macros

    @Test
    fun `decorator maps to DECORATOR`() {
        assertSame(BasedPythonHighlightKeys.DECORATOR, key("decorator"))
    }

    @Test
    fun `macro maps to DECORATOR`() {
        assertSame(BasedPythonHighlightKeys.DECORATOR, key("macro"))
    }

    // endregion

    // region: lexical-ish token types

    @Test
    fun `keyword maps to KEYWORD`() {
        assertSame(BasedPythonColors.KEYWORD, key("keyword"))
    }

    @Test
    fun `modifier maps to KEYWORD`() {
        assertSame(BasedPythonColors.KEYWORD, key("modifier"))
    }

    @Test
    fun `comment maps to COMMENT`() {
        assertSame(BasedPythonColors.COMMENT, key("comment"))
    }

    @Test
    fun `string maps to STRING`() {
        assertSame(BasedPythonColors.STRING, key("string"))
    }

    @Test
    fun `a string marked documentation is a docstring`() {
        assertSame(BasedPythonHighlightKeys.DOCSTRING, key("string", "documentation"))
    }

    @Test
    fun `regexp maps to STRING`() {
        assertSame(BasedPythonColors.STRING, key("regexp"))
    }

    @Test
    fun `number maps to NUMBER`() {
        assertSame(BasedPythonColors.NUMBER, key("number"))
    }

    @Test
    fun `operator maps to OPERATOR`() {
        assertSame(BasedPythonColors.OPERATOR, key("operator"))
    }

    // endregion

    // region: fallback / unknown

    @Test
    fun `unknown token type returns null`() {
        assertNull(key("totallyBogusTokenType"))
    }

    @Test
    fun `event token type is unmapped and returns null`() {
        assertNull(key("event"))
    }

    @Test
    fun `empty token type returns null`() {
        assertNull(key(""))
    }

    @Test
    fun `token type matching is case sensitive`() {
        // "Class" (capital C) is not the LSP spec name and must not match.
        assertNull(key("Class"))
        assertNotNull(key("class"))
    }

    @Test
    fun `unknown type with modifiers still returns null`() {
        assertNull(key("mystery", "readonly", "static"))
    }

    // endregion

    // region: default-arg overload & misc invariants

    @Test
    fun `keyFor default modifiers overload equals empty list`() {
        assertSame(
            BasedPythonSemanticTokensMapping.keyFor("variable"),
            BasedPythonSemanticTokensMapping.keyFor("variable", emptyList())
        )
    }

    @Test
    fun `newly defined keys have basedpython external names`() {
        assertTrue(BasedPythonSemanticTokensMapping.CONSTANT.externalName.startsWith("BASEDPYTHON_"))
        assertTrue(BasedPythonSemanticTokensMapping.PROPERTY.externalName.startsWith("BASEDPYTHON_"))
        assertTrue(BasedPythonSemanticTokensMapping.STATIC_PROPERTY.externalName.startsWith("BASEDPYTHON_"))
    }

    @Test
    fun `all standard listed token types resolve to non-null keys`() {
        val listed = listOf(
            "class", "function", "parameter", "variable", "decorator", "namespace", "type",
            "keyword", "string", "number", "comment", "property", "method", "enum",
            "enumMember", "macro", "typeParameter", "selfParameter", "clsParameter", "builtinConstant",
            "operator", "regexp",
        )
        for (t in listed) {
            assertNotNull(key(t), "expected non-null mapping for token type '$t'")
        }
    }

    // endregion
}
