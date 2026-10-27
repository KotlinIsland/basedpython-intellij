package dev.basedpython.pycharm.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.BaseProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspIntegrationProvider.LspClientStarter
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeActionsDisabled
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentColorDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.intellij.platform.lsp.api.customization.LspHoverDisabled
import com.intellij.platform.lsp.api.customization.LspOptimizeImportsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentHighlightsSupport
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolSupport
import com.intellij.platform.lsp.api.customization.LspFindReferencesDisabled
import com.intellij.platform.lsp.api.customization.LspFoldingRangeDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersCustomizer
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersDisabled
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.customization.LspOnTypeFormattingSupport
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.customization.LspSelectionRangeDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled
import com.intellij.platform.lsp.api.customization.LspTypeHierarchyDisabled
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.debug.dfa.ByDataFlowServer
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.diagnostics.ByDiagnosticsSupport
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.inspections.rules.ByRuleConfiguration
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.ui.log.BasedPythonLog
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.ClientCapabilities

private val LOG = Logger.getInstance("dev.basedpython.pycharm.lsp")

/**
 * Mirrors a language server's stderr into the "basedpython" tool window.
 *
 * The servers log to stderr (stdout carries the LSP protocol itself, so it must not be touched).
 * The platform already forwards that to idea.log, but the tool window has its own console, and
 * nothing was writing to it — which is why "Show Logs" opened an empty window.
 *
 * Attaching a listener is additive and does not consume the stream, so the platform's own reader is
 * unaffected.
 */
private fun BaseProcessHandler<*>.mirrorStderrTo(project: Project, serverName: String): BaseProcessHandler<*> {
    val log = BasedPythonLog.getInstance(project)
    addProcessListener(object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType != ProcessOutputTypes.STDERR) return
            val text = event.text.trimEnd('\n', '\r')
            if (text.isBlank()) return
            log.serverOutput(serverName, text, isError = isServerError(text))
        }
    })
    return this
}

/** The servers prefix their own level; a panic has no level but is the thing most worth seeing. */
private fun isServerError(text: String): Boolean =
    text.contains(" ERROR ") || text.contains("panicked")

private val SUPPORTED_EXTENSIONS = setOf("by", "byi", "py", "pyi")

/** Extensions that are basedpython's own, whatever the surrounding project looks like. */
private val OWN_EXTENSIONS = setOf("by", "byi")

private fun VirtualFile.isBasedPythonSource(): Boolean = extension in SUPPORTED_EXTENSIONS

/**
 * What the `by` server is given: python-ish sources, plus django templates.
 *
 * A template is not python and is never read as one — the server checks it as the template it is —
 * but its completions, navigation and diagnostics all come from the same index as the project's
 * models, views and urls, so it is the same server that answers for it.
 */
private fun VirtualFile.isByServerFile(): Boolean =
  isBasedPythonSource() || ByTemplateFiles.isTemplate(this)

/**
 * Whether opening [file] should start a language server for [project].
 *
 * A `.by` file is ours no matter where it lives, so it always does. A `.py` file only does in a
 * project that carries a basedpython marker — otherwise a lone script in a Rust or JS repo would
 * spawn `by`, which is the "don't activate in non-python projects" complaint.
 */
private fun shouldServe(project: Project, file: VirtualFile): Boolean =
  file.extension in OWN_EXTENSIONS || BasedPythonProjectDetector.isBasedPythonProject(project)
// A django template takes the second branch: `.html` is the most common extension there is, so a
// template is only ever ours in a project that already carries a basedpython marker.

private fun splitArgs(raw: String): List<String> =
  raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

// region: `by` server (type checker + general LSP)

/**
 * The descriptor `by` is started from for [project], or `null` — after saying why — when it has no
 * binary to start.
 *
 * What a file being opened starts, and what [startByServer] starts when nothing has been opened.
 */
private fun byDescriptor(project: Project): ByLspServerDescriptor? {
  val launch = BasedPythonBinaries.launchBy(project)
  if (launch == null) {
    LOG.warn("`by` binary not found — not starting its language server")
    BasedPythonNotifications.warnBinaryMissing(project, "by")
    return null
  }
  return ByLspServerDescriptor(project, launch, splitArgs(BasedPythonSettings.getInstance(project).effectiveByExtraArgs))
}

