# Stream L — Template Expansion Integration Notes

## Files created

- `src/main/resources/liveTemplates/BasedPythonExtra.xml`
- `src/main/kotlin/dev/basedpython/pycharm/editor/templates/BasedPythonPostfixTemplateProvider.kt`
- `src/main/kotlin/dev/basedpython/pycharm/editor/templates/BasedPythonSurroundDescriptor.kt`

## Required plugin.xml `<extensions>` additions

Add these inside the existing `<extensions defaultExtensionNs="com.intellij">` block:

```xml
<!-- ===== Stream L: extra templates ===== -->
<defaultLiveTemplates file="liveTemplates/BasedPythonExtra"/>
<codeInsight.template.postfixTemplateProvider
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.editor.templates.BasedPythonPostfixTemplateProvider"/>
<lang.surroundDescriptor
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.editor.templates.BasedPythonSurroundDescriptor"/>
```

## README bullet (inside `<!-- Plugin description -->` block)

```
- **Live templates** (Extra set): `main`, `adef`, `match`, `enum`, `test`, `fix`, `prop`, `field`, `try`, `with`, `compr`
- **Postfix templates**: `.if`, `.else`, `.for`, `.while`, `.not`, `.return`/`.ret`, `.print`, `.len`, `.var`, `.none`, `.notnone`
- **Surround-with**: `if`, `while`, `try/except`, `(…)`, `[…]`
```

## CHANGELOG bullet

```
- Stream L: expanded live-template set, 12 postfix templates, and 5 surround-with descriptors for BasedPython
```

## Caveats

- The `BasedPython` context id used in `BasedPythonExtra.xml` must match exactly what is registered by `BasedPythonTemplateContextType` (id `"BasedPython"`). If another stream renames the context, update both XML files.
- Postfix templates use raw text/offset manipulation because the PSI tree for BasedPython is flat (token-only). They strip indentation correctly but do not reformat via the IntelliJ formatter — if a formatter is added later, wrap the `doc.replaceString` call inside `WriteCommandAction` and invoke `CodeStyleManager.reformat`.
- The `liveTemplateContext contextId` attribute registered in plugin.xml (Stream F) is `BASED_PYTHON`. The XML `<option name="BasedPython" value="true"/>` key matches the `TemplateContextType` constructor arg (`"BasedPython"`), not the `contextId`. Both must remain in sync.
- The second live-template group is named `BasedPythonExtra` (distinct from `BasedPython`) so the IDE shows it as a separate group in Settings → Editor → Live Templates.
