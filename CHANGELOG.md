## [Unreleased]

### Added

- basedpython language registration with `.by` file type, icon, and lexer-driven syntax
  highlighting (Python 3.10+ plus `final`, `override`, `abstract`, `static`, `protocol`,
  `let`, `newtype`, `public`, `private`, `data class`, `frozen data class`, `enum class`,
  `?.`, `??`). Commenter, brace matcher, and quote handler for `.by` files.
- `by` and `buff` LSP integration with capability scoping (formatting/lint/code-actions
  routed to `buff`; completion/navigation/rename to `by`).
- Automatic `.venv` binary discovery with `PATH` fallback and manual override;
  `basedpython.RestartLsp` action; missing-binary notification group.
- Project-level persistent settings (`basedpython.xml`): `by`/`buff` paths, extra args,
  per-server enable toggles, target Python version.
- Run configuration type with `by run`, `by build`, and `by check` factories, plus
  context-aware producers on `.by` files; configurable working dir, extra args,
  `--min-version`, and env vars.
- Tools menu group with seven `by`/`buff` actions: Transpile, Reverse Transpile,
  Generate api.lock, Format with buff (`Ctrl+Alt+Shift+L`), Check Project, Clean Caches,
  Explain Rule. Transpile actions also in editor and project-view popups.
- Settings page (*Languages & Frameworks → basedpython*) and an optional status bar
  widget surfacing real-time `by` / `buff` LSP state.
- Color settings page; file templates (Empty, Class, Data Class, Protocol) under
  *New → basedpython File*; live templates `cdef`, `dcl`, `fdcl`, `ecl`, `proto`, `ovr`,
  `nt`, `let`.
- Semantic highlighting annotator (no LSP needed): built-in names, `self`/`cls`,
  decorators, type names, declarations, parameters, keyword args, string escapes,
  f-string interpolation.
- Structure view, breadcrumbs, and code folding via an indentation scanner.
- Surround-with descriptors and postfix templates (`.if`, `.for`, `.while`, `.not`,
  `.return`/`.ret`, `.print`, `.len`, `.var`, `.none`, `.notnone`, `.else`); added live
  templates for main guard, async def, match/case, enum, and pytest fixture.
- Inspections: mutable default argument, binary-not-configured (quick-fix to settings);
  spellchecking for comments/strings/identifiers; TODO/FIXME indexing.
- Log points for `.by` files. Hover the gutter between two line numbers and click — or press
  `Ctrl+Alt+F8` — to add a breakpoint that logs an expression instead of stopping, and type
  the expression in the field that opens in the gap. Enter commits, Escape abandons, and one
  that is never filled in removes itself rather than leaving an icon that does nothing.
  During a debug session the output reaches the run console the same way a `print` would.
- Breakpoint expression fields (*Condition*, *Evaluate and log*, Evaluate Expression) are
  basedpython editors rather than plain text boxes, with highlighting and `by` behind them.
- "`print()` call can be replaced with a log point" inspection, the counterpart of
  Kotlin's `println` one. The quick fix deletes the call and leaves a log point in the gap
  it occupied. Offered only where a log point says the same thing — not for `print()`, not
  for `file=` / `sep=` / `end=` / `flush=`, and not where the following line belongs to an
  outer block, since that is the line the log point binds to.
- Push-to-hint. Each kind of inlay hint — parameter names, variable types, return types —
  is now *never*, *always*, or *while the push key is held*, so the hints you want at a
  glance and the ones you want only when asking are configured apart. Hold the key
  (`Ctrl+Alt` by default, any modifier under *Settings → basedpython → Inlay hints*) and
  the push hints appear; let go and they are gone. They appear the instant the key goes
  down: the hints are already fetched and drawn as the key comes into it, not requested
  from `by` on the press.
- Intentions: add return type, convert to/from `data class`, wrap null-safe, explain
  anonymous named tuple.
- `buff` format-on-save (settings toggle) and import optimizer; code style settings page
  (line length, quote style).
- Transpilation views: side-by-side `.py` diff, go-to-generated, api.lock diff, in-place
  `.by` ↔ `.py` conversion.
- **New Project → basedpython** wizard; `pyproject.toml` `[tool.ruff]`/buff config
  completion; `out/` excluded from indexing.
- i18n message bundle, unit tests (lexer, file type), and `sinceBuild`/`untilBuild`
  compatibility range.
- Go to Symbol / Go to Class for `.by` files and Go to Related between `.by` and its
  generated `.py` in `out/`.
- Gutter run icons on `if __name__ == "__main__":` / top-level `main()`, and a `by test`
  run configuration.
- Quick Documentation (Ctrl+Q) and External Documentation for basedpython keywords,
  modifiers, and `?.` / `??`; **basedpython Syntax Quick Reference** action.
