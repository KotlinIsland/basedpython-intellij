package dev.basedpython.pycharm.editor.templates

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInsight.template.impl.TemplateSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.letContentHashingFinish
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

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

    /** See [letContentHashingFinish]: every test here expands a template, and that edits a document. */
    @AfterEach
    fun letTheEditSettle() = letContentHashingFinish()

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
     *
     * `main` is the one exclusion, and [`the guard template is not offered in a by file`] is where
     * it is asserted instead.
     */
    @Test
    fun `every bundled template expands in a by file`() {
        val keys = templateKeys("basedpython") + templateKeys("BasedPythonExtra")
        assertEquals(19, keys.size, "expected the two bundled sets to hold nineteen templates")
        for (key in keys - "main") {
            assertNotEquals(key, expand("$key.by", key), "`$key` did not expand in a .by file")
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
     * hand-written one makes [dev.basedpython.pycharm.run.main.ByMainSignature.invokesMain] true,
     * which turns off the generated argument parser, the `def main(` gutter icon and the argument
     * form. The template did not just win the wrong race, it wrote the wrong thing.
     */
    @Test
    fun `the guard template is not offered in a by file`() {
        assertEquals("main", expand("a.by", "main"))
    }

    /**
     * …and is still offered in a `.py` this plugin owns, which is why it is narrowed rather than
     * deleted. `by run` does not transpile a `.py`, so the interpreter runs exactly what is
     * written and the guard has to be written by hand — see [BasedPythonSources.hasGeneratedEntryPoint].
     */
    @Test
    fun `the guard template is offered in an owned py file`() = asBasedPythonProject {
        assertTrue(expand("a.py", "main").startsWith("if __name__ == "), "the guard did not expand")
    }

    /**
     * A template that names only `BASED_PYTHON` still expands in an owned `.py`, through the
     * `BASED_PYTHON_PY` -> `BASED_PYTHON` base chain.
     *
     * Worth its own assertion because the platform makes the narrowing look like a replacement:
     * `TemplateManagerImpl.getDirectlyApplicableContextTypes` *removes* a base context from the
     * applicable set once a more specific one matches, so in a `.py` the only context type left is
     * `BASED_PYTHON_PY`. What saves the other eighteen templates is `TemplateContext.isEnabled`
     * walking up to the base when the template names no value of its own — and if
     * `baseContextId` were ever dropped from the extension point, this is what would notice.
     */
    @Test
    fun `a basedpython template still expands in an owned py file`() = asBasedPythonProject {
        assertTrue(expand("a.py", "dcl").startsWith("data class "), "`dcl` did not expand in a .py")
    }

    /** The plugin owning `.py`, which is what puts a `.py` in the basedpython template contexts. */
    private fun asBasedPythonProject(body: () -> Unit) {
        val settings = BasedPythonSettings.getInstance(fixture.project)
        val handling = settings.pyFileHandling
        val enabled = settings.byEnabled
        val marker = Paths.get(fixture.project.basePath!!)
            .also { Files.createDirectories(it) }
            .resolve("api.lock")
        val created = !Files.exists(marker)
        if (created) Files.createFile(marker)
        settings.byEnabled = true
        settings.pyFileHandling = PyFileHandling.ALWAYS
        try {
            body()
        } finally {
            settings.pyFileHandling = handling
            settings.byEnabled = enabled
            if (created) Files.deleteIfExists(marker)
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
