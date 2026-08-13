package dev.basedpython.pycharm.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.lsp.inlay.ByHintModes
import dev.basedpython.pycharm.lsp.inlay.ByPushKey

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
    /**
     * When each kind of inlay hint is drawn, as a
     * [dev.basedpython.pycharm.lsp.inlay.ByHintMode.id]: never, always, or only while the push key
     * is held.
     *
     * Blank means the mode was never written, and the boolean above is read instead — a project
     * configured before push-to-hint existed keeps exactly the hints it had. Writing a mode writes
     * the boolean too, so a settings file that travels back to an older plugin still says whether
     * the kind is on. Strings rather than the enum for the reason `pyFileHandling` gives: the
     * serializer throws on a constant it cannot match, so a file from a newer plugin would fail to
     * load instead of degrading.
     */
    var inlayParameterMode: String = "",
    var inlayTypeMode: String = "",
    var inlayReturnMode: String = "",
    /**
     * The kinds that used to be drawn under [inlayTypeHints] because nothing told them apart — a
     * call's type arguments (`A[int](1)`), an adornment like `override `, and whatever `by` sends
     * that the plugin cannot place.
     *
     * Blank falls back to [inlayTypeMode] rather than to a boolean, which is where these hints
     * were before they had settings of their own: a project that had variable types on push kept
     * its `[int]` hints on push too, and still does.
     */
    var inlayTypeArgumentMode: String = "",
    var inlayModifierMode: String = "",
    var inlayOtherMode: String = "",
    /** The key held to show "on push" hints, as a [dev.basedpython.pycharm.lsp.inlay.ByPushKey.id]. */
    var inlayPushKey: String = "",
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

  // ---- Inlay hint modes. Not serialised themselves; the strings above are the persisted form. ----

  var inlayParameterMode: ByHintMode
    get() = ByHintMode.resolve(state.inlayParameterMode, ByHintMode.of(state.inlayParameterHints))
    set(value) {
      state.inlayParameterMode = value.id
      state.inlayParameterHints = value != ByHintMode.NEVER
    }

  var inlayTypeMode: ByHintMode
    get() = ByHintMode.resolve(state.inlayTypeMode, ByHintMode.of(state.inlayTypeHints))
    set(value) {
      state.inlayTypeMode = value.id
      state.inlayTypeHints = value != ByHintMode.NEVER
    }

  var inlayReturnMode: ByHintMode
    get() = ByHintMode.resolve(state.inlayReturnMode, ByHintMode.of(state.inlayReturnHints))
    set(value) {
      state.inlayReturnMode = value.id
      state.inlayReturnHints = value != ByHintMode.NEVER
    }

  // The three that used to travel with variable types, and inherit from it when unset.

  var inlayTypeArgumentMode: ByHintMode
    get() = ByHintMode.resolve(state.inlayTypeArgumentMode, inlayTypeMode)
    set(value) { state.inlayTypeArgumentMode = value.id }

  var inlayModifierMode: ByHintMode
    get() = ByHintMode.resolve(state.inlayModifierMode, inlayTypeMode)
    set(value) { state.inlayModifierMode = value.id }

  var inlayOtherMode: ByHintMode
    get() = ByHintMode.resolve(state.inlayOtherMode, inlayTypeMode)
    set(value) { state.inlayOtherMode = value.id }

  /** Every kind's mode at once, which is how the hints collector reads them. */
  val inlayModes: ByHintModes
    get() = ByHintModes(
      parameter = inlayParameterMode,
      type = inlayTypeMode,
      returnType = inlayReturnMode,
      typeArgument = inlayTypeArgumentMode,
      modifier = inlayModifierMode,
      other = inlayOtherMode,
    )

  var inlayPushKey: ByPushKey
    get() = ByPushKey.fromId(state.inlayPushKey)
    set(value) { state.inlayPushKey = value.id }

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
