package dev.basedpython.pycharm.run.django

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.execution.ParametersListUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.jetbrains.annotations.Nls

/** What the *manage.py* window's Now view shows of the runs, as rows; [ByManageNowPanel] draws it. */
internal object ByManageNow {

    enum class State { RUNNING, SERVING, WAITING, WARNING, PASSED, FAILED, STOPPED }

    /** What a row's link does. */
    sealed interface Link {
        data class Browse(val url: String) : Link
        data class Output(val run: Long) : Link
        data class Answer(val run: Long) : Link
        data class Stop(val run: Long, val kill: Boolean) : Link
        data class Again(val run: Long) : Link
        data class Debug(val run: Long) : Link
        data class Edit(val arguments: String) : Link
        data class Open(val run: Long, val path: String) : Link

        /** Does what [warned] asks for, run as what warned of it was. */
        data class Fix(val warned: ByManageWarned) : Link
    }

    data class Row(
        /** What the row is of, the same from one time it is drawn to the next. */
        val key: String,
        val state: State,
        @param:NlsSafe val title: String,
        @param:Nls val detail: String,
        val links: List<Link>,
    )

    data class Section(@param:Nls val title: String, val rows: List<Row>)

    /** [outputs] are the runs whose console is still to be shown. */
    fun sections(state: ByManageRuns.State, outputs: Set<Long>, now: Long): List<Section> = listOf(
        Section(
            BasedPythonBundle.message("manage.now.running"),
            // the server is what a project runs all day: it stays at the top
            state.runs.filter { it.running }.sortedByDescending { it.facts.server != null }.map { running(it, it.id in outputs, now) },
        ),
        Section(BasedPythonBundle.message("manage.now.attention"), state.warned.map(::attention)),
        Section(
            BasedPythonBundle.message("manage.now.recent"),
            state.runs.filterNot { it.running }.sortedByDescending { it.endedAt }.map { finished(it, it.id in outputs) },
        ),
    ).filter { it.rows.isNotEmpty() }

    private fun running(run: ByManageRun, output: Boolean, now: Long): Row {
        val facts = run.facts
        // a program that goes on after an error — a shell, a worker — has not failed by it; a server
        // that could not start has, until a change starts it again
        val error = facts.error.takeIf { facts.watching }
        val debugging = run.executorId == DefaultDebugExecutor.EXECUTOR_ID
        val details = buildList {
            facts.server?.let { add(it.trimEnd('/').substringAfter("://")) }
            if (debugging) add(BasedPythonBundle.message("manage.now.debugging"))
            add(duration(now - run.startedAt))
            when {
                error != null -> add(error)
                facts.prompt != null -> add(BasedPythonBundle.message("manage.now.waiting", facts.prompt))
                facts.reloading -> add(BasedPythonBundle.message("manage.now.reloading"))
            }
            if (facts.reloads > 0) add(BasedPythonBundle.message("manage.now.reloads", facts.reloads))
            if (facts.requests > 0) add(BasedPythonBundle.message("manage.now.requests", facts.requests))
            if (facts.failedRequests > 0) add(BasedPythonBundle.message("manage.now.failedRequests", facts.failedRequests))
            if (facts.migrated > 0) add(BasedPythonBundle.message("manage.now.migrated", facts.migrated))
            facts.migrating?.let { add(it) }
        }
        val state = when {
            error != null -> State.FAILED
            facts.prompt != null -> State.WAITING
            facts.server != null && !facts.reloading -> State.SERVING
            else -> State.RUNNING
        }
        val links = buildList {
            if (output) add(if (facts.prompt != null) Link.Answer(run.id) else Link.Output(run.id))
            facts.server?.let { add(Link.Browse(it)) }
            if (facts.server != null && !debugging) add(Link.Debug(run.id))
            add(Link.Stop(run.id, kill = run.stopped))
        }
        return Row("run ${run.id}", state, title(run), details.joinToString(SEPARATOR), links)
    }

