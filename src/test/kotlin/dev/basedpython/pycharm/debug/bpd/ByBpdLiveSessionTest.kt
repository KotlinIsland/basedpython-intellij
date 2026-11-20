package dev.basedpython.pycharm.debug.bpd

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The whole chain, with both real binaries: `by run` → the wrapper → `bpd dap` → this plugin.
 *
 * Every other test here stands one process in for another. `ByBpdWrapperExecutionTest` gives the
 * wrapper a stand-in `bpd`; `ByBpdConnectionTest` gives the connection a stand-in listener;
 * `ByRunDrivesTheWrapperTest` uses a real `by` and a stand-in everything else. Each of those
 * proves one join. None of them proves the joins agree.
 *
 * This one starts a real `by run`, which starts the real wrapper, which starts a real
 * `bpd dap --listen`, and then connects to it with [ByBpdConnection] — the plugin's own code — and
 * speaks DAP to it the way the platform does. If any link in that chain is wrong, this is where it
 * shows.
 *
 * **Skipped unless both binaries are named.** `BASEDPYTHON_BY_UNDER_TEST` and
 * `BASEDPYTHON_BPD_UNDER_TEST`, deliberately not `PATH` — see [ByRunDrivesTheWrapperTest] for why
 * a `by` on `PATH` breaks eight unrelated tests in this suite.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the wrapper is a shell script; Windows is refused by name")
class ByBpdLiveSessionTest {

    private companion object {
        const val BY = "BASEDPYTHON_BY_UNDER_TEST"
        const val BPD = "BASEDPYTHON_BPD_UNDER_TEST"

        /** The interpreter `bpd` debugs. Its own minimum is 3.13. */
        const val PYTHON = "BASEDPYTHON_PYTHON_UNDER_TEST"

        /** What the child imports and runs: a module, as a view is to `runserver`'s child. */
        val WORK = """
            def run():
                value = 41
                value += 1
                print("child", value)
        """.trimIndent() + "\n"

        /** What `by` writes into a run directory while a program runs out of it; see [writtenByByWhileItRuns]. */
        const val BY_RETRANSPILED = "retranspiled"
        const val BY_CHECK = "_by_check.json"

        /** A program that calls an imported module's `value` twice: a reload between the two shows in the second. */
        val RELOADS = """
            from calc import value

            print("first", value())
            print("second", value())
        """.trimIndent() + "\n"

        /** A program that starts a Python child and waits on it, as Django's reloader does. */
        val STARTS_A_CHILD = """
            import subprocess
            import sys


            def main():
                subprocess.run([sys.executable, "child.py"], check=True)
                print("parent done")
        """.trimIndent() + "\n"
    }

    private fun binary(variable: String): Path? = System.getenv(variable)
        ?.let { Path.of(it) }
        ?.takeIf { Files.isExecutable(it) }

