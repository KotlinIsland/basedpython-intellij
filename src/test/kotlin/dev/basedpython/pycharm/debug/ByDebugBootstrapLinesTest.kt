package dev.basedpython.pycharm.debug

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The bootstrap's half of debugpy's `.by` mapping, run under a real interpreter: inverting
 * `_by_sourcemap.py`, and handing the result to pydevd in-process.
 *
 * In the bootstrap rather than here because that is the only place it can happen before anything
 * connects: the maps are registered inside the debuggee before the report the IDE waits for is
 * written, so no request of the plugin's has to overtake the platform's `setBreakpoints` and
 * `configurationDone`. Tested by running the real file, because a Kotlin copy of the algorithm would
 * be a second implementation agreeing with itself.
 *
 * The forward table is dense over *generated* lines and points back at `.by` lines; what pydevd
 * wants is the other direction, expressed as runs. Everything that makes that non-trivial is a real
 * property of the transpiler: a prelude whose size depends on which features the file uses, and
 * single `.by` lines that become several generated ones.
 */
class ByDebugBootstrapLinesTest {

    @TempDir
    lateinit var dir: Path

    /** `(line, endLine, runtimeLine)`, 1-based, as `_invert_lines` returns them. */
    private data class Run(val line: Int, val endLine: Int, val runtimeLine: Int)

    @Test
    fun `a constant prelude offset collapses to a single run`() {
        // Three prelude lines, then demo.by lines 1-4 emitted one for one.
        assertEquals(listOf(Run(1, 4, 4)), invert(listOf(null, null, null, 0, 1, 2, 3)))
    }

    @Test
    fun `an expanded statement splits the run in two`() {
        // .by line 3 claims two generated lines; it pins to the second, so the run breaks before it.
        assertEquals(listOf(Run(1, 2, 1), Run(3, 5, 4)), invert(listOf(0, 1, 2, 2, 3, 4)))
    }

    /**
     * The bug the last-line rule exists for, from a real `def f(a = [])`:
     *
     * ```
     * gen 2  def f(a = _MISSING):   .by 1
     * gen 3      if a is _MISSING:  .by 2
     * gen 4          a = []         .by 2
     * gen 5      a.append(1)        .by 2
     * ```
     *
     * Pinning `.by` 2 to generated line 3 stopped the debugger on the guard, where `a` is still the
     * sentinel and reads `<object object at 0x…>`. Pinning to 5 stops on `a.append(1)` with `a == []`.
     */
    @Test
    fun `a default-argument guard does not steal the breakpoint from the statement`() {
        val runs = invert(listOf(null, 0, 1, 1, 1, 2, 3, 4, 5, 6))
        val forByLine2 = runs.single { it.line <= 2 && 2 <= it.endLine }
        assertEquals(5, forByLine2.runtimeLine + (2 - forByLine2.line))
    }

    @Test
    fun `a by line claimed by several generated lines keeps the last`() {
        assertEquals(listOf(Run(8, 8, 4)), invert(listOf(null, 7, 7, 7)))
    }

    @Test
    fun `a gap in the by lines starts a new run`() {
        // .by line 2 (index 1) produced nothing — a comment that was dropped, say.
        assertEquals(listOf(Run(1, 1, 1), Run(3, 4, 2)), invert(listOf(0, 2, 3)))
    }

    @Test
    fun `prelude-only output maps nothing`() {
        assertTrue(invert(listOf(null, null)).isEmpty())
        assertTrue(invert(emptyList()).isEmpty())
    }

    /** Runs must arrive sorted by source line — pydevd bisects them by exactly that key. */
    @Test
    fun `runs are ordered by source line even when the output is not`() {
        // A hoisted import: .by line 5 emitted before .by line 1.
        assertEquals(listOf(Run(1, 2, 2), Run(5, 5, 1)), invert(listOf(4, 0, 1)))
    }

    /**
     * The real thing, captured from `by run` 0.0.1a9 on a file that starts with a `data class`.
     *
     * 83 lines of prelude, then `data class Point:` claiming two generated lines, then the rest one
     * for one — which is the whole file in exactly two runs; pinning `.by` 1 to 85 makes it one
     * uninterrupted run, and puts a breakpoint on `class Point:` rather than its decorator.
     */
    @Test
    fun `the map by run actually emits collapses to a single run`() {
        val lines = List(83) { null } + listOf(0, 0) + (1..15).toList()
        assertEquals(listOf(Run(1, 16, 85)), invert(lines))
    }

    /**
     * What pydevd is handed, through a stand-in for the two modules the bootstrap imports: one
     * `set_source_mapping` per `.by` file, entries built the way pydevd's own `setPydevdSourceMap`
     * handler builds them, naming the generated file as the runtime source — and a file with nothing
     * but prelude skipped rather than registered empty, which would only tell pydevd to forget it.
     */
    @Test
    fun `every by file with a mapped line is registered with pydevd in the debuggee`() {
        val result = register(
            """
            [{"source": "/abs/a.by", "generated": "/tmp/x/a.py", "lines": [null, null, null, 0, 1, 2, 3]},
             {"source": "/abs/b.by", "generated": "/tmp/x/b.py", "lines": [null, null]},
             {"source": "/abs/c.by", "generated": "/tmp/x/c.py", "lines": [0, 1, 2, 2]}]
            """,
            refuse = emptyList(),
        )
        assertEquals(2, result.get("registered").asInt)
        assertTrue(result.get("problem").isJsonNull, "nothing was refused: $result")
        assertEquals(
            listOf(
                "/abs/a.by" to listOf(listOf(1, 4, 4, "/tmp/x/a.py")),
                "/abs/c.by" to listOf(listOf(1, 2, 1, "/tmp/x/c.py"), listOf(3, 3, 4, "/tmp/x/c.py")),
            ),
            calls(result),
        )
    }

