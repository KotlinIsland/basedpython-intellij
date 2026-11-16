# Marketplace screenshots

`screenshots/` holds the slides uploaded to the JetBrains Marketplace listing. They are made in two
steps, and both are scripted, so a UI change means rerunning them, not redrawing anything.

| Path | What it is |
|---|---|
| `showcase/` | a small basedpython project, written to be shown: sum types, extensions, optional chaining, a pydantic model with one deliberate warning, and pytest tests |
| `capture/` | drives a sandbox IDE through MCP Steroid and paints each scene to `captures/` |
| `captures/` | raw 2560×1440 captures: the frame at 1280×720, painted at 2x from inside the JVM |
| `shots.yaml` | the slides: crop, layout, headline and body for each capture |
| `screenshots/` | the slides [mpshots](https://github.com/jreznot/mpshots) composes from the two above |

## Capturing

```bash
(cd marketplace/showcase && uv sync)
./gradlew runIde -PideAgent --args="$PWD/marketplace/showcase"
marketplace/capture/capture.sh            # or name scenes: transpiled pydantic completion debugger tests
```

The showcase needs its own `.venv` with `basedpython-debugger` in it: the plugin runs the `by` it finds
there, and looks for `bpd` beside that `by`, so the debugger scene fails without it.

MCP Steroid 0.102 on 263.7307.112 fails every script with a `NoClassDefFoundError` for jackson's
`ObjectMapper`. Copy jackson 2.21's `annotations`, `core` and `databind` jars into the sandbox's
`plugins_runIde/mcp-steroid/lib` and relaunch the IDE's own command line rather than Gradle, since
`prepareSandbox` deletes them again.

The scenes put the caret, breakpoints and scroll positions on lines of the showcase's sources
(`capture/capture.sh` names each one). Edit those files and the numbers move with them.

## Composing

```bash
cd marketplace
npm install --ignore-scripts
npm run build            # mpshots build --strict: refuses to build on any legibility or upscaling warning
```

Every crop starts below the main toolbar, which holds the sandbox's "Unlock Ultimate" button, and
ends above the status bar, which shows MCP Steroid's progress while a script runs. That leaves 1289
px of height, and so 1221×1289 is the largest side crop that fills mpshots' 928×980 window while
keeping IDE text over its 16 px floor without upscaling.
