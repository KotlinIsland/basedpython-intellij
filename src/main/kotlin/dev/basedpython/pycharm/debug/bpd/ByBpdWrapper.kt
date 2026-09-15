package dev.basedpython.pycharm.debug.bpd

/**
 * The launcher `by run` starts the program through when `bpd` is the backend.
 *
 * ## Why there is a wrapper at all
 *
 * `by run` transpiles the project into a temp directory, writes `_by_sourcemap.py` beside the
 * generated python, and then runs `<python> _by_runner.py <module>` — tearing the whole tree down
 * when that process ends. So the map exists for exactly as long as the program does, and the only
 * way for a debugger to be in the picture is to be the process `by run` starts.
 *
 * ## How the IDE gets in
 *
 * By naming this script on `by run`'s command line as `--launcher`. `by run` chooses the
 * interpreter exactly as it would for a plain run, probes its version itself, and then starts
 * `<launcher> <python> _by_runner.py <module> <args...>` — once, for the program. So the wrapper is
 * handed the interpreter rather than having to know it, and never sees the version probe.
 *
 * It used to be `--python` instead, which put the wrapper in the interpreter's place: that switched
 * `by run`'s own discovery off, so the IDE had to guess the interpreter the run would have used and
 * hand it on, and the wrapper had to tell the probe apart from the program by its arguments.
 *
 * The debugpy backend reaches its interpreter differently, through `PYTHONPATH` and a
 * `sitecustomize.py`, because it runs *inside* an interpreter rather than in front of one.
 *
 * ## Why it cannot simply `exec bpd`
 *
 * `bpd dap` is a debug adapter, and what starts a program is the `launch` request its client sends.
 * So the wrapper records what it was asked to run — the interpreter, the arguments and the working
 * directory, none of which the IDE can know before `by run` has chosen them — and then serves DAP.
 *
 * ## Which `bpd`
 *
 * The one beside the `by` the run starts when the IDE found one there ([ENV_BPD]), else the one
 * beside the interpreter `by run` chose — the environment's `bin`, where `uv add --dev` puts it for
 * a uv project, whose launch names no directory of its own — else the one the IDE found on `PATH`
 * ([ENV_BPD_FALLBACK]). The middle answer is only knowable here, which is why the order is decided
 * here rather than in [ByBpdExecutable]. When there is none at all the wrapper records that
 * ([NO_BPD_PREFIX]) and exits without starting the program.
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

    /** The port `bpd dap` should listen on. */
    const val ENV_PORT: String = "BASEDPYTHON_BPD_PORT"

    /** The file the wrapper writes its record to, and `bpd` its announcement. */
    const val ENV_RECORD: String = "BASEDPYTHON_BPD_RECORD"

    /** The `bpd` beside the `by` the run starts, when there is one. Preferred over every other. */
    const val ENV_BPD: String = "BASEDPYTHON_BPD"

    /** The `bpd` on the IDE's `PATH`, for when there is none beside `by` or the interpreter. */
    const val ENV_BPD_FALLBACK: String = "BASEDPYTHON_BPD_FALLBACK"

    /** The prefix on the line naming the interpreter `by run` chose. */
    const val PYTHON_PREFIX: String = "python "

    /** The prefix on the line naming the directory `by run` chose. */
    const val CWD_PREFIX: String = "cwd "

    /** The prefix on each line naming one argument of the program. */
    const val ARG_PREFIX: String = "arg "

    /** The prefix on the line saying no `bpd` was found, and which interpreter it was looked for beside. */
    const val NO_BPD_PREFIX: String = "nobpd "

    /**
     * The wrapper, as a POSIX shell script.
     *
     * Deliberately `sh` rather than `bash`: it runs on whatever the user's machine has. Written
     * here rather than shipped as a resource because it is a handful of decisions long and reads
     * better beside the reasons for them.
     */
    fun script(): String = SCRIPT
        .replace("@PORT@", ENV_PORT)
        .replace("@RECORD@", ENV_RECORD)
        .replace("@BPD_FALLBACK@", ENV_BPD_FALLBACK)
        .replace("@BPD@", ENV_BPD)
        .replace("@PYTHON@", PYTHON_PREFIX.trim())
        .replace("@CWD@", CWD_PREFIX.trim())
        .replace("@ARG@", ARG_PREFIX.trim())
        .replace("@NOBPD@", NO_BPD_PREFIX.trim())

    /**
     * Whether this operating system can start a shell script as the launcher.
     *
     * Windows cannot: `by run` starts its launcher with `CreateProcess`, which runs an executable
     * rather than asking a shell to interpret a shebang. Refusing by name beats producing a session
     * that fails somewhere less obvious.
     */
    fun isSupported(osName: String): Boolean = !osName.lowercase().startsWith("windows")

    private val SCRIPT = """
        #!/bin/sh
        # Written by the basedpython plugin. `by run --launcher` starts the program through this —
        # see dev.basedpython.pycharm.debug.bpd.ByBpdWrapper for why it exists.
        set -e

        # `by run` names the interpreter it chose first, then the program.
        python="$1"
        shift

        # Which bpd: beside `by`, else beside the interpreter, else on the IDE's PATH. A bare
        # interpreter name has no directory to look in, and `dirname` would answer `.`, which is
        # wherever this happens to be standing.
        bpd="${'$'}@BPD@"
        if [ -z "${'$'}bpd" ]; then
          case "${'$'}python" in
            */*)
              if [ -f "$(dirname "${'$'}python")/bpd" ]; then
                bpd="$(dirname "${'$'}python")/bpd"
              fi
              ;;
          esac
        fi
        if [ -z "${'$'}bpd" ]; then
          bpd="${'$'}@BPD_FALLBACK@"
        fi
        if [ -z "${'$'}bpd" ]; then
          printf '@NOBPD@ %s\n' "${'$'}python" > "${'$'}@RECORD@"
          exit 127
        fi

        # Stand where the runner is before anything else: bpd inherits this directory and the
        # `launch` request names the program relative to it. `by run` runs from the project root and
        # names the runner absolutely; `dirname` of a bare `_by_runner.py` is `.`, so either works.
        cd "$(dirname "$1")"

        # Record it: the IDE cannot know the interpreter or the temp directory `by run` chose, and
        # it sends these back as the `launch` request.
        {
          printf '@PYTHON@ %s\n' "${'$'}python"
          printf '@CWD@ %s\n' "${'$'}PWD"
          for arg in "$@"; do
            printf '@ARG@ %s\n' "${'$'}arg"
          done
        } > "${'$'}@RECORD@"

        # bpd's announcement lands on the line below. Appending is what keeps both.
        exec "${'$'}bpd" dap --listen "${'$'}@PORT@" >> "${'$'}@RECORD@"
    """.trimIndent() + "\n"
}
