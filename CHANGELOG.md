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
- Tools menu group with six `by`/`buff` actions: Transpile, Reverse Transpile,
  Generate api.lock, Check Project, Clean Caches, Explain Rule. Transpile actions also in
  editor and project-view popups.
- Settings page (*Languages & Frameworks → basedpython*) and an optional status bar
  widget surfacing real-time `by` / `buff` LSP state.
- Color settings page; file templates (Empty, Class, Data Class, Protocol) under
  *New → basedpython File*; live templates `cdef`, `dcl`, `fdcl`, `ecl`, `proto`, `ovr`,
  `nt`, `let`.
- Semantic highlighting annotator (no LSP needed): built-in names, `self`/`cls`,
  decorators, type names, declarations, parameters, keyword args, string escapes,
  f-string interpolation.
- Structure view, breadcrumbs, and code folding, from the server rather than from the
  plugin: the platform's LSP client builds all three out of `textDocument/documentSymbol`
  and `textDocument/foldingRange`, which the `by` descriptor leaves enabled.
- Surround-with descriptors and postfix templates (`.if`, `.for`, `.while`, `.not`,
  `.return`/`.ret`, `.print`, `.len`, `.var`, `.none`, `.notnone`, `.else`); added live
  templates for the main guard (`main`), async def (`adef`), match/case (`match`), enum
  (`enum`), pytest test and fixture (`test`, `fix`), property (`prop`), data-class field
  (`field`), try/except (`try`), with (`with`), and comprehension (`compr`).
- Inspections: mutable default argument, binary-not-configured (quick-fix to settings);
  spellchecking for comments/strings/identifiers; TODO/FIXME indexing.
- Log points for `.by` files. Hover the gutter between two line numbers and click — or press
  `Ctrl+Alt+F8` — to add a breakpoint that logs an expression instead of stopping, and type
  the expression in the field that opens in the gap. Enter commits, Escape abandons, and one
  that is never filled in removes itself rather than leaving an icon that does nothing.
  In IntelliJ IDEA this is the IDE's own feature, whose modules are bundled with its Java
  plugin and built on the JVM debugger; PyCharm has none of it, so an equivalent ships here
  and the two never both run.
  Ctrl+Z takes a log point back, including the one the `print` quick fix leaves — undo used
  to restore the deleted call and keep the log point, logging the value twice.
  During a debug session the output reaches the run console the same way a `print` would.
- Breakpoint expression fields (*Condition*, *Evaluate and log*, Evaluate Expression) are
  basedpython editors rather than plain text boxes, with highlighting and `by` behind them.
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
  anonymous named tuple, explain rule.
- `buff` format-on-save and import optimizer; code style settings page
  (line length, quote style).
- Transpilation views: side-by-side `.py` diff, go-to-generated, api.lock diff, in-place
  `.by` ↔ `.py` conversion.
- **New Project → basedpython** wizard; `pyproject.toml` `[tool.ruff]`/buff config
  completion; `out/` excluded from indexing.
- i18n message bundle, unit tests (lexer, file type), and `sinceBuild`/`untilBuild`
  compatibility range.
- Go to Symbol for `.by` files, which is the platform's LSP client asking `by` for
  `workspace/symbol`, and Go to Related between `.by` and its generated `.py` in `out/`,
  which is this plugin's.
- Gutter run icons on `if __name__ == "__main__":` / top-level `main()`, and a `by test`
  run configuration.
- Quick Documentation (Ctrl+Q) and External Documentation for basedpython keywords,
  modifiers, and `?.` / `??`; **basedpython Syntax Quick Reference** action.
- Settings: per-kind inlay-hint modes and LSP trace level (off/messages/verbose).
- Smart editing: Enter auto-indents after `:` block headers; Backspace dedents by a full
  indent step in leading whitespace.
- Environment UX: editor banner when `by` is missing (Install with uv / Configure), and a
  **uv sync** action; reusable binary-version helper.
- Run ergonomics: clickable `.by`/`out/*.py` paths in run consoles, a before-run
  `by build` task, and run-config path-macro expansion.
- Move Statement Up/Down (Ctrl+Shift+Up/Down) that respects indentation blocks.
- Local refactorings for `.by`, driven from the selection and computed here rather than asked of
  the server: **Extract Variable**, **Introduce Constant**, **Inline Variable**, and **Extract
  Method** — the last inserting into the nearest enclosing `def`, de-indenting the body and
  re-indenting it at its new depth.
