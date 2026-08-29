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

## Plain `.py` files in a basedpython project

A project is rarely all `.by`. Breakpoints work in its `.py` files too, and the interesting part is
how little it takes: **a `.py` breakpoint needs no source map at all.** `by run` transpiles `.by`
and copies nothing else, so a `.py` module is loaded by the interpreter from where the user wrote
it — the file the breakpoint names *is* the file that runs, at the line it says. Both backends place
it without being told anything: pydevd because it is simply not a file a `setPydevdSourceMap` was
registered for, bpd because its mapping layer sends everything that is not `.by` through to its
agent untouched. Verified live against debugpy 1.8.21 with a mixed project: `helper.py:2` reports
`verified`, stops with `a`/`b` bound, and the frames below it are still `main.by:4` and `main.by:7`.

Two things did have to change.

**Who may hold a breakpoint.** `ByLineBreakpointType.canPutAt` accepted `.by` and nothing else, so
the gutter in a `.py` file did nothing at all — in an IDE with no Python plugin there was no other
line breakpoint type to fall back on, and in PyCharm the Python plugin's type is one this session
would never see: `XDebugSessionImpl` dispatches a breakpoint to a handler by **exact type class**,
never by assignability, and `DapBreakpointsDescription` names exactly one. So the type has to claim
`.py` itself. It claims it exactly when this plugin owns the file type — see `ByBreakpointFiles`,
which asks the registry rather than re-deriving the answer from the project markers and the
*Settings | basedpython* ownership choice. That is also what keeps PyCharm quiet: the platform
collects *every* type whose `canPutAt` says yes and puts a "choose a type" popup in front of the
user when more than one does, and here at most one ever can — `PyLineBreakpointType.canPutAt` asks
whether the file is of `PythonFileType`, which is precisely what the file-type overrider changes.
The same predicate now gates the log point gutter gap and `Ctrl+Alt+F8`, so a log point goes where a
breakpoint goes.

**Making the module reachable.** `by run` starts `<python> _by_runner.py` in its temp directory, so
`sys.path[0]` is the transpiled tree and the project directory is on the path nowhere. A project
mixing `helper.py` with `main.by` died on `ImportError: No module named 'helper'` before a debugger
was ever involved — while `by` itself resolved that same import happily when type checking, so the
editor said the code was fine and the run said it was not. `ByCommandLineState` now puts the run's
working directory on `PYTHONPATH`, which is what `python main.py` would have given the program
anyway. It goes *behind* the debugger's bootstrap directory (which must stay first — prepending a
directory prepends its `sitecustomize.py`, and `site` imports exactly one), and the transpiled tree
is still `sys.path[0]`, so a generated module continues to win over a stale `.py` of the same name
lying beside the source it came from. Only `by run` and test configurations get it: `by build` and
`by check` start no interpreter, and `PYTHONPATH` is a variable `by` itself reads.

