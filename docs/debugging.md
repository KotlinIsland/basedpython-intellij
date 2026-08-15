# Source-mapped debugging for `.by`

Implemented. Set a breakpoint in a `.by` file, hit Debug on a `by run` configuration, and the
session stops on that line with frames, variables and stepping all reported against the `.by`
source.

Verified end to end against `by` 0.0.1a9 and debugpy 1.8.21 by driving the same protocol exchange
the plugin performs: breakpoint hit, and every user frame reported as `demo.by` at the right line
(`total` at 8, `main` at 13, `<module>` at 16) with locals intact, while the `_by_runner.py` frames
below stay unmapped as they should.

This replaces a belief recorded for a long time in FEATURES.md §64 and in a comment on
`DebugWithPdbAction`: that source-mapped debugging was **blocked upstream** because the transpiler
kept its line map private. It does not. The map is right there.

## What `by run` already gives us

`by run <module>` transpiles into a temp directory and writes two files next to the output:

- `_by_sourcemap.py` —
  ```python
  SOURCEMAP = {
      "/tmp/.tmpXXXX/demo.py": ("/abs/path/demo.by", [None, 0, 1, 2, 3, ...]),
  }
  ```
  One entry per generated file. The list is indexed by *generated* line (0-based) and holds the
  0-based `.by` line it came from, or `None` for emitted prelude that has no source.
- `_by_runner.py` — installs `sys.excepthook`, walks the map, and rewrites tracebacks to `.by`
  paths and lines. This is why an exception under `by run` already reports `.by` frames.

It then runs `<python> _by_runner.py <module> <args...>` with the temp directory as the working
directory and as `sys.path[0]`. Which interpreter is used comes from the `PYTHON` environment
variable, defaulting to `python3` on `PATH`. `PYTHONPATH` is inherited, not cleared.

The mapping is emphatically **not** a constant offset — `by` prepends a prelude whose size depends
on which features the file uses (lazy imports, `dataclass`, checked casts), and a single `.by`
line can become several generated lines (`data class Point:` becomes `@dataclass(slots=True)` plus
`class Point:`). Any design that assumes line-preserving output is wrong.

## Why the obvious approaches fail

