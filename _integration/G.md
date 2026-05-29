# Stream G — Structure View, Folding, Breadcrumbs

## Files created

All files under `src/main/kotlin/dev/basedpython/pycharm/structure/`:

| File | Purpose |
|------|---------|
| `IndentScanner.kt` | Shared indent/def scanner (single source of truth for all three features). Parses file text into `ScopeNode` tree by indentation. Detects class/function/field/import-block/region nodes. |
| `BasedPythonStructureViewElement.kt` | `StructureViewTreeElement` implementation. Maps `ScopeNode` kinds to `AllIcons.Nodes.*` icons. Navigates by moving the editor caret to the declaration offset. |
| `BasedPythonStructureViewModel.kt` | `StructureViewModelBase` + `ElementInfoProvider`. Wires in `Sorter.ALPHA_SORTER`, narrows to editor selection. |
| `BasedPythonStructureViewFactory.kt` | `PsiStructureViewFactory` entry point registered in plugin.xml. |
| `BasedPythonFoldingBuilder.kt` | `FoldingBuilderEx` + `DumbAware`. Folds: (1) class/function bodies, (2) multi-line `(`/`[`/`{`, (3) triple-quoted strings, (4) consecutive import blocks (collapsed by default), (5) `# region`/`# endregion`. |
| `BasedPythonBreadcrumbsProvider.kt` | `BreadcrumbsProvider` for `BasedPythonLanguage`. Uses synthetic `ScopeProxy` (`FakePsiElement`) wrappers to traverse the class/def chain without composite PSI. |

## plugin.xml `<extensions>` entries to merge

Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- ===== Stream G: structure view, folding, breadcrumbs ===== -->
<lang.psiStructureViewFactory
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.structure.BasedPythonStructureViewFactory"/>
<lang.foldingBuilder
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.structure.BasedPythonFoldingBuilder"/>
<breadcrumbsInfoProvider
    implementation="dev.basedpython.pycharm.structure.BasedPythonBreadcrumbsProvider"/>
```

EP name notes:
- `lang.psiStructureViewFactory` — correct EP for 2026.1.1 (`PsiStructureViewFactory`).
- `lang.foldingBuilder` — correct EP for `FoldingBuilderEx`.
- `breadcrumbsInfoProvider` — correct EP name for `com.intellij.ui.breadcrumbs.BreadcrumbsProvider` in 2026.1.1. Do **not** use `lang.breadcrumbsInfoProvider` — that EP does not exist.

## README bullets

- Structure view (`View > Tool Windows > Structure`) lists classes, functions/methods, and top-level assignments with icons; supports alphabetical sort.
- Code folding for function/class bodies, multi-line brackets, triple-quoted strings, and `# region`/`# endregion` blocks; consecutive imports collapse by default.
- Editor breadcrumbs show the enclosing class/function chain for the caret position.

## CHANGELOG bullets

- Added structure view for `.by` files (classes, functions, fields, imports).
- Added folding builder: class/function bodies, brackets, triple-quoted strings, import blocks, `# region`/`# endregion`.
- Added breadcrumbs provider showing enclosing class/def chain.

## Caveats

1. **Flat PSI**: BasedPython uses a flat token PSI (no composite nodes). All three features detect structure by scanning raw file text using `IndentScanner`, which uses indentation heuristics. Deeply unusual formatting (e.g., one-liner `def foo(): pass` with no indented body) is detected but produces a zero-height fold region that IntelliJ will silently discard.
2. **Breadcrumbs proxy**: `BreadcrumbsProvider.getParent()` is called on leaf PSI elements; since there is no composite tree, the provider wraps scope nodes in `ScopeProxy` (`FakePsiElement`). This is tested to satisfy the abstract `acceptElement` contract.
3. **Sorting**: `Sorter.ALPHA_SORTER` is the only sorter; "sort by visibility" is not implemented (no visibility info without the LSP).
4. **Import-block detection**: Consecutive `import`/`from` lines are grouped into a single fold. The import block fold collapses by default only when the block spans more than one line.
5. **Bracket folding**: String content inside bracket folds is not rescanned for nested strings, which is safe because the bracket scan skips string interiors.