    private fun finished(run: ByManageRun, output: Boolean): Row {
        val facts = run.facts
        val failed = run.exitCode != 0
        val details = buildList {
            when {
                run.stopped -> add(BasedPythonBundle.message("manage.now.stopped"))
                failed -> add(BasedPythonBundle.message("manage.now.failed", run.exitCode ?: -1))
            }
            add(duration((run.endedAt ?: run.startedAt) - run.startedAt))
            val said = if (failed && !run.stopped) facts.error ?: facts.summary else facts.summary
            said?.let { add(it) }
            if (facts.migrated > 0) add(BasedPythonBundle.message("manage.now.migrated", facts.migrated))
        }
        val links = buildList {
            // a dry run names what it would write
            if (!failed && "--dry-run" !in run.words) facts.written.lastOrNull()?.let { add(Link.Open(run.id, it)) }
            if (output) add(Link.Output(run.id))
            if (failed && !run.stopped) add(Link.Edit(run.arguments))
            add(Link.Again(run.id))
        }
        val state = when {
            run.stopped -> State.STOPPED
            failed -> State.FAILED
            else -> State.PASSED
        }
        return Row("run ${run.id}", state, title(run), details.joinToString(SEPARATOR), links)
    }

    private fun attention(warned: ByManageWarned): Row {
        val title = when (warned.warning) {
            ByManageWarning.UNAPPLIED -> BasedPythonBundle.message("manage.now.unapplied")
            ByManageWarning.UNMIGRATED -> BasedPythonBundle.message("manage.now.unmigrated")
            ByManageWarning.CONFLICT -> BasedPythonBundle.message("manage.now.conflict")
        }
        val details = listOf(StringUtil.shortenTextWithEllipsis(warned.text, 120, 0)) + warned.place.settings
        return Row("warning ${warned.key}", State.WARNING, title, details.joinToString(SEPARATOR), listOf(Link.Fix(warned)))
    }

    /** What does as [warned] asks, against the same settings and, where the command takes one, database. */
    fun fix(warned: ByManageWarned): List<String> = warned.warning.fix + warned.place.settings +
        listOfNotNull(warned.place.database?.takeIf { warned.warning == ByManageWarning.UNAPPLIED }?.let { "--database=$it" })

    private fun title(run: ByManageRun): String = run.arguments.ifBlank { "help" }

    fun label(link: Link): @Nls String = when (link) {
        is Link.Browse -> BasedPythonBundle.message("manage.link.browse")
        is Link.Output -> BasedPythonBundle.message("manage.link.output")
        is Link.Answer -> BasedPythonBundle.message("manage.link.answer")
        is Link.Stop -> BasedPythonBundle.message(if (link.kill) "manage.link.kill" else "manage.link.stop")
        is Link.Again -> BasedPythonBundle.message("manage.link.again")
        is Link.Debug -> BasedPythonBundle.message("manage.link.debug")
        is Link.Edit -> BasedPythonBundle.message("manage.link.edit")
        is Link.Open -> BasedPythonBundle.message("manage.link.open")
        is Link.Fix -> ParametersListUtil.join(link.warned.warning.fix)
    }

    fun description(link: Link): @Nls String? = when (link) {
        is Link.Browse -> link.url
        is Link.Debug -> BasedPythonBundle.message("manage.link.debug.description")
        is Link.Open -> link.path
        is Link.Fix -> BasedPythonBundle.message("manage.link.fix.description", ParametersListUtil.join(fix(link.warned)))
        else -> null
    }

    /** `0.6 s`, `42 s`, `3 min`, `2 h 5 min`. */
    fun duration(ms: Long): @Nls String {
        val seconds = ms / 1000
        return when {
            seconds < 10 -> BasedPythonBundle.message("manage.time.tenths", "%d.%d".format(ms.coerceAtLeast(0) / 1000, ms.coerceAtLeast(0) % 1000 / 100))
            seconds < 60 -> BasedPythonBundle.message("manage.time.seconds", seconds)
            seconds < 3600 -> BasedPythonBundle.message("manage.time.minutes", seconds / 60)
            else -> BasedPythonBundle.message("manage.time.hours", seconds / 3600, seconds % 3600 / 60)
        }
    }

    private const val SEPARATOR = "  ·  "
}
