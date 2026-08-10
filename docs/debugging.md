# Source-mapped debugging for `.by`

Design note. Nothing here is implemented yet; this exists so the next attempt does not have to
rediscover it. It replaces the previous belief — recorded in FEATURES.md §64 and in a comment on
`DebugWithPdbAction` — that source-mapped debugging was **blocked upstream** because the transpiler
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

## The approach that works

Let pydevd do the translation. debugpy vendors pydevd, which has first-class support for debugging
generated code — it is how notebook cell debugging works — exposed as a custom DAP request:

    setPydevdSourceMap { source: {path}, pydevdSourceMaps: [{line, endLine, runtimeSource: {path}, runtimeLine}] }

Confirmed present in debugpy 1.8.21 (`_pydevd_bundle/_debug_adapter/pydevd_schema.py`, and
`_pydevd_bundle/pydevd_source_mapping.py`). Once a map is registered for a `.by` file, breakpoints
set on that file are placed on the corresponding generated lines, and frames come back reported
against the `.by` file. Both directions, in the debuggee, where the map already lives.

Sketch:

1. **Bootstrap.** Ship a `sitecustomize.py` as a plugin resource; on debug launch, copy it to a temp
   directory and prepend that directory to `PYTHONPATH`. Guard it so it only activates when
   `BASEDPYTHON_DEBUG_PORT` is set *and* `sys.argv[0]` ends with `_by_runner.py` — `by` also runs
   the interpreter with `-c` to probe its version, and that must not try to bind a port.
2. **Export the map.** From `sitecustomize`, resolve the temp directory as
   `os.path.dirname(os.path.abspath(sys.argv[0]))`, import `_by_sourcemap` from it, and write
   `SOURCEMAP` as JSON to the path in `BASEDPYTHON_SOURCEMAP_OUT`. Do this before blocking on the
   client so the IDE has the map by the time it attaches.
3. **Listen.** `debugpy.listen(("127.0.0.1", port))` then `debugpy.wait_for_client()`.
4. **IDE side.** A `DebugAdapterSupportProvider` + `DebugAdapterDescriptor` that launches
   `by run` (as `ByCommandLineState` already does) with those two environment variables set, then
   attaches to the port. Override `getDebugAdapterServerClass()` to return an lsp4j
   `IDebugProtocolServer` subinterface carrying `@JsonRequest("setPydevdSourceMap")` — the standard
   lsp4j way to add a protocol extension.
5. **Push the map.** After `initialized`, read the exported JSON, invert each per-file list
   (generated line to `.by` line becomes `.by` line to generated line) and send one
   `setPydevdSourceMap` per `.by` file. Inverting is where the care goes: several generated lines
   can share one `.by` line, and `None` entries have no source at all.

## What it needs from the environment

`debugpy` must be importable by the interpreter `by run` chooses — the one named by `PYTHON`, or
`python3` from `PATH`, which is **not** necessarily the project's `.venv`. Worth surfacing as a
banner with an install action, the way `ByMissingBannerProvider` handles a missing `by`.

## Coverage

FEATURES.md §66 records line coverage as blocked by the same supposed upstream gap. It is blocked by
the same *actual* situation, and unblocked by the same discovery: coverage gathered on the generated
`.py` can be projected onto `.by` lines with `SOURCEMAP`. It would still additionally need a Python
plugin `CoverageEngine`, which the IDE target does not ship.
