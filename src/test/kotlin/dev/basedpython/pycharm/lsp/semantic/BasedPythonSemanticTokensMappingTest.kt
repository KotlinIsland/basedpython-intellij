package dev.basedpython.pycharm.lsp.semantic

import com.intellij.openapi.editor.colors.TextAttributesKey
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for [BasedPythonSemanticTokensMapping.keyFor]. No platform fixture is required —
 * [TextAttributesKey.createTextAttributesKey] works in a plain JUnit run.
 */
class BasedPythonSemanticTokensMappingTest {

    private fun key(type: String, vararg mods: String): TextAttributesKey? =
        BasedPythonSemanticTokensMapping.keyFor(type, mods.toList())

    // region: type-name family

    @Test
    fun `namespace maps to TYPE_NAME`() {
        assertSame(BasedPythonHighlightKeys.TYPE_NAME, key("namespace"))
    }

    @Test
    fun `type maps to TYPE_NAME`() {
        assertSame(BasedPythonHighlightKeys.TYPE_NAME, key("type"))
    }

    @Test
    fun `typeParameter maps to TYPE_NAME`() {
        assertSame(BasedPythonHighlightKeys.TYPE_NAME, key("typeParameter"))
    }

    @Test
    fun `class maps to CLASS_DECLARATION`() {
        assertSame(BasedPythonHighlightKeys.CLASS_DECLARATION, key("class"))
    }

    @Test
    fun `enum maps to CLASS_DECLARATION`() {
        assertSame(BasedPythonHighlightKeys.CLASS_DECLARATION, key("enum"))
    }

    @Test
    fun `interface maps to CLASS_DECLARATION`() {
        assertSame(BasedPythonHighlightKeys.CLASS_DECLARATION, key("interface"))
    }

    @Test
    fun `struct maps to CLASS_DECLARATION`() {
        assertSame(BasedPythonHighlightKeys.CLASS_DECLARATION, key("struct"))
    }

    // endregion

    // region: parameter & variables

    @Test
    fun `parameter maps to PARAMETER`() {
        assertSame(BasedPythonHighlightKeys.PARAMETER, key("parameter"))
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
    fun `function without modifiers maps to FUNCTION_DECLARATION`() {
        assertSame(BasedPythonHighlightKeys.FUNCTION_DECLARATION, key("function"))
    }

    @Test
    fun `function with defaultLibrary maps to BUILTIN_NAME`() {
        assertSame(BasedPythonHighlightKeys.BUILTIN_NAME, key("function", "defaultLibrary"))
    }

    @Test
    fun `method without modifiers maps to FUNCTION_DECLARATION`() {
        assertSame(BasedPythonHighlightKeys.FUNCTION_DECLARATION, key("method"))
    }

    @Test
    fun `method with defaultLibrary maps to BUILTIN_NAME`() {
        assertSame(BasedPythonHighlightKeys.BUILTIN_NAME, key("method", "defaultLibrary"))
    }

    @Test
    fun `method with async still maps to FUNCTION_DECLARATION`() {
        assertSame(BasedPythonHighlightKeys.FUNCTION_DECLARATION, key("method", "async"))
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
            "enumMember", "macro", "typeParameter"
        )
        for (t in listed) {
            assertNotNull("expected non-null mapping for token type '$t'", key(t))
        }
    }

    // endregion
}
