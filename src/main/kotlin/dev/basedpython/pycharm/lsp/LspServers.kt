package dev.basedpython.pycharm.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Path

private val LOG = Logger.getInstance("dev.basedpython.pycharm.lsp")

private val SUPPORTED_EXTENSIONS = setOf("by", "byi", "py", "pyi")

private fun VirtualFile.isBasedPythonSource(): Boolean = extension in SUPPORTED_EXTENSIONS

private fun splitArgs(raw: String): List<String> =
  raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

// region: `by` server (type checker + general LSP)

internal class ByLspServerSupportProvider : LspServerSupportProvider {
  override fun fileOpened(project: Project, file: VirtualFile, serverStarter: LspServerStarter) {
    if (!file.isBasedPythonSource()) return
    val settings = BasedPythonSettings.getInstance(project)
    if (!settings.byEnabled) return
    val binary = BasedPythonBinaries.resolveBy(project)
    if (binary == null) {
      LOG.warn("`by` binary not found — skipping LSP startup for ${file.path}")
      BasedPythonNotifications.warnBinaryMissing(project, "by")
      return
    }
    serverStarter.ensureServerStarted(ByLspServerDescriptor(project, binary, splitArgs(settings.byExtraArgs)))
  }
}

internal class ByLspServerDescriptor(
  project: Project,
  private val binary: Path,
  private val extraArgs: List<String>,
) : ProjectWideLspServerDescriptor(project, "basedpython") {

  override fun isSupportedFile(file: VirtualFile): Boolean = file.isBasedPythonSource()

  override fun createCommandLine(): GeneralCommandLine =
    GeneralCommandLine(buildList(2 + extraArgs.size) {
      add(binary.toString())
      add("server")
      addAll(extraArgs)
    })

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
    val settings = BasedPythonSettings.getInstance(project)
    if (!settings.buffEnabled) return
    val binary = BasedPythonBinaries.resolveBuff(project)
    if (binary == null) {
      LOG.warn("`buff` binary not found — skipping LSP startup for ${file.path}")
      BasedPythonNotifications.warnBinaryMissing(project, "buff")
      return
    }
    serverStarter.ensureServerStarted(BuffLspServerDescriptor(project, binary, splitArgs(settings.buffExtraArgs)))
  }
}

internal class BuffLspServerDescriptor(
  project: Project,
  private val binary: Path,
  private val extraArgs: List<String>,
) : ProjectWideLspServerDescriptor(project, "buff") {

  override fun isSupportedFile(file: VirtualFile): Boolean = file.isBasedPythonSource()

  override fun createCommandLine(): GeneralCommandLine =
    GeneralCommandLine(buildList(2 + extraArgs.size) {
      add(binary.toString())
      add("server")
      addAll(extraArgs)
    })

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
