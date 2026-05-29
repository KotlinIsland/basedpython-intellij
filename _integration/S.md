# Stream S — run line markers + `by test` config

## Files created

- `src/main/kotlin/dev/basedpython/pycharm/run/marker/ByRunLineMarkerContributor.kt`
  - `RunLineMarkerContributor` subclass. Puts the green `AllIcons.RunConfigurations.TestState.Run`
    gutter icon on `.by` files for:
    - `if __name__ == "__main__":` lines
    - top-level `def main(` / `async def main(` declarations
  - Detection is via document line text (PSI is flat / token-only). Fires per-leaf but only
    returns non-null for the FIRST non-whitespace leaf of the matching line to avoid duplicates.
  - Actions come from `ExecutorAction.getActions(0)`, which resolves to the file's `by run <module>`
    configuration produced by `ByRunFromFileProducer`.

- `src/main/kotlin/dev/basedpython/pycharm/run/test/ByTestConfigurationType.kt`
  - Standalone `ConfigurationType` (`ByTestConfigurationType`) + `TestFactory`.
  - Standalone type chosen because `BasedPythonRunConfigurationType.getConfigurationFactories()`
    hard-codes its factory list and that file must not be edited.

- `src/main/kotlin/dev/basedpython/pycharm/run/test/ByTestConfiguration.kt`
  - `ByTestOptions` (extends shared `dev.basedpython.pycharm.run.ByCommonOptions`, adds `paths`).
  - `ByTestConfiguration` runs `by test <paths>` via the shared abstract `ByCommandLineState`
    (working dir, extra args, env, `--min-version` all inherited).

- `src/main/kotlin/dev/basedpython/pycharm/run/test/ByTestSettingsEditor.kt`
  - `SettingsEditor<ByTestConfiguration>` mirroring `ByCheckSettingsEditor`
    (test paths, working dir, extra args, min Python version, env vars).

## EXACT plugin.xml entries to add

Language id resolved from `dev.basedpython.pycharm.lang.BasedPythonLanguage` (Language("BasedPython"))
— same id already used by all other `<...language="BasedPython"...>` registrations.

Inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<runLineMarkerContributor
    language="BasedPython"
    implementationClass="dev.basedpython.pycharm.run.marker.ByRunLineMarkerContributor"/>

<configurationType
    implementation="dev.basedpython.pycharm.run.test.ByTestConfigurationType"/>
```

## Compile status

`./gradlew compileKotlin` → BUILD SUCCESSFUL (see report).
