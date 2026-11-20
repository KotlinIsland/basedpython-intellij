package dev.basedpython.pycharm.console

import dev.basedpython.pycharm.lsp.ByCommandFlags
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** What the basedpython Console starts `by repl` with, by what the project's `by` says it takes. */
class ByReplArgumentsTest {

    private fun saying(experimental: String): ByCommandFlags = ByCommandFlags.of(
        InitializeResult(ServerCapabilities().apply { this.experimental = com.google.gson.JsonParser.parseString(experimental) }),
    )

    private val session = Path.of("/tmp/session.by")

    /** `by` a32cdb0b6's answer: `repl` takes `--in-place`, so the session imports the project's modules where they are. */
    @Test
    fun `a by whose repl takes --in-place starts the session in place`() {
        assertEquals(
            listOf("repl", "--session-file", "/tmp/session.by", "--in-place", "--events", "4711", "--module", "app.main"),
            ByConsoleSession.replArguments(
                session,
                saying("""{"repl": {"events": true, "inPlace": true, "module": true, "sessionFile": true}}"""),
                eventsPort = 4711,
                module = "app.main",
            ),
        )
    }

    @Test
    fun `a by that does not say so stages the session, as before`() {
        assertEquals(
            listOf("repl", "--session-file", "/tmp/session.by"),
            ByConsoleSession.replArguments(session, saying("""{"run": {"inPlace": true}}"""), eventsPort = null, module = null),
        )
        assertEquals(
            listOf("repl", "--session-file", "/tmp/session.by"),
            ByConsoleSession.replArguments(session, ByCommandFlags.NONE, eventsPort = null, module = null),
        )
    }
}