    /** A file pydevd refuses costs that file, says so by name, and the rest are still registered. */
    @Test
    fun `a file pydevd refuses is named and the rest are registered`() {
        val result = register(
            """
            [{"source": "/abs/a.by", "generated": "/tmp/x/a.py", "lines": [0]},
             {"source": "/abs/b.by", "generated": "/tmp/x/b.py", "lines": [0]}]
            """,
            refuse = listOf("/abs/a.by"),
        )
        assertEquals(1, result.get("registered").asInt)
        val problem = result.get("problem").asString
        assertTrue("/abs/a.by" in problem && "overlaps" in problem, "the refusal names the file and why: $problem")
        assertEquals(listOf("/abs/a.by", "/abs/b.by"), calls(result).map { it.first })
    }

    private fun invert(lines: List<Int?>): List<Run> {
        val out = python(
            """
            import json, sys
            print(json.dumps(bootstrap._invert_lines(json.loads(sys.argv[1]))))
            """,
            Gson().toJson(lines),
        )
        return JsonParser.parseString(out).asJsonArray.map { run ->
            val (line, end, first) = run.asJsonArray.map(JsonElement::getAsInt)
            Run(line, end, first)
        }
    }

    /**
     * `_register_source_maps` against a stand-in pydevd that records each call and refuses the
     * files named in [refuse] with the sentence pydevd uses for overlapping entries.
     */
    private fun register(files: String, refuse: List<String>): JsonObject {
        val out = python(
            """
            import json, sys, types

            calls = []
            refused = json.loads(sys.argv[2])

            class Entry:
                def __init__(self, line, end_line, runtime_line, runtime_source):
                    self.values = [line, end_line, runtime_line, runtime_source]

            class PyDevdAPI:
                SourceMappingEntry = Entry
                def filename_to_str(self, name):
                    return name
                def set_source_mapping(self, py_db, source_filename, mapping):
                    calls.append([source_filename, [entry.values for entry in mapping]])
                    return "the mapping overlaps" if source_filename in refused else ""

            pydevd = types.ModuleType("pydevd")
            pydevd.get_global_debugger = lambda: object()
            bundle = types.ModuleType("_pydevd_bundle")
            api = types.ModuleType("_pydevd_bundle.pydevd_api")
            api.PyDevdAPI = PyDevdAPI
            sys.modules.update({"pydevd": pydevd, "_pydevd_bundle": bundle, "_pydevd_bundle.pydevd_api": api})

            registered, problem = bootstrap._register_source_maps(json.loads(sys.argv[1]))
            print(json.dumps({"registered": registered, "problem": problem, "calls": calls}))
            """,
            files.trimIndent(),
            Gson().toJson(refuse),
        )
        return JsonParser.parseString(out).asJsonObject
    }

    private fun calls(result: JsonObject): List<Pair<String, List<List<Any>>>> =
        result.getAsJsonArray("calls").map { call ->
            val (source, entries) = call.asJsonArray.toList()
            source.asString to (entries as JsonArray).map { entry ->
                entry.asJsonArray.map { value ->
                    val primitive = value.asJsonPrimitive
                    if (primitive.isNumber) primitive.asInt else primitive.asString
                }
            }
        }

    /**
     * Runs [body] with the real bootstrap loaded as `bootstrap`, and returns what it printed.
     *
     * Loaded by path with `sys.path` holding only its own directory, which the bootstrap skips when
     * it looks for a `sitecustomize` it displaced — so none of the machine's is run — and with none
     * of the IDE's variables set, so it does not try to activate. `json` and `types`, which the
     * bodies import, are loaded before the path is cut, so their imports find them already there.
     */
    private fun python(body: String, vararg arguments: String): String {
        val bootstrap = dir.resolve("sitecustomize.py")
        if (!Files.exists(bootstrap)) {
            Files.write(
                bootstrap,
                checkNotNull(javaClass.getResourceAsStream("/debug/sitecustomize.py")) {
                    "the debug bootstrap is missing from the plugin resources"
                }.use { it.readBytes() },
            )
        }
        // what the bodies use is imported first, while the standard library is still on the path
        val script = """
            import importlib.util, json, sys, types
            sys.path[:] = [${Gson().toJson(dir.toString())}]
            spec = importlib.util.spec_from_file_location("bootstrap", ${Gson().toJson(bootstrap.toString())})
            bootstrap = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(bootstrap)
        """.trimIndent() + "\n" + body.trimIndent() + "\n"
        val process = ProcessBuilder(listOf(PYTHON, "-I", "-c", script) + arguments)
            .apply {
                environment().remove(ByDebugSetup.ENV_PORT)
                environment().remove(ByDebugSetup.ENV_INFO_OUT)
            }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "$PYTHON did not finish" }
        check(process.exitValue() == 0) { "the bootstrap failed under $PYTHON:\n$err" }
        return out.trim()
    }

    private companion object {
        /** Any CPython the bootstrap supports; it uses nothing newer than 3.8. */
        const val PYTHON = "python3"
    }
}
