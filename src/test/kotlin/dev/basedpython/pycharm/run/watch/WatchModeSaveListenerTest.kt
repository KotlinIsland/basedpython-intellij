package dev.basedpython.pycharm.run.watch

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Which projects a save rebuilds: the saved file's own, and only with watch mode on. */
@TestFixtures
class WatchModeSaveListenerTest {

    private val fixture by codeInsightFixture()

    @TempDir
    lateinit var dir: Path

    @AfterEach
    fun off() = onEdt { PropertiesComponent.getInstance(fixture.project).unsetValue(WatchModeState.KEY) }

    @Test
    fun `a saved by file rebuilds its project when watch mode is on`() = onEdt {
        val file = fixture.configureByText("main.by", "a = 1").virtualFile

        assertEquals(emptyList<Any>(), projectsToBuild(file))
        WatchModeState.toggle(fixture.project)
        assertEquals(listOf(fixture.project), projectsToBuild(file))
    }

    @Test
    fun `a file that is not basedpython rebuilds nothing`() = onEdt {
        WatchModeState.toggle(fixture.project)
        val file = fixture.configureByText("notes.txt", "a = 1").virtualFile

        assertEquals(emptyList<Any>(), projectsToBuild(file))
    }

    @Test
    fun `a by file outside the project does not rebuild it`() = onEdt {
        WatchModeState.toggle(fixture.project)
        val outside = Files.writeString(dir.resolve("elsewhere.by"), "a = 1")
        val file = checkNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outside))

        assertEquals(emptyList<Any>(), projectsToBuild(file))
    }
}