- **Open basedpython REPL** (*Tools | basedpython*): an interactive console running
  `by repl`, falling back to `by run` where that subcommand is not there.
- **Download prebuilt binaries**: fetches the `by` and `buff` for this OS and CPU into
  `~/.basedpython/bin` and points the settings at them, for a machine with no toolchain and no
  wish to build one.
- **Explain Transpilation** on a `.by` file or selection: what the transpiler did to it and why,
  answered by the server over `by/explainTranspilation`.
- **Toggle basedpython Watch Mode** (*Tools | basedpython*): a debounced background `by build`
  after each `.by` is saved, per project, so rapid saves coalesce into one build.
- **Export / Import Settings**: the project's `BasedPythonSettings` written to a `.xml` and read
  back, so a working configuration can move between machines and projects.
- **Debug .by (pdb)** action: builds, then runs the generated `.py` under `python -m pdb`
  in an interactive console (frames are clickable).
- Source-mapped debugging for `by run` configurations: breakpoints in `.by` files, stepping,
  frames and variables reported against `.by` sources. Built on the platform's Debug Adapter
  Protocol client, with two backends behind one setting — see the `bpd` entry below for the
  default. Under the `debugpy` backend, `by run`'s own `_by_sourcemap.py` is handed to pydevd
  with `setPydevdSourceMap`, so the line translation happens in the debuggee, and `debugpy`
  has to be in the interpreter `by run` uses (`uv add --dev debugpy`). See
  [docs/debugging.md](docs/debugging.md).
- **`bpd` drives a `.by` debug session by default**, with `debugpy` kept as the other choice in
  *Settings | basedpython*. `bpd` is PEP 669 native — a line with no breakpoint on it is
  `DISABLE`d the first time it is seen — it reads `_by_sourcemap.py` itself and substitutes
  locations inside its own agent rather than through pydevd's generated-code support, and it
  verifies the digest of both files before mapping anything, where the `debugpy` path maps a line
  whether or not the pair still matches. It is also the only backend that answers `bpd/facts`,
  which is what the data-flow analysis is seeded from. `debugpy` stays reachable because it needs
  no extra binary and because a bug in one backend should not leave `.by` undebuggable.
  The two are not the same shape underneath: `debugpy`'s adapter lives inside the debuggee and the
  IDE dials in, so that is an `attach`; `bpd` *is* the interpreter `by run` starts, so that is a
  real `launch` of `_by_runner.py` relative to bpd's own working directory — the temp tree
  `by run` transpiled into, which the IDE cannot name because it never chose it. A missing `bpd`
  is refused while the launch arguments are built, and does not silently fall back to `debugpy`.
  The backend is stored as a string rather than as the enum, so a settings file written by a newer
  plugin falls back to the default instead of failing to load.
- Custom `bpd` events reach the IDE: `ByDapClient` declares `@JsonNotification` handlers, which
  lsp4j binds by reflecting over the client's runtime class, so an event body arrives as a
  `JsonObject` with nothing discarded for want of a field. `debugpy` answers `unknown command`
  and is unaffected. Two lifecycle steps are taken back from the platform along the same seam: a
  refused start now reports what the adapter said instead of escaping a coroutine into the log as
  an unhandled exception, and a `stopped` event for the thread already on screen is applied rather
  than queued — which is what DAP prescribes after `restartFrame` and `goto`, and what left the
  highlight where the code no longer was.
- Data-flow inlays over a stopped program: with a session suspended, `by` decides which branches
  the stop settles and what value a name will hold below it, seeded from the readings `bpd` proves
  for the names in scope (`bpd/facts` in, `by/dataFlowAt` out). A decided condition and a decided
  value are drawn in the margin under their own attributes keys, so a scheme can tell one from the
  other. A kind the pass does not know is dropped rather than guessed at, so an older plugin
  against a newer server draws what it understands and skips the rest.