    @Test
    fun `a real by run starts a real bpd that this plugin can speak DAP to`(@TempDir dir: Path) {
        Files.writeString(
            dir.resolve("demo.by"),
            """
            def main():
                limit = 5
                if limit > 100:
                    print("over")
                print(limit)
            """.trimIndent() + "\n",
        )

        session(dir, module = "demo") { connection, python, dap ->
            // a real DAP `initialize`, framed the way a client frames one. bpd checks the token
            // before it acts on this, so a reply at all means the handshake was accepted
            val reply = dap.request("initialize", mapOf("adapterID" to "bpd", "clientID" to "basedpython-intellij"))
            assertTrue(
                reply.get("success").asBoolean,
                "bpd refused the plugin's initialize, so the token or the framing is wrong: $reply",
            )

            // and the program, launched with what `by run` recorded rather than what the IDE could
            // have guessed: the interpreter `by run` chose, the runner by the path it named, and its
            // arguments
            assertEquals(python, connection.record.python, "the record names the interpreter `by run` chose")
            val arguments = connection.record.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = false)
            val launched = dap.request("launch", arguments)
            assertTrue(launched.get("success").asBoolean, "bpd would not launch what the record names ($arguments): $launched")
            dap.request("configurationDone", emptyMap())
            dap.until("terminated")
            assertTrue(
                dap.seen.any { it.event == "output" && it.body?.get("output")?.asString?.contains("5") == true },
                "the program ran without printing its `limit`: ${dap.seen}",
            )
        }
    }

    /**
     * What `.by` debugging stands on now that nothing is sent ahead of the platform: every `.by`
     * breakpoint is set **before** the launch — before bpd has read any map, which it does at
     * launch — in the order the platform sends them, and nothing else is sent: no source map, no
     * `bpd/understands`. Each breakpoint is answered `pending`, binds once the map is read and its
     * module imported, and stops the program on the `.by` line, in each of two files.
     */
    @Test
    fun `a by breakpoint set before any map binds and stops, in every file`(@TempDir tempDir: Path) {
        // canonical, because the platform names a file by the path it has on disk and `by run`
        // records the `.by` by the one it was handed
        val dir = tempDir.toRealPath()
        val helper = dir.resolve("helper.by")
        Files.writeString(
            helper,
            """
            def double(value: int) -> int:
                twice = value * 2
                return twice
            """.trimIndent() + "\n",
        )
        val demo = dir.resolve("demo.by")
        Files.writeString(
            demo,
            """
            from helper import double


            def main():
                limit = double(5)
                print(limit)


            main()
            """.trimIndent() + "\n",
        )

        session(dir, module = "demo") { connection, _, dap ->
            dap.request("initialize", mapOf("adapterID" to "basedpython", "linesStartAt1" to true, "pathFormat" to "path"))
            dap.until("initialized")

            // the platform's order: every file's breakpoints, then `configurationDone`, then the
            // launch — nothing of this plugin's in between
            for ((file, line) in listOf(helper to 3, demo to 6)) {
                val set = dap.request(
                    "setBreakpoints",
                    mapOf("source" to mapOf("path" to file.toString()), "breakpoints" to listOf(mapOf("line" to line))),
                )
                assertTrue(set.get("success").asBoolean, "the breakpoint in ${file.fileName} was refused: $set")
                val answer = set.getAsJsonObject("body").getAsJsonArray("breakpoints")[0].asJsonObject
                assertEquals(false, answer.get("verified").asBoolean, "nothing is watching a line before the launch: $answer")
                assertEquals("pending", answer.get("reason")?.asString, "a map not read yet is not a map that will never arrive: $answer")
            }
            dap.request("configurationDone", emptyMap())
            val launched = dap.request(
                "launch",
                connection.record.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = false),
            )
            assertTrue(launched.get("success").asBoolean, "bpd would not launch: $launched")

            for ((file, line) in listOf(helper to 3, demo to 6)) {
                val stopped = dap.until("stopped")
                assertEquals("breakpoint", stopped.body?.get("reason")?.asString, "$stopped")
                val thread = stopped.body!!.get("threadId").asInt
                val top = dap.request("stackTrace", mapOf("threadId" to thread))
                    .getAsJsonObject("body").getAsJsonArray("stackFrames")[0].asJsonObject
                assertEquals(file.toString(), top.getAsJsonObject("source").get("path").asString, "the stop is in the `.by`: $top")
                assertEquals(line, top.get("line").asInt, "and on the line the breakpoint was set on: $top")
                dap.request("continue", mapOf("threadId" to thread))
            }
            dap.until("terminated")

            val bound = dap.seen.filter { it.event == "breakpoint" }
                .mapNotNull { it.body?.getAsJsonObject("breakpoint") }
                .filter { it.get("verified")?.asBoolean == true }
                .map { it.getAsJsonObject("source").get("path").asString to it.get("line").asInt }
            assertEquals(
                setOf(helper.toString() to 3, demo.toString() to 6),
                bound.toSet(),
                "each held breakpoint is told bound, in `.by` terms",
            )
            assertTrue(
                dap.seen.any { it.event == "output" && it.body?.get("output")?.asString?.contains("10") == true },
                "the program ran on to the end after both stops: ${dap.seen}",
            )
        }
    }

    /**
     * A child the program starts is taken up the way the IDE takes it up: bpd asks with
     * `startDebugging`, the configuration it carries is read by [ByChildSession.parse] against the
     * parent's record, a second connection is made with [ByBpdConnection.child], the child arrives
     * held at its interpreter's startup — `entry`, with nothing of its own run — and once let go it
     * runs to its end, its output on the parent's session. What `runserver` does with its reloader
     * child, in a few lines.
     */
    @Test
    fun `a child the program starts is taken up on a second connection and runs once let go`(@TempDir tempDir: Path) {
        val dir = tempDir.toRealPath()
        Files.writeString(dir.resolve("work.py"), WORK)
        Files.writeString(dir.resolve("child.py"), "import work\n\nwork.run()\n")
        Files.writeString(dir.resolve("demo.by"), STARTS_A_CHILD)

        session(dir, module = "demo") { connection, _, dap ->
            takeUpTheChild(connection, dap) { _, childDap ->
                val arrival = childDap.until("stopped")
                assertEquals("entry", arrival.body?.get("reason")?.asString, "a taken-up child is announced where it is held: $arrival")
                childDap.request("continue", mapOf("threadId" to arrival.body!!.get("threadId").asInt))
                childDap.until("terminated")
            }
            dap.until("terminated")
            val printed = dap.seen.filter { it.event == "output" }.mapNotNull { it.body?.get("output")?.asString }
            assertTrue(printed.any { "child 42" in it }, "the child ran, and its output is the parent's session's: $printed")
            assertTrue(printed.any { "parent done" in it }, "the parent ran on once its child had finished: $printed")
        }
    }

    /**
     * A breakpoint in the project's own file stops the child that imports it. Not yet: `by run`
     * runs the program from a staged copy of the project, and bpd translates a session it launched
     * through `by run`'s source map but not one it took up, so the child loads the copy and a
     * breakpoint on the project's file never binds there — measured, with this test stopping at the
     * same line once the breakpoint is set on the copy instead. The fix is bpd's (a taken-up session
     * reads the map its parent read) or `by run`'s (staging what it does not transpile as links).
     */
    @Test
    @Disabled("bpd does not translate a taken-up session through by run's source map; see the doc")
    fun `a breakpoint in a project file stops the child that imports it`(@TempDir tempDir: Path) {
        val dir = tempDir.toRealPath()
        val work = dir.resolve("work.py")
        Files.writeString(work, WORK)
        Files.writeString(dir.resolve("child.py"), "import work\n\nwork.run()\n")
        Files.writeString(dir.resolve("demo.by"), STARTS_A_CHILD)

        session(dir, module = "demo") { connection, _, dap ->
            takeUpTheChild(connection, dap, breakpoint = work to 3) { _, childDap ->
                val arrival = childDap.until("stopped")
                childDap.request("continue", mapOf("threadId" to arrival.body!!.get("threadId").asInt))
                val hit = childDap.until("stopped")
                assertEquals("breakpoint", hit.body?.get("reason")?.asString, "$hit")
                val thread = hit.body!!.get("threadId").asInt
                val top = childDap.request("stackTrace", mapOf("threadId" to thread))
                    .getAsJsonObject("body").getAsJsonArray("stackFrames")[0].asJsonObject
                assertEquals(work.toString(), top.getAsJsonObject("source").get("path").asString, "$top")
                assertEquals(3, top.get("line").asInt, "$top")
                childDap.request("continue", mapOf("threadId" to thread))
                childDap.until("terminated")
            }
        }
    }

    /**
     * A program `by run --in-place` runs: a `.by` breakpoint and a `.py` one, both set before the
     * launch as the platform sets them, bind and stop at the project's own files — the `.py` is the
     * file the program imports, and the `.by` is reported through the run directory's map — and the
     * program runs in the directory `by run` was started in.
     */
    @Test
    fun `in place a by breakpoint and a py breakpoint stop at the project's files`(@TempDir tempDir: Path) {
        val dir = tempDir.toRealPath()
        val helper = dir.resolve("helper.py")
        Files.writeString(helper, "def double(value):\n    twice = value * 2\n    return twice\n")
        val demo = dir.resolve("demo.by")
        Files.writeString(demo, "import os\nfrom helper import double\n\nlimit = double(5)\nprint(limit)\nprint(\"cwd\", os.getcwd())\n")

        session(dir, module = "demo", inPlace = true) { connection, _, dap ->
            assertTrue(
                Files.exists(Path.of(connection.record.argv.first()).resolveSibling("_by_run.json")),
                "the runner is not in a run directory of `by`'s cache: ${connection.record.argv}",
            )
            assertEquals(dir, Path.of(connection.record.cwd).toRealPath(), "the program runs where `by run` was started")
            stopsAt(dap, connection, listOf(helper to 2, demo to 5))
            val printed = dap.seen.filter { it.event == "output" }.mapNotNull { it.body?.get("output")?.asString }.joinToString("")
            assertTrue("10" in printed, "the program ran on to the end after both stops: $printed")
            // where the user ran it, as with no debugger — not the run directory the runner is in
            assertTrue("cwd $dir\n" in printed, "the program ran somewhere other than where `by run` was started: $printed")
        }
    }

    /**
     * In place a child the program starts imports the project's own `work.py`, so a breakpoint set on
     * it in the child's session binds and stops there — which a staged run's child cannot do (see the
     * disabled staged test above).
     */
    @Test
    fun `in place a breakpoint in a project file stops the child that imports it`(@TempDir tempDir: Path) {
        val dir = tempDir.toRealPath()
        val work = dir.resolve("work.py")
        Files.writeString(work, WORK)
        Files.writeString(dir.resolve("child.py"), "import work\n\nwork.run()\n")
        Files.writeString(dir.resolve("demo.by"), STARTS_A_CHILD)

        session(dir, module = "demo", inPlace = true) { connection, _, dap ->
            takeUpTheChild(connection, dap, breakpoint = work to 3) { _, childDap ->
                val arrival = childDap.until("stopped")
                childDap.request("continue", mapOf("threadId" to arrival.body!!.get("threadId").asInt))
                val hit = childDap.until("stopped")
                assertEquals("breakpoint", hit.body?.get("reason")?.asString, "$hit")
                val thread = hit.body!!.get("threadId").asInt
                val top = childDap.request("stackTrace", mapOf("threadId" to thread))
                    .getAsJsonObject("body").getAsJsonArray("stackFrames")[0].asJsonObject
                assertEquals(work.toString(), top.getAsJsonObject("source").get("path").asString, "$top")
                assertEquals(3, top.get("line").asInt, "$top")
                childDap.request("continue", mapOf("threadId" to thread))
                childDap.until("terminated")
            }
        }
    }

    /**
     * Hot reload of a program run in place, as the plugin does it: stopped at a breakpoint, a `.by`
     * the program imported is edited and saved, `by` transpiles it again into the run directory —
     * here `by retranspile RUN_DIR FILE`, whose answer the language server's `by/retranspile` gives
     * as it prints it — [dev.basedpython.pycharm.debug.hotswap.ByReload.planInPlace] reads the answer, and one `bpd/replaceCode` built by
     * the plugin's own [dev.basedpython.pycharm.debug.hotswap.ByReplaceCodeArguments] applies it; the
     * next call runs the edit. Nothing is written into the run directory but by `by`.
     */
    @Test
    fun `in place an edited by is reloaded from by's answer, and the next call runs it`(@TempDir tempDir: Path) {
        val dir = tempDir.toRealPath()
        val calc = dir.resolve("calc.by")
        Files.writeString(calc, "def value() -> int:\n    return 1\n")
        val demo = dir.resolve("demo.by")
        Files.writeString(demo, RELOADS)

        session(dir, module = "demo", inPlace = true) { connection, _, dap ->
            val thread = stopAtSecondCall(dap, connection, demo)
            val runDirectory = checkNotNull(ByBpdRecord.runnerDirectoryIn("cwd ${connection.record.cwd}\narg ${connection.record.argv.first()}\n"))
            val before = snapshot(Path.of(runDirectory))
            Files.writeString(calc, "def value() -> int:\n    return 2\n")
            val answer = retranspile(dir, runDirectory, calc)

            val plan = dev.basedpython.pycharm.debug.hotswap.ByReload.planInPlace(listOf(calc.toString()), emptyList(), listOf(answer))
            val replace = assertInstanceOf(dev.basedpython.pycharm.debug.hotswap.ByReload.InPlace.Replace::class.java, plan) { "$plan" }
            val replaced = replaceCode(dap, replace.files, replace.retranspiled)
            assertTrue(replaced?.applied == true, "bpd did not apply the reload: $replaced")
            val after = snapshot(Path.of(runDirectory))
            assertEquals(
                before.filterKeys { !writtenByByWhileItRuns(it) },
                after.filterKeys { !writtenByByWhileItRuns(it) },
                "the run directory's own files changed, which nothing may write while a program runs out of it",
            )
            val key = Path.of(runDirectory).relativize(Path.of(answer.get("key").asString)).toString()
            assertTrue(key in after && writtenByByWhileItRuns(key), "by's transpile is not where its answer says: $key")

            dap.request("continue", mapOf("threadId" to thread))
            dap.until("terminated")
            val printed = dap.seen.filter { it.event == "output" }.mapNotNull { it.body?.get("output")?.asString }.joinToString("")
            assertTrue("first 1" in printed && "second 2" in printed, "the next call did not run the edit: $printed")
        }
    }

    /** In place a hand-written `.py` the program imports is the file on disk, and is handed to bpd as itself. */
    @Test
    fun `in place an edited py is reloaded as itself`(@TempDir tempDir: Path) {
        val dir = tempDir.toRealPath()
        val calc = dir.resolve("calc.py")
        Files.writeString(calc, "def value():\n    return 1\n")
        val demo = dir.resolve("demo.by")
        Files.writeString(demo, RELOADS)

        session(dir, module = "demo", inPlace = true) { connection, _, dap ->
            val thread = stopAtSecondCall(dap, connection, demo)
            Files.writeString(calc, "def value():\n    return 2\n")
            val plan = dev.basedpython.pycharm.debug.hotswap.ByReload.planInPlace(emptyList(), listOf(calc.toString()), emptyList())
            val replace = assertInstanceOf(dev.basedpython.pycharm.debug.hotswap.ByReload.InPlace.Replace::class.java, plan) { "$plan" }
            val replaced = replaceCode(dap, replace.files, replace.retranspiled)
            assertTrue(replaced?.applied == true, "bpd did not apply the reload: $replaced")
            dap.request("continue", mapOf("threadId" to thread))
            dap.until("terminated")
            val printed = dap.seen.filter { it.event == "output" }.mapNotNull { it.body?.get("output")?.asString }.joinToString("")
            assertTrue("first 1" in printed && "second 2" in printed, "the next call did not run the edit: $printed")
        }
    }

    /** A `.by` that no longer checks is refused by `by` with its diagnostics, and bpd is handed nothing. */
    @Test
    fun `in place an edit that does not check is refused with by's diagnostics`(@TempDir tempDir: Path) {
        val dir = tempDir.toRealPath()
        val calc = dir.resolve("calc.by")
        Files.writeString(calc, "def value() -> int:\n    return 1\n")
        val demo = dir.resolve("demo.by")
        Files.writeString(demo, RELOADS)

        session(dir, module = "demo", inPlace = true) { connection, _, dap ->
            val thread = stopAtSecondCall(dap, connection, demo)
            val runDirectory = Path.of(connection.record.argv.first()).parent.toString()
            Files.writeString(calc, "def value() -> int:\n    return \"two\"\n")
            val plan = dev.basedpython.pycharm.debug.hotswap.ByReload.planInPlace(
                listOf(calc.toString()), emptyList(), listOf(retranspile(dir, runDirectory, calc)),
            )
            val refused = assertInstanceOf(dev.basedpython.pycharm.debug.hotswap.ByReload.InPlace.Refused::class.java, plan) { "$plan" }
            assertTrue(refused.reasons.single().startsWith("calc.by: "), "$refused")
            assertTrue(refused.reasons.single().contains("invalid-return-type"), "the checker's sentence is under it: $refused")
            dap.request("continue", mapOf("threadId" to thread))
            dap.until("terminated")
        }
    }

    /** Launches [connection]'s program with a breakpoint on [demo]'s second `value()` call, and returns the stopped thread. */
    private fun stopAtSecondCall(dap: Dap, connection: ByBpdConnection, demo: Path): Int {
        dap.request("initialize", mapOf("adapterID" to "basedpython", "linesStartAt1" to true, "pathFormat" to "path"))
        dap.until("initialized")
        dap.request("setBreakpoints", mapOf("source" to mapOf("path" to demo.toString()), "breakpoints" to listOf(mapOf("line" to 4))))
        dap.request("configurationDone", emptyMap())
        val launched = dap.request("launch", connection.record.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = false))
        assertTrue(launched.get("success").asBoolean, "bpd would not launch: $launched")
        val stopped = dap.until("stopped")
        assertEquals("breakpoint", stopped.body?.get("reason")?.asString, "$stopped")
        return stopped.body!!.get("threadId").asInt
    }

    /** The breakpoints of [stops] set before the launch, then each stopped at in order, in the file and on the line set. */
    private fun stopsAt(dap: Dap, connection: ByBpdConnection, stops: List<Pair<Path, Int>>) {
        dap.request("initialize", mapOf("adapterID" to "basedpython", "linesStartAt1" to true, "pathFormat" to "path"))
        dap.until("initialized")
        for ((file, line) in stops) {
            val set = dap.request("setBreakpoints", mapOf("source" to mapOf("path" to file.toString()), "breakpoints" to listOf(mapOf("line" to line))))
            assertTrue(set.get("success").asBoolean, "the breakpoint in ${file.fileName} was refused: $set")
        }
        dap.request("configurationDone", emptyMap())
        val launched = dap.request("launch", connection.record.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = false))
        assertTrue(launched.get("success").asBoolean, "bpd would not launch: $launched")
        for ((file, line) in stops) {
            val stopped = dap.until("stopped")
            assertEquals("breakpoint", stopped.body?.get("reason")?.asString, "$stopped")
            val thread = stopped.body!!.get("threadId").asInt
            val top = dap.request("stackTrace", mapOf("threadId" to thread))
                .getAsJsonObject("body").getAsJsonArray("stackFrames")[0].asJsonObject
            assertEquals(file.toString(), top.getAsJsonObject("source").get("path").asString, "the stop is in ${file.fileName}: $top")
            assertEquals(line, top.get("line").asInt, "and on the line the breakpoint was set on: $top")
            dap.request("continue", mapOf("threadId" to thread))
        }
        dap.until("terminated")
    }

    /** What `by retranspile` prints for [file] of the run out of [runDirectory]: the line `by/retranspile` answers with. */
    private fun retranspile(dir: Path, runDirectory: String, file: Path): JsonObject {
        val process = ProcessBuilder(binary(BY).toString(), "retranspile", runDirectory, file.toString())
            .directory(dir.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "`by retranspile` did not finish")
        return JsonParser.parseString(out.lines().first { it.isNotBlank() }).asJsonObject
    }

    /** `bpd/replaceCode` of [files] with [retranspiled], as the plugin builds it, and bpd's account of it. */
    private fun replaceCode(
        dap: Dap,
        files: List<String>,
        retranspiled: List<kotlinx.serialization.json.JsonObject>,
    ): dev.basedpython.pycharm.debug.hotswap.ByReplaced? {
        val arguments = dev.basedpython.pycharm.debug.hotswap.ByReplaceCodeArguments(
            files = files,
            remap = false,
            evenUnderALiveFrame = true,
            retranspiled = retranspiled,
        ).toJson()
        @Suppress("UNCHECKED_CAST")
        val asMap = Gson().fromJson(arguments.toString(), Map::class.java) as Map<String, Any?>
        val answer = dap.request("bpd/replaceCode", asMap)
        assertTrue(answer.get("success").asBoolean, "bpd failed the request: $answer")
        val body = kotlinx.serialization.json.Json.parseToJsonElement(answer.getAsJsonObject("body").toString())
            as kotlinx.serialization.json.JsonObject
        return dev.basedpython.pycharm.debug.hotswap.ByReplaced.parse(body)
    }

    /**
     * Whether [path], relative to a run directory, is one `by` writes there while a program runs out
     * of it. A run directory is named by the sha-256 of the files it was made with — the runner, the
     * map, `_by_project.py`, the runtime, the plugins, `boot/`, `_by_run.json`, the objects list — so
     * those are never written again under its name, by `by` or by anything else. What `by` writes
     * into it afterwards is what `by_stage::run_cache` and how-transpilation-works.md's "running a
     * program in place" document: `retranspiled/`, a transpile and its answer for each module made
     * again, and `_by_check.json`, what the last check `by retranspile` made found (basedpython
     * a7732e32). Everything else in the directory is the run's own, and a reload that changed it
     * changed a run the program is still running out of.
     */
    private fun writtenByByWhileItRuns(path: String): Boolean =
        path == BY_CHECK || path.startsWith("$BY_RETRANSPILED/")

    /** Every file under [directory], by its path from it, with its bytes' hash. */
    private fun snapshot(directory: Path): Map<String, Int> = Files.walk(directory).use { paths ->
        paths.filter { Files.isRegularFile(it) && "__pycache__" !in it.toString() }
            .toList()
            .associate { directory.relativize(it).toString() to Files.readAllBytes(it).contentHashCode() }
    }

    /**
     * The parent launched with `debugChildren`, bpd's `startDebugging` answered and read by
     * [ByChildSession.parse], and the child's session opened on a second connection in the
     * platform's order — [breakpoint], `configurationDone`, then the `attach` bpd's configuration
     * asks for — before [body] drives it.
     */
    private fun takeUpTheChild(
        connection: ByBpdConnection,
        dap: Dap,
        breakpoint: Pair<Path, Int>? = null,
        body: (ByChildSession, Dap) -> Unit,
    ) {
        dap.request(
            "initialize",
            mapOf("adapterID" to "basedpython", "linesStartAt1" to true, "pathFormat" to "path", "supportsStartDebuggingRequest" to true),
        )
        dap.until("initialized")
        dap.request("configurationDone", emptyMap())
        val launched = dap.request(
            "launch",
            connection.record.launchArguments(mapOf("stopOnEntry" to false), watchRecompositions = false) + ("debugChildren" to true),
        )
        assertTrue(launched.get("success").asBoolean, "bpd would not launch with debugChildren: $launched")

        val asked = dap.reverse("startDebugging")
        val arguments = asked.json.getAsJsonObject("arguments")
        assertEquals("attach", arguments.get("request").asString, "$asked")
        val configuration = kotlinx.serialization.json.Json.parseToJsonElement(arguments.getAsJsonObject("configuration").toString())
            as kotlinx.serialization.json.JsonObject
        val parsed = ByChildSession.parse(configuration, connection.record, run = null)
        assertTrue(parsed is ByChildSession.Parsed.Child, "the plugin refused bpd's own request: $parsed")
        val child = (parsed as ByChildSession.Parsed.Child).session
        dap.answer(asked)

        val second = runBlocking { ByBpdConnection.child(child.parent) }
        val reader = Executors.newSingleThreadExecutor()
        try {
            val childDap = Dap(second, reader)
            childDap.request("initialize", mapOf("adapterID" to "basedpython", "linesStartAt1" to true, "pathFormat" to "path"))
            childDap.until("initialized")
            breakpoint?.let { (file, line) ->
                val set = childDap.request(
                    "setBreakpoints",
                    mapOf("source" to mapOf("path" to file.toString()), "breakpoints" to listOf(mapOf("line" to line))),
                )
                assertTrue(set.get("success").asBoolean, "$set")
            }
            childDap.request("configurationDone", emptyMap())
            val attached = childDap.request("attach", child.configuration)
            assertTrue(attached.get("success").asBoolean, "bpd would not take the child up: $attached")
            body(child, childDap)
        } finally {
            reader.shutdownNow()
            runBlocking { second.disconnect() }
        }
    }

    /**
     * `by run` of [module] in [dir] through the real wrapper, connected with [ByBpdConnection], and
     * [body] run against it. Skips unless both binaries are named.
     */
    private fun session(
        dir: Path,
        module: String,
        inPlace: Boolean = false,
        body: (connection: ByBpdConnection, python: String, dap: Dap) -> Unit,
    ) {
        val by = binary(BY)
        val bpd = binary(BPD)
        assumeTrue(by != null && bpd != null, "set $BY and $BPD to run the whole chain")

        val wrapper = dir.resolve("bpd-python")
        Files.writeString(wrapper, ByBpdWrapper.script())
        wrapper.toFile().setExecutable(true)

        val python = System.getenv(PYTHON) ?: "python3"
        val record = dir.resolve("record")
        val port = java.net.ServerSocket(0).use { it.localPort }

        // the interpreter is `by run`'s to choose: a project with no environment of its own takes
        // `PYTHON`, and the wrapper is only ever handed what `by run` chose
        val mode = if (inPlace) listOf(dev.basedpython.pycharm.lsp.ByCommandFlags.IN_PLACE_FLAG) else emptyList()
        val byRun = ProcessBuilder(listOf(by.toString(), "run") + mode + listOf("--launcher", wrapper.toString(), module))
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .redirectOutput(dir.resolve("by-run.log").toFile())
            .apply {
                environment().remove("VIRTUAL_ENV")
                environment()["PYTHON"] = python
                environment()[ByBpdWrapper.ENV_BPD] = bpd.toString()
                environment()[ByBpdWrapper.ENV_BPD_FALLBACK] = ""
                environment()[ByBpdWrapper.ENV_PORT] = port.toString()
                environment()[ByBpdWrapper.ENV_RECORD] = record.toString()
            }
            .start()

        try {
            // the plugin's own connection: it waits for the record, reads where bpd bound, and
            // presents the token before anything the protocol writes
            val connection = runBlocking { ByBpdConnection.open(record, debuggee = null) }
            val reader = Executors.newSingleThreadExecutor()
            try {
                body(connection, python, Dap(connection, reader))
            } finally {
                reader.shutdownNow()
                runBlocking { connection.disconnect() }
            }
        } finally {
            byRun.destroy()
            byRun.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /** One message off the wire, read as the envelope DAP defines. */
    private data class Message(val json: JsonObject) {
        val type: String? get() = json.get("type")?.asString
        val event: String? get() = json.get("event")?.takeIf { type == "event" }?.asString
        val body: JsonObject? get() = json.get("body")?.takeIf { it.isJsonObject }?.asJsonObject
        override fun toString(): String = json.toString()
    }

    /** A client over [connection]: requests answered in order, events kept as they arrive. */
    private inner class Dap(
        private val connection: ByBpdConnection,
        private val reader: java.util.concurrent.ExecutorService,
    ) {
        private var seq = 1
        val seen = mutableListOf<Message>()
        private var taken = 0

        fun request(command: String, arguments: Map<String, Any?>): JsonObject {
            val mine = seq++
            send(connection.output, mine, command, arguments)
            return next { it.type == "response" && it.json.get("request_seq")?.asInt == mine }.json
        }

        /** The next event named [event] not already taken, waiting for it if it has not come. */
        fun until(event: String): Message = next { it.event == event }

        /** The next reverse request — the adapter asking the client — named [command]. */
        fun reverse(command: String): Message =
            next { it.type == "request" && it.json.get("command")?.asString == command }

        /** Answer a reverse request with success and no body, as the platform answers `startDebugging`. */
        fun answer(request: Message) {
            val body = Gson().toJson(
                mapOf(
                    "seq" to seq++,
                    "type" to "response",
                    "request_seq" to request.json.get("seq").asInt,
                    "success" to true,
                    "command" to request.json.get("command").asString,
                ),
            ).toByteArray(StandardCharsets.UTF_8)
            connection.output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            connection.output.write(body)
            connection.output.flush()
        }

        private fun next(wanted: (Message) -> Boolean): Message {
            while (true) {
                while (taken < seen.size) {
                    val message = seen[taken++]
                    if (wanted(message)) return message
                }
                val text = reader.submit<String> { readMessage(connection.input) }.get(60, TimeUnit.SECONDS)
                seen += Message(JsonParser.parseString(text).asJsonObject)
            }
        }
    }

    private fun send(output: java.io.OutputStream, seq: Int, command: String, arguments: Map<String, Any?>) {
        val body = Gson().toJson(mapOf("seq" to seq, "type" to "request", "command" to command, "arguments" to arguments))
            .toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    /** One DAP message: a header block, a blank line, then exactly `Content-Length` bytes. */
    private fun readMessage(input: java.io.InputStream): String {
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0) { "the adapter closed before it answered. it said: $header" }
            header.append(byte.toChar())
        }
        val length = Regex("""Content-Length:\s*(\d+)""")
            .find(header)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: error("no Content-Length in the adapter's reply: $header")

        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            check(n > 0) { "the adapter closed part way through a $length byte message" }
            read += n
        }
        return String(body, StandardCharsets.UTF_8)
    }
}
