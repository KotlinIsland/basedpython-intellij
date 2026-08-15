package dev.basedpython.pycharm.debug.bpd

/**
 * The interpreter `by run` is pointed at when `bpd` is the backend.
 *
 * ## Why there is a wrapper at all
 *
 * `by run` transpiles the project into a temp directory, writes `_by_sourcemap.py` beside the
 * generated python, and then runs `$PYTHON _by_runner.py <module>` **with that directory as the
 * working directory** — tearing the whole tree down when that process ends. So the map exists for
 * exactly as long as the program does, and the only way for a debugger to be in the picture is to
 * *be* the interpreter `by run` starts.
 *
 * The IDE controls the environment of `by run` and nothing else, which leaves `PYTHON`. The
 * debugpy backend reaches its interpreter the same way, through `PYTHONPATH` and a
 * `sitecustomize.py`, for the same reason.
 *
 * ## Why it cannot simply `exec bpd`
 *
 * `by run` calls `$PYTHON` twice and only one of the calls is the program:
 *
 * 1. `$PYTHON -c "import sys; print(...)"`, to find out which version to emit code for. A wrapper
 *    that answered that with anything else would make `by run` target a python that is not the one
 *    running the program — so it is passed straight through to the real interpreter
 * 2. `$PYTHON _by_runner.py <module> <args...>`, which is the program
 *
 * The second is the one `bpd` serves, and it does not run it directly: `bpd dap` is a debug
 * adapter, and what starts a program is the `launch` request its client sends. So the wrapper
 * records what it was asked to run — the arguments and the working directory, neither of which the
 * IDE can know before `by run` has chosen a temp directory — and then serves DAP.
 *
 * ## The record is lines, not json
 *
 * One field per line, prefixed. Quoting a path into json from `sh` needs `sed` and gets it subtly
 * wrong on a backslash; a line does not need quoting at all. The one thing a line cannot carry is
 * a path containing a newline, and [ByBpdRecord] refuses that rather than misreading it.
 *
 * `bpd dap --listen` prints its own one line of json — where it bound, and the token a client must
 * present — and under `by run` its stdout is a pipe the IDE is not holding. So it is appended to
 * the same file, below the wrapper's lines. Read together they are everything the IDE needs.
 */
object ByBpdWrapper {

    /** The real interpreter, for the calls that are not the program. */
    const val ENV_PYTHON: String = "BASEDPYTHON_BPD_PYTHON"

    /** The port `bpd dap` should listen on. */
    const val ENV_PORT: String = "BASEDPYTHON_BPD_PORT"

    /** The file the wrapper writes its record to, and `bpd` its announcement. */
    const val ENV_RECORD: String = "BASEDPYTHON_BPD_RECORD"

    /** The `bpd` executable. */
    const val ENV_BPD: String = "BASEDPYTHON_BPD"

    /** The prefix on the line naming the directory `by run` chose. */
    const val CWD_PREFIX: String = "cwd "

    /** The prefix on each line naming one argument of the program. */
    const val ARG_PREFIX: String = "arg "

    /**
     * The wrapper, as a POSIX shell script.
     *
     * Deliberately `sh` rather than `bash`: it runs on whatever the user's machine has. Written
     * here rather than shipped as a resource because it is four decisions long and reads better
     * beside the reasons for them.
     */
    fun script(): String = SCRIPT
        .replace("@PYTHON@", ENV_PYTHON)
        .replace("@PORT@", ENV_PORT)
        .replace("@RECORD@", ENV_RECORD)
        .replace("@BPD@", ENV_BPD)
        .replace("@CWD@", CWD_PREFIX.trim())
        .replace("@ARG@", ARG_PREFIX.trim())

    /**
     * Whether this operating system can be pointed at a shell script as its interpreter.
     *
     * Windows cannot: `by run` starts `$PYTHON` with `CreateProcess`, which runs an executable
     * rather than asking a shell to interpret a shebang. Refusing by name beats producing a
     * session that fails somewhere less obvious.
     */
    fun isSupported(osName: String): Boolean = !osName.lowercase().startsWith("windows")

    private val SCRIPT = """
        #!/bin/sh
        # Written by the basedpython plugin. `by run` runs this as its interpreter — see
        # dev.basedpython.pycharm.debug.bpd.ByBpdWrapper for why it exists.
        set -e

        # `by run` probes the interpreter before it emits any code, and that question is about the
        # real interpreter. Answering it any other way would make `by run` target the wrong python.
        case "$1" in
          -c|-m|-V|--version|-h|--help)
            exec "${'$'}@PYTHON@" "$@"
            ;;
        esac

        # Anything else is the program. Record it: the IDE cannot know the temp directory `by run`
        # chose, and it sends these back as the `launch` request.
        {
          printf '@CWD@ %s\n' "${'$'}PWD"
          for arg in "$@"; do
            printf '@ARG@ %s\n' "${'$'}arg"
          done
        } > "${'$'}@RECORD@"

        # bpd's announcement lands on the line below. Appending is what keeps both.
        exec "${'$'}@BPD@" dap --listen "${'$'}@PORT@" >> "${'$'}@RECORD@"
    """.trimIndent() + "\n"
}
