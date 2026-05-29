# Stream Q — Test / i18n / Infra

## Files created

| Path | Purpose |
|------|---------|
| `src/test/kotlin/dev/basedpython/pycharm/LexerTest.kt` | JUnit 4 unit tests for `BasedPythonLexer` — no fixture needed |
| `src/test/kotlin/dev/basedpython/pycharm/FileTypeTest.kt` | Static assertions on `BasedPythonFileType` singleton |
| `src/main/kotlin/dev/basedpython/pycharm/util/BasedPythonBundle.kt` | `DynamicBundle` object with `message()` / `messagePointer()` |
| `src/main/resources/messages/BasedPythonBundle.properties` | Seed keys for actions, settings, notifications |

---

## Exact `build.gradle.kts` additions required

### 1. `ideaVersion` — sinceBuild / untilBuild

IntelliJ Platform Gradle Plugin 2.x DSL (2.14.0). Insert inside the existing
`intellijPlatform { pluginConfiguration { … } }` block:

```kotlin
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"        // 2026.1.x  (major=261)
            untilBuild = "261.*"
        }
        // … existing description / changeNotes blocks …
    }
}
```

> Note: for IntelliJ Platform Gradle Plugin 2.x the build number for 2026.1.1
> is in the `261` series. Confirm with
> `./gradlew printProductsReleases` or the JetBrains build matrix if the exact
> minor build matters.

---

### 2. Plugin Verifier DSL

Add a top-level (or nested inside the existing) `intellijPlatform { }` block:

```kotlin
intellijPlatform {
    // … existing pluginConfiguration block …

    pluginVerification {
        ides {
            recommended()
        }
    }
}
```

Run with: `./gradlew verifyPlugin`

---

### 3. Test dependencies — none extra needed

The existing declarations are sufficient for plain JUnit 4 tests against
`BasedPythonLexer` (which has no application/project dependency):

```kotlin
// already in build.gradle.kts:
testImplementation("junit:junit:4.13.2")
intellijPlatform {
    testFramework(TestFrameworkType.Platform)
}
```

`opentest4j` and the JUnit Platform launcher are not required because the tests
use the classic JUnit 4 runner (`@Test` from `org.junit`), which Gradle's built-in
`Test` task supports without extra configuration.

If the JUnit Platform (JUnit 5) runner is wanted in future, add:

```kotlin
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

---

## `plugin.xml` note (do NOT edit without coordination)

To make `BasedPythonBundle` available for use in `<action text="">` / `<group text="">`
via the platform's `@NlsContexts` resolution, add one line inside `<idea-plugin>`:

```xml
<resource-bundle>messages.BasedPythonBundle</resource-bundle>
```

This is optional — the bundle already works from Kotlin code without this entry.
Only add it if action texts or other XML attributes need to reference bundle keys.

---

## README / CHANGELOG bullets

**README** (inside the `<!-- Plugin description -->` block):
```
- Localised string bundle (`BasedPythonBundle`) for all user-visible text.
```

**CHANGELOG** (under Unreleased):
```
- Added `BasedPythonBundle` i18n bundle with seed keys for actions, settings, and notifications.
- Added `LexerTest` and `FileTypeTest` unit tests (JUnit 4, no IDE fixture).
```

---

## Caveats

1. **Pre-existing compile errors** in other streams (`format/`, `inspections/`,
   `project/`, `structure/`, `transpile/`) prevent `./gradlew compileKotlin`
   from succeeding overall. None of those errors are in Stream Q files.
   `compileTestKotlin` also fails transitively because it depends on
   `compileKotlin`. Stream Q sources are error-free as confirmed by targeted
   grep on compiler output.

2. **`FileTypeTest`**: Two assertions (`name`, `language`) access fields that
   are populated at class-load time without an `Application` — this works
   because `BasedPythonFileType` and `BasedPythonLanguage` are simple objects
   with no platform service dependencies. If the IDE adds service calls to those
   constructors in future, the tests will need a `LightPlatformTestCase` fixture.

3. **`sinceBuild`/`untilBuild`**: The value `"261"` is derived from the
   IntelliJ IDEA 2026.1.x branch convention (year×10 + quarter → 2026×1 = 2261?
   — double-check; JetBrains sometimes uses the calendar year directly).
   Verify against the actual IDE artifact version printed by
   `./gradlew printProductsReleases` before publishing.
