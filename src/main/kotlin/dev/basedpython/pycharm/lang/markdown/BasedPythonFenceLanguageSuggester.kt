package dev.basedpython.pycharm.lang.markdown

import com.intellij.lang.Language
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import org.intellij.plugins.markdown.injection.aliases.AdditionalFenceLanguageSuggester

/**
 * Resolves the info string of a markdown code fence to basedpython.
 *
 * The markdown plugin first checks its own alias table and then the ID of every registered
 * language, so ```` ```basedpython ```` already resolves on its own. The names people actually
 * write — ```` ```by ```` after the CLI and the file extension, and ```` ```bython ```` — reach
 * this suggester, which the guesser consults last.
 */
class BasedPythonFenceLanguageSuggester : AdditionalFenceLanguageSuggester {

    override fun suggestLanguage(name: String): Language? =
        if (name.lowercase() in NAMES) BasedPythonLanguage else null

    private companion object {
        val NAMES = setOf("by", "byi", "bython", "based-python")
    }
}
