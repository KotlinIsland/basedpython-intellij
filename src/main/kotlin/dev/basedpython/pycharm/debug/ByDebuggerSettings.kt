package dev.basedpython.pycharm.debug

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.xdebugger.settings.DebuggerSettingsCategory
import com.intellij.xdebugger.settings.XDebuggerSettings
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlin.reflect.KMutableProperty0

/**
 * *Settings | Build, Execution, Deployment | Debugger*, for a `.by` session under bpd: the launch
 * arguments bpd reads that DAP has no request for.
 *
 * Only what `bpd_dap::Configuration` reads, and only what can work from this IDE. That leaves out
 * more than it might seem to:
 *
 *  - **no step filters, no "step into my code"**. bpd has neither: nothing in its launch
 *    configuration filters stepping, so a checkbox for it would be one bpd never reads
 *  - **no `debugChildren`**. bpd refuses it at `launch` unless the client says in `initialize`
 *    that it answers the `startDebugging` reverse request, and the platform's client says so only
 *    when a customization supplies that support, which this plugin's does not — so turning it on
 *    would refuse every session
 *  - **no `console`**. A terminal is the `runInTerminal` reverse request, refused the same way, and
 *    a `.by` program's console is the run configuration's own anyway ([ByDebugAdapterDescriptor])
 *
 * What is left: whether a stop holds every thread, and how much of a value bpd reads. The value
 * bounds are worth a page of their own because bpd says when one bit — a value it cut short reads
 * `100 children, of which the request allowed 100. ask again with a larger children` — and a user
 * needs somewhere to give it a larger one.
 *
 * A bound left empty is bpd's own default, which is bpd's to choose: copying its numbers here would
 * be a second copy that drifts. Read when a session starts ([ByDapLaunchArgumentsProvider]), so a
 * change applies from the next session. debugpy reads none of it.
 */
class ByDebuggerSettings : XDebuggerSettings<ByDebuggerSettings.State>(ID) {

    class State {
        /** `stopTheWorld`: every thread is held for the length of a stop, not only the one that stopped. */
        var stopTheWorld: Boolean = false

        /**
         * `variables.depth`, or null for bpd's own. What an evaluated value — Evaluate, a watch, a
         * value tooltip — is read to. The Variables view is not: bpd reads a scope one level per
         * expansion and applies the level reached, so measured live, `a.b` there reads "the depth of
         * 1 that was applied ran out here" with this at 5, while evaluating `a` at 1 cuts it at `b`.
         */
        var depth: Int? = null

        /** `variables.children`, or null for bpd's own. */
        var children: Int? = null

        /** `variables.text`, or null for bpd's own. */
        var text: Int? = null

        /** `variables.budget`, or null for bpd's own. */
        var budget: Int? = null

        /**
         * `variables.attributes`: read an object's instance dictionary. On, as in bpd, because for an
         * ordinary object it is a slot read that runs nothing.
         */
        var attributes: Boolean = true

        /**
         * `variables.repr`: call `__repr__` on a value with no structure of its own. Off, as in bpd,
         * because it runs the program's code, which can hang it or change it.
         */
        var repr: Boolean = false
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    override fun createConfigurables(category: DebuggerSettingsCategory): Collection<Configurable> = when (category) {
        DebuggerSettingsCategory.GENERAL -> listOf(GeneralConfigurable(state))
        DebuggerSettingsCategory.DATA_VIEWS -> listOf(DataViewsConfigurable(state))
        else -> emptyList()
    }

    /**
     * What bpd's `launch` gets from these settings: `stopTheWorld`, and a `variables` object naming
     * only the bounds that are set. Every key is one `bpd_dap::Configuration` reads, and bpd refuses
     * a misspelled one rather than ignoring it.
     */
    fun launchArguments(): Map<String, Any> {
        val variables = buildMap<String, Any> {
            state.depth?.let { put("depth", it) }
            state.children?.let { put("children", it) }
            state.text?.let { put("text", it) }
            state.budget?.let { put("budget", it) }
            put("attributes", state.attributes)
            put("repr", state.repr)
        }
        return mapOf("stopTheWorld" to state.stopTheWorld, "variables" to variables)
    }

    private class GeneralConfigurable(private val state: State) :
        BoundConfigurable(BasedPythonBundle.message("debug.settings.displayName")) {
        override fun createPanel(): DialogPanel = panel {
            group(BasedPythonBundle.message("debug.settings.displayName")) {
                row {
                    checkBox(BasedPythonBundle.message("debug.settings.stopTheWorld"))
                        .bindSelected(state::stopTheWorld)
                        .comment(BasedPythonBundle.message("debug.settings.stopTheWorld.comment"))
                }
            }
        }
    }

    private class DataViewsConfigurable(private val state: State) :
        BoundConfigurable(BasedPythonBundle.message("debug.settings.displayName")) {
        override fun createPanel(): DialogPanel = panel {
            row { comment(BasedPythonBundle.message("debug.settings.values.comment")) }
            bound("debug.settings.depth", state::depth)
            bound("debug.settings.children", state::children)
            bound("debug.settings.text", state::text)
            bound("debug.settings.budget", state::budget)
            row {
                checkBox(BasedPythonBundle.message("debug.settings.attributes"))
                    .bindSelected(state::attributes)
                    .comment(BasedPythonBundle.message("debug.settings.attributes.comment"))
            }
            row {
                checkBox(BasedPythonBundle.message("debug.settings.repr"))
                    .bindSelected(state::repr)
                    .comment(BasedPythonBundle.message("debug.settings.repr.comment"))
            }
        }

        /** A bound as a number field, empty for bpd's default. */
        private fun Panel.bound(key: String, property: KMutableProperty0<Int?>) {
            row(BasedPythonBundle.message(key)) {
                textField()
                    .columns(10)
                    .bindText(
                        { property.get()?.toString().orEmpty() },
                        { property.set(it.trim().toIntOrNull()?.takeIf { value -> value >= 0 }) },
                    )
                    .validationOnInput {
                        val text = it.text.trim()
                        if (text.isEmpty() || text.toIntOrNull()?.takeIf { value -> value >= 0 } != null) null
                        else error(BasedPythonBundle.message("debug.settings.bound.invalid"))
                    }
                    .applyToComponent { emptyText.text = BasedPythonBundle.message("debug.settings.bound.default") }
            }
        }
    }

    companion object {
        const val ID: String = "basedpython"

        fun getInstance(): ByDebuggerSettings = getInstance(ByDebuggerSettings::class.java)
    }
}