This replaces a limit recorded here and in FEATURES.md §64 — that `.py` files were out of scope and
"fixing it means changing `by run`". The import failure was real; the conclusion drawn from it was
not.

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
[bpd and the basedpython pycharm plugin](https://github.com/KotlinIsland/basedpythondebugger/blob/main/docs/development/basedpython-intellij.md),
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

## What bpd tells us that DAP has no field for

A jump — and therefore a frame restart — produces two facts that no `stopped` event has a place for:
the locals cpython bound to `None` on the way, and the breakpoints on the destination line that will
not fire for this pass. bpd used to have nowhere to put them but the console, which a person can read
and a client cannot.

DAP was never the obstacle. Its event bodies are open JSON objects and an adapter may name its own
events; what drops the extras is a client that deserialises into fixed types, and lsp4j's
`StoppedEventArguments` is exactly that. But lsp4j binds notifications by reflecting over the
**runtime class** of the local service — `GenericEndpoint.recursiveFindRpcMethods` calls
`service.getClass()` — and the platform hands it whatever `DebugAdapterDescriptor.createClient`
returned, which is `ByDapClient`. So an `@JsonNotification` there receives a custom event with a
`JsonObject` body and nothing is lost. No platform change and no protocol change were needed.

bpd sends `bpd/moved` carrying its `Jumped` whole; [ByMoved] reads it. And because narrating the same
facts *and* sending them would show everything twice, a client says what it reads —
`bpd/understands {"events": [...]}` — and bpd stops narrating those. A client that has never heard of
the request keeps the prose, which is what makes this an addition rather than a migration: measured
both ways against one session, the unaware client still gets
`stop 2: ["later"] held nothing before the move and hold \`None\` now`, and the aware one gets
`"bound_to_none": ["later"]` and no console line at all.

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

**Whether the adapter's `output` events are our output depends on the backend, and getting that
wrong deletes the program's entire output.** `DapXDebugProcess` forwards every one to the console,
which is right when the adapter owns the debuggee. Under **debugpy** it does not: the console is
already attached to the real `by run` process, which the interpreter is a child of, so those events
are at best a second copy — and debugpy opens each session with two bare ones reading `ptvsd` and
`debugpy`, which landed in front of the program's first line. They are dropped.

Under **bpd** the opposite holds. bpd starts the interpreter itself and captures its streams, and
the wrapper points `bpd dap`'s own stdout at the record file, so nothing the program prints reaches
the process the IDE started. Dropping these was dropping everything: `print` went nowhere at all.
The two channels are disjoint, and measured so — a `by run` session whose program printed
`total=3` and whose source drew a `redundant-return-annotation` warning put the warning on the
process's stdout and the program's line in a single `('stdout', 'total=3\n')` event, neither on the
other's channel. So forwarding adds the program's output without doubling the diagnostics.

`ByDebugBackend.ownsDebuggeeOutput` is the switch, on the enum rather than derived from
`DapStartRequest.Launch` — which today picks out the same backend, but attaching and owning the
debuggee's streams are two facts, and a third backend that split them would silently get the wrong
answer from the proxy.

**And the category means what DAP says it means.** The platform's mapping is `console` → system,
`stderr` → error, everything else → stdout, which is wrong in both directions once these events are
being printed. `telemetry` is data for the client rather than text for a person, and printing it
puts adapter bookkeeping in the middle of the program's output. `important` is defined as what a
user should see *even with the console collapsed*, and bpd reserves it for exactly that — a blind
spot in subprocess tracking, a refused code replacement, a reminder that recording is on and
costing four times a bare run. Filed as ordinary stdout each of those scrolls past under the
program's own output. `ByAdapterOutput` maps them: `stdout` ordinary, `console` and an omitted
category system (DAP's own default), `stderr` and `important` prominent, `telemetry` nowhere, and
an unrecognised category shown rather than dropped — an adapter may invent one, and silence is the
only outcome nothing later can recover from.

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

## What this plugin does instead of the platform's DAP client

`ByDapXDebugProcess` overrides `sessionInitialized` and does **not** call `super`. That method does
exactly four things — watch for the session to stop, run the start sequence, listen to the thread
list, listen to output — and two of them are gaps this plugin cannot otherwise reach. They are
written up in full in `scratch.ij-dap-issues.md`; in short:

- **a refused start is unreported.** The base catches only `DapInitializationException`, so an
  adapter that *answers* `launch` with an error has its message dropped, the session is never
  stopped, and the user gets an "Unhandled exception" naming `CoroutineScheduler`. That is how a bpd
  which would not debug a build produced a live-looking tab with the one sentence saying what to do
  nowhere. Ours catches it and shows the adapter's own message
- **a `stopped` for the thread you are on is queued rather than applied.** The base asks only
  whether the session is suspended. That is right for a *second* thread stopping under a non-stop
  adapter, and wrong for the `stopped` DAP prescribes after `restartFrame` and `goto`, which means
  "this thread moved". Queued, the highlight stays where the code no longer is — and since the
  platform drains that queue only in `resume`, the next Resume shows the stale position **instead of
  running the program on**. Ours applies a suspension for the thread already on screen and defers
  only a different thread's, with `resume` draining its own queue the same way

What is *not* replaced is the part that matters most: `applySuspendContext` is the platform's, and it
is `protected`, so log points, breakpoint conditions and suspend policies keep working exactly as
they did. Stepping, run to cursor, the breakpoint handlers, the variables tree, expression evaluation
and the editors provider are all inherited untouched. This is one lifecycle method, not a fork of the
client — which is what makes it something to delete when the platform fixes its own.

## Reset Frame

*Reset Frame* runs a stopped frame again from its first line. It is enabled under **bpd** and grey
under debugpy, whose pydevd reports `supportsRestartFrame: false`.

**It is not the JVM's Reset Frame.** There, resetting *pops* the frame: the thread returns to the
caller, the call can be made again, and the parameters are the ones it was originally given. CPython
has no such operation. bpd builds the nearest honest thing out of jumps, two ways, and picks between
them itself:

- **reset in place** — the frame's instruction pointer goes back to its first line, and the locals a
  fresh call would not have bound are put back to *unbound* rather than to `None`. The frame object
  is the one the program already had, and the caller is never touched, so nothing else on the
  caller's line runs a second time
- **rewind through the caller** — the frame is forced to return and the caller's line runs again, so
  the interpreter builds a frame that has never run. This is what serves a frame that has written
  over one of its own parameters: those slots are the only place what the call passed still exists

Measured: stopped inside `work(n)` where `n` arrived as `1` and the body had made it `101`, the
reset is refused because the frame rebinds a parameter, bpd falls back to the rewind, and the call is
made again with `n` back to `1`. What is never undone is a side effect the old frame already
performed, and no block cleanup runs — a `with` the frame was inside gets no `__exit__`.

**Any frame, not only the top one.** A frame below the top is reached by forcing the frames above it
out, innermost first, each made to return the way the rewind forces its own frame out — they are
gone rather than suspended. The plugin therefore asks nothing about *which* frame it is: every
refusal past "does the adapter offer the request" is bpd's, decided off the bytecode of the frames
involved before any of them is touched, and answered as a refused request this action reports.

This used to read "only the frame its thread is executing", which was true of bpd once — CPython
crashes rather than refuses when a frame that is not executing is moved. When bpd gained the unwind,
the plugin's own copy of that limit is what went on greying the action out on a caller.

Refusals are the request's own error response, which is why `ByRestartFrameHandler` catches and
shows them: the platform drops a failed request's message on the floor, and a refusal a person asked
for would otherwise look like a button that did nothing. What a restart really *did* — which locals
were emptied, which frames were discarded, whether any held a block open — comes back from bpd on
the console, in one place rather than two.

### the bridge

`restartFrame` is a DAP request and `supportsRestartFrame` a DAP capability; bpd implements both.
The platform's DAP client implements neither half of the connection to the IDE action —
`intellij.platform.dap` contains no reference to `restartFrame` or to `XDropFrameHandler` — so the
action stays grey however much an adapter advertises. `ByRestartFrameHandler` is the missing bridge,
through `XDebugProcess.getDropFrameHandler`, which is an ordinary supported override rather than a
way around anything.

Whether to offer it is asked of the **adapter's advertised capability**, not of
`ByDebugBackend`: debugpy is the reason it matters (pydevd reports `supportsRestartFrame` as false)
but the wire carries the answer here, and believing what the adapter says beats remembering what we
think it is. The capabilities arrive after `initialize`, so the handler is always returned and the
question is asked live — deciding at process construction would answer "not yet" forever. A
capability that has not arrived declines: an action that is briefly grey beats one that is briefly
wrong, because a refused request's message is discarded by the platform and a wrong "yes" would look
like a button that does nothing.

## Hot reload

You edit a file while the program is stopped, and the code in the process is not the code on your
screen. cpython says nothing about that — a traceback is rendered by `linecache` reading the file
**now**, so an edited file is shown with current text against old line numbers: correct numbers,
wrong text, total confidence. bpd already refuses to be part of it (it compiles a frame's file and
requires the frame's own code object to be in what comes out, or the answer is `not_the_same_code`
rather than a line), and hot reload is that comparison inverted: a mismatch is what makes a
replacement worth offering.

### the platform owns everything visible

`com.intellij.xdebugger.hotswap` is the platform's generic hot swap, added for the JVM and left
IDE-agnostic. It owns the whole UI — the floating toolbar that fades in over the editor the moment a
tracked file stops matching what is running, the button on it, the spinner, the tick, the success
balloon, the `XDebugger.Hotswap.Modified.Files` action and its shortcut. A plugin supplies two
things and no more: **what to watch**, and **what the button does**. That is the entirety of
`debug.hotswap`, and nothing in it draws anything.

The way in is `com.intellij.xdebugger.hotSwapInDebugSessionEnabler`, which the platform asks at
`processStarted` and which decides per session whether hot reload exists at all. PyCharm registers
no implementation of it — only IDEA's Java debugger and Rider do, and neither is here — so ours is
the only one in the IDE and the platform's defaults are what the user sees. It is gated behind
`debugger.hotswap.floating.toolbar`, which is on.

Offered for **bpd sessions only**. `bpd/replaceCode` is bpd's own request and debugpy has nothing
like it, so a debugpy session would raise a button whose only possible answer is that the adapter
does not know what it was asked. Decided from the backend rather than from an advertised capability,
for the reason [Reset Frame](#reset-frame) decides the opposite way: `restartFrame` is a DAP
capability an adapter announces in `initialize`, and there is no capability flag for a custom
request — nothing is on the wire to believe, so the thing that chose the backend is what says which
one it is.

### what the button does

One `bpd/replaceCode` per changed file. DAP has no request of its own for this and could not: its
`restart` throws the process away and starts another, and the whole point here is that the process
stays.

A replacement is a set of assignments to `function.__code__` and nothing else. The top level is
never re-run, no name is bound or unbound, and no object is created — so every reference the program
already holds is the one it held before, and it now runs different code. That is also why a class
needs no machinery: a method **is** a function object in the class dictionary, so rebinding its code
is seen by every instance that already exists.

What is found is every function object in the **process**, through a walk of the heap, rather than
every name in the module's namespace — which is the difference between reloading a module and
rebinding the names in its dictionary. Captured live against a real session: editing a `helper.py`
whose factory had already handed out a closure came back with `factory.<locals>.inner`, one object,
rebound beside `slow`. A namespace walk would have missed it and left a live closure running code
from the file it used to be.

bpd applies it exactly when **every difference between the two trees is inside the body of a
function that exists in both and takes the same arguments**, and nothing is ever applied partially —
a process half way between two versions of a file produces evidence about neither. A replacement
that cannot be made whole changes nothing at all and comes back naming *everything* that stood in
the way rather than the first thing, because a client fixing them one at a time is a client asking
this seventeen times.

### the refusals are bpd's to explain, and it explains them

bpd's DAP adapter writes each reason to the `output` stream under category `important`, which is the
category this plugin already puts where a person cannot miss it (see `ByAdapterOutput`). So nothing
in the plugin re-renders them, for the same reason `bpd/understands` exists for events: a client
that reads a fact and shows it, beside an adapter that narrates the same fact, shows everything
twice. `ByReplaced` therefore reads *that* a replacement was refused and how many reasons there
were, and leaves the eleven-variant vocabulary of `Unreplaceable` where it is authored.

What the plugin does print is the other half, which nothing else says: what an **applied**
replacement changed about the process — the functions whose code moved and where to, how many
function objects held each, and which breakpoints had to bind again. That last one matters and is
easy to miss: binding walks down from the file's registered root code object, so after a replacement
the old root describes code nothing will execute; bpd swaps the root and resolves the whole
breakpoint set again, and a breakpoint on what was a `return` can come back bound to the `def` above
it, because a breakpoint is a *line of a file* and the edit moved what that line is.

"Nothing needed replacing" is printed too, and is deliberately not rendered as "nothing could be
replaced". They are different facts about the process and bpd distinguishes them.

### the frame you are stopped in

bpd refuses a replacement while any frame of the process is running code the replacement would
change — on a thread, or suspended inside a generator, a coroutine or an async generator waiting to
be sent into. That covers the case people most want this in: stopped at a breakpoint *inside* the
function just edited. The refusal names the frame and says to let it return first.

It is a design decision rather than a safety one, and bpd is explicit that it must never be
described as one: assigning `function.__code__` under a live frame is *accepted* on 3.13 through
3.15, the frame runs the old code to completion, and the next call gets the new one. What is refused
is what it leaves behind — until that frame returns the process runs two versions of one function,
and a stack whose frames behave two different ways is evidence about neither. bpd will do it for a
caller that asks by name (`evenUnderALiveFrame`, with every still-old frame reported back); nothing
here asks, and the option is one line away in `ByReplaceCodeArguments` if it should.

One consequence worth knowing, measured rather than reasoned: a program stopped anywhere has its
**own script's module frame** live for the whole run, so editing the file the interpreter was
started on is always refused. That is not the shape a session here has — `by run` starts
`_by_runner.py`, and everything the user wrote is an imported module whose body has already returned
— but it is what you will see if you try this against a bare `bpd launch script.py`.

### nothing you edit is the file that is running

Under `by run` the program runs out of a tree in a temp directory, and every module in it arrived
there from the project: a `.by` because it was **transpiled**, a hand-written `.py` because it was
**copied**. `sys.path[0]` is that tree. Measured rather than argued — a project with a `helper.py`
beside its `main.by` reports both `__file__`s inside `/var/folders/.../T/.tmpXXXX/`.

So both kinds of file take the same route. That was not always what this said. A `.by` used to be
refused, with a long and correct explanation of why the debugger could not transpile it; what was
wrong was the other half of the argument, which held that a plain `.py` needed none of that because
`by run` "copies nothing else". It copies everything else — `stage_verbatim` walks the project root
— so the `.py` path had quietly stopped working too, answering `NotLoaded` about a file that was
plainly running. The refusal was the only part anybody could see.

### the route a press of the button takes

1. **Save.** The platform's collector tracks *documents*; what gets transpiled and what bpd compiles
   are files. Nothing in the platform saves in between, so without this an unsaved edit asked bpd to
   replace the file with the content it already had — and got back `applied`, with nothing changed,
   which the toolbar then reported as a successful reload. The user was told the process matched
   their screen when it did not, which is the one outcome this feature exists to prevent.
2. **Ask `by` what the tree should now hold**, through the language server, one file at a time —
   `by/transpileForBuild`, carrying the build directory the wrapper recorded. It answers with the
   generated python, the whole rewritten `_by_sourcemap.py`, and both digests. It **writes nothing**.
3. **Write it into the tree**, remembering every byte replaced.
4. **One `bpd/replaceCode`** over everything written, with `remap` set.
5. **On any refusal, put the tree back.** A tree holding code the process is not running is a tree
   that lies: bpd reads the map out of it to say which `.by` line a frame is on, and reads the
   generated python to prove a frame's code object is still in it. bpd is honest about the mismatch
   rather than silent — `not_the_same_code` — but it would be a session degraded by a write the
   *plugin* chose to make, on a file the user only edited in the editor.

### why the server and not `by` on the command line

Measured on a 97-file project at `by` HEAD: a full `by build` is 24.9 seconds, of which `by check`
is 8.5. A subprocess would pay project discovery and that whole check on every press of the button.
The language server has already paid both — it is holding the project database, warm, because it has
been answering diagnostics for this project all along — so what is left is one file's emit, about
165ms.

It is also the same binary: the server is started as `by server` from the configured `by`. Where it
is *not* the same — a user pointing the two at different builds — `_by_build.json` in the tree
records which `by` wrote it and the server refuses rather than emitting bytes the build would not
have. That record is also why the configuration cannot drift: `by run` takes its target version from
the interpreter it probed while `by build` takes it from the project, so a re-stage that re-derived
the configuration would emit different code in exactly the case that matters.

### why the map moves in the same message

Re-staging rewrites `_by_sourcemap.py` beside the generated python, so every `.by` breakpoint is
armed on a generated line that came out of the table it replaced. Both have to land before any
`__code__` is assigned — and the agent holds the GIL for the whole of one message and no longer, so
a debugger that sent the tables, the breakpoints and the replacement as three messages would leave
two windows in which another thread's logpoint is mapped through a table describing code it is not
running. One message has no window in it. bpd owns that ordering rather than the client.


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
4. **Enter** commits, **Escape** abandons. A log point you never fill in removes itself, so an
   abandoned click leaves nothing behind.

`Ctrl+Alt+F8` does the same from the keyboard: it adds a log point above the caret's line, or opens
the field on the one already there.

The other way in is the `print` inspection — Alt+Enter on a `print(…)` statement offers *Replace
print with a log point*, which deletes the call and leaves a log point in the gap it occupied.

### Which implementation you are looking at

This is the part that catches people out, and it caught the author out too.

The whole logpoints feature — gutter gap, inline editor, `Ctrl+Alt+F8` — ships in
`intellij.debugger.logpoints.*`, modules bundled with **IntelliJ IDEA's Java plugin**. PyCharm has
none of them, which is why this plugin implements the lot.

The **gap** is this plugin's in every IDE, IDEA included. It used to stand aside there, on the
reasoning that IDEA's implementation is the better one; it is, but it never appeared in `.by` files,
and a better implementation that does not appear is worse than a plainer one that does. A tie is
survivable because both gaps produce the same breakpoint through the same platform toggle —
`findFirstConfiguration` collects providers into a map keyed by id and takes the first available for
the line, so the winner is hash order and the `order=` attribute decides nothing.

The **inline field** does still defer, because two prompts would mean two fields over one log point.

Both can be forced with the registry key (*Help | Find Action | Registry…*):

    basedpython.logpoints.provider = auto* | plugin | ide

One thing this plugin's version needed that IDEA's silently relies on: `ByDebuggerEditorsProvider`.
A breakpoint type without an `XDebuggerEditorsProvider` gets plain text boxes for every expression
the IDE asks for — *Condition*, *Evaluate and log*, Evaluate Expression — and IDEA's inline log point
editor cannot open at all, because it builds an `XDebuggerExpressionEditor` and that will not take a
null provider. It extends `XDebuggerEditorsProviderBase`, not `XDebuggerEditorsProvider`: the
latter's `createDocument` is a compatibility stub that throws `AbstractMethodError`, so a provider
answering only `getFileType()` compiles and then dies the first time a field opens.

## Coverage

FEATURES.md §66 records line coverage as blocked by the same supposed upstream gap. It is blocked by
the same *actual* situation, and unblocked by the same discovery: coverage gathered on the generated
`.py` can be projected onto `.by` lines with `SOURCEMAP`. It would still additionally need a Python
plugin `CoverageEngine`, which the IDE target does not ship.
