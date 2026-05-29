# Stream O — Project Scaffolding + Config Awareness

## Files created

All files under `src/main/kotlin/dev/basedpython/pycharm/project/`:

| File | Purpose |
|------|---------|
| `BasedPythonProjectGenerator.kt` | `GeneratorNewProjectWizard` — surfaces "BasedPython" in the New Project wizard. Scaffolds `pyproject.toml` (with `[tool.ruff]` + `[project]` stub), `src/main.by` (hello-world with `data class` demo), `.gitignore` (`.venv/`, `out/`, `__pycache__/`), and `README.md`. Uses VFS write inside a `WriteAction`. Reuses `/icons/basedpython.svg`. |
| `PyprojectCompletionContributor.kt` | `CompletionContributor` registered on `PlainTextLanguage` scoped to files named `pyproject.toml`. Provides completions for ruff/buff config keys (`line-length`, `select`, `ignore`, `target-version`, `quote-style`, etc.), TOML section headers (`[tool.ruff]`, `[tool.ruff.lint]`, etc.), and common `target-version` / rule-prefix values. No TOML plugin dependency — works without it. |
| `OutDirExcludePolicy.kt` | `DirectoryIndexExcludePolicy` (project-level) — overrides `getExcludeUrlsForProject()` to exclude `<project-base>/out/` from IDE indexing so transpiled `.py` don't pollute search/Go-to-Class. |

## plugin.xml `<extensions>` entries to merge

Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- ===== Stream O: project scaffolding + config awareness ===== -->
<generatorNewProjectWizard
    implementation="dev.basedpython.pycharm.project.BasedPythonProjectGenerator"/>
<completion.contributor
    language="TEXT"
    implementationClass="dev.basedpython.pycharm.project.PyprojectCompletionContributor"/>
<directoryIndexExcludePolicy
    implementation="dev.basedpython.pycharm.project.OutDirExcludePolicy"/>
```

### Optional TOML-language registration (richer completion)

If the integrator adds `bundledPlugin("org.toml.lang")` to `build.gradle.kts` and `<depends>org.toml.lang</depends>` to `plugin.xml`, an additional registration provides completions inside the TOML PSI tree (not just plain text):

```xml
<!-- Only if org.toml.lang is present -->
<completion.contributor
    language="TOML"
    implementationClass="dev.basedpython.pycharm.project.PyprojectCompletionContributor"/>
```

Both registrations can coexist. The same contributor class works for both since it uses only PSI-independent APIs.

## Required build.gradle.kts additions

**None required** — all three features compile without any additional dependencies.

## Optional build.gradle.kts additions (TOML richer completion)

To enable the `language="TOML"` registration above, add to the `intellijPlatform { }` block in `build.gradle.kts`:

```kotlin
intellijPlatform {
  // …existing config…
  dependencies {
    bundledPlugin("org.toml.lang")
  }
}
```

And add to `plugin.xml`:

```xml
<depends>org.toml.lang</depends>
```

**This is NOT required.** The plain-text fallback works out of the box. TOML integration only adds completions inside TOML token positions in the PSI tree.

## README bullets to add

- New project wizard: File → New Project → "BasedPython" scaffolds a ready-to-run project with `pyproject.toml`, a `data class` demo in `src/main.by`, `.gitignore`, and a README.
- `pyproject.toml` completions: typing inside `pyproject.toml` suggests ruff/buff config keys, section headers, and common values.
- `out/` directory is automatically excluded from IDE indexing so transpiled Python doesn't appear in search results.

## CHANGELOG bullets to add

- Added "BasedPython" entry in the New Project wizard with full project scaffolding.
- `pyproject.toml` editor now offers completions for `[tool.ruff]` config keys and section headers.
- `out/` (transpile output) is excluded from file indexing at project level.

## Caveats

1. **`GeneratorNewProjectWizard` EP name**: the extension point name in plugin.xml must match the Kotlin EP_NAME defined in `GeneratorNewProjectWizard.Companion`. As of 2026.1.1 this is `com.intellij.generatorNewProjectWizard` — the XML tag `<generatorNewProjectWizard>` is the conventional short form used by the platform plugin.
2. **Completion scope**: the `language="TEXT"` contributor fires for every plain-text file; the `inFile(psiFile().withName("pyproject.toml"))` pattern guard restricts it to `pyproject.toml` only at pattern-match time. If PlatformPatterns don't short-circuit, the provider checks the file name again inside `addCompletions`.
3. **`OutDirExcludePolicy` constructor**: the platform injects the `Project` via the constructor — the extension point is `projectExtensionPoint`. If the platform changes to service injection, the constructor-arg approach is still safe.
4. **TOML dep**: `PyprojectCompletionContributor` does NOT import any `org.toml.*` classes; the TOML plugin is fully optional. Adding `language="TOML"` registration with the same class works because the contributor uses only `CompletionParameters.position.containingFile.text` (plain String API).
