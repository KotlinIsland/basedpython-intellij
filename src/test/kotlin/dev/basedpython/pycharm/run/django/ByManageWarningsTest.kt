package dev.basedpython.pycharm.run.django

import dev.basedpython.pycharm.run.ByRunOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What is to be done, until something has done it. */
class ByManageWarningsTest {

    private val here = ByManagePlace("/p", "manage.py")

    private fun run(arguments: String, place: ByManagePlace = here, facts: ByManageFacts = ByManageFacts()) =
        ByManageRun(1, arguments, "Run", place, 0, facts = facts)

    private fun warned(warning: ByManageWarning, place: ByManagePlace = here): List<ByManageWarned> {
        val before = run("runserver", place)
        return ByManageWarnings.after(emptyList(), before, before.copy(facts = ByManageFacts(warnings = mapOf(warning to ByManageFacts.Detail("polls", 1)))))
    }

    /** Whether [warning], said [there], is still to be done once [arguments] has ended [here]. */
    private fun kept(
        warning: ByManageWarning,
        arguments: String,
        exit: Int = 0,
        place: ByManagePlace = here,
        there: ByManagePlace = here,
        facts: ByManageFacts = ByManageFacts(),
    ): Boolean {
        val running = run(arguments, place, facts)
        return ByManageWarnings.after(warned(warning, there), running, running.copy(endedAt = 2, exitCode = exit)).isNotEmpty()
    }

    @Test
    fun `migrating everything does it and less does not`() {
        val unapplied = ByManageWarning.UNAPPLIED
        assertEquals(1, warned(unapplied).size)
        assertFalse(kept(unapplied, "migrate"))
        assertFalse(kept(unapplied, "migrate --noinput"))
        assertFalse(kept(unapplied, "migrate -v 2"), "an option's value is no app")
        assertTrue(kept(unapplied, "migrate", exit = 1))
        assertTrue(kept(unapplied, "migrate polls"))
        assertTrue(kept(unapplied, "migrate --plan"))
        assertTrue(kept(unapplied, "check"))
    }

    /** A configuration can say either, and below the root `manage` is not the `manage.py` there. */
    @Test
    fun `the project's manage is one place by its module and by its file`() {
        fun at(module: String, directory: String) =
            ByManagePlace.of(ByRunOptions().also { it.module = module; it.workingDir = directory }, "/p", emptyList(), false)
        assertEquals(at("manage.py", "/p"), at("manage", "/p"))
        assertEquals(at("manage.py", "/p"), at(" manage ", ""))
        assertNotEquals(at("manage.py", "/p/web"), at("manage", "/p/web"))
        assertFalse(kept(ByManageWarning.UNAPPLIED, "migrate", place = at("manage.py", "/p"), there = at("manage", "/p")))
    }

    @Test
    fun `it has to be the same project and database`() {
        val unapplied = ByManageWarning.UNAPPLIED
        fun of(arguments: String) = ByManagePlace.of(ByRunOptions().also { it.module = "manage" }, "/p", ByManageWords.parse(arguments), false)
        for (other in listOf("migrate --database=other", "migrate --database other", "migrate --settings=other", "migrate --settings other")) {
            assertTrue(kept(unapplied, other, place = of(other)), other)
        }
        assertFalse(kept(unapplied, "migrate --settings=a", place = of("migrate --settings=a"), there = of("runserver --settings a")))
        assertTrue(kept(unapplied, "migrate", place = here.copy(environment = mapOf("DATABASE_URL" to "x"))))
        assertTrue(kept(unapplied, "migrate", place = here.copy(directory = "/q")))
        assertTrue(kept(unapplied, "migrate", place = here.copy(staged = true), there = here.copy(staged = true)), "in a copy it is undone with the copy")
    }

    @Test
    fun `a conflict is done by a merge that made a migration`() {
        val merged = ByManageFacts(written = listOf("polls/migrations/0003_merge.py"))
        assertFalse(kept(ByManageWarning.CONFLICT, "makemigrations --merge", facts = merged))
        assertTrue(kept(ByManageWarning.CONFLICT, "makemigrations --merge"), "answered no, it ends as well")
        assertTrue(kept(ByManageWarning.CONFLICT, "makemigrations", facts = merged))
        assertFalse(kept(ByManageWarning.CONFLICT, "migrate"))
        assertFalse(kept(ByManageWarning.CONFLICT, "makemigrations --merge", there = here.copy(database = "other"), facts = merged))
    }

    @Test
    fun `unmigrated models are done by a migration`() {
        assertFalse(kept(ByManageWarning.UNMIGRATED, "makemigrations"))
        assertTrue(kept(ByManageWarning.UNMIGRATED, "makemigrations --check"))
        assertTrue(kept(ByManageWarning.UNMIGRATED, "makemigrations --dry-run"))
        assertTrue(kept(ByManageWarning.UNMIGRATED, "makemigrations --merge"))
    }

    @Test
    fun `a server that starts without it takes it back and one that says it again keeps one`() {
        val all = warned(ByManageWarning.UNAPPLIED)
        val before = run("runserver", facts = ByManageFacts(starts = 1))
        assertTrue(ByManageWarnings.after(all, before, before.copy(facts = ByManageFacts(starts = 2))).isEmpty())
        val again = before.copy(facts = ByManageFacts(starts = 2, warnings = mapOf(ByManageWarning.UNAPPLIED to ByManageFacts.Detail("library", 9))))
        assertEquals(listOf("library"), ByManageWarnings.after(all, before, again).map { it.text })
    }
}
