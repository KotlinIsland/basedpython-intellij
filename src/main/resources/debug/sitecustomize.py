"""Debug bootstrap for `by run`, injected by the basedpython IDE plugin.

The plugin copies this file into a temp directory and prepends that directory to ``PYTHONPATH``
before launching ``by run``. ``by`` builds the interpreter command line itself — the plugin never
gets to add ``-m debugpy`` — so ``sitecustomize`` is the only hook that reaches an interpreter
somebody else launched.

What it does, in order:

1. Refuses to act unless this really is the transpiled program. ``by`` also runs the interpreter as
   ``python -c ...`` to probe its version, and that must not try to bind a port.
2. Reads ``_by_sourcemap.py`` out of ``by run``'s temp directory — the generated-line to
   ``.by``-line table the CLI already writes and ``_by_runner.py`` already uses to rewrite
   tracebacks.
3. Starts ``debugpy`` listening, then writes both the map and the outcome to the JSON file named by
   ``BASEDPYTHON_DEBUG_INFO_OUT``. That file is the IDE's readiness signal *and* its error channel:
   an interpreter with no ``debugpy`` reports why instead of silently never opening a port.
4. Blocks until the IDE attaches.

Nothing here may take the user's program down with it: every step runs under a broad ``except``,
and a bootstrap that fails just means running without a debugger attached.
"""

import os
import sys

_HERE = os.path.dirname(os.path.abspath(__file__))
_RUNNER = "_by_runner.py"

# Popped, not read: `debugpy` re-launches subprocesses with the parent's environment, and a child
# that tried to bind the same port would fail. The IDE debugs one process.
_PORT = os.environ.pop("BASEDPYTHON_DEBUG_PORT", None)
_INFO_OUT = os.environ.pop("BASEDPYTHON_DEBUG_INFO_OUT", None)


def _script_path():
    return os.path.abspath(sys.argv[0]) if sys.argv and sys.argv[0] else ""


def _is_transpiled_program():
    return os.path.basename(_script_path()) == _RUNNER


def _write_info(payload):
    """Write the report atomically, so the IDE never reads a half-written file."""
    import json
    import tempfile

    directory = os.path.dirname(_INFO_OUT) or "."
    handle, temp_path = tempfile.mkstemp(dir=directory, prefix=".by-debug-", suffix=".json")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            json.dump(payload, stream)
        os.replace(temp_path, _INFO_OUT)
    except BaseException:
        try:
            os.unlink(temp_path)
        except OSError:
            pass
        raise


def _sourcemap_path(run_dir):
    return os.path.join(run_dir, "_by_sourcemap.py")


def _read_source_map(run_dir):
    """`_by_sourcemap.py` as ``[{source, generated, lines}]``.

    Loaded by explicit path rather than with a plain ``import``: ``sys.path[0]`` is not the script's
    directory yet when ``site`` runs ``sitecustomize`` — CPython computes it after initialisation.
    """
    import importlib.util

    path = _sourcemap_path(run_dir)
    if not os.path.isfile(path):
        return []

    spec = importlib.util.spec_from_file_location("_by_debug_sourcemap", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    files = []
    for generated, entry in getattr(module, "SOURCEMAP", {}).items():
        source, lines = entry
        files.append({
            # realpath, not the key as written: on macOS the temp directory is reached through
            # /var -> /private/var, `by` records the unresolved form, and Python reports the
            # resolved one in frames. pydevd matches `runtimeSource` against a frame's filename
            # with no normalisation of its own, so a mismatch here means breakpoints verify and
            # then never hit. `by run`'s own `_by_runner.py` calls realpath for the same reason.
            "generated": os.path.realpath(generated),
            "source": source,
            "lines": list(lines),
        })
    return files


def _read_collisions(run_dir):
    """Generated files that more than one ``.by`` source claims.

    Two ``.by`` files whose module paths coincide — ``main.by`` beside ``src/main.by``, say — are
    transpiled to the *same* generated file, and the second write wins. Both the output and the
    debuggability of the first are simply gone; if the winner happens to be an empty file, the
    program runs and does nothing, which is a genuinely baffling thing to sit and watch.

    ``SOURCEMAP`` cannot show this once loaded: it is a dict literal, so the duplicate key has
    already collapsed to the last entry by the time Python hands it over. Reading the file back as
    a syntax tree is what keeps both keys visible.
    """
    import ast

    path = _sourcemap_path(run_dir)
    if not os.path.isfile(path):
        return []
    with open(path, "r", encoding="utf-8") as stream:
        tree = ast.parse(stream.read())

    claims = {}
    order = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Dict):
            continue
        for key, value in zip(node.keys, node.values):
            if not isinstance(key, ast.Constant) or not isinstance(value, ast.Tuple):
                continue
            source = value.elts[0] if value.elts else None
            if not isinstance(source, ast.Constant):
                continue
            generated = os.path.realpath(key.value)
            if generated not in claims:
                claims[generated] = []
                order.append(generated)
            claims[generated].append(source.value)

    return [
        {"generated": generated, "sources": claims[generated]}
        for generated in order
        if len(claims[generated]) > 1
    ]


