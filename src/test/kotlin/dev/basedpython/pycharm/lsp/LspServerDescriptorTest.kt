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
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.inlay.ByHintKind
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
@TestFixtures
@RunInEdt(writeIntent = true)
class LspServerDescriptorTest {

  private val fixture by codeInsightFixture()

  private val project get() = fixture.project

  private val dummyBinary = Paths.get("/nonexistent/fake-binary")

  private fun launch(
    exe: java.nio.file.Path = dummyBinary,
    prependArgs: List<String> = emptyList(),
    env: Map<String, String> = emptyMap(),
  ) = ByLaunch(exe, prependArgs, env, venvRoot = null, kind = ByEnvironmentKind.PATH)

  private fun byDescriptor() = ByLspServerDescriptor(project, launch(), emptyList())
  private fun buffDescriptor() = BuffLspServerDescriptor(project, launch(), emptyList())

  // ---------------------------------------------------------------------------
  // presentable names
  // ---------------------------------------------------------------------------

  @Test
  fun `by descriptor presentable name is basedpython`() {
    assertEquals("basedpython", byDescriptor().presentableName)
  }

  @Test
  fun `buff descriptor presentable name is buff`() {
    assertEquals("buff", buffDescriptor().presentableName)
  }

  // ---------------------------------------------------------------------------
  // supported-file recognition
  // ---------------------------------------------------------------------------

  @Test
  fun `by descriptor supports by byi py and pyi files`() {
    val desc = byDescriptor()
    assertTrue(desc.isSupportedFile(makeFile("a.by")))
    assertTrue(desc.isSupportedFile(makeFile("a.byi")))
    assertTrue(desc.isSupportedFile(makeFile("b.py")))
    assertTrue(desc.isSupportedFile(makeFile("c.pyi")))
  }

  @Test
  fun `by descriptor rejects unrelated files`() {
    val desc = byDescriptor()
    assertFalse(desc.isSupportedFile(makeFile("readme.md")))
    assertFalse(desc.isSupportedFile(makeFile("data.json")))
    assertFalse(desc.isSupportedFile(makeFile("noext")))
  }

