package dev.basedpython.pycharm.lsp

import com.google.gson.Gson
import org.eclipse.lsp4j.InitializeResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What `by`'s `initialize` answer says its commands take, read as the LSP client hands it over. */
class ByCommandFlagsTest {

    private fun answer(json: String): InitializeResult = Gson().fromJson(json, InitializeResult::class.java)

    @Test
    fun `a flag by names under experimental is one it takes`() {
        val flags = ByCommandFlags.of(
            answer(
                """{"capabilities": {"experimental": {
                    "repl": {"events": true, "module": true, "sessionFile": true},
                    "run": {"coverage": true, "inBuild": true, "launcher": true, "profile": true}
                }}}""",
            ),
        )
        assertTrue(flags.takes(ByCommandFlags.REPL, ByCommandFlags.REPL_EVENTS))
        assertTrue(flags.takes("run", "inBuild"))
        assertFalse(flags.takes(ByCommandFlags.REPL, "coverage"), "a flag of another command")
        assertFalse(flags.takes("check", "events"), "a command not named")
    }

    @Test
    fun `a by that names nothing takes nothing`() {
        assertFalse(ByCommandFlags.of(answer("""{"capabilities": {}}""")).takes(ByCommandFlags.REPL, ByCommandFlags.REPL_EVENTS))
        assertFalse(ByCommandFlags.of(answer("""{"capabilities": {"experimental": {"repl": {"events": false}}}}""")).takes(ByCommandFlags.REPL, ByCommandFlags.REPL_EVENTS))
        assertFalse(ByCommandFlags.of(answer("""{"capabilities": {"experimental": {"repl": true}}}""")).takes(ByCommandFlags.REPL, ByCommandFlags.REPL_EVENTS))
        assertFalse(ByCommandFlags.of(null).takes(ByCommandFlags.REPL, ByCommandFlags.REPL_EVENTS))
        assertFalse(ByCommandFlags.NONE.takes(ByCommandFlags.REPL, ByCommandFlags.REPL_EVENTS))
    }
}