- **Hot reload** replaces a `.by` — and a plain `.py` — in the running program without restarting
  it, through the platform's own hot-swap toolbar and button. `by` re-stages the file into the tree
  the program runs out of and `bpd` takes the list with the source map moving in the same message,
  so the debugger never has to transpile anything. Offered on `bpd` sessions only: `bpd/replaceCode`
  is bpd's own request, there is no capability flag on the wire to believe for a custom request, and
  a `debugpy` session would otherwise raise a button whose only possible answer is that the adapter
  does not know it. Both kinds of file take one route, because `by run` copies every module into that
  temp tree, a `.by` transpiled and a `.py` copied — a `.py` is as much *not* the file on disk as a
  `.by` is. Changed documents are saved first: the platform's collector tracks documents and a
  replacement is made from files, so without that, pressing the button with an unsaved editor asked
  for a replacement with the content already there, got back `applied`, and told the user the
  process matched their screen when it did not. Refusals are a balloon tracked through
  `HotSwapStatusNotificationManager`, so the next hot swap clears them; the console keeps the
  account of what an applied replacement actually changed.
- Test tree nodes navigate to their `.by` source, and a test class is its own node in the
  tree rather than being flattened into the file.
- Test configurations can be debugged, with breakpoints in `.by` test files.
- Run and check configurations, and the gutter run icon, are offered on the plain `.py` files
  this plugin owns (see *Settings | basedpython*, and PyCharm keeps `.py` by default).
  `by run pkg.script` starts `pkg/script.py` from where it was written. A `.py` beside a `.by`
  of the same module name is skipped, since `by run` would start the transpiled one; and a bare
  `def main(…)` in a `.py` gets no icon and no argument form, because reading a top-level `main`
  as the program's command line is a basedpython transform and a `.py` runs as written.
- Breakpoints and log points can be set in a basedpython project's plain `.py` files, and hit.
  They need no source map — `by run` transpiles only `.by`, so a `.py` module is loaded from
  where it was written — but two things had to change: the line breakpoint type now claims a
  `.py` whenever this plugin owns the file type (in PyCharm the Python plugin keeps it unless
  *Settings | basedpython* says otherwise, and only one type ever claims a line, so no
  "choose a breakpoint type" popup appears), and a run's working directory is now put on
  `PYTHONPATH`. That last one fixes a plain run too: `by run` makes its temp tree
  `sys.path[0]`, so a project mixing `helper.py` with `main.by` used to die on
  `ImportError: No module named 'helper'` while `by` type-checked the same import happily.
- **Reset Frame** works under the bpd backend, on **any** frame in the stack. `restartFrame`
  is a DAP request bpd implements and advertises, and the platform's DAP client never wires
  it to the IDE action, so it was grey no matter what; `ByRestartFrameHandler` bridges the
  two. It is not the JVM action of the same name — Python cannot pop a frame, so bpd either
  runs the frame again where it stands, with the locals a fresh call would not have bound
  put back to unbound, or forces it to return and runs the caller's line again so the
  interpreter builds a frame that has never run. A frame below the top is reached by forcing
  the frames above it out, innermost first; they are gone rather than suspended. Which of
  those a request gets, and every refusal, is bpd's to decide and to report. Offered on what
  the adapter advertises, so it is grey under debugpy, which does not implement the request.
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
- *Settings | Languages & Frameworks | basedpython | Modules*: the parts a project is built from,
  created, changed and removed. A *module* is what uv calls a workspace member — its own directory
  and `pyproject.toml`, sharing the project's lock file and environment, importable by its
  siblings — and the page lists each one with its type, path, version, required Python, and the
  siblings that depend on it. *New module* scaffolds it and lists it in the workspace in one uv
  command, so a project that is not a workspace yet becomes one by getting its first module;
  editing one changes its metadata and ticks or unticks which siblings depend on it; removing one
  stops every dependent declaring it before it stops being listed, and deleting its files is a
  separate checkbox that is off by default. The dialog shows the `uv init` it is about to run.
- Renaming a module renames everything it is called: the import package under `src/`, the directory,
  `[project] name`, the workspace `members` entry, every sibling that declares it, and the `import`
  statements in code. That last one is asked of the `by` server before anything moves, through the
  protocol's own `workspace/willRenameFiles` — which the server gained for this. The Name field is
  editable only when the running `by` advertises it; an older one gets the field disabled with the
  reason, rather than a rename that leaves every import naming a module that is gone.
- The plugin edits `pyproject.toml` itself for exactly two things uv has no command for — taking a
  `members` entry back out, and setting a project's own version, description or `requires-python`.
  Those edits rewrite the lines in question and leave the rest of the file alone: comments, array
  formatting and line endings all survive.
