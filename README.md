# basedpython for PyCharm / IntelliJ

<!-- Plugin description -->
PyCharm / IntelliJ support for **basedpython** — a Python superset (`.by` files) that
transpiles to pure Python 3.10+. Wraps the `by` language server (type checking,
completion, navigation) and the `buff` formatter/linter, and adds first-class file-type
support, run configurations, CLI actions, and editor tooling.
<!-- Plugin description end -->

## Features

### Language & editor
- `.by` file type with a dedicated icon.
- Lexer-driven syntax highlighting for Python and basedpython keywords (`final`,
  `override`, `abstract`, `static`, `protocol`, `let`, `newtype`, `public`, `private`,
  `data class`, `frozen data class`, `enum class`), strings, numbers, comments, and
  operators (including `?.` and `??`). Strings, comments, numbers and operators are colored
  here in every mode — LSP semantic tokens classify identifiers, not punctuation.
- Line commenting (`#`), brace matching for `()` / `[]` / `{}`, quote auto-pairing.
- **Multiline string trim margin** — basedpython strips the indentation a triple-quoted
  string's lines share, like a Java text block, and a vertical line marks the column it
  strips to. The margin is the least-indented line, and the closing `"""` counts even on a
  line of its own; blank lines never pull it left. Nothing is drawn when nothing is stripped.
- Configurable color scheme under *Settings → Editor → Color Scheme → basedpython*.

### Language servers (LSP)
- **`by` server** — completion, hover, goto definition/declaration/type, find
  references, rename, document highlight, signature help, diagnostics, inlay hints,
  semantic tokens, code actions, document/workspace symbols, folding, type hierarchy.
- **`buff` server** — formatting, organize-imports, fix-all, hover, and lint
  diagnostics. Capability-scoped so it never collides with `by`.
- Automatic `.venv` binary discovery (walks up for `.venv/bin/by`, `.venv/Scripts/by.exe`
  on Windows), with `PATH` fallback and manual override.
- "Restart basedpython LSP Servers" action to pick up env/binary changes.

### Run configurations
- First-class `by run`, `by build`, and `by check` configurations with editable working
  directory, extra CLI args, `--min-version`, and environment variables.
- Right-click a `.by` file to "Run by run &lt;module&gt;" or "Check with by". Plain `.py`
  files in a basedpython project get the same, when this plugin owns them (see
  *Settings | basedpython*): `by run` transpiles only `.by`, so `by run pkg.script` runs
  `pkg/script.py` as the interpreter finds it. A `.py` beside a `.by` of the same module name
  is skipped — the transpiled one is what `by run` would start.
- In a `.py` the run icon marks only `if __name__ == "__main__"`. Reading a top-level `main`
  as the program's command line — the generated argparse parser and the argument form — is a
  basedpython feature, and a plain `.py` gets exactly what it wrote.

### Debugging
- Debug a `by run` configuration and stop on breakpoints set in `.by` files — stepping,
  frames, variables and expression evaluation all report `.by` sources, not the transpiled
  output. Built on the platform's Debug Adapter Protocol client and `debugpy`; the line
  translation is done by pydevd in the debuggee, from the map `by run` already writes.
- Breakpoints work in the project's plain `.py` files too, and need no translation to:
  `by run` transpiles only `.by`, so a `.py` module runs from where it was written. In
  PyCharm the Python plugin keeps `.py` unless *Settings | basedpython* says otherwise, and
  a breakpoint follows whichever of the two owns the file.
- Requires `debugpy`: `uv add --dev debugpy`. `by run` picks `PYTHON`, else `python3` on
  `PATH`, and since every `by` launch here goes out with the project venv activated, that
  is the project's own interpreter. If `debugpy` is missing, the debugger says so and
  names the exact interpreter that could not import it.
