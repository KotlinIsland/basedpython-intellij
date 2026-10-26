package dev.basedpython.pycharm.inspections.rules

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByRule
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `by`'s rules as inspections: one per rule in the profile, at the rule's default level, and what a
 * profile changes about one is what a diagnostic of the rule is drawn as, and what `by` is told.
 */
@TestFixtures
class ByRuleInspectionsTest {

    private val fixture by codeInsightFixture()

    private val rules = listOf(
        ByRule("unresolved-reference", "detects references to names that are not defined", "error", "stable"),
        ByRule("possibly-unresolved-reference", "detects references to possibly undefined names", "warn", "stable"),
        ByRule("unused-ignore-comment", "detects unused ignore comments", "ignore", "preview"),
    )

    private fun profile(): InspectionProfileImpl {
        ByRuleInspections.getInstance().update(rules)
        ByRuleInspections.getInstance().installEverywhere()
        return InspectionProjectProfileManager.getInstance(fixture.project).currentProfile
    }

    @Test
    fun `each rule is an inspection at its default level, ignored ones switched off`() = onEdt {
        val profile = profile()
        val tool = profile.getInspectionTool("ByRule_unresolved-reference", fixture.project)
        assertNotNull(tool)
        assertEquals("unresolved-reference", tool!!.displayName)
        assertEquals(listOf("basedpython", "by rules"), tool.groupPath.toList())
        assertTrue(tool.loadDescription()!!.contains("Detects references to names that are not defined"))
        assertEquals(HighlightDisplayLevel.ERROR, profile.getErrorLevel(HighlightDisplayKey.find("ByRule_unresolved-reference")!!, null as com.intellij.psi.PsiElement?))
        assertTrue(profile.isToolEnabled(HighlightDisplayKey.find("ByRule_possibly-unresolved-reference")))
        assertFalse(profile.isToolEnabled(HighlightDisplayKey.find("ByRule_unused-ignore-comment")))
        assertEquals("unused-ignore-comment (preview)", profile.getInspectionTool("ByRule_unused-ignore-comment", fixture.project)!!.displayName)
    }

    @Test
    fun `a rule's inspection copies as the same rule, as every settings page and scope copies it`() = onEdt {
        // the platform's own copy instantiates the tool's class with no arguments, which a rule's inspection has not got
        val copy = ByRuleInspectionWrapper(rules[0]).createCopy()
        assertEquals("ByRule_unresolved-reference", copy.shortName)
        assertEquals("unresolved-reference", copy.displayName)
    }

    @Test
    fun `only what the profile changes is told to by, and drawn its way`() = onEdt {
        val profile = profile()
        val file = fixture.configureByText("a.by", "x = 1\n")
        assertEquals(emptyMap<String, String>(), ByRuleLevels.overrides(profile, rules), "a profile as it comes leaves every rule to the project's configuration")
        assertEquals(ByRuleLevels.Drawn.AsReported, ByRuleLevels.forDiagnostic(fixture.project, "unresolved-reference", file))

        val unresolved = HighlightDisplayKey.find("ByRule_unresolved-reference")!!
        val unused = HighlightDisplayKey.find("ByRule_unused-ignore-comment")!!
        val possibly = HighlightDisplayKey.find("ByRule_possibly-unresolved-reference")!!
        try {
            profile.setErrorLevel(unresolved, HighlightDisplayLevel.WEAK_WARNING, fixture.project)
            profile.setToolEnabled(unused.shortName, true, fixture.project)
            profile.setToolEnabled(possibly.shortName, false, fixture.project)

            assertEquals(
                mapOf("unresolved-reference" to "warn", "unused-ignore-comment" to "warn", "possibly-unresolved-reference" to "ignore"),
                ByRuleLevels.overrides(profile, rules),
            )
            assertEquals(ByRuleLevels.Drawn.At(HighlightSeverity.WEAK_WARNING, unresolved), ByRuleLevels.forDiagnostic(fixture.project, "unresolved-reference", file))
            assertEquals(ByRuleLevels.Drawn.Hidden, ByRuleLevels.forDiagnostic(fixture.project, "possibly-unresolved-reference", file))
            assertEquals(ByRuleLevels.Drawn.AsReported, ByRuleLevels.forDiagnostic(fixture.project, "not-a-rule", file))
        } finally {
            profile.setErrorLevel(unresolved, HighlightDisplayLevel.ERROR, fixture.project)
            profile.setToolEnabled(unused.shortName, false, fixture.project)
            profile.setToolEnabled(possibly.shortName, true, fixture.project)
        }
    }
}