- *basedpython Environment* tool window: which environment this project runs in, what is
  installed in it, and the one thing to press when something is wrong. It answers what an
  interpreter dropdown does not — whether there *is* an environment, whether it matches what the
  project declares, and what to do about it — with a banner naming the single problem and a
  *Set Up* button that installs the environment manager, creates the environment and syncs it, as
  far as this project needs. A healthy project gets no banner at all. Add and remove dependencies,
  upgrade past the lock file's pins, and pick the Python version, with installed and downloadable
  versions offered together.
- Dependencies in that window are a tree grouped by where they are declared — the main list,
  optional extras, and named groups such as `dev` — with each requirement's own dependencies
  beneath it. A flat package list answers "what is installed" and nothing else: it cannot say
  which of forty rows the project actually asked for, or which are test-only, and both change
  what you do next. The grouping also makes the operations exact rather than approximate.
  *Remove* is offered only on a requirement the project declares — never on one pulled in by
  another package, which no tool can remove — and it removes it from the list it is declared in,
  with a selection spanning groups becoming one command per group. *Add* goes into whichever
  group is selected, and a group that does not exist yet can be typed and will be created.
- The tree is what the project resolves to; the installed list is what the machine has. Where
  they disagree, the row says so: a package the environment does not have is greyed, one
  installed at a version other than the lock's is called out. That is drift shown on the row it
  happens on, rather than only asserted in a banner.
- The environment window shows what is happening while it happens. Adding, syncing, upgrading or
  removing puts a spinner on each package as it is worked on, with what it is doing beside it —
  *downloading…*, *installing…*, *removing…* — and the header names the package currently being
  fetched and how big it is. The state is read from the tool's own output, which reports each
  download as it starts and again as it finishes even when piped rather than attached to a terminal,
  so several concurrent downloads each spin on their own row. A package already in the cache is
  never downloaded and so never spins, which is honest: it really is instantaneous. Removals are
  marked before the command runs, since the tool says nothing per package while uninstalling.
- The `dev` group is shown even when it is empty, as the main list already was. Both are places a
  project *has* rather than lists it happens to have filled, so removing the last entry should leave
  a heading to add to rather than making the group vanish and need conjuring back by typing its name
  into a dialog. Every other group is still dropped when empty, where an empty heading is only noise.
- Pre-releases are visible in *Add Package* rather than implied. A package index reports its newest
  *stable* release as "latest", so a project whose real work happens in alphas — `basedpython`
  itself, whose only stable is an ancient `0.0.0` beneath eight `0.0.1aN` — was described as if none
  of its actual releases existed. The summary now names the newest pre-release when it is newer than
  the index's latest, and the version list marks each pre-release as one. They were always in the
  dropdown; nothing said what they were, and nothing said one existed before you opened it. Both
  halves are shown deliberately: a resolver will not choose a pre-release on its own, so `latest` is
  still what you get by default, and pinning is the only way to the alpha.
- *Add Package* has a results list under the field, instead of an editor completion popup. Typing
  filters the package catalogue and the list is repopulated in place, so it no longer blinks on
  every keystroke; Up/Down move through it from the field and Enter or a double-click picks a name.
  This is the shape PyCharm's own package dialog uses, and adopting it retires a run of defects that
  all came from the completion machinery's defaults rather than from anything specific to packages:
  the autopopup does not fire unless the provider overrides `acceptChar`; its matchers accept
  subsequences (`ba` matching `b-aws-dynamodb-backup`) and, in `PlainPrefixMatcher`'s one-argument
  form, substrings (the `ba` in `backup`); and a lookup assumes the answer for a short prefix
  contains the answer for a longer one, which a capped query over 872,009 names cannot promise —
  forcing a restart per keystroke fixed the results and produced the blinking.
- *Add Package* searches the package catalogue as you type, and gained a **version
  picker**. The list is honest about what it contains: a release the maintainer withdrew is marked
  *yanked* with their reason, and one whose `requires_python` this environment does not satisfy is
  marked with what it needs. Neither is filtered out — a version missing from a list with no
  explanation is a worse outcome than one that says why picking it is a bad idea. Picking a version
  pins it, preserving any extras already ticked; the two pickers edit different parts of the same
  requirement and do not undo each other.
