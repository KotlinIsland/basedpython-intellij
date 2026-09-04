package dev.basedpython.pycharm.lsp.inject

import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
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
@RunInEdt(writeIntent = true)
class ByInjectedLanguagesTest {

    @Suppress("unused") // The language registry needs an application, which the fixture provides.
    private val fixture by codeInsightFixture()

    private val plainText get() = PlainTextLanguage.INSTANCE

    @Test
    fun `an id spelled exactly as the language registers it`() {
        assertEquals(plainText, ByInjectedLanguages.find(plainText.id))
    }

    @Test
    fun `case is not what a marker is judged on`() {
        assertEquals(plainText, ByInjectedLanguages.find(plainText.id.lowercase()))
        assertEquals(plainText, ByInjectedLanguages.find(plainText.id.uppercase()))
    }

    @Test
    fun `the name a language shows a user also names it`() {
        assertEquals(plainText, ByInjectedLanguages.find(plainText.displayName))
    }

    @Test
    fun `a file extension names the language of that file type`() {
        assertEquals(plainText, ByInjectedLanguages.find("txt"))
    }

    @Test
    fun `surrounding space is not part of the id`() {
        assertEquals(plainText, ByInjectedLanguages.find("  ${plainText.id} "))
    }

    @Test
    fun `an id nothing here provides is no language, and not an error`() {
        assertNull(ByInjectedLanguages.find("no-such-language-anywhere"))
        assertNull(ByInjectedLanguages.find(""))
        assertNull(ByInjectedLanguages.find("   "))
    }
}