/**
 * Asks the platform to start `by` for [project] if it is not running, for work that asks about the
 * whole project rather than about a file somebody opened. Returns why it cannot be started — turned
 * off in settings, or no binary — or `null` once the start has been asked for. The start itself is
 * the platform's and asynchronous; [awaitByServer] is what waits for it.
 */
internal fun startByServer(project: Project): String? {
  if (!BasedPythonSettings.getInstance(project).byEnabled) return BasedPythonBundle.message("by.start.disabled")
  val descriptor = byDescriptor(project) ?: return BasedPythonBundle.message("by.start.noBinary")
  LspClientManager.getInstance(project).ensureClientStarted(ByLspServerSupportProvider::class.java, descriptor)
  return null
}

internal class ByLspServerSupportProvider : LspIntegrationProvider {
  override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspClientStarter) {
    if (!file.isByServerFile()) return
    if (!shouldServe(project, file)) return
    val settings = BasedPythonSettings.getInstance(project)
    if (!settings.byEnabled) return
    // Resolved for the project, not for `file`. The descriptor is project-wide, so the platform
    // keeps whichever one started first and ignores the rest: resolving from the file's content
    // root would make the whole project's server run from the `.venv` of whichever module happened
    // to have a file opened first. One server serves every module, so it resolves the way the
    // project does.
    val descriptor = byDescriptor(project) ?: return
    clientStarter.ensureClientStarted(descriptor)
  }

  /**
   * The `by` row in the language services popup, carrying the basedpython mark.
   *
   * Without this the platform draws `AllIcons.Json.Object` — the `{}` it gives every LSP
   * integration that never says otherwise — so the row for our own type checker looked like any
   * other server's. The icon is the file type's ([dev.basedpython.pycharm.lang.BasedPythonFileType],
   * via [BasedPythonIcons.Logo]) rather than one drawn for the widget: the row names the server
   * that owns `.by` files, and the popup is where a user goes looking for it by that mark.
   */
  override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem =
    LspClientWidgetItem(lspClient, currentFile, BasedPythonIcons.Logo)
}

/**
 * Every kind of folding range `by` sends: LSP's three and basedpython's own, as `by`'s
 * `docs/basedpython/features/editor.md` lists them.
 */
private val FOLDING_RANGE_KINDS =
  listOf("imports", "region", "comment", "docstring", "function", "class", "string", "collection", "annotation")

