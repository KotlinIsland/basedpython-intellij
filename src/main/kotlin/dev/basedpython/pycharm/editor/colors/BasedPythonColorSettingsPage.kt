package dev.basedpython.pycharm.editor.colors

import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import dev.basedpython.pycharm.lang.BasedPythonColors
import dev.basedpython.pycharm.lang.BasedPythonSyntaxHighlighter
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.editor.highlight.ByStringMarginColors
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lsp.inlay.ByInlayColors
import javax.swing.Icon

class BasedPythonColorSettingsPage : ColorSettingsPage {

    override fun getDisplayName(): String = "basedpython"

    override fun getIcon(): Icon = BasedPythonFileType.INSTANCE.icon

    override fun getHighlighter(): SyntaxHighlighter = BasedPythonSyntaxHighlighter()

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = COLORS

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, com.intellij.openapi.editor.colors.TextAttributesKey>? = null

    override fun getDemoText(): String = DEMO_TEXT

    companion object {
        private val DESCRIPTORS: Array<AttributesDescriptor> = arrayOf(
            AttributesDescriptor("Keyword", BasedPythonColors.KEYWORD),
            AttributesDescriptor("String", BasedPythonColors.STRING),
            AttributesDescriptor("Number", BasedPythonColors.NUMBER),
            AttributesDescriptor("Comment", BasedPythonColors.COMMENT),
            AttributesDescriptor("Operator", BasedPythonColors.OPERATOR),
            AttributesDescriptor("Identifier", BasedPythonColors.IDENTIFIER),
            AttributesDescriptor("Bad character", BasedPythonColors.BAD_CHARACTER),
            // Stream H — semantic annotator keys
            AttributesDescriptor("Builtins//Built-in name", BasedPythonHighlightKeys.BUILTIN_NAME),
            AttributesDescriptor("Function//Declaration", BasedPythonHighlightKeys.FUNCTION_DECLARATION),
            AttributesDescriptor("Class//Declaration", BasedPythonHighlightKeys.CLASS_DECLARATION),
            AttributesDescriptor("Parameters//Parameter", BasedPythonHighlightKeys.PARAMETER),
            AttributesDescriptor("Parameters//Self / cls", BasedPythonHighlightKeys.SELF_PARAMETER),
            AttributesDescriptor("Parameters//Keyword argument", BasedPythonHighlightKeys.KEYWORD_ARGUMENT),
            AttributesDescriptor("Types//Type name", BasedPythonHighlightKeys.TYPE_NAME),
            AttributesDescriptor("Strings//Escape sequence", BasedPythonHighlightKeys.STRING_ESCAPE),
            AttributesDescriptor("Strings//F-string interpolation", BasedPythonHighlightKeys.FSTRING_INTERP),
            AttributesDescriptor("Decorator", BasedPythonHighlightKeys.DECORATOR),
            // Inlay hints from `by`. Undefined in a scheme, the colour is derived from that
            // scheme's own text and background instead — see ByInlayColors.
            AttributesDescriptor("Inlay hint", ByInlayColors.HINT),
        )

        /**
         * The trim margin drawn down a multiline string. A colour rather than text attributes:
         * it is a line beside the text, not a way of drawing text — and like the inlay colours,
         * a scheme that says nothing about it gets one derived from its own string colour
         * (ByStringMarginColors), not a grey chosen here.
         */
        private val COLORS: Array<ColorDescriptor> = arrayOf(
            ColorDescriptor(
                "Multiline string trim margin",
                ByStringMarginColors.MARGIN,
                ColorDescriptor.Kind.FOREGROUND,
            ),
        )

        private val DEMO_TEXT: String = """
            # basedpython demo

            from __future__ import annotations

            protocol Greeter:
                def greet(self) -> str: ...

            enum class Color:
                RED = 1
                GREEN = 2
                BLUE = 3

            data class Point[T]:
                ${'$'}x: T
                ${'$'}y: T

                @staticmethod
                def origin() -> Point[int]:
                    "Return the origin point."
                    return Point(x=0, y=0)

            class def Greeter_en(Greeter):
                "English greeter."
                private ${'$'}_name: str

                def __init__(self, name: str) -> None:
                    self._name = name

                override
                def greet(self) -> str:
                    return f"Hello, {self._name}!"

                abstract
                def farewell(self) -> str: ...

            let pi: float = 3.14159
            let maybe_name: str? = None
            let resolved: str = maybe_name ?? "anon"
            let length: int? = maybe_name?.length

            # Anonymous named tuple
            let coord = (x=1, y=2)
        """.trimIndent()
    }
}
