keep working on this list until it is complete, add new entries as they are thought of

# basedpython PyCharm Plugin — Feature Tracker

Status key: `[x]` done · `[~]` partial · `[ ]` todo

---

## 1. Language registration & file type
- [x] `.by` file type + icon
- [x] Language singleton, parser definition (flat)
- [x] Real PSI tree (composite nodes: defs, classes, imports, params, blocks, decorators) — lang.psi.BasedPythonPsiElements + lang.parser.BasedPythonParser
- [x] Indentation-aware lexer (INDENT/DEDENT/STATEMENT_BREAK tokens) — lang.parser.BasedPythonIndentingLexer
- [x] f-string interpolation sub-lexing (highlight `{expr}` inside strings) — highlight.fstring.FStringInterpolation (pure helper) + FStringInterpolationAnnotator; reuses FSTRING_INTERP color key
- [x] Associate `.pyi` stubs + `.by` variants (no separate `.by` stub variant exists; `.pyi` handled by Python support — N/A)
- [ ] Dialect detection: treat `.py` in basedpython project as basedpython-aware
- [x] File-type icon + marketplace logo (pluginIcon.svg light/dark)

## 2. Syntax highlighting
- [x] Lexer-driven keyword/string/number/comment/operator highlighting
- [x] basedpython extras (`?.`, `??`, `final`, `override`, `protocol`, `let`, `newtype`, `data class`, etc.)
- [x] Annotator-level semantic coloring fallback when LSP off (builtins, self/cls, decorators, type names)
- [~] LSP semantic tokens → color scheme keys (platform default mapping active; custom basedpython-key mapping pending)
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
- [~] Pull-diagnostics workspace mode (platform LSP pull diagnostics active when server advertises; project-view stripe wiring pending)
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
- [x] Per-binary Test buttons, live detection label
- [x] Auto-install prompt: offer `uv add --dev basedpython` if missing (editor banner)
- [x] uv integration: detect uv, surface `uv sync` action
- [ ] Multiple interpreter/venv support per project (SDK association)
- [x] Binary version display — `BasedPythonVersions` helper + Test button output + status bar tooltip (cached)
- [x] Bundled fallback binary download (per-OS) option — env.download.DownloadBinariesAction + ByBinaryDownloadPlan (per-OS asset URL, installs to ~/.basedpython/bin, points settings at it)
- [ ] WSL / remote interpreter / Docker target support

## 6. Run / debug
- [x] `by run` / `by build` / `by check` run configs + producers
- [x] Working dir, env vars, extra args, `--min-version`
- [~] **Debugger** — "Debug .by (pdb)" builds then runs generated `.py` under `python -m pdb` in an interactive console; pdb frames clickable. Full source-mapped IDE debug blocked upstream: the transpiler's line map (`by_transforms/source_map.rs`) is internal and not emitted by the CLI as a sidecar.
- [x] Gutter run icons on `if __name__ == "__main__"` / top-level
- [x] Test runner integration — `by test` run config + factory + SMTRunner test tree (green/red nodes) via run.test.tree.ByTestOutputParser (pure pytest/unittest parser) → ByServiceMessages → ByTestEventsConverter, wired through SMTestRunnerConnectionUtil in ByTestConfiguration.getState; ByTestLocator for source nav
- [x] Test gutter icons + run-single-test (run.testmarker.ByTestRunLineMarkerContributor)
- [ ] Coverage support
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
- [x] Structure view (classes, methods, fields) — indent-scanner based
- [x] Breadcrumbs
- [x] Code folding (imports, functions, classes, multiline strings, regions)
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
- [ ] Safe delete
- [~] Extract variable / method / constant — Extract Variable + Introduce Constant done (selection-driven, refactoring.ExtractVariableAction/IntroduceConstantAction); Extract Method pending
- [x] Inline variable — refactoring.InlineVariableAction (text-heuristic; bails on multiple/blank/multi-line assignments)
- [ ] Change signature
- [ ] Move file/symbol + update imports

## 12. Navigation / search
- [x] Go to class / symbol / file (`.by` indexed)
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
- [x] Application-level defaults vs project override — settings.app.BasedPythonAppSettings (APP service) + BasedPythonDefaults resolver; BasedPythonSettings.effective* getters fall back to IDE-wide defaults; BasedPythonAppConfigurable UI ("BasedPython Defaults")

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
- [~] Plugin verifier (pluginVerifier task) passes for target IDEs
- [x] CI build + verify
- [x] Compatibility range (sinceBuild/untilBuild) set
- [x] Plugin logo/marketplace assets (pluginIcon.svg light/dark)
- [~] i18n message bundles (no hardcoded strings)
- [x] Proper plugin description/vendor/changelog metadata (README desc + CHANGELOG changeNotes + vendor)
- [x] Performance: lexer benchmarks on large files (LexerPerformanceTest)
- [x] Dynamic plugin (no IDE restart on install) compliance (verifier clean)

## 19. Stretch / nice-to-have
- [ ] Notebook (.ipynb) support via LSP notebook sync
- [x] REPL / console for `by run` — console.OpenBasedPythonReplAction (interactive `by repl`, falls back to `by run`; RunContentExecutor + KillableProcessHandler)
- [ ] Inline transpile error decorations mapped to `.by` lines
- [ ] AI-assist hooks (explain transpilation)
- [~] Multi-root workspace support — OutDirExcludePolicy now excludes `out/` under every content root (multi-module aware); broader per-root binary/settings resolution still pending
- [x] Color scheme presets matching basedpython branding (BasedPythonDark/Light.icls)
