package dev.basedpython.pycharm.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level persistent settings for the basedpython plugin.
 *
 * Persisted to `.idea/basedpython.xml`. Null `byPath` / `buffPath` means
 * "autodetect" — see [dev.basedpython.pycharm.lsp.BasedPythonBinaries].
 */
@Service(Service.Level.PROJECT)
@State(name = "BasedPythonSettings", storages = [Storage("basedpython.xml")])
class BasedPythonSettings : PersistentStateComponent<BasedPythonSettings.State> {

  data class State(
    var byPath: String? = null,
    var buffPath: String? = null,
    var byEnabled: Boolean = true,
    var buffEnabled: Boolean = true,
    var byExtraArgs: String = "",
    var buffExtraArgs: String = "",
    var pythonVersion: String = "3.10",
    var formatOnSave: Boolean = false,
    var inlayParameterHints: Boolean = true,
    var inlayTypeHints: Boolean = true,
    var inlayReturnHints: Boolean = true,
    var lspTraceLevel: String = "off",
    /**
     * When true, the generated `out/` directory is NOT excluded from indexing,
     * so a Python plugin (PyCharm, or IDEA with the Python plugin) provides full
     * native code intelligence on the transpiled `.py` files. Off by default to
     * keep `.by` files as the single source of truth and avoid duplicate symbols.
     */
    var indexGeneratedPython: Boolean = false,
    /**
     * Who owns `.py` files, as a [dev.basedpython.pycharm.lang.dialect.PyFileHandling.id].
     *
     * A `String` rather than the enum for the same reason as `ByCommonOptions.environment`: the
     * serializer would persist the constant name and throw on any value it cannot match, so a
     * settings file written by a newer plugin would fail to load instead of degrading.
     */
    var pyFileHandling: String = "",
    // ---- Per-server capability toggles (§142). All default-on. ----
    // `by` server capabilities:
    var byCompletion: Boolean = true,
    var byGoToDefinition: Boolean = true,
    var byFindReferences: Boolean = true,
    var byRename: Boolean = true,
    var bySemanticTokens: Boolean = true,
    var byCodeLens: Boolean = true,
    var byDocumentHighlight: Boolean = true,
    var bySignatureHelp: Boolean = true,
    // `buff` server capabilities:
    var buffFormatting: Boolean = true,
    var buffCodeActions: Boolean = true,
    var buffHover: Boolean = true,
  )

  private var state = State()

  override fun getState(): State = state
  override fun loadState(loaded: State) {
    XmlSerializerUtil.copyBean(loaded, state)
  }

  var byPath: String?
    get() = state.byPath
    set(value) { state.byPath = value }

  var buffPath: String?
    get() = state.buffPath
    set(value) { state.buffPath = value }

  var byEnabled: Boolean
    get() = state.byEnabled
    set(value) { state.byEnabled = value }

  var buffEnabled: Boolean
    get() = state.buffEnabled
    set(value) { state.buffEnabled = value }

  var byExtraArgs: String
    get() = state.byExtraArgs
    set(value) { state.byExtraArgs = value }

  var buffExtraArgs: String
    get() = state.buffExtraArgs
    set(value) { state.buffExtraArgs = value }

  var pythonVersion: String
    get() = state.pythonVersion
    set(value) { state.pythonVersion = value }

  var formatOnSave: Boolean
    get() = state.formatOnSave
    set(value) { state.formatOnSave = value }

  var inlayParameterHints: Boolean
    get() = state.inlayParameterHints
    set(value) { state.inlayParameterHints = value }

  var inlayTypeHints: Boolean
    get() = state.inlayTypeHints
    set(value) { state.inlayTypeHints = value }

  var inlayReturnHints: Boolean
    get() = state.inlayReturnHints
    set(value) { state.inlayReturnHints = value }

  var lspTraceLevel: String
    get() = state.lspTraceLevel
    set(value) { state.lspTraceLevel = value }

  var indexGeneratedPython: Boolean
    get() = state.indexGeneratedPython
    set(value) { state.indexGeneratedPython = value }

  /** [State.pyFileHandling] as an enum. Not serialised — the string is the persisted form. */
  var pyFileHandling: dev.basedpython.pycharm.lang.dialect.PyFileHandling
    get() = dev.basedpython.pycharm.lang.dialect.PyFileHandling.fromId(state.pyFileHandling)
    set(value) {
      state.pyFileHandling =
        if (value == dev.basedpython.pycharm.lang.dialect.PyFileHandling.AUTO) "" else value.id
    }

  // ---- Per-server capability toggles (§142) ----

  var byCompletion: Boolean
    get() = state.byCompletion
    set(value) { state.byCompletion = value }
  var byGoToDefinition: Boolean
    get() = state.byGoToDefinition
    set(value) { state.byGoToDefinition = value }
  var byFindReferences: Boolean
    get() = state.byFindReferences
    set(value) { state.byFindReferences = value }
  var byRename: Boolean
    get() = state.byRename
    set(value) { state.byRename = value }
  var bySemanticTokens: Boolean
    get() = state.bySemanticTokens
    set(value) { state.bySemanticTokens = value }
  var byCodeLens: Boolean
    get() = state.byCodeLens
    set(value) { state.byCodeLens = value }
  var byDocumentHighlight: Boolean
    get() = state.byDocumentHighlight
    set(value) { state.byDocumentHighlight = value }
  var bySignatureHelp: Boolean
    get() = state.bySignatureHelp
    set(value) { state.bySignatureHelp = value }
  var buffFormatting: Boolean
    get() = state.buffFormatting
    set(value) { state.buffFormatting = value }
  var buffCodeActions: Boolean
    get() = state.buffCodeActions
    set(value) { state.buffCodeActions = value }
  var buffHover: Boolean
    get() = state.buffHover
    set(value) { state.buffHover = value }

  // ---- Effective values: project value, else IDE-wide default (see settings.app) ----

  /** `by` path: project value, or the application-level default, or null (autodetect). */
  val effectiveByPath: String?
    get() = dev.basedpython.pycharm.settings.app.BasedPythonDefaults.effectiveByPath(state.byPath)

  /** `buff` path: project value, or the application-level default, or null (autodetect). */
  val effectiveBuffPath: String?
    get() = dev.basedpython.pycharm.settings.app.BasedPythonDefaults.effectiveBuffPath(state.buffPath)

  val effectiveByExtraArgs: String
    get() = dev.basedpython.pycharm.settings.app.BasedPythonDefaults.effectiveByExtraArgs(state.byExtraArgs)

  val effectiveBuffExtraArgs: String
    get() = dev.basedpython.pycharm.settings.app.BasedPythonDefaults.effectiveBuffExtraArgs(state.buffExtraArgs)

  val effectivePythonVersion: String
    get() = dev.basedpython.pycharm.settings.app.BasedPythonDefaults.effectivePythonVersion(state.pythonVersion)

  val effectiveLspTraceLevel: String
    get() = dev.basedpython.pycharm.settings.app.BasedPythonDefaults.effectiveLspTraceLevel(state.lspTraceLevel)

  companion object {
    @JvmStatic
    fun getInstance(project: Project): BasedPythonSettings = project.service()
  }
}
