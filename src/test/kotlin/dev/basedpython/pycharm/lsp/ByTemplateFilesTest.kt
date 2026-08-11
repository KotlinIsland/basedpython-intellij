package dev.basedpython.pycharm.lsp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which files the `by` language server is handed as django templates.
 *
 * Nothing else claims `.html` for the server, so without this the template features do not exist in
 * the editor no matter what the server supports — which is exactly how they were missing. The rule
 * has to stay narrow in the other direction too: `.html` is the most common extension there is.
 */
class ByTemplateFilesTest {

    @Test
    fun `an html file under a templates directory is a template`() {
        assertTrue(ByTemplateFiles.isTemplate("html", listOf("blog", "templates", "blog", "app")))
    }

    /** django's loaders read all of these; the server recognises the same set. */
    @Test
    fun `the other template extensions count too`() {
        for (extension in listOf("htm", "txt", "xml", "django", "dj")) {
            assertTrue(
                ByTemplateFiles.isTemplate(extension, listOf("templates")),
                "expected .$extension under templates/ to be a template",
            )
        }
    }

    /**
     * The load-bearing half. Claiming every `.html` would put the type checker in front of files
     * that have nothing to do with it — a static site, a coverage report, a fixture.
     */
    @Test
    fun `an html file outside a templates directory is not`() {
        assertFalse(ByTemplateFiles.isTemplate("html", listOf("static", "site", "project")))
        assertFalse(ByTemplateFiles.isTemplate("html", emptyList()))
    }

    @Test
    fun `a non-template extension under templates is not claimed`() {
        assertFalse(ByTemplateFiles.isTemplate("css", listOf("templates")))
        assertFalse(ByTemplateFiles.isTemplate("py", listOf("templates")))
        assertFalse(ByTemplateFiles.isTemplate(null, listOf("templates")))
    }

    /** The directory can sit anywhere above the file, which is what app-directories layouts do. */
    @Test
    fun `the templates directory may be any ancestor`() {
        assertTrue(ByTemplateFiles.isTemplate("html", listOf("deep", "nested", "templates", "app")))
    }

    @Test
    fun `matching ignores case`() {
        assertTrue(ByTemplateFiles.isTemplate("HTML", listOf("Templates")))
    }
}