- Judging that needed PEP 440 — sorting versions as text puts `1.9` above `1.10`, and a real
  `requires_python` like urllib3's `>=2.7, !=3.0.*, !=3.1.*, <4` cannot be evaluated by any amount
  of string handling. Versions, pre-releases, epochs and the specifier operators are implemented and
  tested against specifiers taken from PyPI itself.
- *Add Package* now knows what is in the index. Typing a name shows what the package is and its
  newest version, and its declared extras appear as checkboxes that write themselves into the
  requirement — so `httpx[http2]` is something you can discover instead of something you have to
  already know, which previously meant reading the project's own documentation. The field is still
  free text and still takes anything the tool accepts, and everything about it works with the index
  unreachable, the catalogue not yet downloaded, or the package private.
- That data is cached on disk under `~/.basedpython/cache`, keyed by the index it came from so a
  private mirror and the public PyPI never share one. Two lifetimes, because the two things age
  differently: the package catalogue weekly, per-package metadata daily. Nothing is fetched until
  Add Package is opened — a user gesture — and never on project open.
- The catalogue is 872,009 names, so it is streamed out of the index rather than parsed into memory,
  stored as a sorted file, and searched in place with a binary search over byte offsets. Holding it
  as a list would be roughly 40 MB of heap, permanently, for data only read while a dialog is open.
- Adding or removing a dependency now leaves `pyproject.toml` in step with what actually happened
  to it. These commands are separate processes that read the file off disk and rewrite it, knowing
  nothing about the editor showing it, which left a gap on both sides: unsaved editor changes were
  silently overwritten, because uv read the old file and wrote it back; and afterwards the IDE went
  on displaying the stale content until something else happened to make it look again. Unsaved
  changes to the manifests are now flushed before a command runs, and the files re-read once it is
  done — including one it created, such as a first `uv.lock`. The environment directory is
  deliberately left alone: it is thousands of files after a sync, and nothing here reads it through
  the IDE's virtual file system.
- The *Python version* button carries the version it is on — `Python 3.12` — rather than an icon.
  Nothing in the platform's icon set means "Python interpreter" without depending on the Python
  plugin, and the version is more use than any glyph: the button says what you are running and
  offers to change it in the same place. Its popup also opens at the button now, instead of at the
  bottom of the tool window.
- *Refresh* is gone from the environment toolbar, and what is left is named *Re-read*. Sitting
  beside *Sync*, it was a coin flip: both were named after refreshing and both wore a circular
  arrow, while one installs packages and takes minutes and the other re-reads state and takes no
  time. The view already re-reads itself on open, whenever a manifest changes, and after every
  operation, so the button was mostly there to be mistaken for the other one; it keeps its place in
  the right-click and ⋮ menus for what the view cannot see — something installed straight into the
  environment behind the manifests' back.
- Reading that tree never writes to the project. `uv tree` re-locks and writes `uv.lock` unless
  told not to, so the plugin always asks for the frozen one — otherwise opening a project would
  create a lock file, and every save of `pyproject.toml` would rewrite it. A project with no lock
  has nothing resolved to show, and falls back to the flat installed list, which is the right
  answer rather than an error.
- That window reads the environment's Python version from its own `pyvenv.cfg` rather than by
  running the interpreter, so it still reports what an environment was built on when its
  interpreter is broken — a Homebrew upgrade, or a project copied between machines — which is
  when it is most worth knowing. An environment whose interpreter is gone is reported as no
  environment rather than as a healthy one.
- uv is the only backend today, and the code is shaped so that is a fact about the list rather
  than about the design: `env.manager.EnvBackend` decides which projects a manager claims, where
  it puts the environment, what argv each operation becomes, and how to read its own output, so a
  conda or pixi backend is one object added to `EnvBackends.ALL` and nothing else. What each
  command uv is given actually is, and what its output parses to, is checked against a real uv by
  a live test rather than argued about.
- uv installs itself when it is missing, from its release binary into the plugin's own
  `~/.basedpython/bin` — not by piping its install script into a shell. Nothing outside that
  directory is touched. An already-installed uv is also found in `~/.local/bin` and
  `~/.cargo/bin`, which is what fixes "uv is installed but the IDE cannot find it": an IDE started
  from a launcher inherits the session's `PATH`, not a login shell's.
