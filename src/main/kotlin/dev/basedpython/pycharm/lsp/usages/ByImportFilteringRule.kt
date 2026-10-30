package dev.basedpython.pycharm.lsp.usages

import com.intellij.usages.Usage
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageWithType
import com.intellij.usages.rules.ImportFilteringRule

/**
 * Find Usages' and Show Usages' *Show Import Statements* toggle for `.by` usages: off, a usage `by`
 * says is in an import (`by/references`' `import`, [ByUsageTypes.IMPORT]) is hidden, as PyCharm's
 * rule hides a `.py`'s.
 *
 * The toggle is the platform's, offered when any language registers a rule — Python's in PyCharm,
 * Java's in IntelliJ IDEA — and each rule hides what it finds in its own language's PSI, so with the
 * toggle off every `.by` import was still listed (measured in PyCharm 263.7649). Whether a usage is
 * in an import is `by`'s to say, and is the type each `.by` usage already carries, which the
 * platform's usage view hands on through [UsageWithType]. A `by` without `by/references` types no
 * usage, and has none hidden.
 */
internal class ByImportFilteringRule : ImportFilteringRule() {
    override fun isVisible(usage: Usage, targets: Array<out UsageTarget>): Boolean =
        (usage as? UsageWithType)?.usageType != ByUsageTypes.IMPORT
}
