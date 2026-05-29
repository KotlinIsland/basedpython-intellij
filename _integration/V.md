# Stream V — Smart Editing (editor/smart)

## Files created
- `src/main/kotlin/dev/basedpython/pycharm/editor/smart/IndentLogic.kt`
  Shared, document-text-based helpers: `.by` scoping, leading-indent extraction,
  block-header detection (line ends with `:`, comment/quote/bracket aware), and
  `newLineIndent()` (previous indent + one 4-space level after a header).
- `src/main/kotlin/dev/basedpython/pycharm/editor/smart/BasedPythonEnterHandler.kt`
  `EnterHandlerDelegateAdapter`. Indents the new line one level deeper after a block
  header, else preserves the previous line's indent. Works in `postProcessEnter`.
- `src/main/kotlin/dev/basedpython/pycharm/editor/smart/BasedPythonBackspaceHandler.kt`
  `BackspaceHandlerDelegate`. Inside leading indentation, Backspace snaps back to the
  previous 4-column tab stop (deletes a full indent step) instead of one space.

## plugin.xml entries
Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<enterHandlerDelegate
    implementation="dev.basedpython.pycharm.editor.smart.BasedPythonEnterHandler"/>
<backspaceHandlerDelegate
    implementation="dev.basedpython.pycharm.editor.smart.BasedPythonBackspaceHandler"/>
```

NOTE: `enter.between.lines` is NOT a valid EP; use `<enterHandlerDelegate>` as above.

## LineIndentProvider — SKIPPED (intentional)
Not implemented. The `BasedPythonEnterHandler.postProcessEnter` returns
`EnterHandlerDelegate.Result.Stop`, fully owning newline indentation. A
`LineIndentProvider` for the same language would compute indentation on the same
Enter path, producing a high risk of double-indent (header line → +8 spaces) and
two competing sources of truth. Per the task's "prefer correctness over coverage"
guidance it is omitted. Auto-indent on paste is left to the platform default, which
is acceptable for the flat-PSI `.by` model. If desired later, a provider would need
to coordinate with (or replace) the Enter handler so only one applies the extra level.

If it WERE added, the entry would be:

```xml
<!-- intentionally NOT added; see note above -->
<lineIndentProvider
    implementation="dev.basedpython.pycharm.editor.smart.BasedPythonLineIndentProvider"/>
```

## Build
`./gradlew compileKotlin` → BUILD SUCCESSFUL (only pre-existing `env/` deprecation
warnings, none from editor/smart).
