package dev.basedpython.pycharm.docs

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which keyword documentation a token gets. The declaration words are the ones that need `by`: the
 * provider this replaced matched `data\s+class` against the line, so a `class` keyword on a `data
 * class` line showed `class def`'s entry, and a `.` anywhere on a line with a `?.` showed `?.`'s.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonDocumentationProviderTest {

    private val fixture by codeInsightFixture()

    private fun titleAt(snippet: String, occurrence: Int = 0): String? {
        var offset = -1
        repeat(occurrence + 1) { offset = fixture.file.text.indexOf(snippet, offset + 1) }
        val element = fixture.file.findElementAt(offset)
        return BasedPythonDocumentationProvider().getQuickNavigateInfo(element, element)
    }

    @Test
    fun `a declaration keyword is documented by the declaration by parsed it into`() {
        fixture.configureByText("a.by", "frozen data class P:\n    x: int\nclass Q:\n    class def make(cls): ...\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            compound(modifiers = listOf("frozen data" to "frozen_data_class")) {
                clause("class P:") { simple("x: int") }
            }
            compound {
                clause("class Q:") {
                    compound(modifiers = listOf("class" to "classmethod")) {
                        clause("def make(cls):") { simple("...") }
                    }
                }
            }
        }

        assertEquals("frozen data class", titleAt("frozen"))
        assertEquals("frozen data class", titleAt("data"))
        assertEquals("frozen data class", titleAt("class"))
        assertEquals(null, titleAt("class Q"))
        assertEquals("class def", titleAt("class def"))
        assertEquals("class def", titleAt("def make"))
    }

    @Test
    fun `a name spelled like a declaration keyword is not documented as one`() {
        fixture.configureByText("b.by", "data = user?.name.upper()\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) { simple("data = user?.name.upper()") }

        assertEquals(null, titleAt("data"))
        assertEquals("?. (null-safe access)", titleAt("?."))
        assertEquals(null, titleAt(".", occurrence = 1))
    }
}
