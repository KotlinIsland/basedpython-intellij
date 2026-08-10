keep working on this list until it is complete, add new entries as they are thought of

# basedpython PyCharm Plugin — Feature Tracker

Status key: `[x]` done · `[~]` partial · `[ ]` todo

---

## 1. Language registration & file type
- [x] `.by` file type + icon
- [x] Language singleton, parser definition (flat — file node plus one leaf per token, nothing more)
- [x] ~~Real PSI tree (composite nodes)~~ **removed**: a second, always-behind implementation of a grammar the `by` server already knows. Its only consumer was the structure view, which now comes from LSP document symbols. `lang.parser.BasedPythonParser`, `lang.parser.BasedPythonIndentingLexer` and `lang.psi.*` are gone
- [x] f-string interpolation sub-lexing (highlight `{expr}` inside strings) — highlight.fstring.FStringInterpolation (pure helper) + FStringInterpolationAnnotator; reuses FSTRING_INTERP color key
- [x] Associate `.pyi` stubs + `.by` variants (no separate `.by` stub variant exists; `.pyi` handled by Python support — N/A)
- [x] Dialect detection: treat `.py` in basedpython project as basedpython-aware — lang.dialect.BasedPythonFileTypeOverrider + BasedPythonProjectDetector. A basedpython marker is `api.lock`, `basedpython.toml`, a top-level `.by`/`.byi`, or a `pyproject.toml` that mentions basedpython — a *bare* `pyproject.toml` is only a Python project, and claiming those was what made the plugin activate everywhere
- [x] Who owns `.py` is a setting (lang.dialect.PyFileHandling: auto / never / always), defaulting to "only when no other plugin provides the Python language" so the plugin works alongside PyCharm instead of taking `.py` from it. The `by` server still attaches to `.py` either way
- [x] File-type icon + marketplace logo (pluginIcon.svg light/dark)

## 2. Syntax highlighting

**Policy: the `by` LSP is the source of truth for semantic colour and is always preferred when
available.** It knows the types and symbols a token-stream heuristic can only guess at, and it
tracks the language on its own — new syntax colours with no plugin change. The lexer stays
regardless (the platform requires one to register the language, and semantic tokens never cover
strings, comments, numbers, operators or brackets).

There is no longer a no-LSP fallback for anything *semantic*, and that is deliberate: basedpython is
not usable without `by`, so an approximate second implementation bought nothing and cost a
permanent maintenance debt against a language that keeps moving. Without a server a `.by` file gets
lexical colour only. Don't reintroduce guessed semantic colour; fix the LSP path instead.

- [x] Lexer-driven keyword/string/number/comment/operator highlighting (needed in all modes)
- [x] basedpython extras (`?.`, `??`, `final`, `override`, `protocol`, `let`, `newtype`, `data class`, etc.) — lexer keyword set; a stale list is tolerable since the server reports keywords itself
- [x] ~~Annotator-level semantic coloring (builtins, self/cls, decorators, type names)~~ **removed**: every one of those is a question about what the code means, which `by` answers from real types and reports as a semantic token. `highlight.BasedPythonAnnotator` and `highlight.BasedPythonSoftKeywords` are gone; `highlight.StringEscapeAnnotator` keeps the one part a semantic token cannot carry (escapes inside a literal)
- [x] LSP semantic tokens → color scheme keys — lsp.semantic.BasedPythonSemanticTokensMapping (pure) + BasedPythonLspSemanticTokensSupport, wired into ByLspServerDescriptor.semanticTokensCustomizer; maps LSP token types/modifiers to basedpython TextAttributesKeys (themeable)
- [x] String escape sequence highlighting
- [x] f-string interpolation highlighting
- [x] Highlight numeric separators, complex literals distinctly (editor.highlight.BasedPythonNumericLiteralAnnotator)
- [x] Matched-brace + same-keyword (`if`/`elif`/`else`) highlighting (editor.highlight.BasedPythonKeywordHighlightUsagesHandlerFactory)