- Settings: format-on-save toggle, inlay-hint toggles (parameter/type/return), and LSP
  trace level (off/messages/verbose).
- Smart editing: Enter auto-indents after `:` block headers; Backspace dedents by a full
  indent step in leading whitespace.
- Environment UX: editor banner when `by` is missing (Install with uv / Configure), and a
  **uv sync** action; reusable binary-version helper.
- Run ergonomics: clickable `.by`/`out/*.py` paths in run consoles, a before-run
  `by build` task, and run-config path-macro expansion.
- Move Statement Up/Down (Ctrl+Shift+Up/Down) that respects indentation blocks.
- **Debug .by (pdb)** action: builds, then runs the generated `.py` under `python -m pdb`
  in an interactive console (frames are clickable).
- Source-mapped debugging for `by run` configurations: breakpoints in `.by` files, stepping,
  frames and variables reported against `.by` sources. Built on the platform's Debug Adapter
  Protocol client and `debugpy`; `by run`'s own `_by_sourcemap.py` is handed to pydevd with
  `setPydevdSourceMap`, so the line translation happens in the debuggee. Requires `debugpy`
  in the interpreter `by run` uses (`uv add --dev debugpy`). See
  [docs/debugging.md](docs/debugging.md).
- Test tree nodes navigate to their `.by` source, and a test class is its own node in the
  tree rather than being flattened into the file.
- Test configurations can be debugged, with breakpoints in `.by` test files.
- Exception breakpoints for `.by` programs (*Breakpoints | basedpython Exceptions*), with
  On raise / On termination.
- *basedpython Tests* tool window: the project's tests as `by run pytest --collect-only`
  finds them, grouped by directory, file, class, function and parametrized case, with
  Run / Debug on any node, Jump to Source on double-click, and collection errors shown
  in place. Offered only to basedpython projects; collects on first open and on Refresh.
- Test gutter icons follow that collection: a function pytest does not collect no longer
  offers to run — including a `def test_…` in a file pytest never collects, such as
  `main.by` — one it collects under a different naming convention now does, and the
  tooltip counts what the icon would run ("Run 2 collected cases"). Opening a `.by` file
  collects once in the background if nothing has yet, so the icons correct themselves
  without opening the tool window. The old `test_…` / `Test…` behaviour remains wherever
  the collection cannot answer: while that first collection runs, after one that errored,
  and in files written or edited since.
- Bundled distributions: `-PbundledBinariesDir=<dir> -PbundledPlatform=<slug>` packs `by` and
  `buff` into `<plugin>/bin`, and a *Bundled with plugin* environment source runs them — an install
  that needs no venv, no `PATH` and no download for the toolchain to work. One artifact per
  platform (the binaries are ~200 MB each), six in all including `windows-arm64`, built by the
  *Bundled distributions* GitHub workflow from the basedpython release assets. Each is a separate
  Marketplace version gated to its OS and CPU (`<depends>` on `com.intellij.modules.os.*` /
  `com.intellij.modules.arch.*`, which Marketplace has routed on since 2026.1), so an IDE is
  offered only the build it can run. Belt and braces for a direct download: `bin/platform.txt`
  records the target, and binaries that cannot run here are skipped during resolution rather than
  exec'd. The plain `buildPlugin` is unchanged and ships no binaries.
- Type Info (`Ctrl+Shift+P`) works in `.by` files. The caret's name gets the type `by` infers for
  it, pressed again the full hover — the signature and docstring. The action is driven by whichever
  `ExpressionTypeProvider` is registered for the caret's language, and with none for basedpython it
  was simply dead. `by` has no dedicated "provide type" request — its LSP surface is the standard
  one, its only custom entries being the `ty.printDebugInformation` / `ty.runManageCommand` commands
  — so the answer comes from `textDocument/hover`, whose payload the server builds type-first.
- Clause keywords pair up in `.by` files. With the caret on `if`, its `elif`s and `else` highlight
  with it; likewise `try`/`except`/`else`/`finally`, a loop and its `else`, and `match` with its
  `case`s. `Ctrl+Shift+M` (*Move Caret to Matching Brace*) now jumps between the head keyword and
  the end of the statement's last branch, which is new for an indentation-delimited language —
  PyCharm registers no code block support for Python at all. Chains follow the grammar, so two
  adjacent `if`s stay two statements, a `try` after an `else` is its own, and an `else` in a
  conditional expression or a keyword inside a string or comment is not a clause. Nothing here can
  come from the server: LSP has no paired-keyword request, and `textDocument/documentHighlight` —
  which is about occurrences of a symbol — answers `null` at every keyword position in `by`.

### Fixed