  @Test
  fun `buff descriptor recognizes the same source extensions as by`() {
    val buff = buffDescriptor()
    val by = byDescriptor()
    for (name in listOf("a.by", "a.byi", "b.py", "c.pyi", "x.txt", "noext")) {
      val f = makeFile(name)
      assertEquals(
        by.isSupportedFile(f),
        buff.isSupportedFile(f),
        "buff and by should agree on supported-file recognition for $name",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // buff capability customization: only format/lint/hover/code-actions stay on
  // ---------------------------------------------------------------------------

  @Test
  fun `buff disables navigation completion and structural capabilities`() {
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

  @Test
  fun `buff keeps formatting hover and code-actions enabled`() {
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
  // by capability customization: the platform never renders the inlay hints
  // ---------------------------------------------------------------------------

  @Test
  fun `by leaves the platform's inlay hint rendering off whatever the toggles say`() {
    // The hints themselves are on: they are fetched and drawn by ByInlayHintsProvider, in the
    // editor font. What this switches off is only the platform's own small-text-in-a-pill
    // rendering of the same hints, which would otherwise draw them a second time.
    val s = BasedPythonSettings.getInstance(project)
    for (kind in ByHintKind.entries) s.setInlayMode(kind, ByHintMode.ALWAYS)
    assertSame(LspInlayHintDisabled, byDescriptor().lspCustomization.inlayHintCustomizer)

    for (kind in ByHintKind.entries) s.setInlayMode(kind, ByHintMode.NEVER)
    assertSame(LspInlayHintDisabled, byDescriptor().lspCustomization.inlayHintCustomizer)
  }

  @Test
  fun `by does not blanket-disable navigation capabilities`() {
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

  // ---------------------------------------------------------------------------
  // command line assembly (builds a command line; never launches it)
  // ---------------------------------------------------------------------------

  @Test
  fun `a direct binary launch is exe then server`() {
    val cmd = ByLspServerDescriptor(project, launch(), emptyList()).createCommandLine()
    assertEquals(dummyBinary.toString(), cmd.exePath)
    assertEquals(listOf("server"), cmd.parametersList.list)
  }

  @Test
  fun `a uv launch puts the prepend args before the server subcommand`() {
    // uv is only "just another source" if its argument prefix lands in the right place:
    // `uv run --project <dir> by server`, not `uv server run …`.
    val uv = Paths.get("/usr/local/bin/uv")
    val desc = ByLspServerDescriptor(
      project,
      launch(exe = uv, prependArgs = listOf("run", "--project", "/w", "by")),
      emptyList(),
    )
    val cmd = desc.createCommandLine()
    assertEquals(uv.toString(), cmd.exePath)
    assertEquals(listOf("run", "--project", "/w", "by", "server"), cmd.parametersList.list)
  }

  @Test
  fun `extra args follow the server subcommand`() {
    val desc = ByLspServerDescriptor(project, launch(), listOf("--verbose"))
    assertEquals(listOf("server", "--verbose"), desc.createCommandLine().parametersList.list)
  }

  @Test
  fun `the activation environment reaches the server process`() {
    // Resolving `.venv/bin/by` but running it with the IDE's own environment lets anything the
    // server spawns escape the venv it came from; the descriptor must carry activation through.
    val env = mapOf("VIRTUAL_ENV" to "/w/.venv", "PATH" to "/w/.venv/bin")
    val cmd = ByLspServerDescriptor(project, launch(env = env), emptyList()).createCommandLine()
    assertEquals("/w/.venv", cmd.environment["VIRTUAL_ENV"])
    assertEquals("/w/.venv/bin", cmd.environment["PATH"])
  }

  @Test
  fun `buff assembles its command line the same way`() {
    val cmd = BuffLspServerDescriptor(project, launch(), emptyList()).createCommandLine()
    assertEquals(dummyBinary.toString(), cmd.exePath)
    assertEquals(listOf("server"), cmd.parametersList.list)
  }

  // ---------------------------------------------------------------------------
  // by initialization options: which hints the server is asked to compute
  // ---------------------------------------------------------------------------

  @Test
  fun `by is told to skip only the kinds of hint set to never`() {
    val s = BasedPythonSettings.getInstance(project)
    s.setInlayMode(ByHintKind.VARIABLE_TYPES, ByHintMode.NEVER)
    s.setInlayMode(ByHintKind.INFERRED_RAISES, ByHintMode.ON_PUSH)
    s.setInlayMode(ByHintKind.CALL_ARGUMENT_NAMES, ByHintMode.ALWAYS)

    val options = byDescriptor().createInitializationOptions()
    @Suppress("UNCHECKED_CAST")
    val hints = (options as Map<String, Any>)["inlayHints"] as Map<String, Boolean>

    assertEquals(false, hints["variableTypes"], "a hint nobody draws should not be computed")
    // On push still needs computing: the inlay is built before the key goes down.
    assertEquals(true, hints["inferredRaises"])
    assertEquals(true, hints["callArgumentNames"])
    assertFalse(hints.containsKey("other"), "the plugin's catch-all is not one of `by`'s options")
  }

  @Test
  fun `the option names are the ones by answers to`() {
    // Names `by` does not recognise are reported back to the user as unknown options, so this is
    // spelling that has to match the server's `InlayHintOptions`, field for field.
    @Suppress("UNCHECKED_CAST")
    val hints =
      (byDescriptor().createInitializationOptions() as Map<String, Any>)["inlayHints"] as Map<String, Boolean>
    assertEquals(
      setOf(
        "variableTypes", "lambdaParameterTypes", "callTypeArguments", "typeArgumentNames",
        "numericPromotions", "revealedTypes", "inferredRaises", "callArgumentNames",
        "implicitParameters", "implicitSelf", "implicitArguments", "inferredOverride",
        "inferredVariance", "inferredReification", "inferredReads", "parameterStability",
        "derivedDependencies",
      ),
      hints.keys,
    )
  }

  @AfterEach
  fun resetSettings() {
    BasedPythonSettings.getInstance(project).loadState(BasedPythonSettings.State())
  }

  /** Creates an in-memory virtual file with the given name (extension drives support). */
  private fun makeFile(name: String): VirtualFile = LightVirtualFile(name, "")
}
