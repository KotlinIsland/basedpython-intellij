package dev.basedpython.pycharm.lsp.build

import com.intellij.navigation.GotoRelatedItem
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.editor.templates.macro.ByMacroSupport
import dev.basedpython.pycharm.lsp.ext.ByBuildOutput
import dev.basedpython.pycharm.lsp.ext.ByBuildOutputParams
import dev.basedpython.pycharm.navigation.BasedPythonRelatedProvider
import dev.basedpython.pycharm.run.test.node.ByPytestCollect
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The plugin's half of `by/buildOutput`: the wire format, and the callers that relate a `.by` to
 * its output taking `by`'s answer rather than a layout of their own.
 */
@TestFixtures
class ByBuildOutputsTest {

    private val fixture by codeInsightFixture()

    /** lsp4j's own Gson, so this is the json the plugin will actually put on the wire. */
    private val gson = MessageJsonHandler(emptyMap()).gson

    @Test
    fun `the params are the shape the server accepts`() = onEdt {
        // `BuildOutputParams` is `deny_unknown_fields`: one extra key and the request fails.
        assertEquals(
            """{"uri":"file:///p/src/pkg/main.by"}""",
            gson.toJson(ByBuildOutputParams("file:///p/src/pkg/main.by")),
        )
    }

    /** Captured from `by server` on a src-layout project right after `by build` wrote `build/pkg/main.py`. */
    @Test
    fun `the answer reads as the server writes it`() = onEdt {
        val json = """{"buildDirectory":"/p/build","generated":"/p/build/pkg/main.py","projectRoot":"/p","source":null}"""
        assertEquals(
            ByBuildOutput(projectRoot = "/p", buildDirectory = "/p/build", generated = "/p/build/pkg/main.py"),
            gson.fromJson(json, ByBuildOutput::class.java),
        )
    }

    @Test
    fun `a file that is not on disk is never asked about`() = onEdt {
        // `toNioPath` throws `UnsupportedOperationException` for one of these, which the layout
        // guesses this replaced did not catch.
        assertNull(ByBuildOutputs.getInstance(fixture.project).of(LightVirtualFile("scratch.by", "x = 1\n")))
    }

    @Test
    fun `go to related asks nothing of a file outside the local file system, and does not throw`() = onEdt {
        // The light fixture's files live in `temp://`, which is exactly such a file system.
        val file = fixture.configureByText("main.by", "x = 1\n")
        assertEquals(emptyList<GotoRelatedItem>(), BasedPythonRelatedProvider().getItems(file))
    }

    @Test
    fun `the template macro writes the path by gave, relative to the project root`() = onEdt {
        val file = fixture.configureByText("macro.by", "x = 1\n").virtualFile
        val outputs = ByBuildOutputs.getInstance(fixture.project)
        assertEquals("", ByMacroSupport.outPath(fixture.project, file), "nothing is guessed before by answers")

        outputs.remember(file, ByBuildOutput(projectRoot = "/p", buildDirectory = "/p/build", generated = "/p/build/pkg/macro.py"))
        assertEquals("build/pkg/macro.py", ByMacroSupport.outPath(fixture.project, file))
    }

    @Test
    fun `plain pytest is told to skip every build directory by reported`() = onEdt {
        assertEquals(
            listOf("-m", "pytest", "--collect-only", "-q", "--ignore=/p/build"),
            ByPytestCollect.pythonArguments(listOf("/p/build")),
        )
    }
}
