package dev.basedpython.pycharm.inspections.grazie

import com.intellij.grazie.text.TextContent
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextExtractor
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.docs.render.ByDocstring
import dev.basedpython.pycharm.docs.render.ByDocstringSpanCache
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The prose the grammar checker is given from a `.by` file, read through the platform's own entry
 * point ([TextExtractor.findTextsAt]), which is how the checker asks.
 *
 * Which strings are docstrings is `by`'s answer, put in here as though it had just arrived.
 */
@TestFixtures
class BasedPythonTextExtractorTest {

    private val fixture by codeInsightFixture()

    private fun configure(source: String): PsiFile = fixture.configureByText("main.by", source)

    /** Tells [file] that the literal spelled [literal] is a docstring, as `by` would. */
    private fun docstring(file: PsiFile, literal: String) {
        val start = file.text.indexOf(literal)
        file.project.service<ByDocstringSpanCache>().remember(
            file.virtualFile,
            fixture.editor.document.modificationStamp,
            listOf(ByDocstring(TextRange(start, start + literal.length), null)),
        )
    }

    private fun textsAt(file: PsiFile, snippet: String, domains: Set<TextDomain> = TextContent.TextDomain.ALL): List<String> =
        TextExtractor.findTextsAt(file.findElementAt(file.text.indexOf(snippet))!!, domains).map { it.toString() }

    @Test
    fun `adjacent comment lines are one text without their hashes`() = onEdt {
        val file = configure("x = 1\n# This comment have\n#   two lines.\ny = 2\n")
        assertEquals(listOf("This comment have\ntwo lines."), textsAt(file, "have"))
    }

    @Test
    fun `a blank line ends a comment's text`() = onEdt {
        val file = configure("# First thought.\n\n# Second thought.\n")
        assertEquals(listOf("First thought."), textsAt(file, "First"))
    }

    @Test
    fun `a shebang is not prose`() = onEdt {
        val file = configure("#!/usr/bin/env by\nx = 1\n")
        assertEquals(emptyList<String>(), textsAt(file, "usr"))
    }

    @Test
    fun `a docstring by reports is documentation, read between its quotes without its indentation`() = onEdt {
        val q = "\"\"\""
        val literal = "$q\n    It are a docstring.\n    Second line.\n    $q"
        val file = configure("def f():\n    $literal\n    return 1\n")
        docstring(file, literal)

        assertEquals(listOf("It are a docstring.\nSecond line."), textsAt(file, "It are", setOf(TextDomain.DOCUMENTATION)))
    }

    @Test
    fun `an escape in a docstring is left unknown rather than read`() = onEdt {
        val literal = "\"\"\"Tab\\there.\"\"\""
        val file = configure("def f():\n    $literal\n")
        docstring(file, literal)

        val text = TextExtractor.findTextsAt(file.findElementAt(file.text.indexOf("Tab"))!!, setOf(TextDomain.DOCUMENTATION)).single()
        assertEquals("Tabhere.", text.toString())
        assertTrue(text.hasUnknownFragmentsIn(TextRange(3, 3)))
    }

    @Test
    fun `a string by does not call a docstring is not extracted`() = onEdt {
        val file = configure("x = \"It are a string.\"\n")
        assertEquals(emptyList<String>(), textsAt(file, "It are"))
    }

    @Test
    fun `an answer that arrives after a check did without it moves the tracker the texts are cached on`() = onEdt {
        val file = configure("def f():\n    \"\"\"Doc.\"\"\"\n")
        val cache = file.project.service<ByDocstringSpanCache>()
        val before = cache.modificationTracker.modificationCount
        cache.servedNothing(file.virtualFile)
        docstring(file, "\"\"\"Doc.\"\"\"")
        assertNotEquals(before, cache.modificationTracker.modificationCount)
    }
}