## 3. LSP — `by` server
- [x] Server descriptor, start/stop, supported extensions
- [x] Completion, hover, goto def/decl/type, references, rename, signature help, diagnostics, inlay hints, semantic tokens, code actions, symbols, folding, type hierarchy
- [x] Inlay hint toggle settings (param names, types, return types)
- [x] Call hierarchy (LSP — enabled on `by`)
- [x] Document link support (LSP — enabled on `by`)
- [x] Code lens (run/references counts) — `by` LSP codeLens enabled
- [x] Pull-diagnostics workspace mode — platform LSP integration consumes `by` pull diagnostics when the server advertises them, surfacing them in the editor + Problems tool window and feeding WolfTheProblemSolver (project-view error stripes / red filenames) automatically; no extra wiring required
- [x] LSP server version check + min-version warning (lsp.version.ByVersionCheckActivity)
- [x] LSP stderr → dedicated log console (ui.log.BasedPythonLog tool window)
- [x] Restart-on-settings-change (debounced) (lsp.reload.BasedPythonLspReloader)
- [x] Graceful "server crashed" recovery + notification (lsp.reload listener)

## 4. LSP — `buff` server
- [x] Capability-scoped descriptor (format, lint, code actions, hover)
- [x] Organize imports, fix-all commands
- [x] Format-on-save integration
- [x] Reformat selection / file routed to buff (editor.format.BuffFormattingService)
- [x] Optimize-imports action routed to buff organize-imports
- [x] Lint severity mapping → IDE inspection severities (LSP diagnostic severities mapped by platform)
- [x] Quick-fix preview (LSP code actions via platform intention preview)

## 5. Binary / environment management
- [x] `.venv` walk-up resolve + PATH fallback + manual override
- [x] Per-binary Test buttons, live detection label (shows the resolved command *and* which source produced it)
- [x] Auto-install prompt: offer `uv add --dev basedpython` if missing (editor banner)
- [x] uv integration: detect uv, surface `uv sync` action
- [x] **Single resolution path** — `env.ByEnvironments` owns binary lookup, venv activation, and uv for every call site (run configs, LSP startup, `ByCli`, the pdb action). Returns a `ByLaunch` of (exe, prependArgs, env); uv is not special-cased, it just contributes `exe=uv, prependArgs=[run, --project, …, by]`
- [x] **venv activation** — a venv-backed launch sets `VIRTUAL_ENV`, prepends the venv's bin dir to `PATH`, and clears `PYTHONHOME`. Previously `.venv/bin/by` was resolved but run with the IDE's inherited environment, so anything it spawned could escape the venv it came from
- [x] Per-run-configuration `Environment` selector (Auto-detect / `.venv` / uv / interpreter / downloaded / PATH). Auto order: configured path → `.venv` walk-up → SDK venv → `~/.basedpython/bin` → PATH. A non-Auto choice pins one source and fails rather than falling back — and ignores the configured path, which is layered over an *IDE-wide* default and would otherwise let a global preference beat a per-configuration choice (and cannot express uv at all)
- [x] **uv is opt-in, never automatic** — `uv run` creates a `.venv`, writes `uv.lock`, and may download a CPython toolchain. Right when asked for, unacceptable as a side effect of opening a file (every implicit caller — LSP startup, banner, inspections — resolves with Auto), so uv is excluded from the Auto chain and runs only when explicitly selected. Bootstrapping-with-consent stays with the "Install with uv" banner
- [~] Multiple interpreter/venv support per project (SDK association) — a *configured* Python interpreter is now read through platform-only API (`ProjectRootManager` / `ModuleRootManager` → `Sdk.homePath` → venv root, confirmed via `pyvenv.cfg`), so `by` resolves from the interpreter's venv with no dependency on the Python plugin and no breakage when it is absent. Still BLOCKED: rendering PyCharm's *own* interpreter dropdown / creating and managing interpreters, which needs `PythonSdkType` from the Python plugin — not bundled in the IDE this targets (IU-262 ships no `Pythonid`; verified against the distribution). uv covers the create-an-environment case instead, and is the only source that can bootstrap one
- [x] Binary version display — `BasedPythonVersions` helper + Test button output + status bar tooltip (cached)
- [x] Bundled fallback binary download (per-OS) option — env.download.DownloadBinariesAction + ByBinaryDownloadPlan (per-OS asset URL, installs to ~/.basedpython/bin, points settings at it)
- [~] WSL / remote interpreter / Docker target support — `by`/`buff` commands honor a configurable working dir + env; running against a remote/Docker target needs the platform's `TargetEnvironment` API wired through every process launch + remote binary resolution. BLOCKED on the same missing-Python-SDK infra (no bundled Python plugin in IU-261 to reuse its target providers); a from-scratch target integration is out of scope for the LSP-first design

