package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.util.xmlb.annotations.Transient
import dev.basedpython.pycharm.env.ByEnvironmentKind

/**
 * Common option fields shared by all `by` run configurations.
 * Persisted by IntelliJ via reflective getter/setter scan on this RunConfigurationOptions subclass.
 */
open class ByCommonOptions : RunConfigurationOptions() {
    private val workingDirProp = string("").provideDelegate(this, "workingDir")
    private val extraArgsProp = string("").provideDelegate(this, "extraArgs")
    private val pythonVersionProp = string("").provideDelegate(this, "pythonVersion")
    private val environmentProp = string("").provideDelegate(this, "environment")

    var workingDir: String
        get() = workingDirProp.getValue(this) ?: ""
        set(v) { workingDirProp.setValue(this, v) }
    var extraArgs: String
        get() = extraArgsProp.getValue(this) ?: ""
        set(v) { extraArgsProp.setValue(this, v) }
    var pythonVersion: String
        get() = pythonVersionProp.getValue(this) ?: ""
        set(v) { pythonVersionProp.setValue(this, v) }

    /**
     * Which environment source resolves `by`, as a [ByEnvironmentKind.id].
     *
     * Deliberately a `String` rather than the enum itself. The serializer converts values with
     * `toString()` and back with a strict name lookup, so an enum-typed property would persist the
     * *constant name* (ignoring [ByEnvironmentKind.id]) and, worse, would throw on any value it
     * cannot match — a configuration written by a newer plugin and opened by an older one would fail
     * to load rather than degrade. Round-tripping the id through [ByEnvironmentKind.fromId] keeps
     * unknown values falling back to [ByEnvironmentKind.AUTO].
     *
     * [ByEnvironmentKind.AUTO] persists as `""` so it matches the property default and adds no
     * `<option>` line to configurations that never changed it.
     */
    var environment: String
        get() = environmentProp.getValue(this) ?: ""
        set(v) { environmentProp.setValue(this, v) }

    /** [environment] as a kind. Not serialised — [environment] is the persisted form. */
    @get:Transient
    @set:Transient
    var environmentKind: ByEnvironmentKind
        get() = ByEnvironmentKind.fromId(environment)
        set(v) { environment = if (v == ByEnvironmentKind.AUTO) "" else v.id }

    // env vars are not persisted via StoredProperty (no map support);
    // EnvironmentVariablesComponent handles its own (de)serialization at the editor level
    // and `ByCommandLineState` reads from these in-memory fields.
    var envVars: MutableMap<String, String> = linkedMapOf()
    var passParentEnv: Boolean = true
}

class ByRunOptions : ByCommonOptions() {
    private val moduleProp = string("").provideDelegate(this, "module")
    var module: String
        get() = moduleProp.getValue(this) ?: ""
        set(v) { moduleProp.setValue(this, v) }
}

class ByBuildOptions : ByCommonOptions()

class ByCheckOptions : ByCommonOptions() {
    private val pathsProp = string("").provideDelegate(this, "paths")
    var paths: String
        get() = pathsProp.getValue(this) ?: ""
        set(v) { pathsProp.setValue(this, v) }
}
