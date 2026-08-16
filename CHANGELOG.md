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
- Running a `def main` with required parameters **asks for them** instead of failing on
  argparse's `the following arguments are required` — a form generated from the signature (file
  chooser for a `Path`, checkbox for a `bool`, docstring as the description, values validated
  against the annotation), opened as the run starts however it was started, and cancelling it
  just doesn't run. Only asked when the run could not otherwise start; answers are remembered per
  module and seeded into the next context configuration, and stored as the run configuration's
  *Program arguments*. **Run with Arguments…** in the gutter opens the same form to change them,
  and a run that fails on the argparse error anyway gets a console link to it.
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
- Log points for `.by` files — a breakpoint that logs an expression instead of stopping,
  whose output reaches the run console the same way a `print` would. `.by` breakpoints now
  declare inter-line placement, which is what lets the IDE offer one from the gutter gap
  between two line numbers.
- Breakpoint expression fields (*Condition*, *Evaluate and log*, Evaluate Expression) are
  basedpython editors rather than plain text boxes, with highlighting and `by` behind them —
  and without one the inline log point field could not open at all.
- "`print()` call can be replaced with a log point" inspection, the counterpart of
  Kotlin's `println` one. The quick fix deletes the call and leaves a log point in the gap
  it occupied. Offered only where a log point says the same thing — not for `print()`, not
  for `file=` / `sep=` / `end=` / `flush=`, and not where the following line belongs to an
  outer block, since that is the line the log point binds to.
- Push-to-hint, with a setting for each kind of hint `by` computes: variable types, lambda
  parameter types, call type arguments, type argument names, numeric promotions, revealed
  types, inferred raises, call argument names, implicit parameters, implicit self, implicit
  arguments, inferred override, variance, reification, and a catch-all for anything a newer
  `by` adds. Each is *never*, *always*, or *while the push key is held*, so the hints you
  want at a glance and the ones you want only when asking are configured apart. A kind set
  to never is switched off in the server, which then never infers it. Hold the key
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
- Settings: format-on-save toggle, per-kind inlay-hint modes, and LSP trace level
  (off/messages/verbose).
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
- The test view collects tests written in `.py` as well as `.by`: `by run pytest` only ever
  sees the transpiled `.by` tree, so a second plain `pytest --collect-only` runs in the
  project and the two are combined. A `.py` test runs as `python -m pytest` in the project,
  where its `pyproject.toml` and `conftest.py` apply.
- Test nodes show the state of the last run — not run, running, passed, failed, skipped —
  from any run, with a parent showing the worst of its children. The tree also collects at
  project open and re-collects a couple of seconds after the project's sources change, so
  it keeps itself in step without pressing Refresh.
- Filter the test view by state (funnel on its toolbar): show only failed tests, only
  skipped, only what has not run yet. The toolbar says when something is hidden.
- *View Collection Output* in the test tool window's ⋮ menu: the exact commands, exit codes
  and output of the last collection, for when the view disagrees with pytest run by hand.
  A collection stopped by `by run`'s type check now says so, with the diagnostic count.
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
- Multiline strings show their trim margin. basedpython strips the indentation a triple-quoted
  string's lines share, the way Java strips a text block's, and a vertical line now marks the
  column it strips to — so the content of the literal is something you can read off the screen
  instead of work out. The margin is the least-indented line, and the line carrying the closing
  quotes counts even though it holds nothing else: move it and the whole literal's content
  changes, which is exactly the edit that was invisible before. Blank lines never pull it left.
  The rule runs beside the text and stops where closing quotes on a line of their own begin —
  they stand at the column it marks, so it points at them rather than down past them — and it
  is measured as the document changes, not when the daemon next gets round to the file, so it
  never lags a keystroke or leaves the previous column behind on the lines above.
  A docstring with text on its opening line (`"""Summary.`) keeps that line outside the margin —
  it starts after the quotes and has no indentation to lose — and a literal whose lines share no
  indentation is left unmarked, since nothing comes off it. It is drawn where the editor draws
  its own indent guide for that column and in that guide's colour: a string's lines are an
  indented run like any other, so the platform already rules a guide down them — on the interior
  lines only — and the margin continues it across the first and last rather than standing a
  couple of pixels off it or changing colour halfway down. Restyle it under *Settings | Editor |
  Color Scheme | basedpython | Multiline string trim margin*.
