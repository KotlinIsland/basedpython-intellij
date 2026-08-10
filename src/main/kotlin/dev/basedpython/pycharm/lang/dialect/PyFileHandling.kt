package dev.basedpython.pycharm.lang.dialect

import com.intellij.lang.Language

/**
 * Who owns `.py` files in a basedpython project.
 *
 * The plugin re-types `.py` to basedpython so those files get highlighting and code intelligence in
 * an IDE with no Python support. In PyCharm that is the wrong trade: it takes `.py` away from a
 * Python plugin that understands it far better, in exchange for a dialect the file is not written
 * in. This setting is the choice between the two.
 *
 * Whichever way it goes, the `by` language server still attaches to `.py` in a basedpython project.
 * This is about who owns the *file type* — lexer, PSI, structure — not about who reports
 * diagnostics.
 *
 * Persisted by [id]; the ids are part of the settings file format and must not change.
 */
enum class PyFileHandling(val id: String, val display: String) {
    /**
     * Re-type `.py` only when nothing else can handle it. The default: correct in both an IDE with
     * a Python plugin and one without, with no configuration.
     */
    AUTO("auto", "Only when no Python plugin is installed"),

    /** Never re-type `.py`. Leave them to the Python plugin, or to plain text. */
    NEVER("never", "Never — leave .py to the Python plugin"),

    /** Always re-type `.py` in a basedpython project, Python plugin or not. */
    ALWAYS("always", "Always — treat .py as basedpython"),
    ;

    companion object {
        /** Unknown and blank ids degrade to [AUTO] rather than throwing. */
        fun fromId(id: String?): PyFileHandling = entries.firstOrNull { it.id == id } ?: AUTO

        /**
         * True when some other plugin already provides the Python language.
         *
         * Asks the language registry rather than looking for plugin ids, so it holds for PyCharm's
         * `PythonCore`, IDEA Ultimate's `Pythonid`, and anything else that registers Python.
         */
        fun isPythonLanguageAvailable(): Boolean = Language.findLanguageByID("Python") != null
    }
}
