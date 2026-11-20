package dev.basedpython.pycharm.debug.bpd

import com.intellij.openapi.util.Disposer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Reading bpd's `startDebugging` against the parent's record, and the tabs a reloading server's
 * children take over. The whole exchange, with the real binaries, is
 * [ByBpdLiveSessionTest]'s `a child the program starts is taken up on a second connection`.
 */
class ByChildSessionTest {

    private val parent = ByBpdRecord.Ready(
        python = "/project/.venv/bin/python3",
        cwd = "/tmp/by-build-1",
        argv = listOf("/tmp/by-build-1/_by_runner.py", "manage.py", "runserver"),
        host = "127.0.0.1",
        port = 51234,
        tokenHeader = "x-bpd-token",
        token = "abc",
    )

    /** What bpd 84bc9f7 sent for runserver's reloader child, cut to what is read. */
    private fun request(
        port: Int = 51234,
        token: String = "abc",
        session: String = "2",
        extra: String = "",
    ): JsonObject = Json.parseToJsonElement(
        """
        {"args":["manage.py","runserver","18734"],
         "bpdConnect":{"header":"x-bpd-token","host":"127.0.0.1","port":$port,"token":"$token"},
         "bpdSession":$session,"debugChildren":true,"justMyCode":true,"name":"bpd: forked child (session 2)",
         "request":"attach","stopOnEntry":false,"variables":{"repr":false,"attributes":true},
         "suspendPolicies":[{"path":"/project/app/views.py","line":5,"suspend":"all"}]$extra}
        """.trimIndent(),
    ) as JsonObject

    @Test
    fun `bpd's request for a child is taken up at the parent's listener with bpd's own arguments`() {
        val parsed = ByChildSession.parse(request(), parent, run = ByBpdRun(Path.of("/tmp/record"), inPlace = true))
        val child = assertInstanceOf(ByChildSession.Parsed.Child::class.java, parsed).session

        assertSame(parent, child.parent, "the connection is made with what the IDE already trusted")
        assertEquals(ByBpdRun(Path.of("/tmp/record"), inPlace = true), child.run, "a child runs out of its parent's directory, in place as its parent does")
        assertFalse(child.stopsOnEntry)
        // the arguments go back to bpd on the `attach` as it wrote them, the session an integer
        assertEquals(2L, child.configuration["bpdSession"])
        assertEquals(listOf("manage.py", "runserver", "18734"), child.configuration["args"])
        assertEquals(mapOf("repr" to false, "attributes" to true), child.configuration["variables"])
        assertEquals(
            listOf(mapOf("path" to "/project/app/views.py", "line" to 5L, "suspend" to "all")),
            child.configuration["suspendPolicies"],
        )
    }

    @Test
    fun `a child asked to stop on entry is left where bpd holds it`() {
        val parsed = ByChildSession.parse(request(extra = ""","stopOnEntry":true"""), parent, run = null)
        assertTrue(assertInstanceOf(ByChildSession.Parsed.Child::class.java, parsed).session.stopsOnEntry)
    }

    @Test
    fun `an address other than the parent's listener is refused rather than dialled`() {
        val parsed = ByChildSession.parse(request(port = 4711), parent, run = null)
        val why = assertInstanceOf(ByChildSession.Parsed.Refused::class.java, parsed).why
        assertTrue("127.0.0.1:4711" in why && "127.0.0.1:51234" in why, why)
    }

    @Test
    fun `a token other than the parent's is refused`() {
        val parsed = ByChildSession.parse(request(token = "other"), parent, run = null)
        assertInstanceOf(ByChildSession.Parsed.Refused::class.java, parsed)
    }

    @Test
    fun `a request naming no session is refused`() {
        val parsed = ByChildSession.parse(request(session = "null"), parent, run = null)
        assertInstanceOf(ByChildSession.Parsed.Refused::class.java, parsed)
    }

    @Test
    fun `a session bpd dialled cannot take a child up`() {
        val dialled = ByBpdRecord.Chosen(python = parent.python, cwd = parent.cwd, argv = parent.argv)
        val parsed = ByChildSession.parse(request(), dialled, run = null)
        assertInstanceOf(ByChildSession.Parsed.Refused::class.java, parsed)
        assertInstanceOf(ByChildSession.Parsed.Refused::class.java, ByChildSession.parse(request(), null, run = null))
    }

    @Test
    fun `a reloaded child takes over the newest ended child's tab, and a closed tab is not offered`() {
        val tabs = ByChildTabs<com.intellij.openapi.Disposable>()
        assertNull(tabs.reusable(), "no child has ended")

        val first = Disposer.newDisposable("first")
        val second = Disposer.newDisposable("second")
        tabs.ended(first)
        tabs.ended(second)
        Disposer.dispose(second)
        assertSame(first, tabs.reusable(), "the user closed the newest; the one still open is offered")
        assertNull(tabs.reusable(), "a tab is offered once")

        Disposer.dispose(first)
        val closed = Disposer.newDisposable("closed")
        Disposer.dispose(closed)
        tabs.ended(closed)
        assertNull(tabs.reusable(), "a tab already closed when its child ended is never offered")
    }
}
