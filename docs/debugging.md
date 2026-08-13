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

## The other debugger in this ecosystem

`bpd` ([basedpythondebugger](https://github.com/KotlinIsland/basedpythondebugger)) is a Python
debugger built on PEP 669, and it now has an IntelliJ plugin of its own — `editors/intellij/` —
registering the **same two extension points this one does**,
`platform.dap.debugAdapterSupportProvider` and `platform.dap.launchArgumentsProvider`. That is not
a conflict, since the platform routes on adapter id and run configuration type, but it is
duplicated plumbing.

**The agreement between the two plugins, and the plan for converging them, lives here:**
[bpd and the basedpython pycharm plugin](https://github.com/KotlinIsland/basedpythondebugger/blob/main/docs/development/basedpython-pycharm.md)

The short version: they are not merged, because this plugin debugs `.by` and `bpd` cannot yet.
The end state is this plugin **switching its adapter from debugpy to `bpd`** once `bpd` can, at
which point there is one adapter between them and packaging is the only question left.

Two things from this page are ahead of `bpd`'s plugin and are recorded there as such: the console
(`DapXDebugProcess` builds one over the adapter's own process handler, and `bpd` spawns the
interpreter itself) and adapter `output` events. A third — a failure surfacing as an IDE internal
error naming `CoroutineScheduler` — `bpd`'s plugin already handles.

**What `bpd` needs from `by`, and it is one thing.** `bpd` will not report a line that came from a
map it could not verify against the thing the map describes. `_by_sourcemap.py` has the mapping and
has the provenance — `None` for prelude lines — and carries **no digest of the two artefacts**. That
digest is the whole of what stands between `bpd` and `.by` support. `bpd`'s roadmap recorded this as
"blocked upstream, the transpiler must emit a source map", which this page had already disproved;
that entry has been corrected to ask for the digest and nothing else.

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

## Coverage

FEATURES.md §66 records line coverage as blocked by the same supposed upstream gap. It is blocked by
the same *actual* situation, and unblocked by the same discovery: coverage gathered on the generated
`.py` can be projected onto `.by` lines with `SOURCEMAP`. It would still additionally need a Python
plugin `CoverageEngine`, which the IDE target does not ship.
