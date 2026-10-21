package dev.basedpython.pycharm.inspections.grazie

import com.intellij.grazie.text.ProblemFilter
import com.intellij.grazie.text.RuleGroup
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextProblem
import com.intellij.grazie.utils.ProblemFilterUtil

/**
 * The grammar findings that do not fit how a docstring is written.
 *
 * A docstring is very often one line in the imperative — `"""Return the greeting."""` — or a
 * phrase with no full stop, or a list of `name: what it is` lines under a heading; the checker reads
 * each of those as an incomplete or undecorated sentence. These are the findings Python's own
 * docstrings are spared in PyCharm, for the same reason: a missing capital or full stop on a
 * single-sentence docstring, and sentences run together without punctuation between list lines.
 *
 * Comments are left to the platform's own filter, which already makes the same allowances for
 * them.
 */
internal class BasedPythonProblemFilter : ProblemFilter() {

    override fun shouldIgnore(problem: TextProblem): Boolean {
        if (problem.text.domain != TextDomain.DOCUMENTATION) return false
        return ProblemFilterUtil.isUndecoratedSingleSentenceIssue(problem) ||
            ProblemFilterUtil.isInitialCasingIssue(problem) ||
            problem.fitsGroup(RuleGroup(RuleGroup.UNDECORATED_SENTENCE_SEPARATION))
    }
}