- Django templates get the `by` language server. `.html`/`.htm`/`.txt`/`.xml`/`.django`/`.dj`
  under a `templates/` directory are now handed to it, so tag and filter completion,
  `{{ book. }}` off a model's own fields, go-to-definition on `{% extends %}` / `{% url %}` /
  `{% block %}` and template diagnostics work in the editor. Only in a project with a
  basedpython marker — `.html` is far too common to claim outright.

- A debug session that cannot start now reports itself as a notification with an
  **Install debugpy** action (`uv add --dev debugpy` in a uv project, otherwise
  `pip install` into the interpreter that reported the failure), instead of an
  "Unhandled exception" error box. The program is no longer left to run to completion
  after Debug has already failed.
- Traceback frames in run consoles navigate to the line they name. `by run` rewrites
  tracebacks onto `.by` sources, but the console filter only understood `file.by:12:5`
  and not CPython's `File "…", line 12`, so every frame opened at line 1. pytest failure
  lines now resolve to the `.by` file they were transpiled from, too.
- "Explain Rule" works for `by`'s own rules. It invoked `by explain <code>`, but `explain`
  is a command group, so every checker-owned rule (as opposed to a `buff` lint code) failed
  with `unrecognized subcommand` and reported "no explanation".
- Breakpoints land on the statement rather than on the transpiler's prologue for it. A
  `.by` line that becomes several generated lines now pins to the last, not the first, so
  a breakpoint in a function with a mutable default argument stops with the argument bound
  to its real value instead of an internal `<object object at 0x…>` sentinel.
- Program output appears as it is produced. `by run` feeds its Python child through a pipe,
  which CPython block-buffers, so output only arrived when the program exited — useless
  while stepping.
- Stop actually stops a run. `by run` is a launcher that spawns the interpreter and waits,
  and a soft kill reached neither, leaving an orphaned debuggee holding its port; runs are
  killed as a process tree now.
- A debug session no longer hangs after the program finishes. `debugpy.listen()` spawns an
  adapter subprocess that outlives the debuggee and inherited its stdout pipe, so the IDE
  never saw EOF and the run stayed "running" until stopped by hand.
- Stray `ptvsd` / `debugpy` text no longer appears in front of program output. Those are
  the adapter's own DAP output events, echoed into a console that already shows the real
  process output.
- A debug session whose source map is unusable now says why. Two `.by` files with the
  same module path (`main.by` beside `src/main.by`) are transpiled to one generated file
  and the second wins, so the first never runs and no breakpoint in it binds; the warning
  used to blame a missing `_by_sourcemap.py` and suggest updating `by`, which was wrong on
  both counts.
- The test runner no longer risks `NoSuchClassError` on 2026.2. The SM test runner
  (`SMTRunnerConsoleProperties`, `OutputToGeneralTestEventsConverter`, `SMTestLocator`)
  left the core platform there and ships as a bundled plugin the plugin did not declare;
  it is now an optional dependency, so 2026.1 — where those classes are still in the
  platform — keeps loading too.
- The `by` version check no longer reports every up-to-date install as outdated. Its
  floor was `0.1.0` while basedpython is at `0.0.1a9`, so any `by` that reported a real
  version would have been flagged forever; it was quiet only because the binary answers
  `by unknown`.
- Running tests no longer executes the suite twice. The console was built by calling
  `startProcess()` a second time, which spawned a second `by run pytest`; every test showed
  up twice in the tree and Stop killed only one of the two processes.

### Changed

- Inlay hints are drawn in the editor's own font, at the editor's own size, on the code's
  own baseline — dimmed rather than boxed, the way VS Code draws them. The platform renders
  every LSP hint as UI-font small text inside a rounded grey pill, which in a language whose
  hints are almost all types (`: list[int]`, `: dict[str, int]`) reads as a foreign body
  wedged into the line: the glyphs don't line up with the code, the pill breaks the column,
  and a type in a hint looks nothing like the same type written out. There is no hook for
  the presentation alone, so the plugin now fetches `textDocument/inlayHint` itself
  (`lsp.inlay.ByInlayHintsProvider`) and the platform's own LSP rendering is switched off
  for `by`. The colour is a new `BASEDPYTHON_INLAY_HINT` key, themeable and set in both
  bundled schemes; a scheme that says nothing about it gets ordinary editor text faded
  halfway into the editor background, so hints read the same under any theme. Zoom,
  presentation mode and distraction-free mode carry the hints with them, which the
  platform's rendering cannot do — its font is one global checkbox for every language.
  The three toggles (parameters, types, return types) stay where they were, in
  *Settings | basedpython | Inlay hints*.

- A `by run` configuration created from a `.by` file is named after the module (`pkg.main`)
  rather than after the command (`by run pkg.main`), and basedpython run configurations use
  the basedpython icon.

- Extended IDE compatibility range to `262.*` (sinceBuild `261`). Verified Compatible
  against both IU-261.25134.67 and IU-262.6653.22.
