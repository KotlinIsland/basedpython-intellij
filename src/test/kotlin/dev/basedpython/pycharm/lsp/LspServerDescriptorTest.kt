package dev.basedpython.pycharm.lsp

import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
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
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Paths

/**
 * Binary-free tests for [ByLspServerDescriptor] and [BuffLspServerDescriptor].
 *
 * Descriptors are constructed with a dummy [java.nio.file.Path]; we never call
 * `createCommandLine()` (which would reference the fake binary) nor start a server.
 * We only assert on pure, declarative descriptor state: presentable name, supported
 * file recognition, and the LSP capability customization each server advertises.
 *
 * The descriptors and provider classes are `internal`, so this test lives in the same
 * package to reach them.
 */
class LspServerDescriptorTest : BasePlatformTestCase() {

  private val dummyBinary = Paths.get("/nonexistent/fake-binary")

  private fun byDescriptor() = ByLspServerDescriptor(project, dummyBinary, emptyList())
  private fun buffDescriptor() = BuffLspServerDescriptor(project, dummyBinary, emptyList())

  // ---------------------------------------------------------------------------
  // presentable names
  // ---------------------------------------------------------------------------

  fun `test by descriptor presentable name is basedpython`() {
    assertEquals("basedpython", byDescriptor().presentableName)
  }

  fun `test buff descriptor presentable name is buff`() {
    assertEquals("buff", buffDescriptor().presentableName)
  }

  // ---------------------------------------------------------------------------
  // supported-file recognition
  // ---------------------------------------------------------------------------

  fun `test by descriptor supports by byi py and pyi files`() {
    val desc = byDescriptor()
    assertTrue(desc.isSupportedFile(makeFile("a.by")))
    assertTrue(desc.isSupportedFile(makeFile("a.byi")))
    assertTrue(desc.isSupportedFile(makeFile("b.py")))
    assertTrue(desc.isSupportedFile(makeFile("c.pyi")))
  }

  fun `test by descriptor rejects unrelated files`() {
    val desc = byDescriptor()
    assertFalse(desc.isSupportedFile(makeFile("readme.md")))
    assertFalse(desc.isSupportedFile(makeFile("data.json")))
    assertFalse(desc.isSupportedFile(makeFile("noext")))
  }

  fun `test buff descriptor recognizes the same source extensions as by`() {
    val buff = buffDescriptor()
    val by = byDescriptor()
    for (name in listOf("a.by", "a.byi", "b.py", "c.pyi", "x.txt", "noext")) {
      val f = makeFile(name)
      assertEquals(
        "buff and by should agree on supported-file recognition for $name",
        by.isSupportedFile(f),
        buff.isSupportedFile(f),
      )
    }
  }

  // ---------------------------------------------------------------------------
  // buff capability customization: only format/lint/hover/code-actions stay on
  // ---------------------------------------------------------------------------

  fun `test buff disables navigation completion and structural capabilities`() {
    val c = buffDescriptor().lspCustomization
    assertSame(LspGoToDefinitionDisabled, c.goToDefinitionCustomizer)
    assertSame(LspGoToTypeDefinitionDisabled, c.goToTypeDefinitionCustomizer)
    assertSame(LspCompletionDisabled, c.completionCustomizer)
    assertSame(LspFindReferencesDisabled, c.findReferencesCustomizer)
    assertSame(LspRenameDisabled, c.renameCustomizer)
    assertSame(LspSignatureHelpDisabled, c.signatureHelpCustomizer)
    assertSame(LspSemanticTokensDisabled, c.semanticTokensCustomizer)
    assertSame(LspInlayHintDisabled, c.inlayHintCustomizer)
    assertSame(LspDocumentHighlightsDisabled, c.documentHighlightsCustomizer)
    assertSame(LspDocumentSymbolDisabled, c.documentSymbolCustomizer)
    assertSame(LspFoldingRangeDisabled, c.foldingRangeCustomizer)
    assertSame(LspSelectionRangeDisabled, c.selectionRangeCustomizer)
    assertSame(LspTypeHierarchyDisabled, c.typeHierarchyCustomizer)
    assertSame(LspCallHierarchyDisabled, c.callHierarchyCustomizer)
    assertSame(LspCodeLensDisabled, c.codeLensCustomizer)
    assertSame(LspDocumentColorDisabled, c.documentColorCustomizer)
    assertSame(LspDocumentLinkDisabled, c.documentLinkCustomizer)
  }

  fun `test buff keeps formatting hover and code-actions enabled`() {
    val c = buffDescriptor().lspCustomization
    // These are NOT replaced with a Disabled singleton, so they keep the default
    // (enabled) customizer. Asserting they differ from the obvious disabled markers
    // documents the intent without depending on the concrete default class.
    assertNotSame(LspCompletionDisabled, c.formattingCustomizer)
    assertNotNull(c.formattingCustomizer)
    assertNotNull(c.hoverCustomizer)
    assertNotNull(c.codeActionsCustomizer)
    assertNotNull(c.diagnosticsCustomizer)
  }

  // ---------------------------------------------------------------------------
  // by capability customization: inlay hints gated on settings
  // ---------------------------------------------------------------------------

  fun `test by keeps inlay hints enabled when any inlay setting is on`() {
    val s = BasedPythonSettings.getInstance(project)
    s.inlayParameterHints = true
    s.inlayTypeHints = false
    s.inlayReturnHints = false
    val c = byDescriptor().lspCustomization
    assertNotSame(
      "inlay hints should remain enabled while at least one inlay toggle is on",
      LspInlayHintDisabled,
      c.inlayHintCustomizer,
    )
  }

  fun `test by disables inlay hints when all inlay settings are off`() {
    val s = BasedPythonSettings.getInstance(project)
    s.inlayParameterHints = false
    s.inlayTypeHints = false
    s.inlayReturnHints = false
    val c = byDescriptor().lspCustomization
    assertSame(LspInlayHintDisabled, c.inlayHintCustomizer)
  }

  fun `test by does not blanket-disable navigation capabilities`() {
    // Unlike buff, the `by` type-checker advertises full navigation; assert these are
    // NOT the disabled singletons.
    val s = BasedPythonSettings.getInstance(project)
    s.inlayParameterHints = true
    val c = byDescriptor().lspCustomization
    assertNotSame(LspGoToDefinitionDisabled, c.goToDefinitionCustomizer)
    assertNotSame(LspCompletionDisabled, c.completionCustomizer)
    assertNotSame(LspFindReferencesDisabled, c.findReferencesCustomizer)
    assertNotSame(LspRenameDisabled, c.renameCustomizer)
  }

  override fun tearDown() {
    try {
      val s = BasedPythonSettings.getInstance(project)
      s.inlayParameterHints = true
      s.inlayTypeHints = true
      s.inlayReturnHints = true
    } finally {
      super.tearDown()
    }
  }

  /** Creates an in-memory virtual file with the given name (extension drives support). */
  private fun makeFile(name: String): VirtualFile = LightVirtualFile(name, "")
}
