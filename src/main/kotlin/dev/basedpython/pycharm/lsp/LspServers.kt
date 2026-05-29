package dev.basedpython.pycharm.lsp

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider
import com.intellij.platform.lsp.api.LspServerSupportProvider.LspServerStarter
import com.intellij.platform.lsp.api.ProjectWideLspServerDescriptor
import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentColorDisabled
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

private val SUPPORTED_EXTENSIONS = setOf("by", "py", "pyi")

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
  // Only inlay hints are user-gated: turning off all three inlay toggles in
  // settings disables LSP inlay hints entirely.
  override val lspCustomization: LspCustomization = run {
    val s = BasedPythonSettings.getInstance(project)
    val anyInlay = s.inlayParameterHints || s.inlayTypeHints || s.inlayReturnHints
    if (anyInlay) object : LspCustomization() {} else InlayHintsOffCustomization
  }

  private object InlayHintsOffCustomization : LspCustomization() {
    override val inlayHintCustomizer = LspInlayHintDisabled
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
  // Disable everything else so the two servers don't collide on capabilities
  // the type-checker (`by`) handles better.
  override val lspCustomization: LspCustomization = BuffOnlyFmtAndLintCustomization

  private object BuffOnlyFmtAndLintCustomization : LspCustomization() {
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
  }
}

// endregion