def _listen_without_inheriting_console(debugpy, port):
    """Start listening without handing the console to the debug adapter.

    ``debugpy.listen()`` spawns the adapter as a subprocess that deliberately outlives this one —
    it has to, since it brokers the session — and a subprocess inherits file descriptors 1 and 2.
    The IDE decides a run has finished when the process's stdout reaches EOF, and the adapter holds
    that pipe open for as long as the debug session lasts. The program prints its output, exits,
    and the run sits there looking hung until somebody presses Stop.

    Pointing 1 and 2 at ``os.devnull`` across the spawn is enough: what the adapter inherits is
    fixed at that moment, and it talks over sockets, not stdio. The program's own descriptors are
    restored immediately afterwards, so its output is untouched.
    """
    sys.stdout.flush()
    sys.stderr.flush()
    devnull = os.open(os.devnull, os.O_RDWR)
    saved_out = os.dup(1)
    saved_err = os.dup(2)
    try:
        os.dup2(devnull, 1)
        os.dup2(devnull, 2)
        debugpy.listen(("127.0.0.1", port))
    finally:
        os.dup2(saved_out, 1)
        os.dup2(saved_err, 2)
        os.close(saved_out)
        os.close(saved_err)
        os.close(devnull)


def _activate(port):
    run_dir = os.path.dirname(_script_path())

    try:
        import debugpy
    except Exception as exc:
        _write_info({
            "status": "error",
            "python": sys.executable,
            "runDir": run_dir,
            # States the fact and names the interpreter; it deliberately does not prescribe a
            # command. Only the IDE knows whether this project is uv-managed, where a plain
            # `pip install` would be undone by the next sync.
            "message": "debugpy is not installed in the interpreter by run uses ({0}): {1}"
                       .format(exc, sys.executable),
        })
        return

    warning = None
    collisions = []
    try:
        files = _read_source_map(run_dir)
        collisions = _read_collisions(run_dir)
        if not os.path.isfile(_sourcemap_path(run_dir)):
            warning = "by run wrote no _by_sourcemap.py into {0}, so breakpoints in .by files " \
                      "cannot be placed on the transpiled output".format(run_dir)
    except Exception as exc:
        files = []
        warning = "could not read _by_sourcemap.py: {0}".format(exc)

    try:
        _listen_without_inheriting_console(debugpy, port)
    except Exception as exc:
        _write_info({
            "status": "error",
            "python": sys.executable,
            "runDir": run_dir,
            "message": "debugpy could not listen on port {0}: {1}".format(port, exc),
        })
        return

    # Written only once the port is open, which is what makes its appearance a readiness signal.
    _write_info({
        "status": "listening",
        "port": port,
        "python": sys.executable,
        "runDir": run_dir,
        "message": warning,
        "files": files,
        "collisions": collisions,
    })
    debugpy.wait_for_client()


def _find_shadowed_sitecustomize():
    """The ``sitecustomize.py`` this one displaced, if there was one.

    Prepending a directory to ``PYTHONPATH`` also prepends its ``sitecustomize``, and ``site``
    imports exactly one. Environments that ship their own would silently stop being customised.

    Resolved *before* anything else runs, because ``sys.path`` does not stay still: importing
    debugpy puts pydevd's own ``pydev_sitecustomize`` on it, and that file is not a user
    customisation at all — it is the shim pydevd injects into subprocesses it wants to debug, and
    it ends by deleting ``sys.modules['sitecustomize']`` and re-importing, which from here would
    find *this* module again. Skipped by name as well, belt and braces.
    """
    for entry in sys.path:
        try:
            directory = os.path.abspath(entry or os.getcwd())
        except Exception:
            continue
        if directory == _HERE or "pydev_sitecustomize" in directory:
            continue
        candidate = os.path.join(directory, "sitecustomize.py")
        if os.path.isfile(candidate):
            return candidate
    return None


def _run_shadowed_sitecustomize(candidate):
    if not candidate:
        return
    import importlib.util

    spec = importlib.util.spec_from_file_location("_by_shadowed_sitecustomize", candidate)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)


# Captured now, while sys.path is still the one `site` built.
try:
    _SHADOWED = _find_shadowed_sitecustomize()
except BaseException:
    _SHADOWED = None

try:
    if _PORT and _INFO_OUT and _is_transpiled_program():
        _activate(int(_PORT))
except BaseException:
    # A broken debugger must never be the reason the program does not run.
    import traceback

    traceback.print_exc()

try:
    _run_shadowed_sitecustomize(_SHADOWED)
except BaseException:
    import traceback

    traceback.print_exc()