internal class ByLspServerDescriptor(
  project: Project,
  private val launch: ByLaunch,
  private val extraArgs: List<String>,
) : ProjectWideLspClientDescriptor(project, "basedpython") {

  override fun isSupportedFile(file: VirtualFile): Boolean = file.isByServerFile()

  /** Start/stop for [dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader] and the status widget. */
  override val lspServerListener: LspServerListener = ByLspLifecycleListener.Broadcaster(project, "by")

  override fun createCommandLine(): GeneralCommandLine =
    GeneralCommandLine(buildList(2 + launch.prependArgs.size + extraArgs.size) {
      add(launch.exe.toString())
      addAll(launch.prependArgs)
      add("server")
      addAll(extraArgs)
    }).withEnvironment(launch.env)

  override fun startServerProcess(): BaseProcessHandler<*> =
    super.startServerProcess().mirrorStderrTo(project, "by")

  /**
   * Which kinds of inlay hint `by` should bother computing.
   *
   * `by` has a switch per kind of hint it produces (`inlayHints.variableTypes`,
   * `inlayHints.inferredRaises`, …) and this is what turns off the ones set to "never": a hint
   * nobody will draw is better not inferred than inferred and dropped. Everything else stays on,
   * push-to-hint included — those hints are drawn from inlays built before the key goes down.
   *
   * Sent as initialization options rather than pushed later because `by` does not implement
   * `workspace/didChangeConfiguration` yet (its own VS Code extension restarts the server on these
   * settings for the same reason, and so does this plugin — see `BasedPythonLspReloader`).
   */
  /**
   * Adds `by/dataFlowAt`; see [dev.basedpython.pycharm.debug.dfa.ByDataFlowServer].
   *
   * LSP has no request whose shape fits "what does this program's own state settle about the code
   * below it" — an `inlayHint` carries a range and nowhere to put what a debugger saw — so it is a
   * protocol extension, which is what `lsp4jServerClass` is for.
   */
  override val lsp4jServerClass: Class<out org.eclipse.lsp4j.services.LanguageServer> =
    ByDataFlowServer::class.java

  /**
   * The platform's capabilities, with every kind of folding range `by` has listed: `by` sends a
   * kind of its own — a docstring, a function's body, a long string — only to a client that lists
   * it, and the folding builder folds each by default under its own option. The platform lists
   * `imports` and `region` only.
   */
  override val clientCapabilities: ClientCapabilities
    get() = super.clientCapabilities.apply {
      textDocument.foldingRange.foldingRangeKind.valueSet = FOLDING_RANGE_KINDS
    }

  override fun createInitializationOptions(): Any =
    mapOf(
      "inlayHints" to BasedPythonSettings.getInstance(project).inlayModes.serverOptions(),
      // Every file checked when a whole-project request asks — `workspace/diagnostic`, for
      // Problems | Project Errors, and `by/checkWorkspace`, for an inspection run. The mode costs
      // nothing until one is sent: it chooses which files a check reports, and the per-document
      // requests the editor sends answer the same in either mode. Always on rather than following
      // the setting, so turning the setting on needs no restart, and Inspect Code works without it.
      "diagnosticMode" to "workspace",
      // *Settings | Editor | General | Code Completion | Insert parentheses automatically*: `by`
      // completes a callable as its call — `total()`, the caret after the parentheses when it
      // takes no arguments — only while it is on. Read once, at start; BasedPythonLspReloader
      // restarts the server when it changes.
      // *Settings | basedpython | Auto-import*: how a name is imported from a module the file
      // imports nothing from yet, `from m import name` or `import m`. Read at start too; the
      // settings page restarts the servers when it is applied.
      // *Settings | Editor | General | Code Completion | basedpython | Suggest importable names*:
      // whether completion offers names the file does not import yet, with the import. Read at
      // start; ByCodeCompletionOptions restarts the servers when it changes.
      // *Postfix templates*, on the same page: whether completion offers `by`'s `xs.for` and the
      // rest (`.await`, which is syntax, either way). Read at start, restarted on change, the same.
      "completions" to mapOf(
        "autoImport" to BasedPythonAppSettings.getInstance().suggestImportableNames,
        "completeFunctionParentheses" to EditorSettingsExternalizable.getInstance().isInsertParenthesesAutomatically,
        "importStyle" to BasedPythonSettings.getInstance(project).importStyle.id,
        "postfixTemplates" to BasedPythonAppSettings.getInstance().offerPostfixTemplates,
      ),
      // The rules the inspection profile changes from their defaults, a rule switched off as
      // `ignore`, above the project's own configuration; ByRuleConfiguration restarts the servers
      // when a profile change changes them.
      "configuration" to mapOf("rules" to ByRuleConfiguration.getInstance(project).current()),
    )

  // `by` advertises: completion, hover, goto-def/decl/type-def, references, rename,
  // doc highlight, signature help, diagnostics, inlay hints, semantic tokens,
  // code actions, doc/workspace symbols, selection/folding range, type hierarchy.
  // Per-capability toggles (§142) let the user disable individual features;
  // inlay hints are rendered by the plugin itself (see `lsp.inlay.ByInlayHintsProvider`).
  override val lspCustomization: LspCustomization =
    ByCapabilityCustomization(BasedPythonSettings.getInstance(project))

  /** Disables only the `by` capabilities the user turned off in settings. */
  private class ByCapabilityCustomization(private val s: BasedPythonSettings) : LspCustomization() {
    /** Not a toggle: `by`'s messages are written in markdown, and a tooltip is HTML. */
    override val diagnosticsCustomizer = ByDiagnosticsSupport()

    /** `by`'s completion, which smart completion leaves to `by/smartCompletion` — see [dev.basedpython.pycharm.lsp.completion.ByCompletionSupport]. */
    override val completionCustomizer
      get() = if (s.byCompletion) dev.basedpython.pycharm.lsp.completion.ByCompletionSupport else LspCompletionDisabled
    override val goToDefinitionCustomizer
      get() = if (s.byGoToDefinition) super.goToDefinitionCustomizer else LspGoToDefinitionDisabled
    override val goToTypeDefinitionCustomizer
      get() = if (s.byGoToDefinition) super.goToTypeDefinitionCustomizer else LspGoToTypeDefinitionDisabled
    override val findReferencesCustomizer
      get() = if (s.byFindReferences) super.findReferencesCustomizer else LspFindReferencesDisabled
    /**
     * Not the platform's LSP rename, which edits only the occurrence under the caret and ignores
     * *Specify refactoring options*: Shift+F6 in a `.by` file is
     * [dev.basedpython.pycharm.lsp.refactor.ByRenameHandler], which renames through the same
     * `by` requests the way a `.py` file renames. Off here, the platform's handler is never also
     * available on the same caret, which would make it ask which of the two to run.
     */
    override val renameCustomizer = LspRenameDisabled

    /**
     * `by`'s quick fixes as the platform attaches them to the diagnostics they fix, and none of its
     * other code actions: those are each an intention of the plugin's, named for what it does
     * ([dev.basedpython.pycharm.lsp.refactor.ByCodeActionIntentions]), where the platform would show
     * them all under one *LSP* entry of the settings and ask `by` for them a second time.
     */
    override val codeActionsCustomizer = object : LspCodeActionsSupport() {
      override val intentionActionsSupport: Boolean = false
    }
    override val semanticTokensCustomizer
      get() = if (s.bySemanticTokens) {
        dev.basedpython.pycharm.lsp.semantic.BasedPythonLspSemanticTokensSupport()
      } else {
        LspSemanticTokensDisabled
      }
    private val codeLenses = ByCodeLensSupport()

    /** `by`'s lenses, with the navigating ones navigated here rather than sent back to it. */
    override val codeLensCustomizer
      get() = if (s.byCodeLens) codeLenses else LspCodeLensDisabled
    /**
     * `by`'s document highlights, asked for in `.by` files too.
     *
     * The platform's [LspDocumentHighlightsSupport] only asks for plain-text and TextMate files,
     * on the assumption that a language with PSI highlights usages itself — and `.by`'s PSI is flat,
     * so nothing did: the toggle above switched a request that was never sent. `by` answers it for
     * symbols and for keywords, where it lights up an `if` with its `elif`s and `else`, a `def` with
     * its `return`s and a loop with its `break`s.
     */
    override val documentHighlightsCustomizer
      get() = if (s.byDocumentHighlight) ByDocumentHighlights else LspDocumentHighlightsDisabled
    override val signatureHelpCustomizer
      get() = if (s.bySignatureHelp) super.signatureHelpCustomizer else LspSignatureHelpDisabled
    /**
     * The platform's *is overridden*, *is implemented* and *is subclassed* icons, off: they are
     * [dev.basedpython.pycharm.lsp.supers.ByInheritorMarkers], drawn from the count the
     * *Inheritors* code vision already asks for. The platform's asked `documentSymbol`, then
     * `textDocument/implementation` for each method of a class with subclasses and
     * `prepareTypeHierarchy` + `typeHierarchy/subtypes` for each class, every edit once the document
     * had been still for a second: ~240 `implementation` and ~150 `subtypes` per quiet edit on
     * fastapi's `applications.py`.
     */
    override val inheritanceMarkersCustomizer: LspInheritanceMarkersCustomizer = LspInheritanceMarkersDisabled
    /**
     * Always off — and that is not the feature being switched off, only the platform's rendering
     * of it.
     *
     * The platform draws every LSP hint through `PresentationFactory.smallText`: the UI label font
     * at four-fifths of the editor size, in a rounded grey pill. `LspInlayHintCustomizer` offers no
     * way to change that, so the hints are fetched and drawn by `lsp.inlay.ByInlayHintsProvider`
     * instead, in the editor's own font. The three settings toggles moved with them.
     *
     * Costs nothing on the wire: the `inlayHint` *client capability* is advertised whatever this
     * returns (`LspClientCapabilities` sets it unconditionally), so `by` still answers the requests
     * the provider sends.
     */
    override val inlayHintCustomizer = LspInlayHintDisabled

    /**
     * Off: folding is [dev.basedpython.pycharm.editor.folding.ByFoldingBuilder], over the same
     * `textDocument/foldingRange`. The platform's asked once a file was in an editor and folded what
     * came back in passes after the first, the only one that folds by default.
     */
    override val foldingRangeCustomizer = LspFoldingRangeDisabled

    /**
     * `by`'s `textDocument/onTypeFormatting`, which the platform leaves off unless a customization
     * turns it on: typing the `:` that ends an `else`, `elif`, `except`, `finally` or a further
     * `case` header moves the clause out to the statement it continues, which is where Enter left
     * it one level too deep. Which statement that is, and whether the `:` ends the header at all, is
     * `by`'s parse.
     *
     * `by` triggers on `:` alone. The platform also runs on-type formatting from Enter when a server
     * lists `\n`, which `by` does not, so [dev.basedpython.pycharm.editor.smart.BasedPythonEnterHandler]
     * stays the only thing that indents a new line. The request goes out after the key's own
     * handling and its edit is applied only if nothing has been typed since, so a quick next key
     * leaves the clause where it is.
     */
    override val onTypeFormattingCustomizer = LspOnTypeFormattingSupport()

    /**
     * Document symbols for the Structure view and the File Structure popup, but not breadcrumbs:
     * those, and the sticky lines, diff headers and Recent Locations entries the same switch
     * controls, are [dev.basedpython.pycharm.editor.ByBreadcrumbsProvider], from `by`'s outline. The
     * platform's made a crumb and a sticky line of every symbol whatever its kind, so a multi-line
     * constant and each of a class's fields were pinned to the top of the editor.
     */
    override val documentSymbolCustomizer = object : LspDocumentSymbolSupport() {
      override val breadcrumbsSupport: Boolean = false
    }

    /**
     * `by`'s workspace symbols, each placed in its `containerName` and given a qualified name to
     * match a dotted query against — [dev.basedpython.pycharm.navigation.ByWorkspaceSymbols].
     */
    override val workspaceSymbolCustomizer = dev.basedpython.pycharm.navigation.ByWorkspaceSymbols
  }

  private object ByDocumentHighlights : LspDocumentHighlightsSupport() {
    override fun shouldAskServerForDocumentHighlights(psiFile: PsiFile): Boolean = true
  }
}

