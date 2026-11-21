package dev.basedpython.pycharm.editor.templates

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
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
    fun `both bundled template sets load`() = onEdt {
        assertEquals(
            listOf("cdef", "dcl", "ecl", "enum", "fdcl", "let", "nt", "proto"),
            templateKeys("basedpython"),
        )
        assertEquals(
            listOf("adef", "compr", "field", "fix", "main", "match", "prop", "test", "try", "with"),
            templateKeys("BasedPythonExtra"),
        )
    }

    /** The templates written in basedpython's own syntax, the whole `basedpython` group. */
    private fun basedpythonTemplates(): List<String> = templateKeys("basedpython")

    /** The templates written in Python a `.by` reads the same: the rest, but the `.py` only ones. */
    private fun sharedTemplates(): List<String> = templateKeys("BasedPythonExtra") - PY_ONLY

    /**
     * Every bundled template a `.by` is offered, expanded in one. One assertion per template rather
     * than a spot check: the context block is copy-pasted per template, so one of them being wrong is
     * the shape this actually breaks in.
     *
     * `main` is the exclusion, asserted in the tests below instead.
     */
    @Test
    fun `every bundled template expands in a by file`() = onEdt {
        val keys = basedpythonTemplates() + sharedTemplates()
        assertEquals(17, keys.size, "expected seventeen templates offered in a .by")
        for (key in keys) {
            assertNotEquals(key, expand("$key.by", key), "`$key` did not expand in a .by file")
        }
    }

    /**
     * …and in a `.byi`, which is basedpython syntax as much as a `.by` is: the two halves are told
     * apart by the syntax a file is written in, not by whether it runs.
     */
    @Test
    fun `a basedpython template expands in a byi file`() = onEdt {
        assertTrue(expand("a.byi", "dcl").startsWith("data class "), "`dcl` did not expand in a .byi")
        assertEquals("main", expand("a.byi", "main"))
    }

    /**
     * No template in basedpython's own syntax is offered in a `.py` this plugin owns. Owning a `.py`
     * re-types it, it does not transpile it, so it is plain Python, and `data class` or `enum class`
     * there is a syntax error the template wrote. This is the regression: all of them were offered,
     * `enum` among them writing `enum class` into a `.py`.
     */
    @Test
    fun `no basedpython template expands in an owned py file`() = asBasedPythonProject {
        for (key in basedpythonTemplates()) {
            assertEquals(key, expand("$key.py", key), "`$key` expanded in a .py file")
        }
    }

    /** `enum` is basedpython's `enum class` in a `.by`, with a `case` member, as basedpython writes one. */
    @Test
    fun `enum is an enum class in a by file`() = onEdt {
        assertEquals("enum class MyEnum:\n    case VALUE", expand("a.by", "enum"))
    }

    /**
     * …and no live template in a `.py`, where `enum` is `by`'s completion: a Python enum needs
     * `from enum import Enum, auto`, which a live template cannot add and `by`'s importer places as
     * the file's other imports are. A live template under the key would be preselected over it, an
     * exact match winning whatever else is listed.
     */
    @Test
    fun `enum is not a live template in an owned py file`() = asBasedPythonProject {
        assertEquals("enum", expand("a.py", "enum"))
    }

    /** Every template written in Python both read still expands in an owned `.py`. */
    @Test
    fun `every shared template expands in an owned py file`() = asBasedPythonProject {
        for (key in sharedTemplates()) {
            assertNotEquals(key, expand("$key.py", key), "`$key` did not expand in a .py file")
        }
    }

    /**
     * The `main` guard template is `.py` only, and typing `main` in a `.by` must reach the `by`
     * server's own `main` completion instead.
     *
     * Two things go wrong when it is offered here, and the second is the one that bites. The first
     * is the visible one: a live template whose key is exactly the typed prefix is *preselected*
     * over everything else, whatever its relevance or position in the list —
     * `CompletionLookupArrangerImpl.getExactMatches` returns it as the sole exact match, so
     * `main` + Enter took the guard rather than the server's `main`. The second is that taking it
     * was wrong anyway: basedpython generates the `__main__` guard from `def main`, and a
     * hand-written one is a module that invokes `main` itself (`by/entryPoint`'s
     * `moduleInvokesMain`), which turns off the generated argument parser and the argument form. The template did not just win the wrong race, it wrote the wrong thing.
     */
    @Test
    fun `the guard template is not offered in a by file`() = onEdt {
        assertEquals("main", expand("a.by", "main"))
    }

    /**
     * …and is still offered in a `.py` this plugin owns, which is why it is narrowed rather than
     * deleted. `by run` does not transpile a `.py`, so the interpreter runs exactly what is
     * written and the guard has to be written by hand — see [BasedPythonSources.isWrittenInBasedPython].
     */
    @Test
    fun `the guard template is offered in an owned py file`() = asBasedPythonProject {
        assertTrue(expand("a.py", "main").startsWith("if __name__ == "), "the guard did not expand")
    }

    /**
     * A template that names only `BASED_PYTHON` expands in an owned `.py` and in a `.by`, through the
     * `BASED_PYTHON_PY` -> `BASED_PYTHON` and `BASED_PYTHON_BY` -> `BASED_PYTHON` base chains.
     *
     * Worth its own assertion because the platform makes the narrowing look like a replacement:
     * `TemplateManagerImpl.getDirectlyApplicableContextTypes` *removes* a base context from the
     * applicable set once a more specific one matches, so in a `.py` the only context type left is
     * `BASED_PYTHON_PY`, and in a `.by` `BASED_PYTHON_BY`. What saves the shared templates is
     * `TemplateContext.isEnabled` walking up to the base when the template names no value of its
     * own — and if `baseContextId` were ever dropped from either extension point, this is what
     * would notice.
     */
    @Test
    fun `a shared template expands through the base context`() = asBasedPythonProject {
        assertTrue(expand("a.py", "match").startsWith("match "), "`match` did not expand in a .py")
        assertTrue(expand("a.by", "match").startsWith("match "), "`match` did not expand in a .by")
    }

    /** The plugin owning `.py`, which is what puts a `.py` in the basedpython template contexts. */
    private fun asBasedPythonProject(body: () -> Unit) = onEdt {
        dev.basedpython.pycharm.testFramework.asBasedPythonProject(fixture.project, body)
    }

    /** The abbreviation the changelog and the docs name, expanded end to end. */
    @Test
    fun `dcl expands to a data class`() = onEdt {
        val text = expand("a.by", "dcl")
        assertTrue(text.startsWith("data class "), "expected a `data class` header, got: $text")
    }

    /**
     * A template must not expand in a file this plugin does not own. `OTHER` is set to `false`
     * beside the context id in every block, and this is what says that half still works.
     */
    @Test
    fun `a bundled template does not expand in a plain text file`() = onEdt {
        assertEquals("dcl", expand("a.txt", "dcl"))
    }

    private companion object {
        /** The `.py` only template, the `__main__` guard. */
        val PY_ONLY = setOf("main")
    }
}
