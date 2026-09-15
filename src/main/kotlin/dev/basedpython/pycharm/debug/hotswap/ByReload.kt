package dev.basedpython.pycharm.debug.hotswap

import dev.basedpython.pycharm.lsp.ext.ByRestage as ByRestageAnswer
import dev.basedpython.pycharm.lsp.ext.ByRestageRefusal
import dev.basedpython.pycharm.lsp.ext.ByRestaged
import java.io.IOException
import java.nio.file.Path

/**
 * What one press of the reload button does with `by`'s answer, apart from the platform and the
 * debugger it sits between.
 *
 * Kept out of [ByHotSwapProvider] so the parts that can quietly corrupt a running session — which
 * files are written, that the map is written once and whole, and that a failure takes all of it
 * back — can be driven in a test against a real directory, without a debug session or a server.
 */
internal object ByReload {

    /** What to do with an answer. */
    sealed interface Plan {
        /** Nothing is written. Each entry is a sentence for the user, naming the file it is about. */
        data class Refused(val reasons: List<String>) : Plan

        /**
         * Every edited file already is the code the process is running, and the map already is the
         * one in the tree — an edit typed and typed back out again lands here.
         */
        data object UpToDate : Plan

        /** Bytes to put in the tree, and the files to hand bpd afterwards. */
        data class Write(
            /** Every slot whose bytes differ from what the tree holds. */
            val files: List<ByRestaged>,
            /** The one new `_by_sourcemap.py` for the whole set, or null when it did not change. */
            val sourcemap: String?,
            /** What `bpd/replaceCode` is given, as paths in the tree. */
            val replace: List<String>,
        ) : Plan
    }

    /** Read `by`'s answer as something to do. */
    fun plan(answer: ByRestageAnswer): Plan {
        answer.refusals?.let { refusals ->
            return Plan.Refused(
                refusals.map(::sentence).ifEmpty { listOf("the `by` language server refused without saying why") },
            )
        }
        val files = answer.files
            ?: return Plan.Refused(listOf("the `by` language server answered with neither bytes nor a refusal"))
        val incomplete = files.filter { it.generated == null || it.content == null }
        if (incomplete.isNotEmpty()) {
            return Plan.Refused(
                incomplete.map { "${nameOf(it.source)}: the `by` language server answered without the bytes to write" },
            )
        }

        val changed = files.filter { it.changed }
        val replace = when {
            changed.isNotEmpty() -> changed
            // No module's bytes moved, but its map entry did — a `.by` edited in a way that emits
            // the same python, which still moves the line table and the `.by` digest. The map is
            // written, and bpd is handed the set anyway: `remap` only rides on a replacement, and a
            // file whose bytes are what the process runs replaces to nothing and says so.
            answer.sourcemap != null -> files
            else -> return Plan.UpToDate
        }
        return Plan.Write(files = changed, sourcemap = answer.sourcemap, replace = replace.map { it.generated!! })
    }

    /**
     * Put a [Plan.Write] into the tree through `tree`, so that [ByBuildTree.rollback] takes back all
     * of it.
     *
     * The map is written **once**, after every module: it is one text describing all of them, and
     * writing it per file is exactly how every entry but the last used to be lost. On an
     * [IOException] part of the set may be on disk; the caller rolls `tree` back.
     */
    @Throws(IOException::class)
    fun write(plan: Plan.Write, buildDirectory: Path, tree: ByBuildTree) {
        for (file in plan.files) {
            tree.write(Path.of(file.generated!!), file.content!!)
        }
        plan.sourcemap?.let { tree.write(buildDirectory.resolve(BY_SOURCEMAP), it) }
    }

    /** A refusal from `by`, with the checker's own sentences under it when that is the reason. */
    private fun sentence(refusal: ByRestageRefusal): String {
        val reason = refusal.refused ?: "refused without saying why"
        val head = refusal.file?.let { "${nameOf(it)}: $reason" } ?: "nothing was reloaded: $reason"
        if (refusal.diagnostics.isEmpty()) return head
        return head + refusal.diagnostics.joinToString("\n  ", prefix = "\n  ")
    }

    private fun nameOf(path: String?): String = path?.let { Path.of(it).fileName?.toString() ?: it } ?: "a file"

    /**
     * The map `by` writes beside the python it generated, named by `by_stage::sourcemap`.
     *
     * Spelled here because the plugin writes it: `by` answers with the whole new text of it and
     * says nothing about where it goes, since it goes where it always was.
     */
    const val BY_SOURCEMAP = "_by_sourcemap.py"
}