- **Log points** — hover the gutter between two line numbers and click (or press
  `Ctrl+Alt+F8`) to add a breakpoint that logs an expression instead of stopping, and type
  the expression in the field that opens in the gap. Enter commits, Escape abandons; one
  that is never filled in removes itself. Needs *Breakpoints Over Line Numbers* — a toggle in
  the editor gutter's right-click menu, under *Appearance*, not in the Settings dialog —
  since without it the gutter has no row between two lines to click. See
  [docs/debugging.md](docs/debugging.md#log-points).
- Breakpoint expression fields (*Condition*, *Evaluate and log*) are basedpython editors,
  not plain text boxes.
- **Debug .by (pdb)** remains as a fallback that needs no extra package: builds, then runs
  the generated `.py` under `python -m pdb` with clickable frames.
- See [docs/debugging.md](docs/debugging.md) for how it works and what it does not cover.

### Actions (Tools | basedpython)
- **Transpile to .py** / **Reverse Transpile to .by** (also in editor & project popups).
- **Generate api.lock** — `by generate-api-file`.
- **Format with buff** (`Ctrl+Alt+Shift+L`) — works without the LSP.
- **Check Project** — `by check` in a Run console.
- **Clean buff Caches** — `buff clean`.
- **Explain Rule...** — looks up the rule under the caret via `buff rule` / `by explain`.

### Settings & status
- Project settings page at *Settings → Languages & Frameworks → basedpython*: binary
  paths (with **Test** buttons + live detection), per-server toggles, extra args, target
  Python version, format-on-save, inlay-hint modes, and LSP trace level.
- Inlay hints configured per kind, one row for each kind `by` computes: variable types,
  lambda parameter types, call type arguments, type argument names, numeric promotions,
  revealed types, inferred raises, call argument names, implicit parameters, implicit
  self, implicit arguments, inferred override, variance and reification. Each is *never*,
  *always*, or **push-to-hint**: drawn only while you hold a key (`Ctrl+Alt` by default,
  configurable). Hold it to read the inferred types, let go and the code is as you wrote
  it. A kind set to never is switched off in `by` itself, so it is never even inferred.
- Optional status bar widget showing live `by` / `buff` LSP health (green = running,
  gray = stopped, red = binary missing).

### Semantic highlighting
- Driven by the `by` LSP, which is always preferred when available: it knows the types and
  symbols behind each name, and it tracks the language on its own — new basedpython syntax
  colors correctly with no plugin update.
- String escape-sequence and f-string interpolation (`{expr}`) highlighting.
- With no server running, a `.by` file gets lexical color only. There is deliberately no
  second, guessed implementation of semantic color: basedpython isn't usable without `by`,
  and an approximation would only ever be a worse answer that also had to be kept in step
  with a language that keeps moving.

### Code intelligence
- Structure view, breadcrumbs, code folding and Go to Symbol / Go to Class come from the
  `by` server — document symbols, folding ranges and workspace symbols — through the
  platform's LSP integration.
- Surround-with (try/except, if, while, brackets) and postfix templates
  (`.if`, `.for`, `.while`, `.not`, `.return`/`.ret`, `.print`, `.len`, `.var`,
  `.none`, `.notnone`, `.else`).

### Inspections & intentions
- Spellchecking in comments, strings, and identifiers (camelCase / snake_case aware).
- TODO/FIXME scanning into the TODO tool window.
- Inspections: mutable default argument; `by`/`buff` binary not configured (quick-fix to
  settings); `print(…)` that could be a log point (quick-fix deletes the call and leaves a
  logging, non-suspending breakpoint in the gap it occupied).
- Intentions: add return type, convert to/from `data class`, wrap in null-safe access,
  explain anonymous named tuple.

### Formatting
- Format-on-save via `buff` (toggle in settings) and `buff`-backed import optimizer.
- Code style settings page (line length, quote style) under
  *Settings → Editor → Code Style → basedpython*.

### Transpilation views
- Show transpiled `.py` in a side-by-side diff, "Go to generated .py" in `out/`,
  api.lock diff viewer, and in-place `.by` ↔ `.py` conversion.

### Navigation & run
- Go to Symbol / Go to Class across `.by` files, and Go to Related to jump between a
  `.by` source and its generated `.py` in `out/`.
- Gutter run icons on `if __name__ == "__main__":` and top-level `main()` — one click
  runs the file's `by run` configuration.
- **Arguments for `main`** — `main`'s parameters are the program's command line, so running a
  `def main(a: int)` asks for them instead of failing: a form generated from the signature, with
  a file chooser for a `Path`, a checkbox for a `bool`, the docstring as its description, and
  values checked against the annotation before the run starts. Only asked when the run could not
  otherwise start, and remembered against the module, so Run keeps being one click. **Run with
  Arguments…** in the gutter changes them; they are editable as a plain command line, and live on
  the configuration as *Program arguments*.
- Test run configuration — runs pytest against the transpiled output via `by run pytest`,
  with results in the standard test tree. Gutter icons on `def test_...` and `class Test...`
  run a single test. Needs `pytest` importable by the interpreter `by run` uses.
- **Debug .by (pdb)** — builds, then runs the generated `.py` under `python -m pdb` in an
  interactive console with clickable frames.

### Hook tasks (pre-commit, prek, lefthook, pyprojectx)
- **basedpython Tasks** tool window — the repository's own checks, listed where they can be
  run, the way the built-in npm view lists a `package.json`'s scripts. Reads
  `.pre-commit-config.yaml` (hooks, grouped by the repo they come from), `lefthook.yml`
  (each git hook with its commands, scripts and jobs, groups included), and a
  `pyproject.toml` with `[tool.pyprojectx.aliases]`.
- Double-click a row to run it; the run is a real run configuration, so it lands in the run
  combo box with a Rerun button and can be edited or saved. The row then shows how it went,
  and a group turns red when anything under it failed.
- **All files** toggle (on by default): a hook run from a tool window with nothing staged
  would otherwise inspect nothing and report success.
- Runners come from the project first — `.venv/bin/pre-commit` before a global one — and
  pyprojectx runs through the `./pw` wrapper checked into the repository, so a clone needs
  nothing installed. `.pre-commit-config.yaml` is run with `prek` when that is the only one
  of the two installed.
- Discovery is a fixed list of file names read at the project root and nothing else — no
  process is started to find out what exists — so the list follows the config file as it is
  edited, and the window appears by itself when a project grows its first one.

### Docs & help
- Quick Documentation (Ctrl+Q) for basedpython keywords, modifiers, and the `?.` / `??`
  operators; External Documentation (Ctrl+Shift+I) opens the basedpython docs.
- **basedpython Syntax Quick Reference** action with a bundled cheat-sheet.

### Environment management
- **basedpython Environment** tool window — which environment this project runs in, what is
  installed in it, and the one thing to press when something is wrong. It answers the question
  the interpreter dropdown does not: *is* there an environment, does it match what the project
  declares, and what do I do about it.
- A banner names the single problem worth fixing, and **Set Up** fixes it: it installs the
  environment manager, creates the environment and syncs it, as far as this project needs.
  A healthy project gets no banner at all.
- **Dependencies are shown as a tree, grouped by where they are declared** — the main list,
  optional extras, and named groups such as `dev` — with each requirement's own dependencies
  beneath it. A flat list cannot say which of forty rows the project actually asked for, or
  which are test-only; both change what you do next.
- That structure makes the operations exact rather than approximate. **Remove** is offered only
  on a requirement the project declares — never on something pulled in by another package, which
  no tool can remove — and it removes from the right list. **Add** goes into whichever group is
  selected, and a group that does not exist yet can be typed and will be created.
- A package the environment does not actually have is greyed, and one installed at a different
  version than the lock resolves to says so in red — which is what "out of sync" looks like when
  you point at it.
- Reading the tree never modifies the project. (`uv tree` re-locks by default; the plugin always
  asks for the frozen one. A project with no lock file falls back to a flat installed list.)
- **`pyproject.toml` stays in step with what the tools did to it.** Adding or removing a
  dependency runs a separate process that rewrites the file on disk, so the plugin flushes any
  unsaved editor changes to it first — otherwise they are overwritten — and re-reads it
  afterwards, instead of leaving an open editor showing a version of the file that no longer
  exists.
- The **Python version** button carries the version it is on (`Python 3.12`), so the toolbar
  says what you are running as well as offering to change it.
- **Sync** installs, removes and updates packages so the environment matches the project; it is
  the only command in the toolbar that changes anything about the environment. **Re-read** — in
  the right-click and ⋮ menus, since the view keeps itself current on its own — only re-reads
  what is on disk.
- Add and remove dependencies, upgrade everything past the lock file's pins, and choose the
  Python version — installed ones and ones that will be downloaded, offered together, because
  "I want 3.13" should not require first knowing whether 3.13 is installed.
- The environment's Python version is read from its own `pyvenv.cfg` rather than by running the
  interpreter, so it is still reported for an environment whose interpreter is broken — which is
  when it is most worth knowing.
- **Add Package knows the index.** The requirement field completes against the whole package
  catalogue as you type, and once a name is recognised it shows what the package is, offers a
  **version picker**, and lists the **extras** it declares as checkboxes — so `httpx[http2]` is
  something you can discover rather than something you have to already know. The field still
  takes anything the tool accepts (`httpx>=0.27`, a git URL, a local path), and works unchanged
  with the index unreachable or the package private.
- The version list is **honest about what it offers**: a release the maintainer withdrew is
  marked *yanked* with their reason, and one whose `requires_python` this environment does not
  satisfy is marked with what it needs. Neither is hidden — a version missing from a list with no
  explanation is worse than one that says why it is a bad idea.
- That information is cached under `~/.basedpython/cache`, keyed by the index it came from, and
  fetched only when you open Add — never on project open. The package catalogue is refreshed
  weekly and per-package metadata daily, because neither changes faster than that.
- **uv today, pluggable by construction.** `env.manager.EnvBackend` is the seam a conda or pixi
  backend slots into: adding one is writing a single object and listing it in `EnvBackends.ALL`.
- **uv installs itself if it is missing** — the release binary, into the plugin's own
  `~/.basedpython/bin`. No shell profile is edited and no `PATH` is changed. An already-installed
  uv is found even when the IDE's `PATH` cannot see it, which is the usual case for an IDE
  started from a launcher rather than a terminal.
- **Nothing happens on its own.** Reading the environment starts no process unless one exists to
  ask about; creating, syncing, adding and downloading only ever run from a click.

### Project structure (modules)
- **Settings | Languages & Frameworks | basedpython | Modules** — the parts the project is built
  from, listed with their type, path, version, required Python, and which other modules depend on
  them. A *module* is what uv calls a workspace member: its own directory and `pyproject.toml`,
  sharing the project's lock file and environment, importable by its siblings.
- **New module** scaffolds it and lists it in the workspace in one command. A project that is not
  a workspace yet becomes one by getting its first module — the `[tool.uv.workspace]` table is
  written by uv, not by the plugin. Choose a library, an application, a packaged application, or
  a bare `pyproject.toml`, and optionally the module that should depend on it straight away.
- The dialog **shows the `uv init` it is about to run**, and keeps it current as you type. Two of
  the flags are there for a reason worth stating: `--vcs none`, or uv makes a second git
  repository inside your project, and `--no-pin-python`, or every module gets a
  `.python-version` that can disagree with the project's.
- **Editing a module** changes its version, description and required Python, and ticks or unticks
  the siblings that depend on it — which runs `uv add --package` / `uv remove --package`, so the
  `[tool.uv.sources]` entry that makes a sibling resolve locally is written by uv.
- **Removing a module** first stops every sibling declaring it, in each list it was declared in,
  then stops the project listing it. Deleting the files is a separate checkbox, off by default,
  as it is for the platform's own *Remove module*. A module a wildcard pattern covers cannot be
  un-listed by name, so keeping its files excludes the path instead — and the dialog says so.
- The plugin only edits `pyproject.toml` itself for the two things uv has no command for: taking
  a `members` entry back out, and setting a project's own version, description or
  `requires-python`. Those edits rewrite the lines in question and nothing else — your comments,
  array formatting and line endings survive.
- Creating a module deliberately **does not sync**. The environment view will report the drift and
  offer the button, rather than a new directory setting off a resolve that can reach the network.
- **Renaming a module renames all six things it is called.** The import package under `src/`, the
  directory, `[project] name`, the workspace `members` entry, every sibling that declares it — and
  the `import` statements in code, which are asked of the `by` server before anything moves
  (`workspace/willRenameFiles`). The directory is renamed only if it was named after the module, and
  keeps the spelling it had; a module kept in a differently-named directory stays where it is.
- The Name field is editable only when the running `by` says it can rewrite those imports. An older
  binary gets the field disabled with the reason, rather than a rename that moves a directory and
  leaves every `import` in the project naming the old one.

### Smart editing
- Enter auto-indents after a `:` block header; Backspace dedents by a full indent step.
- Editor banner when `by` is missing — one-click **Install with uv**
  (`uv add --dev basedpython`), open the environment window, or jump to settings; **uv sync**
  action. A successful install restarts the language servers and clears the banner by itself.

### Project & config
- **New Project → basedpython** wizard scaffolds `pyproject.toml`, `src/main.by`,
  `.gitignore`, and `README.md`.
- Completion for `[tool.ruff]` / buff config keys in `pyproject.toml`.
- `out/` excluded from indexing so generated `.py` don't pollute search.

### Templates
- File templates (Empty, Class, Data Class, Protocol) under **New → basedpython File**.
- Live templates: `cdef`, `dcl`, `fdcl`, `ecl`, `proto`, `ovr`, `nt`, `let`, plus main
  guard, async def, match/case, enum, and pytest fixture.

## Requirements
- IntelliJ Platform 2026.1.1+ (IntelliJ IDEA or PyCharm, any edition — the LSP API is
  available in the free Community editions).
- A project with basedpython installed (`uv add --dev basedpython`), exposing `by` and
  `buff` in `.venv/` or on `PATH`.

## Building
```bash
./gradlew buildPlugin   # -> build/distributions/basedpython-pycharm-*.zip
```

To check UI work in a live IDE rather than by eye, add `-PideAgent` to `runIde`. It puts
[MCP Steroid](https://github.com/JetBrains/mcp-steroid) in the sandbox, which exposes the running
IDE over a local MCP server — run Kotlin inside its JVM, screenshot windows, send real keyboard and
mouse input. The IDE writes the server's URL and a per-run token to
`~/.mcp-steroid/markers/<pid>.mcp-steroid`. Off by default: it is a large download and opens a local
port, and it never reaches the built plugin either way.

```bash
./gradlew runIde -PideAgent
```

`runIde` starts IntelliJ IDEA. **`./gradlew runPyCharm` starts PyCharm Professional**, in a sandbox
of its own, and some of this plugin only runs there: PyCharm ships none of IDEA's logpoints modules
— they are bundled with its Java plugin, and `intellij.debugger.logpoints.backend` is built on
`intellij.java.debugger.impl` — so the gutter-gap log point UI is the IDE's own in IDEA and this
plugin's in PyCharm, and testing in one exercises neither the other's code nor the choice between
them.

```bash
./gradlew runPyCharm
./gradlew runPyCharm -PpycharmPath=/Applications/PyCharm.app   # a PyCharm you already have
./gradlew runPyCharm -PpycharmVersion=2026.3                   # a different published build
```

Both are also **Run IDE** and **Run PyCharm** in the IDE's own run-configuration dropdown.
