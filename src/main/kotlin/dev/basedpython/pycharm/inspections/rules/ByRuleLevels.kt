package dev.basedpython.pycharm.inspections.rules

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lsp.ext.ByRule

/**
 * What the inspection profile says about `by`'s rules, where it says something other than the rule's
 * own default: a rule switched off, or given a severity of its own.
 *
 * Only those. A rule whose inspection is left as it came keeps the level the project's configuration
 * gives it — a `[tool.basedpython.rules]` that makes a `warn` rule an `error` is not undone by an
 * inspection still at its default warning. So the profile's changes are sent to `by` as its
 * `configuration.rules` ([overrides]), which `by` applies above the project's configuration, and a
 * diagnostic of a changed rule is drawn at the severity the profile gives it for its file, or not at
 * all ([forDiagnostic]).
 */
internal object ByRuleLevels {

    /** What a diagnostic of a rule is drawn as. */
    sealed interface Drawn {
        /** As `by` reported it. */
        data object AsReported : Drawn

        /** Not at all: the rule's inspection is switched off for the file. */
        data object Hidden : Drawn

        /** At the severity the profile gives the rule for the file. */
        data class At(val severity: HighlightSeverity, val key: HighlightDisplayKey) : Drawn
    }

    /** How a diagnostic reported under [code] in [file] is drawn, by [project]'s current profile. */
    fun forDiagnostic(project: Project, code: String, file: PsiElement): Drawn {
        val key = HighlightDisplayKey.find(ByRuleInspection.shortName(code)) ?: return Drawn.AsReported
        val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
        val rule = ByRuleInspections.getInstance().rules.firstOrNull { it.name == code } ?: return Drawn.AsReported
        if (!changed(profile, key, rule)) return Drawn.AsReported
        if (!profile.isToolEnabled(key, file)) return Drawn.Hidden
        return Drawn.At(profile.getErrorLevel(key, file).severity, key)
    }

    /** The `by` level of each rule [profile] changes from its default, `ignore` for one switched off. */
    fun overrides(profile: InspectionProfileImpl, rules: List<ByRule>): Map<String, String> = buildMap {
        for (rule in rules) {
            val key = HighlightDisplayKey.find(ByRuleInspection.shortName(rule.name)) ?: continue
            if (!changed(profile, key, rule)) continue
            put(rule.name, if (!profile.isToolEnabled(key)) ByRuleInspection.IGNORE else ByRuleInspection.levelFor(profile.getErrorLevel(key, null as PsiElement?).severity))
        }
    }

    /** Whether [profile] has [rule]'s inspection other than as it comes: on where it is off, or at another severity. */
    private fun changed(profile: InspectionProfileImpl, key: HighlightDisplayKey, rule: ByRule): Boolean {
        val enabledByDefault = rule.defaultLevel != ByRuleInspection.IGNORE
        if (profile.isToolEnabled(key) != enabledByDefault) return true
        val tools = profile.getToolsOrNull(key.shortName, null) ?: return false
        if (tools.nonDefaultTools?.isNotEmpty() == true) return true
        val defaultLevel = ByRuleInspection.levelOf(rule.defaultLevel) ?: return false
        return enabledByDefault && profile.getErrorLevel(key, null as PsiElement?) != defaultLevel
    }
}
