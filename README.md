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
- Right-click a `.by` file to "Run by run &lt;module&gt;" or "Check with by".

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
  Python version, format-on-save, inlay-hint toggles, and LSP trace level.
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
  settings).
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
- Test run configuration — runs pytest against the transpiled output via `by run pytest`,
  with results in the standard test tree. Gutter icons on `def test_...` and `class Test...`
  run a single test. Needs `pytest` importable by the interpreter `by run` uses.
- **Debug .by (pdb)** — builds, then runs the generated `.py` under `python -m pdb` in an
  interactive console with clickable frames.

### Docs & help
- Quick Documentation (Ctrl+Q) for basedpython keywords, modifiers, and the `?.` / `??`
  operators; External Documentation (Ctrl+Shift+I) opens the basedpython docs.
- **basedpython Syntax Quick Reference** action with a bundled cheat-sheet.

### Smart editing & environment
- Enter auto-indents after a `:` block header; Backspace dedents by a full indent step.
- Editor banner when `by` is missing — one-click **Install with uv**
  (`uv add --dev basedpython`) or jump to settings; **uv sync** action.

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
