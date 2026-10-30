package dev.basedpython.pycharm.lsp.inject

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Matching what a marker said to a language this IDE has.
 *
 * Plain text throughout, because it is the one language every IDE this plugin runs in registers:
 * html and sql belong to plugins that may not be on the classpath, and a test that quietly turns
 * into "this IDE has no html" is worse than no test.
 */
@TestFixtures
class ByInjectedLanguagesTest {

    @Suppress("unused") // The language registry needs an application, which the fixture provides.
    private val fixture by codeInsightFixture()

    private val plainText get() = PlainTextLanguage.INSTANCE

    @Test
    fun `an id spelled exactly as the language registers it`() = onEdt {
        assertEquals(plainText, ByInjectedLanguages.find(plainText.id))
    }

    @Test
    fun `case is not what a marker is judged on`() = onEdt {
        assertEquals(plainText, ByInjectedLanguages.find(plainText.id.lowercase()))
        assertEquals(plainText, ByInjectedLanguages.find(plainText.id.uppercase()))
    }

    @Test
    fun `the name a language shows a user also names it`() = onEdt {
        assertEquals(plainText, ByInjectedLanguages.find(plainText.displayName))
    }

    @Test
    fun `a file extension names the language of that file type`() = onEdt {
        assertEquals(plainText, ByInjectedLanguages.find("txt"))
    }

    @Test
    fun `surrounding space is not part of the id`() = onEdt {
        assertEquals(plainText, ByInjectedLanguages.find("  ${plainText.id} "))
    }

    /**
     * The test IDE is IntelliJ IDEA without the Python plugin, so it has the platform's `RegExp` and
     * not the Python plugin's `PythonRegExp`, which is the id `by` marks a pattern handed to `re` with.
     */
    @Test
    fun `Python's dialect of regular expressions is read as RegExp where the dialect is not`() = onEdt {
        assertNull(Language.findLanguageByID("PythonRegExp"), "the test IDE has the Python plugin after all")
        val regExp = Language.findLanguageByID("RegExp")
        assertNotNull(regExp, "the platform's RegExp language is missing")
        assertEquals(regExp, ByInjectedLanguages.find("PythonRegExp"))
        assertEquals(regExp, ByInjectedLanguages.find("pythonregexp"))
    }

    @Test
    fun `an id nothing here provides is no language, and not an error`() = onEdt {
        assertNull(ByInjectedLanguages.find("no-such-language-anywhere"))
        assertNull(ByInjectedLanguages.find(""))
        assertNull(ByInjectedLanguages.find("   "))
    }
}
