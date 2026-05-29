# Stream R — Navigation & Search

## Files created (all under `src/main/kotlin/dev/basedpython/pycharm/navigation/`)

- `ByNavigationItem.kt` — `NavigationItem` backed by a `.by` `VirtualFile` + offset;
  navigates via `OpenFileDescriptor` (flat PSI, no composite elements required).
- `ByChooseByNameSupport.kt` — shared scanner: enumerates `.by` files via
  `FileTypeIndex.getFiles(BasedPythonFileType.INSTANCE, scope)`, runs
  `IndentScanner.buildFlat(text)`, and builds names/items for requested `NodeKind`s.
- `BasedPythonSymbolContributor.kt` — `ChooseByNameContributorEx` for **Go to Symbol**
  (CLASS + FUNCTION + FIELD).
- `BasedPythonClassContributor.kt` — `ChooseByNameContributorEx` for **Go to Class**
  (CLASS only).
- `BasedPythonRelatedProvider.kt` — `GotoRelatedProvider` mapping `.by` <-> generated
  `out/…​.py` (mirrors `transpile.GoToGeneratedPyAction` path logic). Only returns an
  item when the counterpart file exists on disk.

## plugin.xml entries to add

Add inside the existing `<extensions defaultExtensionNs="com.intellij">` block:

```xml
<gotoSymbolContributor
    implementation="dev.basedpython.pycharm.navigation.BasedPythonSymbolContributor"/>
<gotoClassContributor
    implementation="dev.basedpython.pycharm.navigation.BasedPythonClassContributor"/>
<gotoRelatedProvider
    implementation="dev.basedpython.pycharm.navigation.BasedPythonRelatedProvider"/>
```

## Notes
- No shared/manifest files were edited (plugin.xml, build.gradle.kts, settings, docs untouched).
- Contributors index live document/file text on demand; no custom stub/file index is registered.
