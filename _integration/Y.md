# Stream Y — Run ergonomics

New subpackage: `dev.basedpython.pycharm.run.ergonomics`

## Files created

- `src/main/kotlin/dev/basedpython/pycharm/run/ergonomics/ByConsoleFilter.kt`
  - `class ByConsoleFilter(project) : com.intellij.execution.filters.Filter`
  - `class ByConsoleFilterProvider : com.intellij.execution.filters.ConsoleFilterProvider`
  - Makes `.by` and transpiled `.py` paths in `by`/`buff` console output clickable, with
    optional `:line` / `:line:col` suffixes (e.g. `src/main.by:12:5`, `out/main.py:7`).
    Resolves absolute first, then relative to `project.basePath`; returns
    `OpenFileHyperlinkInfo` at the (0-based) line/col.

- `src/main/kotlin/dev/basedpython/pycharm/run/ergonomics/BuildBeforeRunTask.kt`
  - `class BuildBeforeRunTask : BeforeRunTask<BuildBeforeRunTask>(PROVIDER_ID)`
  - `Key<BuildBeforeRunTask> PROVIDER_ID = Key.create("BasedPython.ByBuildBeforeRunTask")`

- `src/main/kotlin/dev/basedpython/pycharm/run/ergonomics/BuildBeforeRunTaskProvider.kt`
  - `class BuildBeforeRunTaskProvider : BeforeRunTaskProvider<BuildBeforeRunTask>()`
  - Adds an optional "Run `by build` first" step to any run config. On execute, runs
    `by build` (binary via `BasedPythonBinaries.resolveBy(project)`) at the config's
    working dir (if it is a `ByCommonOptions` config) else the project base. Non-zero
    exit (or unresolved `by`) fails the run.

- `src/main/kotlin/dev/basedpython/pycharm/run/ergonomics/ByMacros.kt`
  - `object ByMacros { fun expand(raw, context: DataContext, firstQueueExpand=true): String }`
  - Delegates to platform `MacroManager.getInstance().expandSilentMacros(...)` so all
    IDE path macros (`$FilePath$`, `$FileName$`, `$ProjectFileDir$`, `$ModuleName$`, ...)
    are supported. Self-contained utility for future wiring; no existing files touched.

## plugin.xml entries (add inside `<extensions defaultExtensionPointName="com.intellij">`)

```xml
<consoleFilterProvider
    implementation="dev.basedpython.pycharm.run.ergonomics.ByConsoleFilterProvider"/>
<stepsBeforeRunProvider
    implementation="dev.basedpython.pycharm.run.ergonomics.BuildBeforeRunTaskProvider"/>
```

### EP tag verification

Confirmed against `intellij.platform.ide.impl.jar` (IntelliJ Platform 2026.1.1):

- `<extensionPoint name="consoleFilterProvider"
   interface="com.intellij.execution.filters.ConsoleFilterProvider" dynamic="true"/>`
- `<extensionPoint name="stepsBeforeRunProvider"
   interface="com.intellij.execution.BeforeRunTaskProvider" area="IDEA_PROJECT" dynamic="true"/>`

The EP for `BeforeRunTaskProvider` is indeed `com.intellij.stepsBeforeRunProvider`
(short tag `stepsBeforeRunProvider`), as the task description anticipated.
