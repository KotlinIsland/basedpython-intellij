# Stream I — Formatting Integration

## Files produced

| File | Purpose |
|------|---------|
| `src/main/kotlin/dev/basedpython/pycharm/format/BuffFormatOnSave.kt` | Format-on-save action + info + UI provider |
| `src/main/kotlin/dev/basedpython/pycharm/format/BuffImportOptimizer.kt` | `ImportOptimizer` for `.by` files |
| `src/main/kotlin/dev/basedpython/pycharm/format/BuffCodeStyleSettings.kt` | `CustomCodeStyleSettings` subclass (line length, quote style) |
| `src/main/kotlin/dev/basedpython/pycharm/format/BuffCodeStyleSettingsProvider.kt` | `CodeStyleSettingsProvider` + `LanguageCodeStyleSettingsProvider` + panel |

---

## plugin.xml `<extensions>` to add

Add the following block inside `<extensions defaultExtensionNs="com.intellij">` (label it `<!-- ===== Stream I: formatting ===== -->`):

```xml
<!-- ===== Stream I: formatting ===== -->

<!-- Format-on-save checkbox (Editor → Actions on Save) -->
<actionOnSaveInfoProvider
    implementation="dev.basedpython.pycharm.format.BuffFormatOnSaveInfoProvider"/>
<actionOnSave
    implementation="dev.basedpython.pycharm.format.BuffFormatOnSaveAction"/>

<!-- Optimize Imports (Ctrl+Alt+O) for .by files -->
<lang.importOptimizer
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.format.BuffImportOptimizer"/>

<!-- Editor → Code Style → BasedPython -->
<codeStyleSettingsProvider
    implementation="dev.basedpython.pycharm.format.BuffCodeStyleSettingsProvider"/>
<langCodeStyleSettingsProvider
    implementation="dev.basedpython.pycharm.format.BuffLanguageCodeStyleSettingsProvider"/>
```

---

## New `BasedPythonSettings` field required

Add the following field to `BasedPythonSettings.State` (and a matching property):

| Field name | Type | Default | Purpose |
|------------|------|---------|---------|
| `formatOnSave` | `Boolean` | `false` | Controls "Format .by files on save" |

Example diff for `BasedPythonSettings.kt`:

```kotlin
// Inside data class State:
var formatOnSave: Boolean = false,

// Inside BasedPythonSettings:
var formatOnSave: Boolean
    get() = state.formatOnSave
    set(value) { state.formatOnSave = value }
```

Until this field is added the format-on-save action compiles and runs safely
(it reads the field via reflection and falls back to `false`).

---

## README bullets (add under Features)

```markdown
- **Format on save** — enable buff formatting of `.by` files automatically via
  Editor → Actions on Save → "Format .by files with buff".
- **Optimize Imports** — Ctrl+Alt+O runs `buff check --fix --select I` on the
  current `.by` file to sort and deduplicate imports.
- **Code Style settings** — Editor → Code Style → BasedPython exposes buff's
  line-length and quote-style options with project-level persistence.
```

## CHANGELOG bullet (add to Unreleased)

```markdown
- Formatting integration: format-on-save (Actions on Save), Optimize Imports
  (buff organize-imports), and a Code Style settings page for BasedPython.
```

---

## Caveats

1. **`formatOnSave` field** must be added to `BasedPythonSettings.State` before
   the toggle persists across IDE restarts. Without it the action defaults to
   disabled and writes are silently no-ops (no crash).

2. **`actionOnSave` EP** — the extension point name may be
   `com.intellij.actionOnSave` in older SDK docs; verify against 2026.1.1
   platform sources if the IDE fails to pick up the action on save. The class
   `ActionsOnSaveFileDocumentManagerListener.ActionOnSave` is in
   `intellij.platform.ide.impl.jar`.

3. **Import Optimizer + LSP** — when the buff LSP server is running, the IDE
   may route `source.organizeImports` through it first (via a code action). The
   `ImportOptimizer` is the CLI fallback and is harmless to register alongside
   the LSP.

4. **Code style preview** — `BuffOptionsTab.getPreviewText()` returns `null`
   because BasedPython has no PSI-based highlighter suitable for the preview
   editor. The options panel still works; you can add a preview later by
   returning sample code and wiring up `BasedPythonSyntaxHighlighter`.

5. **No `@OptIn` needed** — all APIs used (`ActionOnSave`, `ActionOnSaveInfo`,
   `ActionOnSaveInfoProvider`, `CodeStyleAbstractPanel`, `ImportOptimizer`)
   are stable public APIs in 2026.1.1.
