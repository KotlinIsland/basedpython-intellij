package dev.basedpython.pycharm.debug.hotswap

import dev.basedpython.pycharm.lsp.ext.ByRestage as ByRestageAnswer
import dev.basedpython.pycharm.lsp.ext.ByRestageRefusal
import dev.basedpython.pycharm.lsp.ext.ByRestaged
import com.google.gson.JsonObject as ByAnswerEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
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
            /**
             * Whether the map's line tables moved, which bpd is told so that it reads them again:
             * `by`'s answer when it gives one ([ByRestageAnswer.remap]), and otherwise whether the map
             * was rewritten at all, which is what that meant from a `by` before the answer. Not
             * whether the map was written: one rewritten only to list a re-staged copy leaves the
             * tables as they were, and bpd told to read it again holds a map listing a copy it never
             * compiled.
             */
            val remap: Boolean,
            /**
             * What `bpd/replaceCode` is given: the files the user edited, as `by` names them.
             *
             * Not the paths in the tree. `by run`'s runner compiles a hand-written `.py`'s copy as
             * the project file, so no code in the process is named after the copy — bpd handed the
             * copy found nothing of it and refused, on the first reload anyone tries. A `.by` bpd
             * resolves through the map to the generated python itself, and its answer then names
             * the file the user is looking at rather than one in a temporary directory.
             */
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
        return Plan.Write(
            files = changed,
            sourcemap = answer.sourcemap,
            remap = answer.remap ?: (answer.sourcemap != null),
            replace = replace.map { it.source ?: it.generated!! },
        )
    }

    /** What to hand bpd for an edit of a program run in place, or why nothing is. */
    sealed interface InPlace {
        /** Nothing is handed over. Each entry is a sentence for the user, naming the file it is about. */
        data class Refused(val reasons: List<String>) : InPlace

        /** One `bpd/replaceCode`: [files] by their paths, and what `by` answered for each `.by` of them. */
        data class Replace(val files: List<String>, val retranspiled: List<JsonObject>) : InPlace
    }

    /**
     * What an edit of a program run in place hands bpd: every edited file by its path — [modules],
     * the `.by` files `by` was asked to transpile again, and [asIs], the files the program imports
     * from where they are, a hand-written `.py` — with `by`'s [answers] for the modules, one each in
     * their order, passed on as `by` wrote them.
     *
     * Nothing is handed over when `by` refused any module — a set goes in together or not at all, as
     * bpd applies one — and every refusal is said, with the checker's sentences under it.
     */
    fun planInPlace(modules: List<String>, asIs: List<String>, answers: List<ByAnswerEntry>): InPlace {
        if (answers.size != modules.size) {
            return InPlace.Refused(
                listOf("nothing was reloaded: the `by` language server answered ${answers.size} of ${modules.size} modules"),
            )
        }
        val refused = answers.zip(modules).mapNotNull { (answer, module) ->
            when {
                answer.has(KEY) -> null
                answer.has(REFUSED) -> refusalSentence(
                    file = answer.stringOrNull(SOURCE) ?: module,
                    reason = answer.stringOrNull(REFUSED),
                    diagnostics = answer.getAsJsonArray(DIAGNOSTICS)?.mapNotNull { it.takeIf { it.isJsonPrimitive }?.asString }.orEmpty(),
                )
                else -> "${nameOf(module)}: the `by` language server answered with neither a transpile nor a refusal"
            }
        }
        if (refused.isNotEmpty()) return InPlace.Refused(refused)
        return InPlace.Replace(
            files = modules + asIs,
            retranspiled = answers.map { Json.parseToJsonElement(it.toString()).jsonObject },
        )
    }

    private fun ByAnswerEntry.stringOrNull(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    /** The fields of a `by retranspile` answer that say which shape it is, and what it is about. */
    private const val KEY = "key"
    private const val REFUSED = "refused"
    private const val SOURCE = "source"
    private const val DIAGNOSTICS = "diagnostics"

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
    private fun sentence(refusal: ByRestageRefusal): String =
        refusalSentence(refusal.file, refusal.refused, refusal.diagnostics)

    private fun refusalSentence(file: String?, reason: String?, diagnostics: List<String>): String {
        val why = reason ?: "refused without saying why"
        val head = file?.let { "${nameOf(it)}: $why" } ?: "nothing was reloaded: $why"
        if (diagnostics.isEmpty()) return head
        return head + diagnostics.joinToString("\n  ", prefix = "\n  ")
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
