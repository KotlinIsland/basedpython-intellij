package dev.basedpython.pycharm.settings.app

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level (IDE-wide) default settings for the basedpython plugin.
 *
 * These hold the *defaults* that new projects inherit unless the project-level
 * [dev.basedpython.pycharm.settings.BasedPythonSettings] overrides them. The
 * resolution logic lives in [BasedPythonDefaults], which falls back to these
 * values whenever a project value is unset (null / blank).
 *
 * Persisted to the application config directory's `basedpython.xml` (distinct
 * from the per-project `.idea/basedpython.xml`).
 */
@Service(Service.Level.APP)
@State(name = "BasedPythonAppSettings", storages = [Storage("basedpython.xml")])
class BasedPythonAppSettings : PersistentStateComponent<BasedPythonAppSettings.State> {

  /**
   * Mirrors the application-relevant subset of the project state. Path defaults
   * are nullable so that "no global default" (autodetect) is representable.
   */
  data class State(
    var defaultByPath: String? = null,
    var defaultBuffPath: String? = null,
    var defaultByEnabled: Boolean = true,
    var defaultBuffEnabled: Boolean = true,
    var defaultByExtraArgs: String = "",
    var defaultBuffExtraArgs: String = "",
    var defaultPythonVersion: String = "3.10",
    var defaultLspTraceLevel: String = "off",
  )

  private var state = State()

  override fun getState(): State = state
  override fun loadState(loaded: State) {
    XmlSerializerUtil.copyBean(loaded, state)
  }

  var defaultByPath: String?
    get() = state.defaultByPath
    set(value) { state.defaultByPath = value }

  var defaultBuffPath: String?
    get() = state.defaultBuffPath
    set(value) { state.defaultBuffPath = value }

  var defaultByEnabled: Boolean
    get() = state.defaultByEnabled
    set(value) { state.defaultByEnabled = value }

  var defaultBuffEnabled: Boolean
    get() = state.defaultBuffEnabled
    set(value) { state.defaultBuffEnabled = value }

  var defaultByExtraArgs: String
    get() = state.defaultByExtraArgs
    set(value) { state.defaultByExtraArgs = value }

  var defaultBuffExtraArgs: String
    get() = state.defaultBuffExtraArgs
    set(value) { state.defaultBuffExtraArgs = value }

  var defaultPythonVersion: String
    get() = state.defaultPythonVersion
    set(value) { state.defaultPythonVersion = value }

  var defaultLspTraceLevel: String
    get() = state.defaultLspTraceLevel
    set(value) { state.defaultLspTraceLevel = value }

  companion object {
    @JvmStatic
    fun getInstance(): BasedPythonAppSettings =
      ApplicationManager.getApplication().service()
  }
}
