package dev.basedpython.pycharm.lsp

import com.intellij.platform.lsp.api.customization.LspCallHierarchyDisabled
import com.intellij.platform.lsp.api.customization.LspCodeActionsSupport
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
import com.intellij.platform.lsp.api.customization.LspInheritanceMarkersSupport
import com.intellij.platform.lsp.api.customization.LspInlayHintDisabled
import com.intellij.platform.lsp.api.customization.LspRenameDisabled
import com.intellij.platform.lsp.api.customization.LspRenameSupport
import com.intellij.platform.lsp.api.customization.LspSelectionRangeDisabled
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import com.intellij.platform.lsp.api.customization.LspSignatureHelpDisabled
import com.intellij.platform.lsp.api.customization.LspTypeHierarchyDisabled
import com.intellij.testFramework.LightVirtualFile
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lsp.inlay.ByHintKind
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
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
  fun `by descriptor presentable name is basedpython`() = onEdt {
    assertEquals("basedpython", byDescriptor().presentableName)
  }

  @Test
  fun `buff descriptor presentable name is buff`() = onEdt {
    assertEquals("buff", buffDescriptor().presentableName)
  }

  // ---------------------------------------------------------------------------
  // supported-file recognition
  // ---------------------------------------------------------------------------

  @Test
  fun `by descriptor supports by byi py and pyi files`() = onEdt {
    val desc = byDescriptor()
    assertTrue(desc.isSupportedFile(makeFile("a.by")))
    assertTrue(desc.isSupportedFile(makeFile("a.byi")))
    assertTrue(desc.isSupportedFile(makeFile("b.py")))
    assertTrue(desc.isSupportedFile(makeFile("c.pyi")))
  }

  @Test
  fun `by descriptor rejects unrelated files`() = onEdt {
    val desc = byDescriptor()
    assertFalse(desc.isSupportedFile(makeFile("readme.md")))
    assertFalse(desc.isSupportedFile(makeFile("data.json")))
    assertFalse(desc.isSupportedFile(makeFile("noext")))
  }

  @Test
  fun `buff descriptor recognizes the same source extensions as by`() = onEdt {
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
  fun `buff disables navigation completion and structural capabilities`() = onEdt {
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
  fun `buff keeps formatting hover and code-actions enabled`() = onEdt {
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
  fun `by leaves the platform's inlay hint rendering off whatever the toggles say`() = onEdt {
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
  fun `by does not blanket-disable navigation capabilities`() = onEdt {
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

  @Test
  fun `by renames in a by file`() = onEdt {
    // The platform's own rename support only runs in plain-text and TextMate files, so without this
    // Shift+F6 was disabled in every `.by` file however the toggle was set.
    val c = byDescriptor().lspCustomization.renameCustomizer
    assertTrue(c is LspRenameSupport, "rename must not be disabled for by: $c")
    val byFile = fixture.configureByText("a.by", "x = 1\n")
    assertTrue((c as LspRenameSupport).shouldRunRename(byFile))
  }

  @Test
  fun `by leaves renaming a plain text file to whoever else renames it`() = onEdt {
    val c = byDescriptor().lspCustomization.renameCustomizer as LspRenameSupport
    assertFalse(c.shouldRunRename(fixture.configureByText("notes.txt", "x\n")))
  }

  @Test
  fun `the rename toggle still switches by's rename off`() = onEdt {
    BasedPythonSettings.getInstance(project).byRename = false
    assertSame(LspRenameDisabled, byDescriptor().lspCustomization.renameCustomizer)
  }

  @Test
  fun `by draws inheritance markers in by files`() = onEdt {
    // Off in the platform unless a customization turns them on.
    val c = byDescriptor().lspCustomization.inheritanceMarkersCustomizer
    assertTrue(c is LspInheritanceMarkersSupport, "inheritance markers must be on for by: $c")
    assertTrue((c as LspInheritanceMarkersSupport).shouldAskServerForMarkers(fixture.configureByText("a.by", "").virtualFile))
  }

  @Test
  fun `by draws no inheritance markers where another language already does`() = onEdt {
    val c = byDescriptor().lspCustomization.inheritanceMarkersCustomizer as LspInheritanceMarkersSupport
    assertFalse(c.shouldAskServerForMarkers(makeFile("readme.txt")))
  }

  @Test
  fun `by's navigating code lenses are run by the client`() = onEdt {
    // `editor.action.showReferences` is not a command `by` executes; sent back to it, it is refused.
    assertTrue(byDescriptor().lspCustomization.codeLensCustomizer is ByCodeLensSupport)
    BasedPythonSettings.getInstance(project).byCodeLens = false
    assertSame(LspCodeLensDisabled, byDescriptor().lspCustomization.codeLensCustomizer)
  }

  @Test
  fun `by's refactorings reach Alt+Enter as intentions`() = onEdt {
    // `by`'s refactorings are code actions that are not quick fixes. The platform only asks for
    // those, and lists them in Alt+Enter, through a code actions customizer that supports
    // intention actions — the only place Inline Variable, Extract Function and the rest now live.
    val c = byDescriptor().lspCustomization.codeActionsCustomizer
    assertTrue(c is LspCodeActionsSupport, "code actions must not be disabled for by: $c")
    assertTrue((c as LspCodeActionsSupport).intentionActionsSupport)
    assertTrue(c.quickFixesSupport)
  }

  // ---------------------------------------------------------------------------
  // command line assembly (builds a command line; never launches it)
  // ---------------------------------------------------------------------------

  @Test
  fun `a direct binary launch is exe then server`() = onEdt {
    val cmd = ByLspServerDescriptor(project, launch(), emptyList()).createCommandLine()
    assertEquals(dummyBinary.toString(), cmd.exePath)
    assertEquals(listOf("server"), cmd.parametersList.list)
  }

  @Test
  fun `a uv launch puts the prepend args before the server subcommand`() = onEdt {
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
  fun `extra args follow the server subcommand`() = onEdt {
    val desc = ByLspServerDescriptor(project, launch(), listOf("--verbose"))
    assertEquals(listOf("server", "--verbose"), desc.createCommandLine().parametersList.list)
  }

  @Test
  fun `the activation environment reaches the server process`() = onEdt {
    // Resolving `.venv/bin/by` but running it with the IDE's own environment lets anything the
    // server spawns escape the venv it came from; the descriptor must carry activation through.
    val env = mapOf("VIRTUAL_ENV" to "/w/.venv", "PATH" to "/w/.venv/bin")
    val cmd = ByLspServerDescriptor(project, launch(env = env), emptyList()).createCommandLine()
    assertEquals("/w/.venv", cmd.environment["VIRTUAL_ENV"])
    assertEquals("/w/.venv/bin", cmd.environment["PATH"])
  }

  @Test
  fun `buff assembles its command line the same way`() = onEdt {
    val cmd = BuffLspServerDescriptor(project, launch(), emptyList()).createCommandLine()
    assertEquals(dummyBinary.toString(), cmd.exePath)
    assertEquals(listOf("server"), cmd.parametersList.list)
  }

  // ---------------------------------------------------------------------------
  // by initialization options: which hints the server is asked to compute
  // ---------------------------------------------------------------------------

  @Test
  fun `by is told to skip only the kinds of hint set to never`() = onEdt {
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
  fun `the option names are the ones by answers to`() = onEdt {
    // Names `by` does not recognise are reported back to the user as unknown options, so this is
    // spelling that has to match the server's `InlayHintOptions`, field for field — every field but
    // the two django-template ones, whose hints this plugin does not draw.
    @Suppress("UNCHECKED_CAST")
    val hints =
      (byDescriptor().createInitializationOptions() as Map<String, Any>)["inlayHints"] as Map<String, Boolean>
    assertEquals(
      setOf(
        "variableTypes", "lambdaParameterTypes", "inheritedParameterTypes", "propertyTypes",
        "inferredReturnTypes", "callTypeArguments", "typeArgumentNames", "numericPromotions",
        "revealedTypes", "inferredRaises", "enumValues", "callArgumentNames", "implicitParameters",
        "implicitSelf", "implicitArguments", "inheritedParameterDefaults", "inferredOverride",
        "inferredVariance", "inferredReification", "inferredReads", "parameterStability",
        "derivedDependencies", "inferredInvalidations",
      ),
      hints.keys,
    )
  }

  @AfterEach
  fun resetSettings() = onEdt {
    BasedPythonSettings.getInstance(project).loadState(BasedPythonSettings.State())
  }

  /** Creates an in-memory virtual file with the given name (extension drives support). */
  private fun makeFile(name: String): VirtualFile = LightVirtualFile(name, "")
}
