# Stream Z — Move Statement Up/Down

## Files created
- `src/main/kotlin/dev/basedpython/pycharm/editor/mover/BasedPythonStatementMover.kt`

## plugin.xml entry (add inside `<extensions defaultExtensionPointName="com.intellij">`)

```xml
<statementUpDownMover
    implementation="dev.basedpython.pycharm.editor.mover.BasedPythonStatementMover"
    order="before line"/>
```

### EP tag verification
The correct extension-point tag **is** `statementUpDownMover` (no namespace prefix needed
since it is a `com.intellij` core EP). Verified against the bundled platform:

- Declared in `intellij.platform.ide.impl.jar :: META-INF/EditorExtensionPoints.xml`:
  `<extensionPoint name="statementUpDownMover"
   interface="com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover" dynamic="true"/>`
- The platform's own default mover registers as
  `<statementUpDownMover implementation="...LineMover" id="line" order="last"/>`,
  and XML uses `order="before line"`.

Use `order="before line"` so our mover is consulted before the fallback `LineMover`.
Returning `false` from `checkAvailable` defers to that default line mover, so behavior
for non-`.by` files and uncomputable cases is unchanged.

## API notes (IntelliJ Platform 2026.1.1, IU-261.23567.138)
- Base class: `com.intellij.codeInsight.editorActions.moveUpDown.StatementUpDownMover`.
- Implemented `checkAvailable(Editor, PsiFile, MoveInfo, boolean down): Boolean` (abstract).
- `MoveInfo.toMove` / `MoveInfo.toMove2` are `LineRange`; `LineRange(int startLine,
  int endLine)` with `endLine` **exclusive**.
- `MoveInfo.prohibitMove()` returns true while `toMove2` is null, so both ranges are
  always populated when a move is allowed; otherwise we return `false`.

## Behavior
- Selection or caret on a block header (non-blank line ending in `:`): moves the whole
  logical block (header + more-indented body) above the previous / below the next sibling.
- Otherwise: single-line move that skips a deeper-indented child block as a unit.
- Ranges are computed from `Document` text by indentation (PSI is flat / token-only),
  consistent with `IndentScanner`. Any failure or unsafe case returns `false`.

## Compile status
`./gradlew compileKotlin` → **BUILD SUCCESSFUL**
