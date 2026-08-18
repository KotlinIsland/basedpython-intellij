package dev.basedpython.pycharm.debug.hotswap

import dev.basedpython.pycharm.lang.dialect.BasedPythonSources

/**
 * Which of the files a user edited mid-session the debugger can actually reload, and what to say
 * about the ones it cannot.
 *
 * Pure, and separate from [ByHotSwapProvider] for the reason
 * [dev.basedpython.pycharm.debug.ByRestartFrame] is separate from its handler: the decision is the
 * part worth testing, and it needs no debug session to be wrong.
 */
internal object ByHotSwap {

    /**
     * Why the running program cannot be given this file's edits, or null when it can.
     *
     * ## `.by` is the one that cannot, and the reason is not the debugger's
     *
     * A replacement is a set of assignments to `function.__code__` against the code the
     * **interpreter** compiled, and under `by run` that is never the `.by` file — it is the python
     * `by run` transpiled it to, in a temp directory it deletes when the program ends. Handing bpd
     * a `.by` would be asking it to replace code that no interpreter has ever seen.
     *
     * Nor is producing the replacement bpd's to do: giving the running build the edit means
     * transpiling that one file again *into the tree the program is running from*, with its line
     * table and its digests, and that is `by`'s job — `by transpile` emits the python and no line
     * table, so the map beside the generated file would describe the file it used to be. bpd says
     * the same thing from its side, in its roadmap: `replaceCode` is named as generated python
     * rather than accepting a `.by` and doing something adjacent to what was asked.
     *
     * So the honest answer is a refusal that names what is missing, and a session restarted to pick
     * the edit up. It is a refusal rather than a silence because the thing worth having here is the
     * *first* half — knowing that the source on screen is not the code that is running is the whole
     * reason a debugger offers this at all, and that is true of a `.by` edit exactly as much as of
     * a `.py` one.
     *
     * A plain `.py` needs none of that. `by run` transpiles `.by` and copies nothing else, so the
     * interpreter loads a `.py` module from where the user wrote it — the file on disk *is* the
     * file that is running, which is precisely the comparison bpd makes.
     */
    fun refuse(path: String): String? =
        if (path.substringAfterLast('.').lowercase() == BasedPythonSources.BY) BY_NEEDS_TRANSPILING else null

    /** What every refused file has to say, said once. */
    const val BY_NEEDS_TRANSPILING: String =
        "the program is running the python `by run` transpiled it to, not this file, and giving " +
            "that build the edit means transpiling it again — which is `by`'s to do, not the " +
            "debugger's. Restart the session to run the edited source"

    /**
     * What one press of Reload amounts to: the files to ask bpd about, and the ones to explain
     * instead.
     *
     * Sorted so that a console account of a session reads the same way twice — a change set is a
     * hash set, and the order it iterates in is not a fact about anything.
     */
    fun plan(changed: Collection<String>): ByHotSwapPlan {
        val replaceable = mutableListOf<String>()
        val refused = mutableListOf<Pair<String, String>>()
        for (path in changed.sorted()) {
            val why = refuse(path)
            if (why == null) replaceable += path else refused += path to why
        }
        return ByHotSwapPlan(replaceable, refused)
    }
}

/**
 * @property replaceable the files to send `bpd/replaceCode` for
 * @property refused the files this plugin refuses itself, each with why
 */
internal data class ByHotSwapPlan(
    val replaceable: List<String>,
    val refused: List<Pair<String, String>>,
) {
    /** What to print for a file this plugin would not even ask bpd about. */
    fun refusals(): List<String> = refused.map { (path, why) ->
        "did not reload ${path.substringAfterLast('/')}: $why"
    }
}
