package dev.basedpython.pycharm.run.django

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What a command's output says of it, on what django 6.1.3 wrote to a terminal and to a pipe. */
class ByManageOutputTest {

    private val output = ByManageOutput { 7 }

    private fun facts(text: String): ByManageFacts {
        output.text(text)
        return output.facts
    }

    private val started = "Watching for file changes with StatReloader\r\nPerforming system checks...\r\n\r\n" +
        "System check identified no issues (0 silenced).\r\n" +
        "\u001b[31m\r\nYou have 21 unapplied migration(s). Your project may not work properly until you apply the migrations for app(s): admin, auth, polls.\u001b[0m\r\n" +
        "\u001b[31mRun 'python manage.py migrate' to apply them.\u001b[0m\r\nNovember 21, 2026 - 22:47:02\r\n" +
        "Django version 6.1.3, using settings 'proj.settings'\r\nStarting WSGI development server at http://127.0.0.1:8933/\r\nQuit the server with CONTROL-C.\r\n"

    @Test
    fun `runserver says where it serves and what is unapplied`() {
        val facts = facts(started)
        assertEquals("http://127.0.0.1:8933/", facts.server)
        assertEquals(0, facts.reloads)
        assertEquals(mapOf(ByManageWarning.UNAPPLIED to ByManageFacts.Detail("admin, auth, polls", 7)), facts.warnings)
        assertNull(facts.error)
    }

    @Test
    fun `it reads the same however it is cut up`() {
        started.forEach { output.text(it.toString()) }
        assertEquals("http://127.0.0.1:8933/", output.facts.server)
        assertEquals("admin, auth, polls", output.facts.warnings.getValue(ByManageWarning.UNAPPLIED).text)
    }

    @Test
    fun `the server line before django 6 and daphne's`() {
        assertEquals("http://127.0.0.1:8000/", facts("Starting development server at http://127.0.0.1:8000/\n").server)
        assertEquals("http://127.0.0.1:8001/", facts("Starting ASGI/Daphne version 4.0.0 development server at http://127.0.0.1:8001/\n").server)
    }

    @Test
    fun `requests are counted, the failed ones apart, and one's traceback is not the server's failure`() {
        facts(started)
        val facts = facts(
            "[21/Nov/2026 22:44:28] \"GET /slow/ HTTP/1.1\" 200 1\nNot Found: /nope/\n" +
                "\u001b[33m[21/Nov/2026 22:44:28] \"GET /nope/ HTTP/1.1\" 404 2240\u001b[0m\n" +
                "Internal Server Error: /x/\nTraceback (most recent call last):\n  File \"a.py\", line 1, in f\n    g()\nValueError: no\n" +
                "[21/Nov/2026 22:44:29] \"GET /x/ HTTP/1.1\" 500 59\nHTTP GET /y/ 200 [0.01, 127.0.0.1:1]\n",
        )
        assertEquals(4, facts.requests)
        assertEquals(1, facts.failedRequests)
        assertNull(facts.error)
    }

    @Test
    fun `a reload that fails says why until one succeeds`() {
        facts(started)
        assertTrue(facts("/p/polls/views.py changed, reloading.\n").reloading)
        assertEquals("NameError: name 'x' is not defined", facts("Traceback (most recent call last):\n  File \"a.py\"\nNameError: name 'x' is not defined\n").error)
        val again = facts("Starting WSGI development server at http://127.0.0.1:8933/\n")
        assertNull(again.error)
        assertFalse(again.reloading)
        assertEquals(1, again.reloads)
    }

    @Test
    fun `migrate says which migration it is at and how many it ran`() {
        output.text("Running migrations:\n  Applying contenttypes.0001_initial... OK\n  Applying auth.0001_initial...")
        assertEquals("auth.0001_initial", output.facts.migrating)
        assertEquals(1, output.facts.migrated)
        output.text(" OK\n  Applying a.0002_x... FAKED\n")
        assertEquals(3, output.facts.migrated)
        assertNull(output.facts.migrating)
        output.text("  Unapplying polls.0002_a...\u001b[32;1m OK\u001b[0m\n")
        assertEquals(4, output.facts.migrated)
    }

    @Test
    fun `makemigrations names what it wrote`() {
        val facts = facts("\u001b[36;1mMigrations for 'polls':\u001b[0m\r\n  \u001b[1mpolls/migrations/0003_drift.py\u001b[0m\r\n    + Create model Drift\r\n")
        assertEquals(listOf("polls/migrations/0003_drift.py"), facts.written)
        assertEquals("No changes detected", facts("No changes detected\n").summary)
    }

