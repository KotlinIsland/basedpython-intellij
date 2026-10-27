package dev.basedpython.pycharm.console

import dev.basedpython.pycharm.console.ByReplEvents.Event
import dev.basedpython.pycharm.console.ByReplEvents.Reading
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * What the console writes to `by repl`: an input framed, a line the program reads as it is — the
 * round-7 console framed both, so `input()` returned the paste markers with the line.
 */
class ByReplInputTest {

    private val written = mutableListOf<String>()
    private val numbered = mutableListOf<String>()

    private fun input(alwaysInput: Boolean = false) =
        ByReplInput(written::add, { numbered += it.text }, alwaysInput)

    private fun submit(reader: ByReplInput, text: String) = reader.submit(ByReplInput.Submission(text, null))

    @Test
    fun `an input is framed, and the line the program then reads is sent as it is`() {
        val reader = input()
        reader.reading(Reading.INPUT)
        submit(reader, "x = input('name? ')")
        reader.reading(Reading.PROGRAM)
        assertTrue(reader.readsLine)
        submit(reader, "hello world")

        assertEquals(listOf(ByConsoleText.framed("x = input('name? ')"), "hello world\n"), written)
        assertEquals(listOf("x = input('name? ')"), numbered)
    }

    @Test
    fun `a line typed while an input runs goes to whatever reads next`() {
        val reader = input()
        reader.reading(Reading.INPUT)
        submit(reader, "import time; time.sleep(1); y = input()")
        submit(reader, "typed ahead")
        submit(reader, "1 + 1")
        assertEquals(1, written.size, "nothing reads yet")

        reader.reading(Reading.PROGRAM)
        reader.reading(Reading.INPUT)

        assertEquals(listOf("typed ahead\n", ByConsoleText.framed("1 + 1")), written.drop(1))
        assertFalse(reader.readsLine)
    }

    @Test
    fun `a blank line is an empty answer to the program and no input to the session`() {
        val reader = input()
        reader.reading(Reading.PROGRAM)
        submit(reader, "")
        reader.reading(Reading.INPUT)
        submit(reader, "   ")
        submit(reader, "2")

        assertEquals(listOf("\n", ByConsoleText.framed("2")), written)
        assertEquals(listOf("2"), numbered)
    }

    @Test
    fun `a by that cannot say what it reads is sent every line as an input at once`() {
        val reader = input(alwaysInput = true)
        submit(reader, "x = input()")
        submit(reader, "hello")
        reader.reading(Reading.PROGRAM)

        assertEquals(listOf(ByConsoleText.framed("x = input()"), ByConsoleText.framed("hello")), written)
        assertFalse(reader.readsLine)
    }

    @Test
    fun `events are read as by repl writes them`() {
        assertEquals(Event.Waiting(Reading.INPUT), ByReplEvents.parse("""{"reading":"input"}"""))
        assertEquals(Event.Waiting(Reading.CONTINUATION), ByReplEvents.parse("""{"reading":"continuation"}"""))
        assertEquals(Event.Waiting(Reading.PROGRAM), ByReplEvents.parse("""{"reading":"program"}"""))
        assertEquals(Event.Finished(3, false), ByReplEvents.parse("""{"finished":3,"ran":false}"""))
        assertNull(ByReplEvents.parse("""{"reading":"something newer"}"""))
        assertNull(ByReplEvents.parse("""{"newer":1}"""))
        assertNull(ByReplEvents.parse("not json"))
        assertEquals("T", ByReplEvents.tokenOf("""{"token": "T"}"""))
        assertNull(ByReplEvents.tokenOf("""{"token": null}"""))
    }

    @Test
    fun `only the connection that presents the session's token is listened to`() {
        ByReplEvents.open().use { events ->
            val heard = LinkedBlockingQueue<Event>()
            events.listen(heard::add)
            connect(events.port) { it.println("""{"token":"not it"}"""); it.println("""{"reading":"program"}""") }
            connect(events.port) { it.println("""{"token":"${events.token}"}"""); it.println("""{"reading":"input"}"""); it.println("""{"finished":1,"ran":true}""") }

            assertEquals(Event.Waiting(Reading.INPUT), heard.poll(10, TimeUnit.SECONDS))
            assertEquals(Event.Finished(1, true), heard.poll(10, TimeUnit.SECONDS))
            assertNull(heard.poll(200, TimeUnit.MILLISECONDS))
        }
    }

    private fun connect(port: Int, write: (PrintWriter) -> Unit) {
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            PrintWriter(socket.getOutputStream().bufferedWriter(), true).use(write)
        }
    }
}
