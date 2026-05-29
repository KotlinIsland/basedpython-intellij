# Stream U — Docs & Help (integration notes)

## Files created
- `src/main/kotlin/dev/basedpython/pycharm/docs/BasedPythonDocEntries.kt`
  Bundled HTML descriptions + docs anchors for keywords/modifiers/operators.
- `src/main/kotlin/dev/basedpython/pycharm/docs/BasedPythonDocumentationProvider.kt`
  `DocumentationProvider` implementation (Quick Documentation + `getUrlFor`).
- `src/main/kotlin/dev/basedpython/pycharm/docs/BasedPythonQuickReferenceAction.kt`
  `AnAction` showing a bundled HTML syntax cheat-sheet popup.

## Modified (allowed)
- `src/main/resources/messages/BasedPythonBundle.properties`
  Appended key: `action.quickReference.text=BasedPython Syntax Quick Reference`

## plugin.xml — extension (inside `<extensions defaultExtensionNs="com.intellij">`)
Language id is `BasedPython` (from `BasedPythonLanguage : Language("BasedPython")`).

```xml
<lang.documentationProvider
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.docs.BasedPythonDocumentationProvider"/>
```

## plugin.xml — action (inside `<actions>`)
```xml
<action id="BasedPython.QuickReference"
        class="dev.basedpython.pycharm.docs.BasedPythonQuickReferenceAction"
        text="BasedPython Syntax Quick Reference"
        description="Show a quick reference of BasedPython syntax">
    <add-to-group group-id="BasedPython.ActionGroup"/>
</action>
```

Notes:
- The action also sets its presentation text at runtime from
  `BasedPythonBundle.message("action.quickReference.text")`, so the static
  `text=` attribute is a fallback only.
- Confirm the existing group id in plugin.xml. The constraint named it
  `BasedPython.ActionGroup`; the class `BasedPythonActionGroup` is registered
  there. If the actual registered id differs, adjust `group-id` accordingly.

## Behaviour
- DocumentationProvider returns `null` for unrecognised elements / non-`.by`
  files, so the LSP hover still wins where applicable.
- `getUrlFor` returns `https://basedpython.dev/docs/<anchor>` for known entries,
  else the base URL for any `.by` element (External Documentation / Ctrl+Shift+I).

## Compile
`./gradlew compileKotlin` → BUILD SUCCESSFUL