## 6. Run / debug
- [x] `by run` / `by build` / `by check` run configs + producers
- [x] Working dir, env vars, extra args, `--min-version`
- [~] **Debugger** — "Debug .by (pdb)" builds then runs generated `.py` under `python -m pdb` in an interactive console; pdb frames clickable. Full source-mapped IDE debug is **not** blocked upstream, contrary to what this line used to say: `by run` writes `_by_sourcemap.py` (generated line → `.by` line, per file) into its temp directory, and `_by_runner.py` already uses it to rewrite tracebacks. See [docs/debugging.md](docs/debugging.md) for the design — debugpy's `setPydevdSourceMap` does the translation in the debuggee, because IntelliJ's DAP client offers no hook to remap a breakpoint's line on the way out.
- [x] Gutter run icons on `if __name__ == "__main__"` / top-level
- [x] Test runner integration — runs `by run pytest -v <targets>` (there is no `by test` subcommand; asking for one died on `error: unrecognized subcommand 'test'` before any output reached the tree). `by run <module>` transpiles the project into a temp dir and runs `python -m <module>` there, so pytest collects the transpiled `.py`; relative paths are preserved, so targets differ from the `.by` source only in the extension — rewritten in run.test.ByPytest. Output goes through run.test.tree.ByTestOutputParser (pure pytest/unittest parser) → ByServiceMessages → ByTestEventsConverter, wired through SMTestRunnerConnectionUtil in ByTestConfiguration.getState
  - Caveat: pytest's rootdir is that temp dir, so `[tool.pytest.ini_options]` and a hand-written `conftest.py` are not picked up — only `.by` files are transpiled there. A `conftest.by` works
  - [ ] ByTestLocator is registered as the SMTestLocator but the converter emits no `locationHint`, so tree nodes are not navigable and the locator is dead code
- [x] Test gutter icons + run-single-test (run.testmarker.ByTestRunLineMarkerContributor)
- [~] Coverage support — the test tree runs via `by run pytest` (§66). Mapping line coverage back onto `.by` is unblocked by the same finding as the debugger (§64): `by run`'s `_by_sourcemap.py` can project coverage gathered on the generated `.py` onto `.by` lines. It would still additionally require the Python plugin's CoverageEngine (absent in the IDE target)
- [x] Build output (`out/`) console with clickable paths
- [x] Before-run task: `by build`
- [x] Macro support in config (`$FilePath$`, `$ModuleName$`) — `ByMacros` helper

## 7. Editor actions / Tools menu
- [x] Transpile / Reverse transpile (popups + Tools)
- [x] Generate api.lock, Format with buff, Check Project, Clean Caches, Explain Rule
- [x] Show transpiled `.py` side-by-side diff view (live, updates on edit)
- [x] "Reveal generated .py in out/" navigation
- [x] api.lock diff viewer (what changed in public API)
- [x] Convert Python file → basedpython in-place (apply reverse transpile to file)
- [x] Inline "transpile this snippet" for selection (transpile.selection.TranspileSelectionAction)

## 8. Code intelligence (beyond LSP)
- [x] Structure view — LSP document symbols. The platform's `LspStructureViewFactory` is registered for every language; the plugin's own factory shadowed it
- [x] Breadcrumbs — LSP document symbols (`LspFileBreadcrumbsCollector`)
- [x] Code folding — LSP folding ranges (`LspFoldingBuilder`). Folding collects builders via `allForLanguageOrAny`, so the plugin's own builder ran *alongside* the LSP one and doubled up the regions
- [x] Surround-with (try/except, if, while, brackets)
- [x] Smart enter / smart backspace (dedent)
- [x] Move statement up/down (indentation-block aware)
- [x] Extend/shrink selection (LSP selection range — enabled on `by`)
- [x] Parameter info popup (LSP signature help — enabled on `by`)
- [x] Quick documentation popup (LSP hover + local doc provider)
- [x] Auto-import on completion (LSP completion code actions)
- [x] Postfix completion templates (`.if`, `.for`, `.not`, `.return`)

## 9. Formatting & style
- [x] Code style settings page (delegate to buff config)
- [x] EditorConfig support (platform editorconfig applies to `.by` automatically)
- [x] Trailing whitespace / final newline handling (platform On-Save options apply)
- [x] Indent detector for `.by` (editor.indent.BasedPythonFileIndentOptionsProvider)
- [x] Wrap-on-typing, continuation indents (CONTINUATION_INDENT_SIZE via indent provider; wrap-on-typing platform default)

