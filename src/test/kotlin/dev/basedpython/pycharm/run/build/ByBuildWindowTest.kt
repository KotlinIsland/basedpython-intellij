package dev.basedpython.pycharm.run.build

import com.intellij.build.BuildContentManager
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.ui.content.ContentFactory
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Job
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import javax.swing.JPanel

/**
 * The Build window's tabs that show a basedpython build go when the plugin unloads, and a build
 * still running is stopped and reported over first; a tab showing only another tool's build stays.
 */
@TestFixtures
class ByBuildWindowTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private val disposable = Disposer.newDisposable("build window test")

    private val window get() = ToolWindowManager.getInstance(project).getToolWindow(BuildContentManager.TOOL_WINDOW_ID)!!

    /** The light project is shared, so the window it is given stays, emptied after each test. */
    @BeforeEach
    fun registerWindow() = onEdt {
        val manager = ToolWindowManager.getInstance(project)
        if (manager.getToolWindow(BuildContentManager.TOOL_WINDOW_ID) == null) {
            manager.registerToolWindow(RegisterToolWindowTask(id = BuildContentManager.TOOL_WINDOW_ID))
        }
    }

    @AfterEach
    fun cleanUp() = onEdt {
        window.contentManager.removeAllContents(true)
        Disposer.dispose(disposable)
    }

    /** A Build tab whose view holds a console, as the window's builds view does. */
    private fun tab(name: String): ConsoleView {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        Disposer.register(disposable, console)
        window.contentManager.addContent(ContentFactory.getInstance().createContent(JPanel().apply { add(console.component) }, name, false))
        return console
    }

    private fun stop(): ByBuildView.Stop {
        val descriptor = DefaultBuildDescriptor(Any(), "basedpython", project.basePath.orEmpty(), System.currentTimeMillis())
        return ByBuildView.Stop("basedpython").apply {
            progress = BuildViewManager.createBuildProgress(project).start(
                object : BuildProgressDescriptor {
                    override fun getTitle() = "basedpython"
                    override fun getBuildDescriptor() = descriptor
                },
            )
            startNotify()
        }
    }

    private fun tabNames() = window.contentManager.contents.map { it.displayName }

    @Test
    fun `the tabs showing a basedpython build close, a running one stopped first, and another tool's stays`() = onEdt {
        val finished = stop().apply { ended(0) {} }
        val running = stop().apply { build = Job() }
        val build = running.build!!
        ByBuildWindow.getInstance(project).shown(tab("ours, finished"), finished)
        ByBuildWindow.getInstance(project).shown(tab("ours, running"), running)
        tab("Gradle's")

        ByBuildWindow.getInstance(project).close()

        assertEquals(listOf("Gradle's"), tabNames())
        assertTrue(build.isCancelled)
        assertTrue(running.isProcessTerminated)
    }

    /** The build's own end, once the window has been told it is over, changes nothing. */
    @Test
    fun `a build is reported over once`() = onEdt {
        val stop = stop()
        var reports = 0
        stop.abandon()
        stop.ended(1) { reports++ }

        assertEquals(0, reports)
        assertTrue(stop.isProcessTerminated)
        assertEquals(-1, stop.exitCode)
    }
}
