package dev.basedpython.pycharm.terminal

import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.asBasedPythonProject
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

/**
 * A terminal's shell in a basedpython project starts with the `by` and `buff` the IDE runs on `PATH`;
 * in any other project, or on another machine, it starts as it would without basedpython.
 */
@TestFixtures
class ByTerminalExecOptionsCustomizerTest {

    private val fixture by codeInsightFixture()

    @TempDir
    lateinit var tools: Path

    /** What a shell starting at the project's base on [eel] with `/usr/bin` as its `PATH` is given. */
    private fun customize(eel: EelDescriptor = LocalEelDescriptor): List<String> {
        val directory = EelPath.parse(fixture.project.basePath!!, LocalEelDescriptor)
        val activation = ByTerminalExecOptionsCustomizer.activation(fixture.project, eel, directory, "/usr/bin") ?: return emptyList()
        return activation.variables.map { (name, value) -> "$name=$value" } + activation.path.map { "PATH += $it" }
    }

    /** An executable [name] in [tools], as a configured path names one. */
    private fun executable(name: String): Path =
        Files.createFile(tools.resolve(name)).also { it.toFile().setExecutable(true) }

    private fun withBinaries(body: () -> Unit) {
        val settings = BasedPythonSettings.getInstance(fixture.project)
        val by = settings.state.byPath
        val buff = settings.state.buffPath
        settings.byPath = executable("by").toString()
        settings.buffPath = executable("buff").toString()
        try {
            body()
        } finally {
            settings.state.byPath = by
            settings.state.buffPath = buff
        }
    }

    @Test
    fun `a basedpython project's shell finds the by and buff the IDE runs`() = onEdt {
        withBinaries {
            asBasedPythonProject(fixture.project) {
                assertEquals(listOf("PATH += $tools"), customize())
            }
        }
    }

    @Test
    fun `any other project's shell is left alone`() = onEdt {
        withBinaries {
            assertEquals(emptyList<String>(), customize())
        }
    }

    @Test
    fun `a shell on another machine is left alone`() = onEdt {
        withBinaries {
            asBasedPythonProject(fixture.project) {
                val elsewhere = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(EelDescriptor::class.java)) { self, method, args ->
                    when (method.name) {
                        "equals" -> self === args?.get(0)
                        "hashCode" -> 1
                        "toString" -> "a container"
                        else -> null
                    }
                } as EelDescriptor
                assertEquals(emptyList<String>(), customize(elsewhere))
            }
        }
    }
}
