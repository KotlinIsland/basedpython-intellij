# Stream W: Environment / setup UX

## Files created (all under `src/main/kotlin/dev/basedpython/pycharm/env/`)

- `BasedPythonVersions.kt` — `object BasedPythonVersions` with `byVersion(project): String?`
  and `buffVersion(project): String?`. Resolves the binary via `BasedPythonBinaries`, runs
  `<binary> version` (5s timeout via `ExecUtil.execAndGetOutput`), returns the trimmed first
  non-blank stdout line or `null`.
- `UvSupport.kt` — internal helper: `findUv()` (PATH lookup), `basePath(project)`,
  `hasProjectMarker(project)` (uv.lock / pyproject.toml), `canSync(project)`, and a
  `notify(...)` wrapper for the existing `"BasedPython"` notification group.
- `UvSyncAction.kt` — `class UvSyncAction : AnAction`. Runs `uv sync` at the project base via
  `GeneralCommandLine` + `OSProcessHandler` on a pooled thread; reports exit code through a
  `"BasedPython"`-group notification. Enabled only when `UvSupport.canSync(project)` is true.
- `ByMissingBannerProvider.kt` — `class ByMissingBannerProvider : EditorNotificationProvider`.
  Shows an `EditorNotificationPanel` (Warning) on `.by` files when
  `BasedPythonBinaries.resolveBy(project)` returns `null`. Action labels: "Install with uv"
  (`uv add --dev basedpython` at base), "Configure…" (opens BasedPython settings via
  `ShowSettingsUtil.showSettingsDialog(project, "BasedPython")`), "Dismiss" (per-file, session).

## plugin.xml entries to add (integrator — do NOT applied by this stream)

Inside `<extensions defaultExtensionNs="com.intellij"> … </extensions>`:

```xml
<editorNotificationProvider
    implementation="dev.basedpython.pycharm.env.ByMissingBannerProvider"/>
```

Inside `<actions> … </actions>`:

```xml
<action id="BasedPython.UvSync"
        class="dev.basedpython.pycharm.env.UvSyncAction"
        text="uv sync"
        description="Run `uv sync` at the project base.">
    <add-to-group group-id="BasedPython.ActionGroup" anchor="last"/>
</action>
```

## Build

`./gradlew compileKotlin` → BUILD SUCCESSFUL.