## 10. Inspections / intentions (local, no LSP)
- [x] Spellchecking in strings/comments/identifiers
- [x] TODO/FIXME comment scanning → TODO tool window
- [x] Intention: add type annotation
- [x] Intention: convert `def` → `data class` / `class def`
- [x] Intention: add `?.` / `??` null-safety
- [x] Intention: convert mutable default arg
- [x] Intention: anonymous tuple → NamedTuple expand preview
- [x] Unresolved-binary inspection w/ quick fix to settings

## 11. Refactoring
- [x] Rename (LSP rename — Shift+F6, enabled on `by`)
- [~] Safe delete — delegated to the `by` LSP at runtime: rename/find-references/go-to-definition are LSP-backed (§142 toggles), so deleting a symbol after an LSP reference check is available. A native IDE SafeDelete dialog (usage preview + conflict detection) needs a local cross-file symbol resolver the plugin intentionally does NOT duplicate (the LSP is the source of truth); building one would re-implement a Python resolver
- [x] Extract variable / method / constant — all three done (selection-driven): refactoring.ExtractVariableAction, IntroduceConstantAction, ExtractMethodAction (pure ExtractMethodLogic: nearest-enclosing-def insertion, body re-indentation, optional trailing-return heuristic)
- [x] Inline variable — refactoring.InlineVariableAction (text-heuristic; bails on multiple/blank/multi-line assignments)
- [~] Change signature — requires whole-program symbol resolution to rewrite every call site; delegated to the `by` LSP (rename + references are enabled). A native Change Signature dialog needs a local resolver the plugin intentionally defers to the LSP rather than duplicating
- [~] Move file/symbol + update imports — moving a file is supported by the platform; auto-rewriting import statements across the project needs cross-file symbol resolution, delegated to the `by` LSP (which updates references on rename). Native move-with-import-update needs a local resolver the plugin defers to the LSP

## 12. Navigation / search
- [x] Go to class / symbol / file — LSP workspace symbols (`LspGoToSymbolContributor` / `LspGoToClassContributor`). The plugin's own contributors listed every result a second time
- [x] Find usages (LSP references — enabled on `by`)
- [x] Highlight usages in file (LSP document highlight — enabled on `by`)
- [x] Bookmarks / mnemonics work in `.by` (platform-automatic for registered file type)
- [x] Recent files / locations include `.by` (platform-automatic)
- [x] Goto related (`.by` ↔ generated `.py`)

## 13. Templates
- [x] File templates (File, Class, Data Class, Protocol)
- [x] Live templates (cdef, dcl, fdcl, ecl, proto, ovr, nt, let)
- [x] More live templates (main guard, async def, match/case, enum, pytest fixture)
- [x] Postfix templates (see §8)
- [x] Surround templates
- [x] Template variable functions (byModuleName, byHeader, byOutPath macros)

## 14. Settings UI
- [x] Binary paths, toggles, args, py-version, Test buttons
- [x] Inlay hint toggles
- [x] Format-on-save toggle
- [x] Inspection severity config (IDE-automatic per LocalInspectionTool)
- [x] LSP trace level (off/messages/verbose)
- [x] Per-server enable + capability toggles — byEnabled/buffEnabled plus per-capability switches (by: completion, goto-def, references, rename, semantic tokens, code lens, highlight usages, signature help; buff: formatting, code actions, hover) wired into LspServers descriptors + Configurable; restart-on-change
- [x] Import/export settings (actions.settings.Export/ImportSettingsAction)
- [x] Application-level defaults vs project override — settings.app.BasedPythonAppSettings (APP service) + BasedPythonDefaults resolver; BasedPythonSettings.effective* getters fall back to IDE-wide defaults; BasedPythonAppConfigurable UI ("basedpython Defaults")

## 15. Status / UX
- [x] Status bar widget (LSP health)
- [x] LSP log tool window / console (ui.log.BasedPythonLogToolWindowFactory)
- [x] Progress indicators for long CLI (Task.Backgroundable in actions)
- [x] Notification actions (open settings, restart, view log) (ui.log.BasedPythonLogNotifications)
- [x] First-run welcome / setup notification (onboarding.BasedPythonWelcomeActivity)
- [x] Quick-fix banner when binary missing in editor (env.ByMissingBannerProvider)

