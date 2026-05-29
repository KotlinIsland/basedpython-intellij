package dev.basedpython.pycharm.run

import com.intellij.execution.configurations.RunConfigurationOptions

/**
 * Common option fields shared by all `by` run configurations.
 * Persisted by IntelliJ via reflective getter/setter scan on this RunConfigurationOptions subclass.
 */
open class ByCommonOptions : RunConfigurationOptions() {
    private val workingDirProp = string("").provideDelegate(this, "workingDir")
    private val extraArgsProp = string("").provideDelegate(this, "extraArgs")
    private val pythonVersionProp = string("").provideDelegate(this, "pythonVersion")

    var workingDir: String
        get() = workingDirProp.getValue(this) ?: ""
        set(v) { workingDirProp.setValue(this, v) }
    var extraArgs: String
        get() = extraArgsProp.getValue(this) ?: ""
        set(v) { extraArgsProp.setValue(this, v) }
    var pythonVersion: String
        get() = pythonVersionProp.getValue(this) ?: ""
        set(v) { pythonVersionProp.setValue(this, v) }

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
