package dev.basedpython.pycharm.lang.markdown

import dev.basedpython.pycharm.lang.BasedPythonLanguage
import junit.framework.TestCase

/**
 * Which markdown code fence info strings resolve to basedpython.
 *
 * `basedpython` is not in the list on purpose: the markdown plugin matches registered language IDs
 * before it consults suggesters, so that name already resolves without us.
 */
class BasedPythonFenceLanguageSuggesterTest : TestCase() {

    private val suggester = BasedPythonFenceLanguageSuggester()

    fun `test the CLI and extension names resolve`() {
        assertEquals(BasedPythonLanguage, suggester.suggestLanguage("by"))
        assertEquals(BasedPythonLanguage, suggester.suggestLanguage("bython"))
        assertEquals(BasedPythonLanguage, suggester.suggestLanguage("byi"))
    }

    fun `test matching ignores case`() {
        assertEquals(BasedPythonLanguage, suggester.suggestLanguage("BY"))
        assertEquals(BasedPythonLanguage, suggester.suggestLanguage("Bython"))
    }

    fun `test unrelated names are left alone`() {
        // `python` in particular must keep resolving to whatever the Python plugin registers.
        assertNull(suggester.suggestLanguage("python"))
        assertNull(suggester.suggestLanguage("py"))
        assertNull(suggester.suggestLanguage("ruby"))
        assertNull(suggester.suggestLanguage(""))
    }
}