- Block keywords pair up in `.by` files. With the caret on `if`, its `elif`s and `else` highlight
  with it; likewise `try`/`except`/`else`/`finally`, a loop and its `else`, and `match` with its
  `case`s. A `def` highlights with the `return`s and `raise`s that leave it, and a loop with its
  `break`s and `continue`s — what a braced language shows as its exit points, including when the
  branch is written on one line (`if x: return 1`). Each binds to the block that owns it, so a
  nested `def` keeps its own `return`s and a `break` in a loop's `else` goes with the loop outside
  it. `Ctrl+Shift+M` (*Move Caret to Matching Brace*) now jumps between the head keyword and
  the end of the statement's last branch, which is new for an indentation-delimited language —
  PyCharm registers no code block support for Python at all. Chains follow the grammar, so two
  adjacent `if`s stay two statements, a `try` after an `else` is its own, and an `else` in a
  conditional expression or a keyword inside a string or comment is not a clause. Nothing here can
  come from the server: LSP has no paired-keyword request, and `textDocument/documentHighlight` —
  which is about occurrences of a symbol — answers `null` at every keyword position in `by`.

### Fixed

- A diagnostic's tooltip reads as the message it is. `by` writes every type and symbol it names in
  markdown code spans, and the platform hands the message to the tooltip unchanged — where it is
  read as HTML. So ``Object of type `<class 'int'>` is not callable`` arrived as *Object of type
  `` `` is not callable*: the backticks shown verbatim, the type itself swallowed by the HTML parser
  as an unknown tag. The tooltip is now escaped and its code spans marked up as code. The message
  itself is untouched — that is the plain-text side (Problems view, error stripe, *Copy problem
  description*), where a backtick is just how a type is quoted. Pairing the backticks cannot be
  exact, because nothing escapes the ones that come out of the code being checked and
  ``Type `Literal["`"]` is not assignable to `str` `` really is ambiguous. Three rules bound what
  that can cost: a run of backticks matches a run of the same length, a span never crosses a line,
  and a closer that would leave a `"` open is passed over for the next one — which recovers that
  exact case. Nothing is ever dropped or rewritten, so the worst outcome is a fragment styled as
  prose that should have been code. The same rendering reaches the docstring in Type Info
  (`Ctrl+Shift+P`), the `by explain rule` balloons, and `buff`'s diagnostics, which quote names the
  same way.

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

- Inlay hints are drawn in the editor's own font, at the editor's own size, on the
  code's own baseline — faded onto a faint tint rather than boxed, the way VS Code draws
  them. The platform renders every LSP hint as UI-font small text inside a rounded grey
  pill, which in a language whose hints are almost all types (`: list[int]`, `:
  dict[str, int]`) reads as a foreign body wedged into the line: the glyphs don't line
  up with the code, the pill breaks the column, and a type in a hint looks nothing like
  the same type written out. There is no hook for the presentation alone, so the plugin
  now fetches `textDocument/inlayHint` itself (`lsp.inlay.ByInlayHintsProvider`) and the
  platform's own LSP rendering is switched off for `by`. Both halves of the colour
  matter: the fade alone is how the IDE already draws code that does not run, so an
  untinted hint is indistinguishable from an unused symbol on the same line
  (`unused_local: int` had the name and the hint in one grey). The tint is the smallest
  mark that separates them, and is not the platform's capsule — it is sized to the text
  box rather than the line box, barely rounded, and leaves the glyphs on the code's
  column. Colours come from a new `BASEDPYTHON_INLAY_HINT` key, themeable and set in
  both bundled schemes; a scheme that says nothing about it gets both derived from its
  own text and background, so hints read the same under any theme. Zoom, presentation
  mode and distraction-free mode carry the hints with them, which the platform's
  rendering cannot do — its font is one global checkbox for every language. The three
  toggles (parameters, types, return types) stay where they were, in *Settings |
  basedpython | Inlay hints*.

- A hint takes exactly the room the same text takes as code, so a line that leaves an
  annotation to the hints lands character for character on one that writes it out — `a =
  A(1)` and `a: A[int] = A(1)` both render as `a: A[int] = A[int](t=1)`, ending in the
  same place. Two things were spending pixels that are not a hint's to spend: two of
  padding either side of the tint, and a width rounded up rather than to nearest. The
  editor lays a line out by accumulating fractional advances and flooring each position,
  so the same characters span 62px starting on one column and 63px on the next and there
  is no single integer that is 'the width as source' — rounding up looks right at
  whichever column it was measured at and pads every hint in the same direction
  everywhere else, which is how three hints on one line drifted against two on the next.

- A `by run` configuration created from a `.by` file is named after the module (`pkg.main`)
  rather than after the command (`by run pkg.main`), and basedpython run configurations use
  the basedpython icon.

- Extended IDE compatibility range to `262.*` (sinceBuild `261`). Verified Compatible
  against both IU-261.25134.67 and IU-262.6653.22.
