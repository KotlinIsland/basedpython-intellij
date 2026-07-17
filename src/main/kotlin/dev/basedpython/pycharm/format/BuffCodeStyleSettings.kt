package dev.basedpython.pycharm.format

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import dev.basedpython.pycharm.lang.BasedPythonLanguage

/**
 * Persisted code-style options for basedpython files.
 *
 * These values shadow buff's own configuration; the panel is read-friendly
 * (shows what buff will do) but also writes here so project-level overrides
 * are possible.
 *
 * Stored in the IDE's `*.xml` code style scheme, keyed by [CONTAINER_NAME].
 */
class BuffCodeStyleSettings(container: CodeStyleSettings) :
    CustomCodeStyleSettings(CONTAINER_NAME, container) {

    /** Maximum line length passed to buff (`--line-length`). */
    @JvmField
    var lineLength: Int = DEFAULT_LINE_LENGTH

    /**
     * Quote style preference:
     *  0 = double (buff default)
     *  1 = single
     *  2 = preserve (no normalisation)
     */
    @JvmField
    var quoteStyle: Int = QUOTE_DOUBLE

    companion object {
        const val CONTAINER_NAME = "BuffCodeStyleSettings"

        const val DEFAULT_LINE_LENGTH = 88  // buff/ruff default
        const val QUOTE_DOUBLE = 0
        const val QUOTE_SINGLE = 1
        const val QUOTE_PRESERVE = 2

        val QUOTE_OPTIONS = arrayOf("Double (“\"”)", "Single (‘'’)", "Preserve")
    }
}
