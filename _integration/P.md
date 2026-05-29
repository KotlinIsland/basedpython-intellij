# Stream P — Transpilation Views Integration Notes

## Files created

- `src/main/kotlin/dev/basedpython/pycharm/transpile/ShowTranspiledDiffAction.kt`
- `src/main/kotlin/dev/basedpython/pycharm/transpile/GoToGeneratedPyAction.kt`
- `src/main/kotlin/dev/basedpython/pycharm/transpile/DiffApiLockAction.kt`
- `src/main/kotlin/dev/basedpython/pycharm/transpile/ConvertInPlaceActions.kt`
  - contains `ConvertByToPyAction` and `ConvertPyToByAction`

## plugin.xml `<actions>` block to add

Paste inside the existing `<actions>` element (e.g. at the end, before `</actions>`).
All four actions are also wired into the existing `BasedPython.ActionGroup` via `<add-to-group>`.

```xml
<!-- ===== Stream P: transpilation views ===== -->
<action id="dev.basedpython.pycharm.transpile.ShowTranspiledDiff"
        class="dev.basedpython.pycharm.transpile.ShowTranspiledDiffAction"
        text="Show Transpiled Python"
        description="Open a side-by-side diff of the current .by file and its generated Python (live-refresh on edit).">
    <add-to-group group-id="BasedPython.ActionGroup" anchor="after" relative-to-action="BasedPython.TranspileFile"/>
    <add-to-group group-id="EditorPopupMenu" anchor="last"/>
    <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
</action>

<action id="dev.basedpython.pycharm.transpile.GoToGeneratedPy"
        class="dev.basedpython.pycharm.transpile.GoToGeneratedPyAction"
        text="Go to Generated .py"
        description="Open the out/ counterpart of the current .by file; offers to run `by build` if missing.">
    <add-to-group group-id="BasedPython.ActionGroup" anchor="after" relative-to-action="dev.basedpython.pycharm.transpile.ShowTranspiledDiff"/>
    <add-to-group group-id="EditorPopupMenu" anchor="last"/>
    <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
</action>

<action id="dev.basedpython.pycharm.transpile.DiffApiLock"
        class="dev.basedpython.pycharm.transpile.DiffApiLockAction"
        text="Diff api.lock"
        description="Compare the current api.lock against a freshly regenerated one to see public-API changes.">
    <add-to-group group-id="BasedPython.ActionGroup" anchor="after" relative-to-action="BasedPython.GenerateApiFile"/>
    <add-to-group group-id="ToolsMenu" anchor="last"/>
</action>

<action id="dev.basedpython.pycharm.transpile.ConvertByToPy"
        class="dev.basedpython.pycharm.transpile.ConvertByToPyAction"
        text="Convert .by → .py (in place)"
        description="Run `by transpile` and write result to out/<relPath>.py, creating the file if needed.">
    <add-to-group group-id="BasedPython.ActionGroup" anchor="last"/>
    <add-to-group group-id="EditorPopupMenu" anchor="last"/>
    <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
</action>

<action id="dev.basedpython.pycharm.transpile.ConvertPyToBy"
        class="dev.basedpython.pycharm.transpile.ConvertPyToByAction"
        text="Convert .py → .by (in place)"
        description="Run `by transpile --reverse` and write result to a .by sibling next to the source .py.">
    <add-to-group group-id="BasedPython.ActionGroup" anchor="last"/>
    <add-to-group group-id="EditorPopupMenu" anchor="last"/>
    <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
</action>
```

## README bullets (add under features list)

- **Show Transpiled Python** — side-by-side diff of a `.by` file and its generated Python; auto-refreshes on edit (debounced 500 ms).
- **Go to Generated .py** — jumps directly to the `out/` counterpart; offers to run `by build` if the file does not yet exist.
- **Diff api.lock** — compares the current `api.lock` against a freshly regenerated version to surface public-API changes.
- **Convert .by → .py (in place)** — transpiles the current file and writes it to `out/<relPath>.py`.
- **Convert .py → .by (in place)** — reverse-transpiles a `.py` file and writes a `.by` sibling next to it.

## CHANGELOG bullets

- Added "Show Transpiled Python" action with live diff view and debounced auto-refresh.
- Added "Go to Generated .py" action with on-demand `by build` fallback.
- Added "Diff api.lock" action to preview public-API surface changes before committing.
- Added "Convert .by → .py (in place)" and "Convert .py → .by (in place)" write-back actions.

## Caveats

1. **DiffContentFactory.create(project, LightVirtualFile)** returns `DiffContent` (not
   `DocumentContent`) in the 2026.1 platform API; `SimpleDiffRequest` accepts `DiffContent`
   directly, so the broader type is used and no explicit cast is needed.

2. **Document listener lifetime** — `ShowTranspiledDiffAction.installDocumentListener` tracks
   installed files in a plain `mutableSetOf` keyed by `"${project.locationHash}::${file.path}"`.
   This is intentionally leaned toward simplicity; in production you would want to tie listener
   removal to the editor's lifetime (e.g. via `FileEditorManagerListener`).

3. **api.lock restoration** — `DiffApiLockAction` detects whether `by generate-api-file` wrote
   in-place by byte-comparing the file after the command returns.  If the tool writes to stdout
   only (and does not modify the file), no restoration is needed and the flow still works.

4. **out/ path mapping** — `GoToGeneratedPyAction` and `ConvertByToPyAction` both assume the
   standard `by build` output layout of `<projectRoot>/out/<relativePath>.py`.  Adjust
   `resolveOutPath` if the project uses a custom output directory.
