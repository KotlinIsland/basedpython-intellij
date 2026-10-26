package dev.basedpython.pycharm.editor.folding

import com.intellij.codeInsight.folding.CodeFoldingSettings
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.folding.ByFoldingRanges
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Folding a `.by` file from `by`'s ranges, put in by hand: what each folds to, and what *Fold by
 * default* folds.
 */
@TestFixtures
class ByFoldingBuilderTest {

    private val fixture by codeInsightFixture()

    private val text = """
        |import os
        |import sys
        |
        |# region Helpers
        |def f(
        |    a: int,
        |) -> None:
        |    ""${'"'}Doc.
        |
        |    More.
        |    ""${'"'}
        |    x = [
        |        1,
        |    ]
        |# endregion
        |
    """.trimMargin()

    /**
     * Each fold as `its first line → placeholder (folded by default)`; the platform's custom folding
     * decides for a region itself, so a region's default is null here.
     */
    private fun folds(vararg ranges: Pair<String, String?>): List<String> {
        fixture.configureByText("a.by", text)
        val document = fixture.editor.document
        ByFoldingRanges.getInstance(fixture.project).remember(
            document,
            ranges.map { (spelled, kind) ->
                val (from, to) = spelled.split("…")
                val start = text.indexOf(from)
                val end = text.indexOf(to, start + from.length) + to.length
                ByFoldingRanges.Fold(TextRange(start, end), kind, if (kind == "imports") "import ..." else null)
            },
        )
        val builder = LanguageFolding.INSTANCE.forLanguage(BasedPythonLanguage)
        return LanguageFolding.buildFoldingDescriptors(builder, fixture.file, document, false)
            .sortedBy { it.range.startOffset }
            .map { "${it.range.substring(text).lines().first { line -> line.isNotBlank() }.trim()} → ${it.placeholderText} (${it.isCollapsedByDefault})" }
    }

    @Test
    fun `by's ranges fold to its text, imports by default`() = onEdt {
        assertEquals(
            listOf(
                "import os → import ... (true)",
                "# region Helpers → Helpers (null)",
                "a: int, → ... (false)",
                "\"\"\"Doc. → ... (false)",
                "1, → ... (false)",
            ),
            folds(
                "import os…import sys" to "imports",
                "# region…# endregion" to "region",
                "\n    a: int,…None" to "function",
                "    \"\"\"Doc…\"\"\"" to "docstring",
                "\n        1,…    ]" to "collection",
            ),
        )
    }

    @Test
    fun `the options fold their kinds by default, and a method only by its body`() = onEdt {
        val settings = CodeFoldingSettings.getInstance()
        val basedpython = ByFoldingSettings.getInstance().state
        val before = Triple(settings.COLLAPSE_IMPORTS, settings.COLLAPSE_METHODS, settings.COLLAPSE_DOC_COMMENTS)
        settings.COLLAPSE_IMPORTS = false
        settings.COLLAPSE_METHODS = true
        settings.COLLAPSE_DOC_COMMENTS = true
        basedpython.collapseLongCollections = true
        try {
            assertEquals(
                listOf(
                    "import os → import ... (false)",
                    "# region Helpers → Helpers (null)",
                    "a: int, → ... (false)",
                    "\"\"\"Doc. → ... (true)",
                    "\"\"\"Doc. → ... (true)",
                    "1, → ... (true)",
                ),
                folds(
                    "import os…import sys" to "imports",
                    "# region…# endregion" to "region",
                    "\n    a: int,…    ]" to "function",
                    "\n    \"\"\"Doc…    ]" to "function",
                    "    \"\"\"Doc…\"\"\"" to "docstring",
                    "\n        1,…    ]" to "collection",
                ),
            )
        } finally {
            settings.COLLAPSE_IMPORTS = before.first
            settings.COLLAPSE_METHODS = before.second
            settings.COLLAPSE_DOC_COMMENTS = before.third
            basedpython.collapseLongCollections = false
        }
    }

    @Test
    fun `with no answer only the region comments fold`() = onEdt {
        fixture.configureByText("a.by", text)
        val builder = LanguageFolding.INSTANCE.forLanguage(BasedPythonLanguage)
        val folds = LanguageFolding.buildFoldingDescriptors(builder, fixture.file, fixture.editor.document, false)
        assertEquals(listOf("Helpers"), folds.map { it.placeholderText })
    }
}
