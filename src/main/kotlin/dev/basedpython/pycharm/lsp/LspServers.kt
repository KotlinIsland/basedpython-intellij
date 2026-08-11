package dev.basedpython.pycharm.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerSupportProvider.LspServerStarter
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
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
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspDocumentSymbolDisabled
import com.intellij.platform.lsp.api.customization.LspFindReferencesDisabled
import com.intellij.platform.lsp.api.customization.LspFoldingRangeDisabled
import com.intellij.platform.lsp.api.customization.LspGoToDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspGoToTypeDefinitionDisabled
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.customization.LspSelectionRangeDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled
import com.intellij.platform.lsp.api.customization.LspTypeHierarchyDisabled
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.ui.log.BasedPythonLog

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
private fun OSProcessHandler.mirrorStderrTo(project: Project, serverName: String): OSProcessHandler {
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

internal class ByLspServerSupportProvider : LspServerSupportProvider {
  override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerStarter) {
    if (!file.isByServerFile()) return
    if (!shouldServe(project, file)) return
    val settings = BasedPythonSettings.getInstance(project)
    if (!settings.byEnabled) return
    // `file` makes resolution content-root-aware, so a per-module `.venv` wins over the
    // workspace-level one (FEATURES.md §186) for the server too, not just for `ByCli`.
    val launch = BasedPythonBinaries.launchBy(project, file)
    if (launch == null) {
      LOG.warn("`by` binary not found — skipping LSP startup for ${file.path}")
      BasedPythonNotifications.warnBinaryMissing(project, "by")
      return
    }
    serverStarter.ensureServerStarted(ByLspServerDescriptor(project, launch, splitArgs(settings.effectiveByExtraArgs)))
  }
}

internal class ByLspServerDescriptor(
  project: Project,
  private val launch: ByLaunch,
  private val extraArgs: List<String>,
) : ProjectWideLspServerDescriptor(project, "basedpython") {

  override fun isSupportedFile(file: VirtualFile): Boolean = file.isByServerFile()

  override fun createCommandLine(): GeneralCommandLine =
    GeneralCommandLine(buildList(2 + launch.prependArgs.size + extraArgs.size) {
      add(launch.exe.toString())
      addAll(launch.prependArgs)
      add("server")
      addAll(extraArgs)
    }).withEnvironment(launch.env)

  override fun startServerProcess(): OSProcessHandler =
    super.startServerProcess().mirrorStderrTo(project, "by")

  // `by` advertises: completion, hover, goto-def/decl/type-def, references, rename,
  // doc highlight, signature help, diagnostics, inlay hints, semantic tokens,
  // code actions, doc/workspace symbols, selection/folding range, type hierarchy.
  // Per-capability toggles (§142) let the user disable individual features;
  // inlay hints are disabled when all three inlay toggles are off.
  override val lspCustomization: LspCustomization =
    ByCapabilityCustomization(BasedPythonSettings.getInstance(project))

  /** Disables only the `by` capabilities the user turned off in settings. */
  private class ByCapabilityCustomization(private val s: BasedPythonSettings) : LspCustomization() {
    override val completionCustomizer
      get() = if (s.byCompletion) super.completionCustomizer else LspCompletionDisabled
    override val goToDefinitionCustomizer
      get() = if (s.byGoToDefinition) super.goToDefinitionCustomizer else LspGoToDefinitionDisabled
    override val goToTypeDefinitionCustomizer
      get() = if (s.byGoToDefinition) super.goToTypeDefinitionCustomizer else LspGoToTypeDefinitionDisabled
    override val findReferencesCustomizer
      get() = if (s.byFindReferences) super.findReferencesCustomizer else LspFindReferencesDisabled
    override val renameCustomizer
      get() = if (s.byRename) super.renameCustomizer else LspRenameDisabled
    override val semanticTokensCustomizer
      get() = if (s.bySemanticTokens) {
        dev.basedpython.pycharm.lsp.semantic.BasedPythonLspSemanticTokensSupport()
      } else {
        LspSemanticTokensDisabled
      }
    override val codeLensCustomizer
      get() = if (s.byCodeLens) super.codeLensCustomizer else LspCodeLensDisabled
    override val documentHighlightsCustomizer
      get() = if (s.byDocumentHighlight) super.documentHighlightsCustomizer else LspDocumentHighlightsDisabled
    override val signatureHelpCustomizer
      get() = if (s.bySignatureHelp) super.signatureHelpCustomizer else LspSignatureHelpDisabled
    override val inlayHintCustomizer
      get() = if (s.inlayParameterHints || s.inlayTypeHints || s.inlayReturnHints) {
        super.inlayHintCustomizer
      } else {
        LspInlayHintDisabled
      }
  }
}

// endregion

// region: `buff` server (ruff fork — formatter / linter)

internal class BuffLspServerSupportProvider : LspServerSupportProvider {
  override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerStarter) {
    if (!file.isBasedPythonSource()) return
    if (!shouldServe(project, file)) return
    val settings = BasedPythonSettings.getInstance(project)
    if (!settings.buffEnabled) return
    val launch = BasedPythonBinaries.launchBuff(project, file)
    if (launch == null) {
      LOG.warn("`buff` binary not found — skipping LSP startup for ${file.path}")
      BasedPythonNotifications.warnBinaryMissing(project, "buff")
      return
    }
    serverStarter.ensureServerStarted(BuffLspServerDescriptor(project, launch, splitArgs(settings.effectiveBuffExtraArgs)))
  }
}

internal class BuffLspServerDescriptor(
  project: Project,
  private val launch: ByLaunch,
  private val extraArgs: List<String>,
) : ProjectWideLspServerDescriptor(project, "buff") {

  override fun isSupportedFile(file: VirtualFile): Boolean = file.isBasedPythonSource()

  override fun createCommandLine(): GeneralCommandLine =
    GeneralCommandLine(buildList(2 + launch.prependArgs.size + extraArgs.size) {
      add(launch.exe.toString())
      addAll(launch.prependArgs)
      add("server")
      addAll(extraArgs)
    }).withEnvironment(launch.env)

  override fun startServerProcess(): OSProcessHandler =
    super.startServerProcess().mirrorStderrTo(project, "buff")

  // `buff` advertises only formatting + code actions + hover + diagnostics.
  // Everything the type-checker (`by`) handles better stays disabled; the three
  // buff capabilities are individually user-gated (§142).
  override val lspCustomization: LspCustomization =
    BuffCapabilityCustomization(BasedPythonSettings.getInstance(project))

  private class BuffCapabilityCustomization(private val s: BasedPythonSettings) : LspCustomization() {
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

    // User-gated buff capabilities:
    override val formattingCustomizer
      get() = if (s.buffFormatting) super.formattingCustomizer else LspFormattingDisabled
    override val optimizeImportsCustomizer
      get() = if (s.buffFormatting) super.optimizeImportsCustomizer else LspOptimizeImportsDisabled
    override val codeActionsCustomizer
      get() = if (s.buffCodeActions) super.codeActionsCustomizer else LspCodeActionsDisabled
    override val hoverCustomizer
      get() = if (s.buffHover) super.hoverCustomizer else LspHoverDisabled
  }
}

// endregion