- Installing or syncing from anywhere in the plugin now restarts the language servers and
  re-evaluates the editor banners afterwards. Both the *Install with uv* banner and the *uv sync*
  action used to spawn uv themselves and do neither, which is how a successful install could leave
  the editor still insisting `by` was missing.
- Nothing in any of this runs on its own. Reading the environment starts no process unless there
  is one to ask about, and creating, syncing, adding or downloading only ever happens from a
  click — the same rule that has always kept `uv run` out of automatic binary resolution.
- *basedpython Tasks* tool window: the repository's own checks, listed where they can be run —
  the built-in npm view, for the task runners a Python project uses. Reads
  `.pre-commit-config.yaml` (hooks under the repo they come from), `lefthook.yml` and its
  variants (each git hook with its commands, scripts and jobs), and `[tool.pyprojectx.aliases]`
  in `pyproject.toml`. Double-click runs a row as a run configuration, so it appears in the run
  combo with Rerun and can be edited or saved; the row then carries how it went, and a group
  turns red when anything under it failed. *All files* is on by default, since a hook run with
  nothing staged would otherwise inspect nothing and report success. `.pre-commit-config.yaml`
  runs under `prek` when that is the only one of the two installed, `pre-commit` otherwise, and
  pyprojectx aliases run through the `./pw` wrapper checked into the repository.
- Finding those tasks starts no process — it is a fixed list of file names read at the project
  root — so the list follows the configuration as it is edited, and the tool window appears when a
  project grows its first hook config. A hook that declares `stages: [pre-push]` is run with its
  stage rather than skipped silently by the default one; a lefthook `scripts:` entry says in the
  row that it runs its whole git hook, because `lefthook run` has no flag that selects a script.
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
  was simply dead. `by` has no dedicated "provide type" request — nothing in its custom surface
  (`by/transpile`, `by/transpileForBuild`, `by/explainRule`, `by/explainTranspilation`,
  `by/dataFlowAt`, and the `ty.printDebugInformation` / `ty.runManageCommand` commands) answers
  one — so the answer comes from `textDocument/hover`, whose payload the server builds type-first.
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
- *Tools | basedpython | Dump Inlay Hint Record* — and a check that runs whenever the daemon
  finishes — for a hint drawn more times than it should be. Each pass records what it collected,
  which run of the collector collected it and where it sat in `by`'s reply; the audit reads that
  against the inlays the editor is actually showing and says which of the three possible causes it
  was: the collector ran twice in one pass, `by` sent the hint twice, or the platform is holding an
  inlay the pass never handed it. The check writes to `idea.log` only when something is doubled, and
  only once per set of findings; the action dumps the whole record — findings, everything collected,
  everything drawn — to the log and the clipboard whether or not anything is wrong, which is how a
  doubling that is on screen *now* gets written down before the next keystroke repairs it.

### Fixed

- The bundled live templates expand. All nineteen of them — `dcl`, `cdef`, `main` and the rest —
  loaded, appeared in *Settings | Editor | Live Templates*, and expanded nowhere. A template's
  `<context>` names the context by the id the `liveTemplateContext` extension point declares, which
  is `BASED_PYTHON`; every template instead named `basedpython`, which is the string passed to the
  context type's constructor and which the platform reads as the *presentable name*, not the id. So
  `TemplateContext.isEnabled` found no own value for `BASED_PYTHON`, fell through to the base
  context, and read the `OTHER` the same block sets to `false` — enabled in no context at all. It
  looked healthy from every direction except the editor, which is why it went unnoticed: the
  templates are listed, tickable, and editable in settings whichever id they carry. A test now
  expands every bundled template in a `.by` file, and asserts one does not expand in a `.txt`.

