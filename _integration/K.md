# Stream K Integration Notes

## Files Created

### Kotlin source (`src/main/kotlin/dev/basedpython/pycharm/`)

| File | Purpose |
|------|---------|
| `inspections/spellcheck/BasedPythonSpellcheckingStrategy.kt` | `IdentifierSplitter` utility (camelCase/snake_case splitting). Full `SpellcheckingStrategy` subclass requires `bundledPlugin("com.intellij.spellchecker")` — see caveat below. |
| `inspections/BasedPythonIndexPatternBuilder.kt` | `IndexPatternBuilder` implementation; exposes COMMENT tokens to the TODO tool window. |
| `inspections/MutableDefaultArgInspection.kt` | `LocalInspectionTool`: detects `def f(x=[])` / `={}` / `=set()` with WEAK_WARNING severity. |
| `inspections/BinaryNotConfiguredInspection.kt` | `LocalInspectionTool`: file-level WEAK_WARNING when `by` binary is not found; quick-fix opens BasedPython settings. |
| `inspections/intentions/AddReturnTypeIntention.kt` | Adds `-> None` to a `def` line missing a return type. |
| `inspections/intentions/ConvertToDataClassIntention.kt` | Converts `class X:` → `data class X:` when caret is on `class`. |
| `inspections/intentions/ConvertFromDataClassIntention.kt` | Reverses `data class X:` → `class X:`. |
| `inspections/intentions/WrapNullSafeIntention.kt` | Converts `.` to `?.` on a DOT token that is not already null-safe. |
| `inspections/intentions/ExplainNamedTupleIntention.kt` | Informational pop-up explaining `(name: str, age: int)` syntax. |

### Resources (`src/main/resources/`)

Intention description directories with `description.html`, `before.by.template`, `after.by.template`:
- `intentionDescriptions/AddReturnTypeIntention/`
- `intentionDescriptions/ConvertToDataClassIntention/`
- `intentionDescriptions/ConvertFromDataClassIntention/`
- `intentionDescriptions/WrapNullSafeIntention/`
- `intentionDescriptions/ExplainNamedTupleIntention/`

---

## plugin.xml `<extensions>` entries

Add the following inside the `<extensions defaultExtensionNs="com.intellij">` block:

```xml
<!-- ===== Stream K: spellcheck, TODO index, inspections, intentions ===== -->

<!-- TODO / FIXME in .by comments populate the TODO tool window -->
<indexPatternBuilder
    implementation="dev.basedpython.pycharm.inspections.BasedPythonIndexPatternBuilder"/>

<!-- Spell-checking: requires bundledPlugin("com.intellij.spellchecker") in build.gradle.kts.
     Once that dependency is present, also create:
       class BasedPythonSpellcheckingStrategy : SpellcheckingStrategy() {
           override fun isMyContext(element: PsiElement) = element.containingFile is BasedPythonFile
           override fun getTokenizer(element: PsiElement): Tokenizer<out PsiElement> = ...
       }
     using IdentifierSplitter from the spellcheck subpackage.
     Uncomment when bundledPlugin is declared: -->
<!--
<spellchecker.support
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.inspections.spellcheck.BasedPythonSpellcheckingStrategy"/>
-->

<!-- Local inspections -->
<localInspection
    language="BasedPython"
    groupName="BasedPython"
    displayName="Mutable default argument"
    shortName="BasedPythonMutableDefaultArg"
    enabledByDefault="true"
    level="WEAK WARNING"
    implementationClass="dev.basedpython.pycharm.inspections.MutableDefaultArgInspection"/>

<localInspection
    language="BasedPython"
    groupName="BasedPython"
    displayName="by binary not configured"
    shortName="BasedPythonBinaryNotConfigured"
    enabledByDefault="true"
    level="WEAK WARNING"
    implementationClass="dev.basedpython.pycharm.inspections.BinaryNotConfiguredInspection"/>

<!-- Intentions -->
<intentionAction>
    <language>BasedPython</language>
    <className>dev.basedpython.pycharm.inspections.intentions.AddReturnTypeIntention</className>
    <category>BasedPython</category>
</intentionAction>

<intentionAction>
    <language>BasedPython</language>
    <className>dev.basedpython.pycharm.inspections.intentions.ConvertToDataClassIntention</className>
    <category>BasedPython</category>
</intentionAction>

<intentionAction>
    <language>BasedPython</language>
    <className>dev.basedpython.pycharm.inspections.intentions.ConvertFromDataClassIntention</className>
    <category>BasedPython</category>
</intentionAction>

<intentionAction>
    <language>BasedPython</language>
    <className>dev.basedpython.pycharm.inspections.intentions.WrapNullSafeIntention</className>
    <category>BasedPython</category>
</intentionAction>

<intentionAction>
    <language>BasedPython</language>
    <className>dev.basedpython.pycharm.inspections.intentions.ExplainNamedTupleIntention</className>
    <category>BasedPython</category>
</intentionAction>
```

---

## README bullets

```markdown
- **TODO tool window**: `# TODO` and `# FIXME` comments in `.by` files appear in the IDE TODO tool window.
- **Inspections**: detects mutable default arguments (`def f(x=[])`) with quick-fix info; warns when the `by` binary is not found.
- **Intentions**: add `-> None` return type, convert `class`↔`data class`, wrap `.` → `?.`, explain anonymous named-tuple syntax.
- **Spell-checking**: identifier tokens are split on camelCase/snake_case boundaries before spell-checking (requires `bundledPlugin("com.intellij.spellchecker")` — see caveats).
```

## CHANGELOG bullets

```markdown
- Mutable-default-argument inspection (WEAK_WARNING) with informational quick-fix.
- Binary-not-configured inspection (WEAK_WARNING) with Settings quick-fix.
- Intentions: add return type, convert class↔data class, wrap null-safe access, explain named-tuple literal.
- TODO/FIXME in `.by` comments now populate the IDE TODO tool window.
- `IdentifierSplitter` utility for camelCase/snake_case identifier word splitting (used by spellchecker when enabled).
```

---

## Caveats

### Spellcheck: bundledPlugin required

`SpellcheckingStrategy` lives in `intellij.spellchecker.jar` which is a bundled plugin, **not** part of the default `intellijIdea("2026.1.1")` compile classpath. To enable the full spellcheck strategy:

1. Add to `build.gradle.kts` inside the `intellijPlatform { }` block:
   ```kotlin
   bundledPlugin("com.intellij.spellchecker")
   ```
2. Rename/extend `BasedPythonSpellcheckingStrategyCompileStub` → a real class extending `SpellcheckingStrategy` using the `IdentifierSplitter` already present in the `spellcheck` subpackage.
3. Uncomment the `<spellchecker.support>` EP entry in `plugin.xml`.

### IndexPatternBuilder import

`IndexPatternBuilder` is at `com.intellij.psi.impl.search.IndexPatternBuilder` (in `intellij.platform.analysis.impl`) — NOT `com.intellij.psi.search`.

### Lexer-based PSI

All inspections and intentions operate on the flat lexer token stream (no PSI tree). Offsets are document-level. For intentions that perform edits, `WriteCommandAction.runWriteCommandAction` is used correctly with `file` as the affected element.

### `def` detection heuristic

`MutableDefaultArgInspection` scans for `=` followed by `[`, `{`, or a call to `set()`/`dict()`/`list()` within a `def` parameter list. BasedPython auto-rewrites these; the inspection is informational only (WEAK_WARNING).