## 16. Project / build system
- [x] Project wizard / new-project template (basedpython project scaffold)
- [x] pyproject.toml `[tool.ruff]` / basedpython config awareness + completion
- [x] Mark `out/` as generated/excluded (project.OutDirExcludePolicy)
- [x] Python interop: opt-in setting to index generated `.py` in `out/` so an installed Python plugin (PyCharm / IDEA+Python) gives native code intelligence on the transpiled output — `BasedPythonSettings.indexGeneratedPython` gates OutDirExcludePolicy; toggle fires roots rescan (IDEA Ultimate does NOT bundle Python, so reuse is opt-in when a Python plugin is present)
- [x] Watch mode: auto `by build` on save (opt-in) (run.watch.WatchModeSaveListener + ToggleWatchModeAction)
- [x] Module facet for basedpython (facet.BasedPythonFacetType)

## 17. Documentation / help
- [x] External docs links (Ctrl+Shift+I → basedpython docs)
- [x] Plugin settings help buttons → docs (docs.help.BasedPythonWebHelpProvider + getHelpTopic)
- [x] Bundled quick-reference of basedpython syntax
- [x] Quick documentation (Ctrl+Q) for basedpython keywords/modifiers/operators
- [x] `by explain` integrated as editor intention (inspections.explain.ExplainRuleIntention)

## 18. Quality / infra
- [x] Unit tests (lexer, file type, binary resolution)
- [x] LSP integration tests (lsp/*Test — 22 binary-free tests)
- [x] Plugin verifier (pluginVerifier task) passes for target IDEs — Compatible against IU-261.25134.12 (only informational deprecated/experimental/internal-API usages)
- [x] CI build + verify
- [x] Compatibility range (sinceBuild/untilBuild) set
- [x] Plugin logo/marketplace assets (pluginIcon.svg light/dark)
- [x] i18n message bundles (no hardcoded strings) — all user-visible action text/descriptions in plugin.xml via resource-bundle convention; all Kotlin notifications/dialogs/progress/UI strings via BasedPythonBundle.message(...); 109 keys, MessageFormat-escaped; BasedPythonBundleTest validates coverage + format round-trip
- [x] Proper plugin description/vendor/changelog metadata (README desc + CHANGELOG changeNotes + vendor)
- [x] Performance: lexer benchmarks on large files (LexerPerformanceTest)
- [x] Dynamic plugin (no IDE restart on install) compliance (verifier clean)

## 19. Stretch / nice-to-have
- [~] Notebook (.ipynb) support via LSP notebook sync — needs the Jupyter plugin's notebook editor + `textDocument/didOpen` notebook-document sync; BLOCKED in IU-261 (no bundled Jupyter/Python notebook editor to host `.by` cells). The `by` REPL (§182) covers interactive use instead
- [x] REPL / console for `by run` — console.OpenBasedPythonReplAction (interactive `by repl`, falls back to `by run`; RunContentExecutor + KillableProcessHandler)
- [~] Inline transpile error decorations mapped to `.by` lines — BLOCKED upstream (same root cause as §64): the transpiler's line map (`by_transforms/source_map.rs`) is internal and not emitted by the CLI, so transpile/runtime errors reported against generated `.py` lines cannot be remapped to `.by` source. Diagnostics that the `by` LSP reports directly on `.by` files DO surface inline (§36)
- [x] AI-assist hooks (explain transpilation) — transpile.explain.TranspilationExplainer (pure: detects null-safe `?.`/`?[`, elvis `?:`, `??`, `!!`, data-class, match/case, pipe `|>`, interpolation, val/var/let/const) + ExplainTranspilationAction (runs `by transpile`, shows HTML notes popup)
- [x] Multi-root workspace support — OutDirExcludePolicy excludes `out/` under every content root (multi-module aware); BasedPythonBinaries resolution is now content-root-aware (a file's own content root is searched for `.venv/bin/<by|buff>` before the workspace base, so a per-module venv wins), threaded via an optional `contextFile` through ByCli.run/runBuff and all file-based action callers (Transpile/ReverseTranspile/FormatWithBuff/format-on-save/optimize-imports/AsyncFormattingService); ByMacroSupport.outPath resolves `out/<rel>.py` relative to the file's content root. Pure `searchStartDirs` ordering unit-tested
- [x] Color scheme presets matching basedpython branding (BasedPythonDark/Light.icls)
