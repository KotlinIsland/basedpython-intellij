package dev.basedpython.pycharm.lsp.usages

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import com.intellij.usages.Usage
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageWithType
import com.intellij.usages.rules.ImportFilteringRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Show Import Statements off hides the `.by` usages `by` typed as in an import, and no other, through
 * the rule registered for the platform's toggle.
 */
@TestFixtures
class ByImportFilteringRuleTest {

    // for the application the extension is registered in, as every test here gets one (see ByHintPushTest)
    @Suppress("unused")
    private val fixture by codeInsightFixture()

    /** A usage the usage view hands the rule, of [type] — or of none, as a usage nobody typed. */
    private fun usage(type: UsageType?, typed: Boolean = true): Usage {
        val interfaces = if (typed) arrayOf(Usage::class.java, UsageWithType::class.java) else arrayOf(Usage::class.java)
        return Proxy.newProxyInstance(javaClass.classLoader, interfaces) { proxy, method, args ->
            when (method.name) {
                "getUsageType" -> type
                "isValid" -> true
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "usage of $type"
                else -> null
            }
        } as Usage
    }

    @Test
    fun `the toggle's rule hides an import by typed, and keeps every other usage`() {
        val rule = ImportFilteringRule.EP_NAME.extensionList.filterIsInstance<ByImportFilteringRule>().single()
        val targets = emptyArray<UsageTarget>()

        assertFalse(rule.isVisible(usage(ByUsageTypes.IMPORT), targets))
        assertTrue(rule.isVisible(usage(UsageType.READ), targets))
        assertTrue(rule.isVisible(usage(null), targets), "a usage by did not classify")
        assertTrue(rule.isVisible(usage(null, typed = false), targets), "a usage of another language's, with no type")
        assertEquals(1, ImportFilteringRule.EP_NAME.extensionList.count { it is ByImportFilteringRule })
    }
}
