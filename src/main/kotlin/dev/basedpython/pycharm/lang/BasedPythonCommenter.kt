package dev.basedpython.pycharm.lang

import com.intellij.codeInsight.generation.IndentedCommenter
import com.intellij.lang.Commenter

/**
 * `#` comments, put where Python's go: at the line's indent, with a space after the `#`.
 *
 * *Comment with Line Comment* puts the prefix at column 0 unless the commenter forces it indented
 * ([forceIndentedLineComment]), which is the half this class says, as PyCharm's own
 * `PythonCommenter` does. The space is the language code style's `LINE_COMMENT_ADD_SPACE`, which
 * [dev.basedpython.pycharm.format.BuffLanguageCodeStyleSettingsProvider] turns on by default,
 * rather than a prefix of `"# "` as Python spells it: the Copyright plugin draws a notice's box out
 * of the prefix's last character, and a `"# "` prefix made it a box of spaces with the text
 * outside the comment. Uncommenting takes a `#` off with or without the space.
 */
class BasedPythonCommenter : Commenter, IndentedCommenter {
    override fun getLineCommentPrefix(): String = "#"
    override fun getBlockCommentPrefix(): String? = null
    override fun getBlockCommentSuffix(): String? = null
    override fun getCommentedBlockCommentPrefix(): String? = null
    override fun getCommentedBlockCommentSuffix(): String? = null
    override fun forceIndentedLineComment(): Boolean = true
}