- Inlay hints are no longer sometimes drawn twice (`def f() → 1 → 1:`). The inlay pass flattens the
  file and pushes every element through *one* collector with
  `JobLauncher.invokeConcurrentlyUnderProgress` — a chunk per pool thread — so the flag saying "the
  server has already been asked for this file" was being read and written from several threads with
  no synchronisation. Two threads getting past it both asked `by` and both added the whole file's
  hints; the sink keeps a list per offset and `InlineInlayRenderer` draws that list end to end, so
  every hint in the file came out twice. The flag is now an `AtomicBoolean` claimed with
  compare-and-set. It looked like the platform misbehaving because it is intermittent and the next
  pass silently repairs it, which is also why it now leaves a record — see *Dump Inlay Hint Record*.

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
  with `unrecognized subcommand` and reported "no explanation". It has since stopped spawning
  the CLI at all and asks the running server over `by/explainRule` / `buff/explainRule`.
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
- Postfix templates no longer offer themselves part way through an ellipsis. Typing the third
  dot of `...` put `print`, `len`, `if` and the rest in the completion popup, and accepting one
  produced `print(bool()..)`. `PostfixLiveTemplate` deletes the dot that triggered it before asking
  whether a template applies, so what it asked about was `bool()..` — and the backwards scan for the
  expression walks over dots, so it read that as the expression `bool()..` and every template said
  yes. No basedpython expression ends in a dot, so a dot still in front of the caret now means there
  is nothing there to apply a template to.

### Changed

- The minimum IDE is 2026.2 (`sinceBuild` 262), where it said 261. Log points are built on
  the platform's inter-line breakpoint API — `XLineBreakpointVerticalPlacement`,
  `XLineBreakpointAdditionalInfo`, `InterLineShiftAnimator`, `InterLineBreakpointConfiguration`
  and the rest, plus `XBreakpointManager.addLineBreakpoint` / `findBreakpointsAtLine` and DAP's
  `applySuspendContext` — and every one of those arrived in 2026.2. They are compile-time
  references across eight files, so on 2026.1 the debugger and every gutter log point were a
  `NoClassDefFoundError` waiting to be touched. Marketplace's verifier reported all 28 of them
  against IU-261.27258.48; the range had been verified against 2026.1 before the log point work
  landed, and nothing re-checked it afterwards. Also fixed there: two `BasedPythonBundle` keys
  the Python version picker asks for and the bundle never had
  (`env.python.recreate.onProject`, `.onVersion`) — the only two binary incompatibilities the
  verifier found against 2026.2 itself.

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
  rendering cannot do — its font is one global checkbox for every language. The hint
  settings stay where they were, in *Settings | basedpython | Inlay hints*, since
  grown into one mode per kind — see the push-to-hint entry above.

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

- Reformat and Optimize Imports on save and on commit actually apply their edits, and there is
  one row for it rather than three. A workspace edit carries its edits in `documentChanges` or in
  `changes`, and the server picks by what the client claimed to understand — the platform sets
  `WorkspaceEditCapabilities.documentChanges`, so `buff` always answered in `documentChanges`
  while this read only `changes`: always null, always an empty edit list, always a silent no-op.
  Both shapes are read now, which fixes Optimize Imports (`Ctrl+Alt+O`) along the same path.
  The plugin's own "reformat and optimize imports" row is gone from both surfaces, because
  *Reformat code* and *Optimize imports* already sit at the top of Actions on Save and among the
  commit options and both reach `buff` for the files this plugin owns. What is left is named for
  what it does rather than for the binary that does it, and is no longer restricted to `.by` —
  which files the fixes apply to is the formatter's own answer, and it serves `.py` and `.pyi`
  too. Those two settings also had checkboxes on the plugin's own settings page, so whichever
  page was applied last quietly turned the other's tick back off; Actions on Save is now the only
  place they live.

- A `by run` configuration created from a `.by` file is named after the module (`pkg.main`)
  rather than after the command (`by run pkg.main`), and basedpython run configurations use
  the basedpython icon.

