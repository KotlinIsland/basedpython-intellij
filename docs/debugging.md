# Source-mapped debugging for `.by`

Implemented. Set a breakpoint in a `.by` file, hit Debug on a `by run` configuration, and the
session stops on that line with frames, variables and stepping all reported against the `.by`
source.

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

## The three things that are easy to get wrong

**Ordering against the breakpoints.** The platform answers `initialized` by submitting a command
that releases the configuration sender, which sends `setBreakpoints` for every file — addressed to
`.by` paths and `.by` lines, and therefore meaningless until the maps are registered. Commands run
sequentially only *up to their first suspension point*, so merely enqueuing the map requests first
would leave them in flight while the breakpoints went out behind them. `BySourceMapPublisher` wraps
the `DapEventConsumer` and calls the delegate from *inside* the command that pushes the maps, which
is what actually orders the two.

**Inverting the map.** The forward table is a total function from generated lines to `.by` lines;
the inverse is a relation, because one `.by` line routinely becomes several generated ones. Each
`.by` line is pinned to the *first* generated line that claims it — where the statement starts, and
so where a breakpoint belongs — and consecutive `.by` lines whose first generated lines are also
consecutive coalesce into one run. A typical file collapses to a handful of runs, one per point
where the emitted code grew. `ByLineMapping`, unit-tested.

**Reading the debuggee's state.** `sys.argv[0]` *is* available inside `sitecustomize` —
`_PySys_UpdateConfig` runs before `init_import_site` — so the bootstrap can tell the transpiled
program apart from the `python -c` version probe `by` also runs, which must not try to bind a port.
`sys.path[0]` is **not** yet the script's directory at that point, though; CPython computes it
after initialisation. So `_by_sourcemap.py` is loaded by explicit path, not by `import`.

## Failure reporting

The JSON file is the readiness signal *and* the error channel. Waiting on it rather than on the
port is what lets a failure be reported instead of merely timing out: `by run` transpiles the whole
project before the interpreter starts, which can outlast any reasonable connect-retry budget, and
an interpreter with no `debugpy` never opens a port at all. A debuggee that cannot import `debugpy`
writes an error naming its own `sys.executable` and the `pip install` line for it, which matters
because that interpreter is the one named by `PYTHON` or found as `python3` on `PATH` — **not
necessarily the project's `.venv`**. A `by` too old to emit `_by_sourcemap.py` still debugs, with a
warning notification that breakpoints in `.by` files will not bind.

Nothing in the bootstrap may take the user's program down with it: every step runs under a broad
`except`, and a bootstrap that fails means running without a debugger attached.

## Known limits

- **Frame lines can drift at a run boundary.** pydevd's `SourceMappingEntry.contains_runtime_line`
  computes its upper bound as `runtime_line + line + end_line` rather than
  `runtime_line + (end_line - line)`, so every entry claims a wider range of generated lines than
  it owns, and `map_to_client` returns the first entry that claims a line. Inside a run this is
  harmless — the entry is linear with slope 1, so a too-wide range still yields the right answer —
  but a generated line that falls in the gap between two runs can be attributed to the earlier run
  and reported one or more `.by` lines late. Breakpoints (`map_to_server`) are unaffected: that
  direction bisects on `contains_line`, which is correct.
- **Only `by run` configurations.** `by build` and `by check` produce no running program;
  debugging the test configuration would work the same way but is not wired up.
- **Only `.by` files.** A plain `.py` in a basedpython project has no source-map entry, so a
  breakpoint in it will not bind.
- **No exception breakpoints.** `ByExceptionBreakpointType` exists because
  `DapBreakpointsDescription` requires one and the platform's breakpoint manager throws without a
  default, but `DapXDebugProcess` registers a handler for line breakpoints only, so nothing is ever
  sent to the adapter. The type is hidden from the Breakpoints dialog rather than shown as a
  checkbox that changes nothing.
- **Local only.** The bootstrap resolves paths on the machine that runs the interpreter.
- **Paths have to agree.** A breakpoint's file comes from `VirtualFile.path`; the map's comes from
  `_by_sourcemap.py`. pydevd matches them after `normcase` and `absolute_path`, which is not
  `realpath` — so if the two ever disagreed (a symlinked project root, say), breakpoints would stay
  unverified rather than bind to the wrong place. `by` records absolute source paths, so this has
  not been observed; it is the first thing to check if breakpoints silently do not bind.

## Coverage

FEATURES.md §66 records line coverage as blocked by the same supposed upstream gap. It is blocked by
the same *actual* situation, and unblocked by the same discovery: coverage gathered on the generated
`.py` can be projected onto `.by` lines with `SOURCEMAP`. It would still additionally need a Python
plugin `CoverageEngine`, which the IDE target does not ship.