// endregion

// region: `buff` server (ruff fork — formatter / linter)

internal class BuffLspServerSupportProvider : LspIntegrationProvider {
  override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspClientStarter) {
    if (!file.isBasedPythonSource()) return
    if (!shouldServe(project, file)) return
    val settings = BasedPythonSettings.getInstance(project)
    if (!settings.buffEnabled) return
    // For the project rather than `file`, for the same reason as `by`'s above.
    val launch = BasedPythonBinaries.launchBuff(project)
    if (launch == null) {
      LOG.warn("`buff` binary not found — skipping LSP startup for ${file.path}")
      BasedPythonNotifications.warnBinaryMissing(project, "buff")
      return
    }
    clientStarter.ensureClientStarted(BuffLspServerDescriptor(project, launch, splitArgs(settings.effectiveBuffExtraArgs)))
  }
}

internal class BuffLspServerDescriptor(
  project: Project,
  private val launch: ByLaunch,
  private val extraArgs: List<String>,
) : ProjectWideLspClientDescriptor(project, "buff") {

  override fun isSupportedFile(file: VirtualFile): Boolean = file.isBasedPythonSource()

  /** Start/stop for [dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader] and the status widget. */
  override val lspServerListener: LspServerListener = ByLspLifecycleListener.Broadcaster(project, "buff")

  override fun createCommandLine(): GeneralCommandLine =
    GeneralCommandLine(buildList(2 + launch.prependArgs.size + extraArgs.size) {
      add(launch.exe.toString())
      addAll(launch.prependArgs)
      add("server")
      addAll(extraArgs)
    }).withEnvironment(launch.env)

  override fun startServerProcess(): BaseProcessHandler<*> =
    super.startServerProcess().mirrorStderrTo(project, "buff")

  /**
   * Adds `buff/explainRule`; see [dev.basedpython.pycharm.lsp.ext.BuffServerExtensions].
   *
   * LSP has no request for "what does this rule mean" — a diagnostic's `codeDescription` is a URL,
   * which sends the reader to a browser for prose the server is already holding — so it is a
   * protocol extension, which is what `lsp4jServerClass` is for.
   */
  override val lsp4jServerClass: Class<out org.eclipse.lsp4j.services.LanguageServer> =
    dev.basedpython.pycharm.lsp.ext.BuffLanguageServer::class.java

  // `buff` advertises only formatting + code actions + hover + diagnostics.
  // Everything the type-checker (`by`) handles better stays disabled; the three
  // buff capabilities are individually user-gated (§142).
  override val lspCustomization: LspCustomization =
    BuffCapabilityCustomization(BasedPythonSettings.getInstance(project))

  private class BuffCapabilityCustomization(private val s: BasedPythonSettings) : LspCustomization() {
    // `buff`'s messages quote names in backticks the same way `by`'s do (it is a ruff fork), so
    // its tooltips are rendered the same way.
    override val diagnosticsCustomizer = ByDiagnosticsSupport()

    // Always-off (handled by `by`):
    override val goToDefinitionCustomizer = LspGoToDefinitionDisabled
    override val goToTypeDefinitionCustomizer = LspGoToTypeDefinitionDisabled
    override val completionCustomizer = LspCompletionDisabled
    override val findReferencesCustomizer = LspFindReferencesDisabled
    override val renameCustomizer = LspRenameDisabled
    override val signatureHelpCustomizer = LspSignatureHelpDisabled
    override val semanticTokensCustomizer = LspSemanticTokensDisabled
    override val inlayHintCustomizer = LspInlayHintDisabled
    override val documentHighlightsCustomizer = LspDocumentHighlightsDisabled
    override val documentSymbolCustomizer = LspDocumentSymbolDisabled
    override val foldingRangeCustomizer = LspFoldingRangeDisabled
    override val selectionRangeCustomizer = LspSelectionRangeDisabled
    override val typeHierarchyCustomizer = LspTypeHierarchyDisabled
    override val callHierarchyCustomizer = LspCallHierarchyDisabled
    override val codeLensCustomizer = LspCodeLensDisabled
    override val documentColorCustomizer = LspDocumentColorDisabled
    override val documentLinkCustomizer = LspDocumentLinkDisabled

    /**
     * Always off, and that is not formatting being switched off.
     *
     * The platform's LSP formatting sends `textDocument/formatting`, which is the formatter and
     * nothing else, and it claims a file ahead of any formatting service this plugin registers. So
     * *Reformat Code* laid the file out and left the imports where it found them.
     * [dev.basedpython.pycharm.editor.format.BuffFormattingService] owns the action instead, and
     * asks for the pass that sorts as well — it is gated on `buffFormatting` in its own `canFormat`.
     */
    override val formattingCustomizer = LspFormattingDisabled

    /**
     * Likewise: the platform's LSP optimize-imports sends `source.organizeImports`, which only
     * sorts, and *Optimize Imports* means sort **and** drop the unused.
     * [dev.basedpython.pycharm.format.BuffImportOptimizer] asks for the pass that does both.
     */
    override val optimizeImportsCustomizer = LspOptimizeImportsDisabled
    override val codeActionsCustomizer
      get() = if (s.buffCodeActions) super.codeActionsCustomizer else LspCodeActionsDisabled
    override val hoverCustomizer
      get() = if (s.buffHover) super.hoverCustomizer else LspHoverDisabled
  }
}

// endregion
