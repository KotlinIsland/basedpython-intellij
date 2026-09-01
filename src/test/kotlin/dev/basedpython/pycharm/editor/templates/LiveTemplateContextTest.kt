package dev.basedpython.pycharm.editor.templates

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The bundled live templates expand in a `.by` file.
 *
 * The regression this exists for: a template's `<context>` names the context by the id the
 * `liveTemplateContext` extension point declares — `BASED_PYTHON` — and *not* by the string passed
 * to [dev.basedpython.pycharm.editor.BasedPythonTemplateContextType]'s constructor, which the
 * platform reads as the presentable name. Every template shipped with `<option name="basedpython">`,
 * which matches no registered context, so `TemplateContext.isEnabled` found no own value, fell
 * through to the base context, and read the `OTHER` the same block sets to `false`. All nineteen
 * templates loaded, appeared in *Settings | Editor | Live Templates*, and expanded nowhere.
 *
 * Loading is asserted separately from expanding because the two fail apart: a wrong file name in
 * `defaultLiveTemplates` loses the templates, a wrong context id keeps them and makes them inert.
 * The inert case is the one that looks fine everywhere except the editor.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class LiveTemplateContextTest {

    private val fixture by codeInsightFixture()

    /** Both `defaultLiveTemplates` files, by the group each `templateSet` declares. */
    private fun templateKeys(group: String): List<String> =
        TemplateSettings.getInstance().templates.filter { it.groupName == group }.map { it.key }.sorted()

    /**
     * Types [key] at the caret, expands it, and ends any template session it started.
     *
     * The ending matters beyond tidiness. These templates stop at a variable (`$NAME$` with
     * `alwaysStopAt`), so a successful expansion leaves a live-template session running on the
     * editor of a *shared light project*. Left open, it outlives the test and every later test in
     * the suite fails somewhere unrecognisable — which is exactly what it did.
     */
    private fun expand(fileName: String, key: String): String {
        fixture.configureByText(fileName, key)
        fixture.editor.caretModel.moveToOffset(key.length)
        fixture.performEditorAction("ExpandLiveTemplateByTab")
        val text = fixture.editor.document.text
        endTemplate(fixture.editor)
        return text
    }

    /** Ending a session writes to the document, so it has to be a command like any other edit. */
    private fun endTemplate(editor: Editor) {
        val state = TemplateManagerImpl.getTemplateState(editor) ?: return
        WriteCommandAction.runWriteCommandAction(fixture.project) { state.gotoEnd(false) }
    }

    @Test
    fun `both bundled template sets load`() {
        assertEquals(
            listOf("cdef", "dcl", "ecl", "fdcl", "let", "nt", "ovr", "proto"),
            templateKeys("basedpython"),
        )
        assertEquals(
            listOf("adef", "compr", "enum", "field", "fix", "main", "match", "prop", "test", "try", "with"),
            templateKeys("BasedPythonExtra"),
        )
    }

    /**
     * Every bundled template, expanded in a `.by` file. One assertion per template rather than a
     * spot check: the context block is copy-pasted per template, so one of them being wrong is the
     * shape this actually breaks in.
     */
    @Test
    fun `every bundled template expands in a by file`() {
        val keys = templateKeys("basedpython") + templateKeys("BasedPythonExtra")
        assertEquals(19, keys.size, "expected the two bundled sets to hold nineteen templates")
        for (key in keys) {
            assertNotEquals(key, expand("$key.by", key), "`$key` did not expand in a .by file")
        }
    }

    /** The abbreviation the changelog and the docs name, expanded end to end. */
    @Test
    fun `dcl expands to a data class`() {
        val text = expand("a.by", "dcl")
        assertTrue(text.startsWith("data class "), "expected a `data class` header, got: $text")
    }

    /**
     * A template must not expand in a file this plugin does not own. `OTHER` is set to `false`
     * beside the context id in every block, and this is what says that half still works.
     */
    @Test
    fun `a bundled template does not expand in a plain text file`() {
        assertEquals("dcl", expand("a.txt", "dcl"))
    }
}