**Directory-level path mapping** (debugpy's `pathMappings`, `localRoot`/`remoteRoot`) maps
directories, not lines. Breakpoints would land on the wrong lines.

**Translating in the IntelliJ DAP client.** The platform ships a DAP client
(`intellij.platform.dap`) with `DebugAdapterSupportProvider`, `DebugAdapterDescriptor`,
`DapProcessStarter`. But the outbound breakpoint path runs through `DapBreakpointManager`, which
builds requests from `SourcePosition(VirtualFile, TextPosition)` with no hook to rewrite the source
path or the line before it goes on the wire. `DapVirtualFileResolver.resolve(String): VirtualFile`
covers only the inbound direction — DAP path to file — and says nothing about lines. So the IDE
side can map *frames* back to `.by`, but it cannot map *breakpoints* forward to the generated `.py`.

## How it works

pydevd does the translation. debugpy vendors pydevd, which has first-class support for debugging
generated code — it is how notebook cell debugging works — exposed as a custom DAP request:

    setPydevdSourceMap { source: {path}, pydevdSourceMaps: [{line, endLine, runtimeSource: {path}, runtimeLine}] }

Once a map is registered for a `.by` file, breakpoints set on that file are placed on the
corresponding generated lines, and frames come back reported against the `.by` file. Both
directions, in the debuggee, where the map already lives.

The pieces, all under `src/main/kotlin/dev/basedpython/pycharm/debug/` unless noted:

1. **`ByDapLaunchArgumentsProvider`** declares `by run` configurations debuggable — and *only*
   under the Debug executor, because `DapProgramRunner.canRun` would otherwise route ordinary runs
   through the debug adapter too. It is the first hook in a DAP start, so it also allocates the
   session's port and temp directory (`ByDebugSetup`) and hands them onward on the run profile's
   user data. The DAP request is `attach`, not `launch`: the IDE starts `by run` exactly as a
   normal run would and connects to the port the debuggee opens.
2. **`ByDebugAdapterDescriptor.configureProfileState`** puts `BASEDPYTHON_DEBUG_PORT`,
   `BASEDPYTHON_DEBUG_INFO_OUT` and the bootstrap directory on `PYTHONPATH` — the platform's hook
   for "this process needs extra parameters before a debugger can connect", and the only moment the
   environment of a process `by` launches can still be changed. `ByCommandLineState` grew
   `infrastructureEnv` and `pythonPathPrefix` for this; the latter *prepends*, so a project that
   sets `PYTHONPATH` itself keeps working.
3. **`resources/debug/sitecustomize.py`** is the bootstrap. `PYTHONPATH` plus a `sitecustomize.py`
   is the one hook that reaches an interpreter you did not launch. It reads `_by_sourcemap.py`,
   calls `debugpy.listen()`, writes both the map and the outcome as JSON, then blocks in
   `debugpy.wait_for_client()`.
4. **`ByDebugAdapterDescriptor.launchDebugAdapter`** waits for that JSON file, inverts the map, and
   opens the socket.
5. **`BySourceMapPublisher`** sends one `setPydevdSourceMap` per `.by` file when `initialized`
   arrives, before the platform releases the breakpoints behind it.

`ByLineBreakpointType` supplies the `.by` line breakpoints (the Python plugin's is unavailable —
see FEATURES.md §5), and `ByDapXDebugProcess` puts the run configuration's own console and process
handler back, since `DapXDebugProcess` otherwise builds a console over the adapter's process and
`by run`'s output — the transpile step included — never travels over DAP.

## The four things that are easy to get wrong

**Ordering against the breakpoints.** The platform answers `initialized` by submitting a command
that releases the configuration sender, which sends `setBreakpoints` for every file — addressed to
`.by` paths and `.by` lines, and therefore meaningless until the maps are registered. Commands run
sequentially only *up to their first suspension point*, so merely enqueuing the map requests first
would leave them in flight while the breakpoints went out behind them. `BySourceMapPublisher` wraps
the `DapEventConsumer` and calls the delegate from *inside* the command that pushes the maps, which
is what actually orders the two.

**Inverting the map.** The forward table is a total function from generated lines to `.by` lines;
the inverse is a relation, because one `.by` line routinely becomes several generated ones. Each
`.by` line is pinned to the **last** generated line that claims it, and consecutive `.by` lines
whose pinned lines are also consecutive coalesce into one run.

Last, not first, and that is not a detail. The extra lines are overwhelmingly *prologue* — setup
emitted ahead of what the user wrote, attributed to the same source line. `def f(a = [])` becomes

```
def f(a = _MISSING):       # .by 1
    if a is _MISSING:      # .by 2
        a = []             # .by 2
    a.append(1)            # .by 2
```

Pinning `.by` 2 to the first of its three lines stopped the debugger on the guard, where `a` is
still the sentinel and the variables view reads `<object object at 0x…>` — the source says `[]`.
Pinning to the last stops on `a.append(1)` with `a == []`. The trade is that a line expanding to
real work *followed* by emitted code (a runtime soundness check after an assignment) now breaks
after the assignment rather than before it: a moment later than ideal, which beats showing an
internal sentinel where a variable should be. `ByLineMapping`, unit-tested against this exact
transpiler output.

**Reading the debuggee's state.** `sys.argv[0]` *is* available inside `sitecustomize` —
`_PySys_UpdateConfig` runs before `init_import_site` — so the bootstrap can tell the transpiled
program apart from the `python -c` version probe `by` also runs, which must not try to bind a port.
`sys.path[0]` is **not** yet the script's directory at that point, though; CPython computes it
after initialisation. So `_by_sourcemap.py` is loaded by explicit path, not by `import`.

**Resolving the generated path.** `SOURCEMAP`'s keys are not the filenames Python reports in
frames. On macOS the temp directory is reached through `/var` -> `/private/var`; `by` records the
unresolved form and the interpreter reports the resolved one. pydevd matches `runtimeSource`
against a frame's filename with no normalisation of its own, so the first live run produced
breakpoints that reported `"verified": true` and then never hit. The bootstrap calls
`os.path.realpath` before reporting, which is exactly what `by run`'s own `_by_runner.py` does, and
for the same stated reason.

## Failure reporting

The JSON file is the readiness signal *and* the error channel. Waiting on it rather than on the
port is what lets a failure be reported instead of merely timing out: `by run` transpiles the whole
project before the interpreter starts, which can outlast any reasonable connect-retry budget, and
an interpreter with no `debugpy` never opens a port at all. A debuggee that cannot import `debugpy`
writes an error naming its own `sys.executable`, and the IDE turns that into a notification with
an **Install debugpy** action on it. The command behind that action is chosen on the IDE side, not
in the bootstrap: a uv-managed project gets `uv add --dev debugpy`, because a bare `pip install`
there lands in an environment the next sync rebuilds from the lock file and the package silently
disappears again; everything else gets `<interpreter> -m pip install debugpy`, aimed at the exact
executable that reported the failure.

Reporting it this way rather than by throwing is deliberate. `DapDebugSession.initialize` wraps
anything `launchDebugAdapter` throws in a `DapInitializationException` whose `userVisible` flag is
`e !is CantRunException.CustomProcessedCantRunException`, and `DapXDebugProcess` rethrows the
user-visible ones out of a coroutine — where a missing package surfaces as an "Unhandled exception"
box naming `CoroutineScheduler` and `Rete`. So the notification is raised here and the throw is the
silenced kind. The debuggee is killed on the way out, too: the bootstrap fails at interpreter
startup, before the program body runs, so otherwise pressing Debug would hit no breakpoints and
still run the program to completion with all its side effects.

Which interpreter that is deserves care. `by run` uses `PYTHON`, or else `python3` from `PATH` —
**not** the project `.venv` by construction. But every `by` launch from this plugin goes out with
venv activation applied (`ByCommandLineState.activationEnv` puts `<venv>/bin` at the front of
`PATH`), so in practice `python3` resolves to the project's own interpreter: confirmed live, a run
under activation reports `sys.executable` as `<venv>/bin/python3`. So `uv add --dev debugpy` is the
right answer for a normal project, and the error message names the actual interpreter for every
other case.

A `by` too old to emit `_by_sourcemap.py` still debugs, with a warning notification that
breakpoints in `.by` files will not bind.

The most confusing failure this reporting has had to name so far was not a debugger fault at all.
Two `.by` files whose module paths coincide — `main.by` beside `src/main.by` — are transpiled to
the *same* generated file, and the second write wins. The first source's code never runs and no
breakpoint in it can bind; when the survivor happens to be empty, the program starts, prints
nothing and exits, which looks exactly like a broken debugger. `SOURCEMAP` cannot show it once
loaded, because it is a dict literal whose duplicate key has already collapsed to the last entry —
the bootstrap re-reads the file as a syntax tree, where both keys are still there, and the IDE
names the colliding sources and which one actually runs.

Nothing in the bootstrap may take the user's program down with it: every step runs under a broad
`except`, and a bootstrap that fails means running without a debugger attached.

## Known limits

- **Frame lines drift by one at a run boundary.** pydevd's
  `SourceMappingEntry.contains_runtime_line` computes its upper bound as
  `runtime_line + line + end_line` rather than `runtime_line + (end_line - line)`, so every entry
  claims a wider range of generated lines than it owns, and `map_to_client` returns the first entry
  that claims one. Inside a run this is harmless — the entry is linear with slope 1, so a too-wide
  range still yields the right answer. Replaying the real map through the real
  `pydevd_source_mapping` puts the damage at 2 of 17 mapped lines for the sample file, both of them
  the continuation lines of the one expanded statement, each reported exactly one `.by` line late.
  **Breakpoints are unaffected — 0 of 17 misplaced:** that direction bisects on `contains_line`,
  which is correct. Note that some of this is inherent rather than a pydevd bug: a
  `SourceMappingEntry` can only express a bijective run, and `data class Point:` becoming two
  generated lines is not bijective, so no set of entries gets both directions exactly right.
- **Only `by run` and test configurations.** A test run *is* a `by run` — the configuration
  invokes `by run pytest -v` — so the same bootstrap reaches the same interpreter and the same
  maps describe the same transpiled tree; verified live, with a breakpoint in a `.by` test
  stopping and reporting its frame against the source. `by build` and `by check` produce no
  running program to attach to.
- **Only `.by` files — and that one is not the debugger's doing.** `by run` does not copy plain
  `.py` files into its temp directory at all: a project mixing `helper.py` with `main.by` dies on
  `ImportError: No module named 'helper'` before any debugger is involved. So there is nothing for
  a breakpoint in a `.py` file to bind *to*, and no amount of path mapping on this side would
  change that. Fixing it means changing `by run`.
- **Exception breakpoints have no "ignore library code" option.** pydevd spells it as a
  `:ignoreLibraries` suffix on the filter id, and with it the breakpoint never fires: the
  transpiled program lives in a temp directory pydevd does not count as project code, and setting
  `IDE_PROJECT_ROOTS` to that directory does not change the verdict. Both filters are offered
  without it. This does mean **On raise** stops on exceptions the program catches deliberately,
  which is why it is off by default.
- **Local only.** The bootstrap resolves paths on the machine that runs the interpreter.
- **Paths have to agree.** A breakpoint's file comes from `VirtualFile.path`; the map's comes from
  `_by_sourcemap.py`. pydevd matches them after `normcase` and `absolute_path`, which is not
  `realpath` — so if the two ever disagreed (a symlinked project root, say), breakpoints would stay
  unverified rather than bind to the wrong place. `by` records absolute source paths, so this has
  not been observed; it is the first thing to check if breakpoints silently do not bind.

## The other debugger in this ecosystem, and the switch to it

`bpd` ([basedpythondebugger](https://github.com/KotlinIsland/basedpythondebugger)) is a Python
debugger built on PEP 669. **This plugin can now drive it, and it is the default** — the setting is
*Settings | basedpython | Debugger*, and debugpy stays reachable there.

The agreement between the two plugins is
[bpd and the basedpython pycharm plugin](https://github.com/KotlinIsland/basedpythondebugger/blob/main/docs/development/basedpython-pycharm.md),
which said the switch was this plugin's to make. It has been made.

**Why bpd is the default.** It is PEP 669 native — a line with no breakpoint on it is `DISABLE`d
the first time it is seen. It reports `.by` locations through the source map itself, in its agent,
where a location is *made*, rather than through pydevd's generated-code support. It verifies the
digest of both artefacts before mapping anything, where the debugpy path maps a line whether or
not the pair still matches. And it is the only backend that answers `bpd/facts`, which the
data-flow analysis is seeded from.

### The two backends are not the same shape

| | debugpy | bpd |
| --- | --- | --- |
| Where the adapter lives | inside the debuggee, via `debugpy.listen()` | its own process, started by the wrapper |
| The DAP start request | `Attach`, with a `connect` block | `Launch` |
| How the IDE reaches it | `PYTHONPATH` + `sitecustomize.py` | `PYTHON` + a wrapper script |
| Who maps `.by` lines | the IDE, via `setPydevdSourceMap` | bpd's own agent |

### Why bpd needs a wrapper

`by run` transpiles into a temp directory, writes `_by_sourcemap.py` beside the generated Python,
runs `$PYTHON _by_runner.py <module>` **with that directory as the working directory**, and deletes
the tree when the program ends. The map lives exactly as long as the program does. So bpd cannot be
handed the program from outside — it has to *be* the interpreter `by run` starts, which is what
bpd's own source-mapping page concluded.

The IDE controls the environment of `by run` and nothing else, which leaves `PYTHON`. The wrapper
has two jobs, because `by run` calls `$PYTHON` twice:

1. `$PYTHON -c "import sys; print(…)"`, to decide which Python version to emit code for. **Passed
   straight through to the real interpreter** — answering it any other way would make `by run` emit
   code for a python that is not the one running it
2. `$PYTHON _by_runner.py <module>`, which is the program. Recorded, then `bpd dap --listen` is
   started; the IDE reads the record and sends it back as the `launch` request

The record is lines rather than JSON: quoting a path into JSON from `sh` needs `sed` and gets a
backslash subtly wrong, and a line needs no quoting at all. bpd's own announcement — where it bound
and the token to present — is appended below it, so one file carries everything.

**Windows is refused by name.** `by run` starts `$PYTHON` with `CreateProcess`, which runs an
executable rather than honouring a shebang, so a shell script cannot be the interpreter there.
Switch the backend to debugpy, or run under WSL.

## Data flow: what a stopped program settles about the code below it

Off by default; the switch is beside the backend one. While the program is stopped, the branches
below the stop line are answered as the definite `true` or `false` they will be, and code that will
not run is greyed.

It is not a second analysis. `by` reads the same file under a program that pins some names to what
the debugger observed, and its existing reachability machinery then evaluates to something definite
where it would otherwise say "could go either way".

**Only bpd can seed it**, and the reason is the interesting part. A fact is worth carrying to code
that has not run only if it is still true when that code runs — and that judgement can only be made
by something holding the object: whether its type is a heap type (so `__class__` could be
reassigned), whether instances keep a dictionary, whether a length can change. A DAP `variables`
reply carries none of it. So a debugpy session draws nothing rather than drawing something built on
a guess.

The facts that do not survive the trip are dropped here rather than sent: a container's length is
true now and false after the next `append`. A reading that lasts until `__class__` is reassigned
*is* sent, because a type checker already assumes nobody does that, and being stricter than the
checker this feeds would be incoherent rather than safe.

**What it will not tell you.** A condition it cannot decide gets nothing at all — an ambiguous
verdict is what an unseeded reading says about nearly every condition, and a mark on each would be
a screen full of hints that say nothing. A name bound by a loop around the stop line is never
seeded, because the back edge rebinds it: what was observed is true for this iteration and false
for the next.

## Two things the console taught us

**The adapter must not inherit the console.** `debugpy.listen()` spawns the debug adapter as a
subprocess that deliberately outlives the debuggee, and a subprocess inherits descriptors 1 and 2.
The IDE decides a run has finished when the process's stdout reaches EOF, and the adapter holds
that pipe for the life of the session — so the program printed its output, exited, and the run sat
there looking hung until somebody pressed Stop. It reproduces only with a pipe *and* a connected
client, which is why redirecting to a file never showed it. The bootstrap points 1 and 2 at
`os.devnull` across the spawn and restores them immediately; the adapter talks over sockets, so it
loses nothing.

**The adapter's `output` events are not our output.** `DapXDebugProcess` forwards every one to the
console, which is right when the adapter owns the debuggee. Here the console is already attached to
the real `by run` process, so those events are at best a second copy — and debugpy opens each
session with two bare ones reading `ptvsd` and `debugpy`, which landed in front of the program's
first line. `ByDapXDebugProcess` drops them.

## Exception breakpoints

*Breakpoints → basedpython Exceptions*, with **On raise** and **On termination** checkboxes.
`DapXDebugProcess` supplies a line-breakpoint handler only, so `ByExceptionBreakpointHandler`
adds the other one and turns each checked box into a pydevd filter id.

basedpython changes which of those is worth defaulting to. The language has **checked exceptions**:
a program whose exception escapes `main` does not compile at all —

    error[unhandled-exception]: `final ValueError` can escape `main`, the entry point

— so the obvious reading is that `uncaught` can never fire. It can. The checker does not model
everything, and a `KeyError` from a dict lookup compiles happily and dies at runtime, where the
breakpoint stops on the right `.by` line. So the PyCharm default carries over: **On termination**
on, **On raise** off.

## Log points

A log point is a breakpoint that logs an expression and does not stop. Nothing on the runtime side
needed building: the platform's DAP client sends `XLineBreakpoint.logExpressionObject` on as
`logMessage`, wrapped `{expr}`, and debugpy turns that back into a `print` inside the debuggee — so
the output arrives on the run console this plugin already attaches, exactly where a `print` would
have put it.

### Using one

1. **Turn on breakpoints over line numbers** — right-click the editor gutter, *Appearance |
   Breakpoints Over Line Numbers*. It is a toggle action in that menu, not an entry in the Settings
   dialog. Without it the gutter has no row *between* two lines, so there is nothing to hover. It is
   also forced off in Presentation and Distraction-free mode.
2. Hover the gutter **between two line numbers** in a `.by` file. A dimmed dot and an *Add Log*
   tooltip appear in the gap.
3. Click it. A **Log:** field opens in the gap. Type an expression — `x`, `f"n={n}"`, anything the
   debugger can evaluate.

Outside IntelliJ IDEA there is no gap: set a breakpoint, open its popup, and fill in *Evaluate and
log*.

The other way in is the `print` inspection — Alt+Enter on a `print(…)` statement offers *Replace
print with a log point*, which deletes the call and leaves a log point in the gap it occupied.

### Where it comes from

Almost none of this is plugin code, and the part that is amounts to two overrides.

The whole logpoints UI — the gutter gap, the inline field, `Ctrl+Alt+F8` — ships in
`intellij.debugger.logpoints.*`, modules bundled with **IntelliJ IDEA's Java plugin**. PyCharm has
none of them, so there the gap does not exist and a log point is set through *Evaluate and log* in
the breakpoint popup.

What `.by` files needed to join in was:

- **`ByLineBreakpointType.supportsInterLinePlacement`**. It defaults to false, and while it was
  false the gap could not appear no matter what else was in place: `XDebuggerLineChangeHandler` asks
  every line breakpoint type that question before it will treat a hover between two line numbers as
  an inter-line one, and with no type saying yes `BreakpointPromoterEditorListener` sets none of the
  gutter's hover properties — no icon, no tooltip, not even a cursor change. Kotlin's and Java's
  types override it. That single default is why the same gesture worked one file type over in the
  same IDE, and it took an embarrassingly long time to find.
- **`ByDebuggerEditorsProvider`**. Without it the inline field cannot open — it builds an
  `XDebuggerExpressionEditor`, which will not take a null provider — and every expression field the
  IDE offers for a `.by` breakpoint is a plain text box. Extend `XDebuggerEditorsProviderBase`, not
  `XDebuggerEditorsProvider`: the latter's `createDocument` is a compatibility stub that throws
  `AbstractMethodError`, so a provider answering only `getFileType()` compiles and then dies the
  first time a field opens.

A second implementation of the gap and the field did exist here for a while, written on the belief
that the IDE's could not work for `.by`. It could; the cause was the missing override above. It is
gone — see FEATURES.md — but the platform pieces it was built on are all present outside IDEA
(`editor.interLineBreakpointConfigurationProvider`, `InterLineShiftAnimator`, whose null default
silently stops the gutter opening any gap at all, and `EditorEmbeddedComponentManager`) if PyCharm
ever wants the gesture.

## Coverage

FEATURES.md §66 records line coverage as blocked by the same supposed upstream gap. It is blocked by
the same *actual* situation, and unblocked by the same discovery: coverage gathered on the generated
`.py` can be projected onto `.by` lines with `SOURCEMAP`. It would still additionally need a Python
plugin `CoverageEngine`, which the IDE target does not ship.
