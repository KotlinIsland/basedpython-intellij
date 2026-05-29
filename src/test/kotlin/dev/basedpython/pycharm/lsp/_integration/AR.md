# LSP test stream — artifact record

**Test-only.** This stream adds tests under
`src/test/kotlin/dev/basedpython/pycharm/lsp/` and does NOT modify any shared
files (no `build.gradle.kts`, `plugin.xml`, or main-source edits). There is
**no manifest merge** required for this stream.

## Files added
- `BasedPythonBinariesTest.kt` — `BasedPythonBinaries` resolution (override path,
  non-executable / bogus override fall-through, graceful null on missing binary).
- `LspServerDescriptorTest.kt` — `ByLspServerDescriptor` / `BuffLspServerDescriptor`
  presentable names, `isSupportedFile`, and LSP capability customization
  (buff disables everything except format/lint/hover/code-actions; by gates inlay
  hints on settings).
- `LspServerSupportProviderTest.kt` — `fileOpened` guard logic via a recording
  `LspServerStarter` fake (extension guard, settings-disabled guard, graceful
  missing-binary branch, and start-on-resolve with a fake executable).

## Binary-free guarantee
No test launches `by`/`buff`. `createCommandLine()` is never invoked. The fake
`LspServerStarter` only records the descriptor it is handed; on CI (no real
binaries) the missing-binary assertions verify the no-op path. Descriptor
construction with a dummy `Path` does not spawn a process.