- Docstrings render in the editor. With *Render documentation comments* on, a `.by` docstring is
  drawn as formatted prose in place of its source, with the gutter control to fold it back — what
  Java and Kotlin have had for their doc comments, and what PyCharm has never done for a Python
  docstring.

  All of it is the server's answer. Which string literals are docstrings is read off
  `textDocument/semanticTokens`, where `by` marks each one `string` + `documentation` as it walks
  its own AST — so `async def`, decorated defs, overload implementations, `frozen data class`,
  `enum class`, `protocol`, nested classes, and the docstring under a `let` or an annotated field
  all arrive without one of them being named anywhere in the plugin. Which symbol a docstring
  documents comes from `textDocument/documentSymbol`, whose `selectionRange` is the name. What it
  says comes from `textDocument/hover` at that name, where the docstring has already been through
  `ty_ide`'s `docstring.rs` — PEP 257 trimming, `Args:` and `:param:` into headed sections,
  doctests fenced, bare `__dunder__` escaped — and only the last step, markdown into the IDE's
  documentation HTML, happens here, through the same converter the platform's own LSP hover uses.
  A file with no `by` server has no rendered docstrings rather than a guess at them.

  What cannot come from the server is the doc-comment element itself: the rendering pass is driven
  from PSI, the LSP client has no hook into it, and `.by` has a flat PSI with no doc-comment node —
  a docstring is a string statement, not a comment. So these are fake `PsiDocCommentBase` elements
  over the server's ranges, which is what `collectDocComments`' contract allows for exactly this
  case. A multi-line docstring arrives as one token per line and is joined back into one range.

  Asking the server means waiting for it, and the rendering pass does not. `by` answers no document
  request for a file it has not been sent `textDocument/didOpen` for — *"Document … is not open in
  the session"* — and the client sends that asynchronously, off the event that opened the file,
  while the pass runs the moment the editor appears. The first look therefore finds nothing, and on
  its own that would be permanent: `DocRenderPassFactory` skips the pass entirely while the PSI
  modification count is unchanged, so an empty answer computed a moment too early is what the file
  keeps until an edit — and a stub in a library is never edited. A `LspClientManagerListener` now
  asks the platform to run the pass again the instant the client tells the server about the file,
  which is exactly when that answer became wrong.

  Finding a docstring again by its range — which is what pressing the gutter control does — asks the
  question afresh rather than trusting that the pass already did, and what it remembers is kept
  against the file rather than against a `PsiFile` the platform is free to drop and rebuild. An item
  can outlive the answer that produced it, and a docstring whose control does nothing when pressed
  is the shape that takes.

  The one docstring the protocol cannot answer for is a module's: it documents the file, and hover
  needs a name. That one is rendered as text — escaped, paragraphs kept, nothing interpreted —
  because a raw docstring is not markdown, and reading one as markdown does not degrade so much as
  invent: `>>> int('0b100', base=0)` is three levels of blockquote to a markdown parser, and comes
  out as nested vertical rules with the `>>>` eaten.

- Docstrings render in `by`'s own stubs too, which needed the IDE's LSP client to be worked around
  rather than configured. `by` answers no document request for a file it has not been sent
  `textDocument/didOpen` for, and the platform's client declines to send one for anything outside a
  module content root — `LspClientImpl.isSupportedFile` asks `ProjectFileIndex.isInContent`, which
  is *content*, not *project*. A typeshed stub is a library file: `isInLibrary` is true, which is
  what Reader Mode asks, and `isInContent` is false, which is what the client asks. So every request
  about a stub came back empty, no docstrings were found in one, and goto-definition landed you
  somewhere the server would say nothing about. The protocol has no such restriction and neither
  does `by`, so the `didOpen` is now sent from here for exactly those files, once per file per
  server. Measured in a running IDE: a stub that produced no rendered docstrings at all produces
  thirty.

- The stubs `by` navigates into are library files, and the IDE is now told so. `by` carries typeshed
  inside its own binary and extracts it into its cache — `~/.cache/ty/vendored/typeshed` on macOS
  and Linux, `%LOCALAPPDATA%\ty\cache` on Windows. That is outside every content root, so the files
  are not project files, and they are ordinary writable files, so they are not read-only either:
  Reader Mode's default — libraries and read-only files — passed them over, which is why a docstring
  in typeshed sat there as source while the same docstring in a Kotlin library renders on sight.
  Registering the root as a library fixes that where it was actually wrong, and the rest follows —
  the stubs appear under External Libraries, join the "Project and Libraries" scope, and turn up in
  Navigate | File. Editing one is meaningless anyway; the extractor rewrites it from the binary.

- Pressing OK in *Settings | basedpython* no longer throws when the `.py` handling has changed, and
  no longer discards the settings below it. Telling the platform that file types changed fires a
  roots change, and `ProjectRootManagerImpl.fireBeforeRootsChanged` asserts write access — but a
  settings dialog applies under a write-*intent* read action, which is a weaker lock and not the
  same thing (`SettingsNonModalDialog.applyWithWriteIntent`). The assertion threw out of `apply()`
  half way down, so every setting written after that line — the per-capability `by` and `buff`
  toggles, the inlay redraw, the language-server reload — was silently skipped, and the one change
  that triggered it was not applied either. It now goes through a write action, the way the roots
  rescan beside it already did.
