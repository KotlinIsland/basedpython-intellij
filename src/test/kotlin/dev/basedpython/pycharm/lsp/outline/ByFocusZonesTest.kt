package dev.basedpython.pycharm.lsp.outline

import com.intellij.codeInsight.daemon.impl.focusMode.FocusModeProvider
import com.intellij.lang.LanguageExtension
import com.intellij.testFramework.junit5.TestApplication
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The focus zones `by`'s document symbols draw. */
@TestApplication
class ByFocusZonesTest {

    private fun symbol(name: String, kind: SymbolKind, from: Pair<Int, Int>, to: Pair<Int, Int>, vararg children: DocumentSymbol) =
        DocumentSymbol(name, kind, Range(Position(from.first, from.second), Position(to.first, to.second)),
            Range(Position(from.first, from.second), Position(from.first, from.second)), null, children.toList())

    @Test
    fun `definitions are zones, a class's methods inside it, and variables are not`() {
        val text = "x = 1\n\n@dec\ndef f():\n    pass\n\nclass C:\n    y = 2\n    def m(self):\n        pass\n"
        val symbols = listOf(
            symbol("x", SymbolKind.Variable, 0 to 0, 0 to 5),
            symbol("f", SymbolKind.Function, 2 to 0, 4 to 8),
            symbol(
                "C", SymbolKind.Class, 6 to 0, 9 to 12,
                symbol("y", SymbolKind.Variable, 7 to 4, 7 to 9),
                symbol("m", SymbolKind.Method, 8 to 4, 9 to 12),
            ),
        ).map { Either.forRight<org.eclipse.lsp4j.SymbolInformation, DocumentSymbol>(it) }

        val zones = ByFocusZones.zones(symbols, text).map { text.substring(it.startOffset, it.endOffset) }

        assertEquals(
            listOf(
                "@dec\ndef f():\n    pass",
                "def m(self):\n        pass",
                "class C:\n    y = 2\n    def m(self):\n        pass",
            ),
            zones,
        )
    }

    @Test
    fun `the provider is the one focus mode finds for basedpython`() {
        val providers = LanguageExtension<FocusModeProvider>("com.intellij.focusModeProvider").allForLanguage(BasedPythonLanguage)
        assertTrue(providers.any { it is ByFocusModeProvider }, "$providers")
    }
}
