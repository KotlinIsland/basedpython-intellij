package dev.basedpython.pycharm.run.django

import dev.basedpython.pycharm.run.django.ByManageNow.Link
import dev.basedpython.pycharm.run.django.ByManageNow.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The Now view's rows. */
class ByManageNowTest {

    private val place = ByManagePlace("/p", "manage")

    private fun run(id: Long, arguments: String, end: Long? = null, exit: Int = 0, stopped: Boolean = false, facts: ByManageFacts = ByManageFacts()) =
        ByManageRun(id, arguments, "Run", place, 0, end, end?.let { exit }, stopped, facts)

    private fun sections(runs: List<ByManageRun>, now: Long = 0, warned: List<ByManageWarned> = emptyList(), outputs: Set<Long> = runs.map { it.id }.toSet()) =
        ByManageNow.sections(ByManageRuns.State(runs, warned), outputs, now)

    @Test
    fun `the server is first, with where it serves and what it can be told`() {
        val sections = sections(
            listOf(run(1, "migrate"), run(2, "runserver", facts = ByManageFacts(server = "http://127.0.0.1:8000/", starts = 3))),
            65_000,
        )
        val rows = sections.single().rows
        assertEquals(listOf("runserver", "migrate"), rows.map { it.title })
        assertEquals("127.0.0.1:8000  ·  1 min  ·  2 reloads", rows[0].detail)
        assertEquals(State.SERVING, rows[0].state)
        assertEquals(listOf(Link.Output(2), Link.Browse("http://127.0.0.1:8000/"), Link.Debug(2), Link.Stop(2, false)), rows[0].links)
    }

    @Test
    fun `a question is answered, and a run already stopping is killed`() {
        val rows = sections(listOf(run(1, "createsuperuser", facts = ByManageFacts(prompt = "Username:")), run(2, "shell", stopped = true))).single().rows
        assertEquals(State.WAITING, rows[0].state)
        assertEquals(Link.Answer(1), rows[0].links.first())
        assertEquals(Link.Stop(2, true), rows[1].links.last())
    }

    @Test
    fun `how the last ones ended, the latest first`() {
        val rows = sections(
            listOf(
                run(1, "check", end = 600, facts = ByManageFacts(summary = "System check identified no issues (0 silenced).")),
                run(2, "loaddata zzz", end = 900, exit = 1, facts = ByManageFacts(error = "No fixture named 'zzz' found.")),
                run(3, "runserver", end = 1_000, exit = 143, stopped = true, facts = ByManageFacts(error = "x")),
            ),
            0,
        ).single().rows
        assertEquals(listOf(State.STOPPED, State.FAILED, State.PASSED), rows.map { it.state })
        assertEquals("stopped  ·  1.0 s", rows[0].detail)
        assertEquals("exit code 1  ·  0.9 s  ·  No fixture named 'zzz' found.", rows[1].detail)
        assertEquals(listOf(Link.Output(2), Link.Edit("loaddata zzz"), Link.Again(2)), rows[1].links)
        assertEquals("0.6 s  ·  System check identified no issues (0 silenced).", rows[2].detail)
    }

    @Test
    fun `a run whose console has gone has no link to it`() {
        val rows = sections(listOf(run(1, "check", end = 1), run(2, "shell", facts = ByManageFacts(prompt = ">>>"))), outputs = emptySet()).flatMap { it.rows }
        assertTrue(rows.flatMap { it.links }.none { it is Link.Output || it is Link.Answer })
    }

    @Test
    fun `an error is a running command's only where it waits for a change`() {
        fun state(watching: Boolean) = sections(listOf(run(1, "x", facts = ByManageFacts(error = "e", watching = watching)))).single().rows.single().state
        assertEquals(State.FAILED, state(watching = true))
        assertEquals(State.RUNNING, state(watching = false))
    }

    @Test
    fun `a dry run wrote nothing to open`() {
        fun links(arguments: String) = sections(listOf(run(1, arguments, end = 1, facts = ByManageFacts(written = listOf("a/migrations/0001_x.py"))))).single().rows.single().links
        assertTrue(Link.Open(1, "a/migrations/0001_x.py") in links("makemigrations"))
        assertTrue(links("makemigrations --dry-run").none { it is Link.Open })
    }

    @Test
    fun `a fix is of the settings and the database that were warned of`() {
        val there = place.copy(settings = listOf("--settings=proj.staging"), database = "other")
        assertEquals(listOf("migrate", "--settings=proj.staging", "--database=other"), ByManageNow.fix(ByManageWarned(ByManageWarning.UNAPPLIED, there, "x")))
        assertEquals(listOf("makemigrations", "--settings=proj.staging"), ByManageNow.fix(ByManageWarned(ByManageWarning.UNMIGRATED, there, "x")))
        val row = sections(emptyList(), warned = listOf(ByManageWarned(ByManageWarning.UNAPPLIED, there, "polls"))).single().rows.single()
        assertEquals("polls  ·  --settings=proj.staging", row.detail)
        assertEquals("migrate", ByManageNow.label(row.links.single()))
    }

    @Test
    fun durations() {
        assertEquals(listOf("0.0 s", "9.9 s", "42 s", "3 min", "2 h 5 min"), listOf(0L, 9_950L, 42_000L, 200_000L, 7_500_000L).map(ByManageNow::duration))
    }
}
