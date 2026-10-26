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
 * These hold the *defaults* every project follows until its project-level
 * [dev.basedpython.pycharm.settings.BasedPythonSettings] sets a value of its own. The
 * resolution logic lives in [BasedPythonDefaults], which falls back to these
 * values whenever a project value is unset (null / blank).
 *
 * Also the IDE-wide options that have no project-level value at all, the way the platform keeps
 * its own auto-import and completion options: [addUnambiguousImportsOnTheFly],
 * [suggestImportableNames].
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
    var addUnambiguousImportsOnTheFly: Boolean = false,
    var suggestImportableNames: Boolean = true,
    var offerPostfixTemplates: Boolean = true,
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

  /**
   * Whether an import `by` says is the only one that resolves an unresolved name is added as the
   * code is typed — *Settings | Editor | General | Auto Import*, see
   * [dev.basedpython.pycharm.lsp.imports.ByReferenceImporter]. IDE-wide, as every language's
   * auto-import options are, and off until switched on, as Java's and Kotlin's are.
   */
  var addUnambiguousImportsOnTheFly: Boolean
    get() = state.addUnambiguousImportsOnTheFly
    set(value) { state.addUnambiguousImportsOnTheFly = value }

  /**
   * Whether completion offers the names of modules a file does not import yet, with the import —
   * *Settings | Editor | General | Code Completion*, see
   * [dev.basedpython.pycharm.lsp.completion.ByCodeCompletionOptions], sent as `by`'s
   * `completions.autoImport`. On, as `by`'s own default and `.py`'s *Suggest importable names*.
   */
  var suggestImportableNames: Boolean
    get() = state.suggestImportableNames
    set(value) { state.suggestImportableNames = value }

  /**
   * Whether completion lists `by`'s postfix templates, `xs.for` and the rest — *Settings | Editor |
   * General | Code Completion*, see [dev.basedpython.pycharm.lsp.completion.ByPostfixCompletion]. On,
   * as postfix completion is for every language until switched off.
   */
  var offerPostfixTemplates: Boolean
    get() = state.offerPostfixTemplates
    set(value) { state.offerPostfixTemplates = value }

  companion object {
    @JvmStatic
    fun getInstance(): BasedPythonAppSettings =
      ApplicationManager.getApplication().service()
  }
}
