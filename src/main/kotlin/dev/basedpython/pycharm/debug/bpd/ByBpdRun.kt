package dev.basedpython.pycharm.debug.bpd

import java.nio.file.Path

/**
 * The program a bpd session debugs, as the session knows it: the file its wrapper records what
 * `by run` chose into, and whether `by run` runs it in place.
 *
 * Both are what hot reload needs to give the running program new code. [record] says where the
 * program runs out of — the runner's directory ([runnerDirectory]), which is only written once
 * `by run` has started it, so it is read when asked rather than when the session is built. [inPlace]
 * says what that directory is, and so how a reload reaches the program: a staged tree is rewritten
 * with the edit, while a run in place transpiles an edited `.by` again into its run directory, which
 * nothing but `by` writes ([dev.basedpython.pycharm.debug.hotswap.ByHotSwapProvider]). It is what
 * the run configuration decided and put on `by run`'s command line
 * ([dev.basedpython.pycharm.run.ByCommandLineState.inPlace]), so it is known rather than read back
 * out of the directory.
 *
 * A child the program starts runs out of the same directory, so its session is handed its parent's.
 */
internal data class ByBpdRun(val record: Path, val inPlace: Boolean) {

    /** The directory of the runner `by run` started the program as, once the wrapper has said; see [ByBpdRecord.runnerDirectoryOf]. */
    fun runnerDirectory(): String? = ByBpdRecord.runnerDirectoryOf(record)
}