    @Test
    fun `an open line is a question once it has been still, and stays one while it is answered`() {
        output.text("Username (leave blank to use 'morgan'): ")
        assertNull(output.facts.prompt)
        assertTrue(output.mayBePrompt())
        assertTrue(output.still())
        assertEquals("Username (leave blank to use 'morgan'):", output.facts.prompt)
        output.text("mor")
        assertEquals("Username (leave blank to use 'morgan'):", output.facts.prompt)
        output.text("gan\r\n")
        assertNull(output.facts.prompt)
        output.text("    Type 'yes' to continue, or 'no' to cancel: ")
        assertTrue(output.still())
        output.finished()
        assertNull(output.facts.prompt)
    }

    @Test
    fun `progress is not a question`() {
        output.text("  Applying polls.0001_initial...")
        assertFalse(output.mayBePrompt())
        output.text(" OK\n")
        assertFalse(output.still())
    }

    @Test
    fun `why a command failed`() {
        fun error(text: String) = ByManageOutput().also {
            it.text(text)
            it.finished()
        }.facts.error
        assertEquals("No fixture named 'zzz' found.", error("\u001b[31;1mCommandError: No fixture named 'zzz' found.\u001b[0m\n"))
        assertEquals("Unknown command: 'nope'", error("Unknown command: 'nope'\nType 'manage.py help' for usage.\n"))
        assertEquals("manage.py migrate: error: unrecognized arguments: --x", error("usage: manage.py migrate [-h]\nmanage.py migrate: error: unrecognized arguments: --x"))
        assertEquals("ModuleNotFoundError: No module named 'x'", error("Traceback (most recent call last):\n  File \"m.py\", line 2\n    import x\nModuleNotFoundError: No module named 'x'"))
    }

    @Test
    fun `a conflict and unmigrated models are to be done`() {
        val facts = facts(
            "CommandError: Conflicting migrations detected; multiple leaf nodes in the migration graph: (0002_b, 0003_drift in polls).\n" +
                "  Your models in app(s): 'polls', 'library' have changes that are not yet reflected in a migration, and so won't be applied.\n",
        )
        assertEquals("(0002_b, 0003_drift in polls)", facts.warnings.getValue(ByManageWarning.CONFLICT).text)
        assertEquals("polls, library", facts.warnings.getValue(ByManageWarning.UNMIGRATED).text)
    }

    /** As `migrate` wrote it: the migration's line is still open where the traceback begins. */
    @Test
    fun `a migration that fails says why`() {
        val facts = facts("  Applying polls.0002_boom...Traceback (most recent call last):\n  File \"manage.py\", line 8, in <module>\n    main()\nValueError: boom\n")
        assertEquals("ValueError: boom", facts.error)
        assertNull(facts.migrating)
        assertEquals(0, facts.migrated)
    }

    @Test
    fun `a server that starts again without the warning takes it back`() {
        facts(started)
        assertTrue(ByManageWarning.UNAPPLIED in output.facts.warnings)
        facts("Starting WSGI development server at http://127.0.0.1:8933/\n")
        assertFalse(ByManageWarning.UNAPPLIED in output.facts.warnings)
        facts(started)
        assertTrue(ByManageWarning.UNAPPLIED in output.facts.warnings)
    }

    @Test
    fun `an error a shell goes on after is not why it failed`() {
        facts(">>> foo\nTraceback (most recent call last):\n  File \"<console>\"\nNameError: name 'foo' is not defined\n>>> ")
        assertTrue(output.still())
        assertNull(output.facts.error)
    }

    @Test
    fun `without an echo what follows a question is the command's`() {
        val piped = ByManageOutput(echoes = false)
        piped.text("Type 'yes' to continue: ")
        assertTrue(piped.still())
        piped.text("Copying 'a.css'\n")
        assertNull(piped.facts.prompt)
        piped.text("Email address: ")
        assertTrue(piped.still())
    }

    @Test
    fun `a progress line is written over`() {
        repeat(5_000) { output.text("\r$it%") }
        output.text("\r")
        output.text("\nUsername: ")
        assertTrue(output.still())
        output.text("\r\nCommandError: x\r\n")
        assertEquals("x", output.facts.error)
    }

    @Test
    fun `a character set escape is not text`() {
        output.text("\u001b(B  Applying a.0001_x...\u001b(")
        output.text("B OK\n")
        assertEquals(1, output.facts.migrated)
    }

    @Test
    fun `a merge names the migration it makes`() {
        assertEquals(listOf("/p/polls/migrations/0003_merge.py"), facts("Created new merge migration /p/polls/migrations/0003_merge.py\n").written)
    }
}
