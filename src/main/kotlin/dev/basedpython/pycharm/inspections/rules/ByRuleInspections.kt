package dev.basedpython.pycharm.inspections.rules

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.profile.codeInspection.InspectionProfileManager
import com.intellij.psi.PsiElement
import com.intellij.util.xmlb.annotations.XCollection
import dev.basedpython.pycharm.lsp.ext.ByRule
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * One inspection per rule `by` checks — `unresolved-reference`, `invalid-argument-type`, … — under
 * *Settings | Editor | Inspections | basedpython | by rules*, each with the rule's summary as its
 * description and its default level as its severity, switched off where the rule is `ignore`d by
 * default. What the rules are is `by/rules`' answer ([ByRuleRefresh]), kept here between sessions so
 * that the inspections exist, and a profile's settings for them are read, before `by` has started.
 *
 * The rules are `by`'s and change with it, so there is no inspection class per rule to register; each
 * is a [ByRuleInspection] added to the profiles as `by` names them, the way the platform adds a
 * structural search pattern's. They find nothing themselves: a rule's diagnostics are `by`'s, drawn by
 * the language server client, and each is matched to its rule's inspection by its `code`
 * ([ByRuleLevels]) — which is what makes a severity changed here, or a rule switched off, apply.
 *
 * Removed with the plugin: the tools come out of every profile and their keys are unregistered, so
 * nothing of the plugin's stays in the platform's registries.
 */
@Service(Service.Level.APP)
@State(name = "BasedPythonByRules", storages = [Storage(StoragePathMacros.CACHE_FILE)])
internal class ByRuleInspections : PersistentStateComponent<ByRuleInspections.State>, Disposable {

    class State {
        @XCollection(style = XCollection.Style.v2)
        var rules: MutableList<StoredRule> = mutableListOf()
    }

    /** A rule as kept between sessions. */
    class StoredRule {
        var name: String = ""
        var summary: String? = null
        var defaultLevel: String? = null
        var status: String? = null

        fun rule() = ByRule(name, summary, defaultLevel, status)

        companion object {
            fun of(rule: ByRule) = StoredRule().apply { name = rule.name; summary = rule.summary; defaultLevel = rule.defaultLevel; status = rule.status }
        }
    }

    @Volatile
    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /** The rules as `by` last named them. */
    val rules: List<ByRule> get() = state.rules.map { it.rule() }

    /**
     * Keeps [rules] as `by`'s rules from now on, and adds an inspection to every profile for each one
     * it has not got yet. Whether anything changed.
     */
    fun update(rules: List<ByRule>): Boolean {
        if (rules == this.rules) return false
        state = State().apply { this.rules = rules.map(StoredRule::of).toMutableList() }
        installEverywhere()
        return true
    }

    /** Adds the rules' inspections to the application's profiles and to every open project's. */
    fun installEverywhere() {
        install(InspectionProfileManager.getInstance(), null)
        for (project in ProjectManager.getInstance().openProjects) install(InspectionProfileManager.getInstance(project), project)
    }

    /**
     * Adds the rules' inspections that [manager]'s profiles do not have yet. A profile is initialized
     * first, so that the settings it has stored for them are what they are read from.
     */
    fun install(manager: InspectionProfileManager, project: Project?) {
        val rules = rules
        if (rules.isEmpty()) return
        for (profile in manager.profiles) {
            profile.initInspectionTools(project)
            var added = false
            for (rule in rules) {
                val shortName = ByRuleInspection.shortName(rule.name)
                if (profile.getToolsOrNull(shortName, project) != null) continue
                profile.addTool(project, ByRuleInspectionWrapper(rule), null)
                added = true
            }
            if (added) profile.profileChanged()
        }
    }

    /** Takes every rule's inspection out of the profiles of the application and of every open project, and out of the key registry. */
    override fun dispose() {
        val shortNames = rules.map { ByRuleInspection.shortName(it.name) }
        val managers = listOf(InspectionProfileManager.getInstance() to null) +
            ProjectManager.getInstance().openProjects.map { InspectionProfileManager.getInstance(it) to it }
        for ((manager, project) in managers) for (profile in manager.profiles) {
            for (shortName in shortNames) profile.getToolsOrNull(shortName, project)?.tool?.let(profile::removeTool)
        }
        for (shortName in shortNames) HighlightDisplayKey.unregister(shortName)
    }

    companion object {
        fun getInstance(): ByRuleInspections = ApplicationManager.getApplication().service()
    }
}

/**
 * A rule's inspection as a profile holds it. Its own class only so that a copy — every settings page
 * and every scope makes one — is made for the same rule: the platform copies a tool by its no-argument
 * constructor, which a rule's inspection has not got.
 */
internal class ByRuleInspectionWrapper(private val rule: ByRule) : LocalInspectionToolWrapper(ByRuleInspection(rule)) {
    override fun createCopy(): LocalInspectionToolWrapper = ByRuleInspectionWrapper(rule)
}

/**
 * A rule of `by`'s as an inspection: named and described by the rule, at the rule's default level. It
 * inspects nothing itself — see [ByRuleInspections].
 */
internal class ByRuleInspection(private val rule: ByRule) : LocalInspectionTool() {

    override fun getShortName(): String = shortName(rule.name)

    override fun getDisplayName(): String = when (rule.status) {
        PREVIEW -> BasedPythonBundle.message("rules.inspection.preview", rule.name)
        DEPRECATED -> BasedPythonBundle.message("rules.inspection.deprecated", rule.name)
        else -> rule.name
    }

    override fun getGroupDisplayName(): String = BasedPythonBundle.message("rules.inspection.group")

    override fun getGroupPath(): Array<String> = arrayOf(BasedPythonBundle.message("rules.inspection.root"), groupDisplayName)

    override fun getStaticDescription(): String = buildString {
        append("<html><body>")
        append(StringUtil.escapeXmlEntities(StringUtil.capitalize(rule.summary.orEmpty())))
        append("<p>").append(BasedPythonBundle.message("rules.inspection.description", rule.name)).append("</p>")
        append("</body></html>")
    }

    override fun getDefaultLevel(): HighlightDisplayLevel = levelOf(rule.defaultLevel) ?: HighlightDisplayLevel.WARNING

    override fun isEnabledByDefault(): Boolean = rule.defaultLevel != IGNORE

    companion object {
        private const val PREVIEW = "preview"
        private const val DEPRECATED = "deprecated"
        const val IGNORE = "ignore"

        /** The inspection's name, and the key its settings and severity are kept under. */
        fun shortName(rule: String): String = "ByRule_$rule"

        /** The rule a key of [shortName] is the inspection of, or null for any other inspection. */
        fun ruleOf(shortName: String): String? = shortName.takeIf { it.startsWith("ByRule_") }?.removePrefix("ByRule_")

        /** A `by` level as a severity; null for `ignore`, which is no severity but switched off. */
        fun levelOf(level: String?): HighlightDisplayLevel? = when (level) {
            "error" -> HighlightDisplayLevel.ERROR
            "warn" -> HighlightDisplayLevel.WARNING
            else -> null
        }

        /** A severity as the `by` level nearest it: `error` from an error up, `warn` below. */
        fun levelFor(severity: HighlightSeverity): String = if (severity >= HighlightSeverity.ERROR) "error" else "warn"
    }
}
