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


def _read_source_map(run_dir):
    """`_by_sourcemap.py` as ``[{source, generated, lines}]``.

    Loaded by explicit path rather than with a plain ``import``: ``sys.path[0]`` is not the script's
    directory yet when ``site`` runs ``sitecustomize`` — CPython computes it after initialisation.
    """
    import importlib.util

    path = os.path.join(run_dir, "_by_sourcemap.py")
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
    try:
        files = _read_source_map(run_dir)
        if not files:
            warning = "no _by_sourcemap.py in {0} — breakpoints in .by files cannot be " \
                      "mapped onto the transpiled output".format(run_dir)
    except Exception as exc:
        files = []
        warning = "could not read _by_sourcemap.py: {0}".format(exc)

    try:
        debugpy.listen(("127.0.0.1", port))
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
    })
    debugpy.wait_for_client()


def _chain_to_shadowed_sitecustomize():
    """Run the ``sitecustomize`` this one displaced, if there was one.

    Prepending a directory to ``PYTHONPATH`` also prepends its ``sitecustomize``, and ``site``
    imports exactly one. Environments that ship their own would silently stop being customised.
    """
    import importlib.util

    for entry in sys.path:
        directory = os.path.abspath(entry or os.getcwd())
        if directory == _HERE:
            continue
        candidate = os.path.join(directory, "sitecustomize.py")
        if not os.path.isfile(candidate):
            continue
        spec = importlib.util.spec_from_file_location("_by_shadowed_sitecustomize", candidate)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return


try:
    if _PORT and _INFO_OUT and _is_transpiled_program():
        _activate(int(_PORT))
except BaseException:
    # A broken debugger must never be the reason the program does not run.
    import traceback

    traceback.print_exc()

try:
    _chain_to_shadowed_sitecustomize()
except BaseException:
    import traceback

    traceback.print_exc()
