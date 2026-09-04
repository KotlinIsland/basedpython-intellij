package dev.basedpython.pycharm.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.lsp.inlay.ByHintKind
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.lsp.inlay.ByHintModes
import dev.basedpython.pycharm.lsp.inlay.ByHintShape
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
    /**
     * Apply every fix the project's lint configuration asks for when a document is saved.
     *
     * There is no companion "format on save": *Reformat code* and *Optimize imports* are rows the
     * platform already offers on the same page, and both reach `buff` for the files this plugin
     * owns, so this plugin only adds what they do not cover.
     */
    var fixAllOnSave: Boolean = false,
    /** Apply every fix the project's lint configuration asks for across the files being committed. */
    var fixAllOnCommit: Boolean = false,
    /**
     * The two toggles that came before the per-kind modes, kept as what an unset kind falls back
     * to: a project configured before this reads exactly as it did, its parameter-ish hints
     * following the first and everything else the second.
     */
    var inlayParameterHints: Boolean = true,
    var inlayTypeHints: Boolean = true,
    /**
     * When each kind of inlay hint is drawn, keyed by the name `by` gives that kind and valued by a
     * [dev.basedpython.pycharm.lsp.inlay.ByHintMode.id].
     *
     * A map rather than a field per kind because the kinds are the server's list, not this
     * plugin's: `by` grows one and the plugin should follow without a settings migration. A kind
     * that is not in the map has never been set and falls back as described above; a key this
     * plugin does not know is left alone rather than dropped, so a settings file written by a newer
     * plugin survives a round trip through an older one.
     */
    var inlayHintModes: MutableMap<String, String> = mutableMapOf(),
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
    /**
     * Read a string `by` says holds another language as that language.
     *
     * Not an LSP capability like its neighbours — `by/injections` is a protocol extension and the
     * injecting is the plugin's — but the same kind of switch, and the one people reach for: an
     * editor that turns a string into html the moment a parameter is annotated somewhere else is a
     * surprise worth being able to turn off.
     */
    var byLanguageInjection: Boolean = true,
    var bySignatureHelp: Boolean = true,
    /**
     * Draw what a stopped program's own state settles about the code below it.
     *
     * Off by default. It costs a round trip to the debuggee on every stop and every step, and it
     * only says anything at all when a debugger is attached — a user who has not asked for it
     * should not be paying for it.
     */
    var debuggerDataFlow: Boolean = false,
    /**
     * Which debugger drives a `.by` session — `bpd` or `debugpy`.
     *
     * A `String` rather than the enum for the same reason `pyFileHandling` is: the serializer
     * persists a constant's name and throws on one it cannot match, so a settings file written by
     * a newer plugin would fail to load instead of falling back. `ByDebugBackend.of` does the
     * falling back.
     */
    var debugBackend: String = "bpd",
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

  var fixAllOnSave: Boolean
    get() = state.fixAllOnSave
    set(value) { state.fixAllOnSave = value }

  var fixAllOnCommit: Boolean
    get() = state.fixAllOnCommit
    set(value) { state.fixAllOnCommit = value }

  var inlayParameterHints: Boolean
    get() = state.inlayParameterHints
    set(value) { state.inlayParameterHints = value }

  var inlayTypeHints: Boolean
    get() = state.inlayTypeHints
    set(value) { state.inlayTypeHints = value }

  /**
   * The mode [kind] is on: what the settings file says, or what the toggle that covered it before
   * the modes existed said.
   */
  fun inlayMode(kind: ByHintKind): ByHintMode =
    ByHintMode.resolve(state.inlayHintModes[kind.settingsKey], legacyInlayMode(kind))

  fun setInlayMode(kind: ByHintKind, mode: ByHintMode) {
    state.inlayHintModes[kind.settingsKey] = mode.id
  }

  /**
   * Which of the two old toggles covered [kind].
   *
   * They were "parameter name hints" and "variable type hints", and between them they switched
   * everything `by` sent: the parameter-shaped hints went with the first and the rest with the
   * second, whatever they actually were.
   */
  private fun legacyInlayMode(kind: ByHintKind): ByHintMode = when (kind.shape) {
    ByHintShape.ARGUMENT_NAME, ByHintShape.IMPLICIT_PARAMETER, ByHintShape.IMPLICIT_ARGUMENT ->
      ByHintMode.of(state.inlayParameterHints)
    else -> ByHintMode.of(state.inlayTypeHints)
  }

  /** Every kind's mode at once, which is how the hints collector and the server config read them. */
  val inlayModes: ByHintModes
    get() = ByHintModes(ByHintKind.entries.associateWith { inlayMode(it) })

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
  var byLanguageInjection: Boolean
    get() = state.byLanguageInjection
    set(value) { state.byLanguageInjection = value }
  var bySignatureHelp: Boolean
    get() = state.bySignatureHelp
    set(value) { state.bySignatureHelp = value }
  var debuggerDataFlow: Boolean
    get() = state.debuggerDataFlow
    set(value) { state.debuggerDataFlow = value }
  var debugBackend: ByDebugBackend
    get() = ByDebugBackend.of(state.debugBackend)
    set(value) { state.debugBackend = ByDebugBackend.settingFor(value) }
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
