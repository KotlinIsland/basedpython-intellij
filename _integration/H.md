# Stream H — Semantic Highlighting Integration Notes

## Files Created

- `src/main/kotlin/dev/basedpython/pycharm/highlight/BasedPythonHighlightKeys.kt`
- `src/main/kotlin/dev/basedpython/pycharm/highlight/BasedPythonAnnotator.kt`

---

## plugin.xml — Required Additions

Add inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<!-- ===== Stream H: semantic annotator ===== -->
<annotator
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.highlight.BasedPythonAnnotator"/>
```

### additionalTextAttributes / colorScheme entries

Register the new keys so the IDE color-settings page can expose them.
The simplest approach is to add them to `BasedPythonColorSettingsPage.DESCRIPTORS`
(in `dev.basedpython.pycharm.editor.colors.BasedPythonColorSettingsPage`):

```kotlin
// Import BasedPythonHighlightKeys from the highlight package, then add:
AttributesDescriptor("Builtins//Built-in name",         BasedPythonHighlightKeys.BUILTIN_NAME),
AttributesDescriptor("Function//Declaration",            BasedPythonHighlightKeys.FUNCTION_DECLARATION),
AttributesDescriptor("Class//Declaration",               BasedPythonHighlightKeys.CLASS_DECLARATION),
AttributesDescriptor("Parameters//Parameter",            BasedPythonHighlightKeys.PARAMETER),
AttributesDescriptor("Parameters//Self / cls",           BasedPythonHighlightKeys.SELF_PARAMETER),
AttributesDescriptor("Parameters//Keyword argument",     BasedPythonHighlightKeys.KEYWORD_ARGUMENT),
AttributesDescriptor("Types//Type name",                 BasedPythonHighlightKeys.TYPE_NAME),
AttributesDescriptor("Strings//Escape sequence",         BasedPythonHighlightKeys.STRING_ESCAPE),
AttributesDescriptor("Strings//F-string interpolation",  BasedPythonHighlightKeys.FSTRING_INTERP),
AttributesDescriptor("Decorator",                        BasedPythonHighlightKeys.DECORATOR),
```

Also extend `getAdditionalHighlightingTagToDescriptorMap()` in that class to return a
map of short tag names → keys so the demo text in the color-settings page can preview them.

---

## README.md — Feature Bullet

```
- **Semantic highlighting** — builtins, `self`/`cls`, decorators, function/class
  declarations, type annotations, parameters, escape sequences, and f-string
  interpolations are colored beyond the basic lexer when the LSP is unavailable.
```

## CHANGELOG.md — Unreleased Entry

```
### Added
- Semantic annotator (`BasedPythonAnnotator`) in `highlight` package: highlights
  builtins, self/cls, decorators, def/class declaration names, parameters, type
  names (after `:` / `->`), keyword arguments, string escapes, and f-string
  interpolation spans.
- New `TextAttributesKey` set (`BasedPythonHighlightKeys`) for all of the above,
  registered under the `BASEDPYTHON_*` namespace.
```

---

## LSP Priority Note

These annotations are **lexer-driven fallbacks**. When the `by` LSP server is running
it emits semantic tokens that the platform renders at a higher priority layer,
overriding the annotator's coloring. The annotator is the active source of semantic
color only when:
  - No LSP server is configured / started.
  - The IDE is a free-tier product where the `com.intellij.modules.lsp` dependency
    is unavailable (the LSP module guard in plugin.xml must then be relaxed or the
    LSP depends-block turned into a soft dependency).

---

## Caveats

1. **Flat PSI** — The annotator fires on the `PsiFile` root element and walks the
   full token stream once per pass. It does not descend into child leaves.
   If another stream introduces a proper PSI tree with expression nodes, the
   annotator can be refactored to target specific node types instead.

2. **Parameter detection heuristic** — Parameters are detected purely by position
   inside `def(…)` token spans. Nested lambdas, multiline signatures, and
   decorator-returned callables may receive incorrect highlighting.

3. **Keyword-argument detection** — `name=` inside a call is identified by the
   `identifier` immediately followed by `=` (not `==`). This can misfire if an
   assignment expression (walrus `:=`) or an f-string expression contains such a
   pattern.

4. **Type-name detection** — Only PascalCase identifiers are highlighted as
   `TYPE_NAME` after `:` / `->`. Lower-case type aliases (`int`, `str`, etc.) are
   instead treated as builtins via `BUILTIN_NAME`.

5. **Performance** — The annotator allocates one `ArrayList` of `TokEntry` records
   per annotated file. On files with tens of thousands of tokens this is acceptable;
   for extremely large generated files the BUILTIN set lookup is O(1) via a HashSet
   so the walk remains linear.
