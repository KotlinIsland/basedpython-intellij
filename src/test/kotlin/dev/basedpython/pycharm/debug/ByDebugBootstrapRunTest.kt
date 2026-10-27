package dev.basedpython.pycharm.debug

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The bootstrap starting debugpy in a `by run` program, run under a real interpreter against
 * stand-ins for debugpy and pydevd that record what they are asked.
 *
 * Tested by running the real file, because a Kotlin copy of what it does would be a second
 * implementation agreeing with itself.
 */
class ByDebugBootstrapRunTest {

    @TempDir
    lateinit var dir: Path

    /**
     * `by run`'s runner compiles each `.by` module under the `.by`'s path with the `.by`'s lines, so
     * a frame of `ok.by`'s `main` is `ok.by` at line 5, and that is where pydevd finds a breakpoint
     * set on `ok.by:5`. The bootstrap used to hand pydevd `_by_sourcemap.py` inverted, which moved
     * that breakpoint to the transpiled `ok.py` at line 21 — a file no frame names — so under
     * debugpy no `.by` breakpoint ever stopped: measured in PyCharm 263.7528 and with a raw DAP
     * client, against every `by` from 18fa185ecc6a to b8b4749b69a5.
     */
    @Test
    fun `pydevd is handed no source map`() {
        val report = activate(
            """
            SOURCEMAP = {"RUN/ok.py": ("/p/ok.by", [None, None, 0, None, 2, 3, 4])}
            DIGESTS = {"RUN/ok.py": "0"}
            """,
        )
        assertEquals("listening", report.get("status").asString, "$report")
        assertTrue(report.getAsJsonArray("pydevd").isEmpty, "pydevd was asked for something: $report")
        assertFalse(report.has("mapped"), "nothing is mapped, so nothing says how much was: $report")
        assertTrue(report.get("message").isJsonNull, "a healthy run warns of nothing: $report")
        assertTrue(report.getAsJsonArray("collisions").isEmpty, "$report")
    }

    /**
     * The runner's path as python names it in the runner's frames — the path the program was started
     * with — which is what a pydevd rule is matched against, character for character.
     */
    @Test
    fun `the report names the runner the program was started under`() {
        val report = activate("SOURCEMAP = {}\nDIGESTS = {}")
        val run = dir.resolve("run").toRealPath()
        assertEquals(run.resolve("_by_runner.py").toString(), report.get("runner").asString)
    }

    /**
     * The failure this guards, taken from a real project: `main.by` beside an empty `src/main.by`,
     * both transpiled to one `main.py`. The empty one was written last, so it won — the program ran
     * and printed nothing. `SOURCEMAP` cannot show this once loaded, because it is a dict literal
     * whose duplicate key has already collapsed; the bootstrap reads it back as a syntax tree.
     */
    @Test
    fun `two by files transpiled to one module are reported in write order`() {
        val report = activate(
            """
            SOURCEMAP = {
                "RUN/main.py": ("/p/main.by", [0]),
                "RUN/main.py": ("/p/src/main.by", [0]),
            }
            DIGESTS = {}
            """,
        )
        val collision = report.getAsJsonArray("collisions").single().asJsonObject
        assertEquals(listOf("/p/main.by", "/p/src/main.by"), collision.getAsJsonArray("sources").map { it.asString })
    }

    /**
     * Runs the bootstrap's activation as the runner's `sitecustomize` would, in a run directory
     * holding `_by_runner.py` and a `_by_sourcemap.py` of [sourcemap] (`RUN` standing for that
     * directory), and returns its report with what pydevd was asked under `pydevd`.
     */
    private fun activate(sourcemap: String): JsonObject {
        val run = Files.createDirectories(dir.resolve("run")).toRealPath()
        Files.writeString(run.resolve("_by_runner.py"), "")
        Files.writeString(run.resolve("_by_sourcemap.py"), sourcemap.trimIndent().replace("RUN", run.toString()))
        val out = python(
            """
            import json, sys, types

            calls = []

            debugpy = types.ModuleType("debugpy")
            debugpy.listen = lambda address: calls.append(["listen", list(address)])
            debugpy.wait_for_client = lambda: calls.append(["wait_for_client"])

            # what pydevd is asked, by anything that asks it
            class PyDevdAPI:
                def __getattr__(self, name):
                    return lambda *args, **kwargs: calls.append(["pydevd", name])

            pydevd = types.ModuleType("pydevd")
            pydevd.get_global_debugger = lambda: calls.append(["pydevd", "get_global_debugger"]) or object()
            bundle = types.ModuleType("_pydevd_bundle")
            api = types.ModuleType("_pydevd_bundle.pydevd_api")
            api.PyDevdAPI = PyDevdAPI
            sys.modules.update({
                "debugpy": debugpy, "pydevd": pydevd,
                "_pydevd_bundle": bundle, "_pydevd_bundle.pydevd_api": api,
            })

            run_dir, info = sys.argv[1], sys.argv[2]
            sys.argv[:] = [run_dir + "/_by_runner.py", "ok"]
            bootstrap._INFO_OUT = info
            bootstrap._activate(5679)

            report = json.load(open(info))
            report["pydevd"] = [call for call in calls if call[0] == "pydevd"]
            report["debugpy"] = [call for call in calls if call[0] != "pydevd"]
            print(json.dumps(report))
            """,
            run.toString(),
            dir.resolve("info.json").toString(),
        )
        return JsonParser.parseString(out).asJsonObject
    }

    /**
     * Runs [body] with the real bootstrap loaded as `bootstrap`, and returns what it printed.
     *
     * Loaded by path with `sys.path` holding only its own directory, which the bootstrap skips when
     * it looks for a `sitecustomize` it displaced — so none of the machine's is run — and with none
     * of the IDE's variables set, so it does not activate as it loads. What the bodies and the
     * bootstrap import is imported before the path is cut, so their imports find it already there.
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
        val script = """
            import ast, importlib.util, json, sys, tempfile, types
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
