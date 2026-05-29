package dev.basedpython.pycharm.lang

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

object BasedPythonColors {
    @JvmField
    val KEYWORD: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD
    )

    @JvmField
    val STRING: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_STRING", DefaultLanguageHighlighterColors.STRING
    )

    @JvmField
    val NUMBER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_NUMBER", DefaultLanguageHighlighterColors.NUMBER
    )

    @JvmField
    val COMMENT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT
    )

    @JvmField
    val OPERATOR: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN
    )

    @JvmField
    val IDENTIFIER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER
    )

    @JvmField
    val PARENTHESES: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES
    )

    @JvmField
    val BRACKETS: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS
    )

    @JvmField
    val BRACES: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_BRACES", DefaultLanguageHighlighterColors.BRACES
    )

    @JvmField
    val COMMA: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_COMMA", DefaultLanguageHighlighterColors.COMMA
    )

    @JvmField
    val SEMICOLON: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON
    )

    @JvmField
    val DOT: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_DOT", DefaultLanguageHighlighterColors.DOT
    )

    @JvmField
    val BAD_CHARACTER: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
        "BASEDPYTHON_BAD_CHARACTER", com.intellij.openapi.editor.HighlighterColors.BAD_CHARACTER
    )
}
