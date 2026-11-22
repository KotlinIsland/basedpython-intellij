package dev.basedpython.pycharm.run.django

import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.lsp.ext.ByDjangoApp
import dev.basedpython.pycharm.lsp.ext.ByDjangoArgument
import dev.basedpython.pycharm.lsp.ext.ByDjangoCommand
import dev.basedpython.pycharm.lsp.ext.ByDjangoCommandArguments
import dev.basedpython.pycharm.lsp.ext.ByDjangoProject
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.testFramework.withLocksForbidden
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.SwingUtilities

/** Following runs, and the window's two views, under a real platform. */
@TestFixtures
class ByManageWindowTest {

    private val fixture by codeInsightFixture()

    /** The project is shared by the tests, and what it ran with it. */
    @AfterEach
    fun forget() = onEdt {
        val runs = ByManageRuns.getInstance(fixture.project)
        runs.state.value.runs.filter { it.running }.forEach { runs.stop(it.id) }
        PlatformTestUtil.waitWithEventsDispatching("every run should end", { runs.state.value.runs.none { it.running } }, 10)
        runs.clearFinished()
        ByManageHistory.getInstance(fixture.project).loadState(ByManageHistory.Data())
    }

    private fun configuration(module: String, arguments: String) =
        (BasedPythonRunConfigurationType.getInstance().runFactory.createTemplateConfiguration(fixture.project) as ByRunConfiguration).also {
            it.options.module = module
            it.options.programArgs = arguments
        }

    private fun follow(module: String, arguments: String): NopProcessHandler {
        val handler = NopProcessHandler()
        ByManageRuns.getInstance(fixture.project).follow(DefaultRunExecutor.EXECUTOR_ID, configuration(module, arguments), null, handler)
        handler.startNotify()
        return handler
    }

    private fun NopProcessHandler.prints(text: String) = notifyTextAvailable(text, ProcessOutputType.STDOUT)

    private fun last() = ByManageRuns.getInstance(fixture.project).state.value.runs.last()

    @Test
    fun `a manage py run is followed to its end and another module's is not`() = onEdt {
        follow("shop.main", "migrate")
        assertTrue(ByManageRuns.getInstance(fixture.project).state.value.runs.isEmpty())
        val handler = follow("manage", "migrate polls")
        assertEquals("migrate", last().command)
        handler.prints("  Applying polls.0001_initial... OK\n")
        assertEquals(1, last().facts.migrated)
        assertTrue(last().running)
        handler.destroyProcess()
        PlatformTestUtil.waitWithEventsDispatching("the run should end", { !last().running }, 10)
        assertFalse(last().stopped, "there was no process to stop")
        ByManageRuns.getInstance(fixture.project).clearFinished()
        assertTrue(ByManageRuns.getInstance(fixture.project).state.value.runs.isEmpty())
    }

    /** The handler says of both that they will be destroyed. */
    @Test
    fun `a process that failed by itself is not one that was stopped`() = onEdt {
        fun ran(stop: Boolean): ByManageRun {
            val command = if (stop) GeneralCommandLine("sleep", "60") else GeneralCommandLine("sh", "-c", "echo CommandError: no; exit 3")
            val handler = KillableColoredProcessHandler(command)
            ByManageRuns.getInstance(fixture.project).follow(DefaultRunExecutor.EXECUTOR_ID, configuration("manage", "x"), null, handler)
            handler.startNotify()
            if (stop) handler.destroyProcess()
            PlatformTestUtil.waitWithEventsDispatching("it should end", { !last().running }, 20)
            return last()
        }
        val failed = ran(stop = false)
        assertEquals(listOf(false, 3, "no"), listOf(failed.stopped, failed.exitCode, failed.facts.error))
        assertTrue(ran(stop = true).stopped)
    }

    @Test
    fun `an open question is seen once the output has been still`() = onEdt {
        val handler = follow("manage", "createsuperuser")
        handler.prints("Username: ")
        assertNull(last().facts.prompt)
        PlatformTestUtil.waitWithEventsDispatching("it should be a question", { last().facts.prompt != null }, 10)
        assertEquals("Username:", last().facts.prompt)
        handler.destroyProcess()
    }

    @Test
    fun `the Now view draws its rows and keeps a row's links while it changes`() = onEdt {
        val handler = follow("manage", "runserver")
        handler.prints("Starting WSGI development server at http://127.0.0.1:8000/\n")
        val panel = ByManageNowPanel(fixture.project) {}
        try {
            val links = UIUtil.findComponentsOfType(panel, ActionLink::class.java)
            assertEquals(listOf("Open", "Debug", "Stop"), links.map { it.text })
            assertEquals("Stop runserver", links.last().accessibleContext.accessibleName)
            panel.setSize(360, 300)
            UIUtil.layoutRecursively(panel)
            for (link in links) {
                val right = SwingUtilities.convertPoint(link, link.width, 0, panel).x
                assertTrue(right <= 360, "${link.text} ends at $right, past the window")
            }
            handler.prints("[21/Nov/2026 22:44:28] \"GET / HTTP/1.1\" 200 1\n")
            fun said() = UIUtil.findComponentsOfType(panel, SimpleColoredComponent::class.java).joinToString { it.getCharSequence(false) }
            PlatformTestUtil.waitWithEventsDispatching("the request should be counted", { "1 request" in said() }, 10)
            assertEquals(links, UIUtil.findComponentsOfType(panel, ActionLink::class.java))
        } finally {
            Disposer.dispose(panel)
            handler.destroyProcess()
        }
    }

    private val projects = listOf(
        ByDjangoProject(
            apps = listOf(ByDjangoApp(label = "library"), ByDjangoApp(label = "auth", external = true)),
            commands = listOf("migrate" to "django", "overdue" to "library", "changepassword" to "auth", "check" to "django").map { (name, app) ->
                ByDjangoCommand(name = name, app = app, help = "Does $name")
            },
        ),
    )

    private fun names(typed: String, starred: Set<String> = emptySet(), lines: List<String> = emptyList()) =
        ByManageCommandList.items(projects, lines, starred, typed).map {
            when (it) {
                is ByManageCommandList.Item.Recent -> "> " + it.line
                is ByManageCommandList.Item.Command -> it.name
            }
        }

    @Test
    fun `commands are listed the project's own before django's, and narrowed by what is typed`() {
        assertEquals(listOf("overdue", "changepassword", "check", "migrate"), names(""))
        assertEquals(listOf("migrate", "overdue", "changepassword", "check"), names("", starred = setOf("migrate")))
        assertEquals(listOf("> check --deploy", "check"), names("che", lines = listOf("check --deploy", "migrate")))
        assertEquals(listOf("> migrate polls", "migrate"), names("migrate ", lines = listOf("migrate polls", "check")))
        assertEquals(listOf("check"), names("check --deploy", lines = listOf("check --deploy")))
    }

    @Test
    fun `what was run is remembered once, the latest first`() {
        val history = ByManageHistory.getInstance(fixture.project)
        listOf("check", "migrate", "check", " ").forEach(history::ran)
        assertEquals(listOf("check", "migrate"), history.lines)
        history.star("migrate", true)
        assertEquals(setOf("migrate"), history.starred)
        history.star("migrate", false)
        assertTrue(history.starred.isEmpty())
    }

    private val manage = ByDjangoManage(Path.of("/p"), "manage")

    private fun ByManageLauncher.options(settings: RunnerAndConfigurationSettings) = (settings.configuration as ByRunConfiguration).options

    @Test
    fun `a second run of a command is the same configuration, and another of its name is left alone`() = onEdt { configurations(RunManager.getInstanceAsync(fixture.project)) }

    private fun configurations(runManager: RunManager) {
        val first = ByManageLauncher.configuration(fixture.project, manage, listOf("migrate"))
        first.isTemporary = true
        runManager.addConfiguration(first)
        try {
            assertSame(first, ByManageLauncher.configuration(fixture.project, manage, listOf("migrate")))
            // saved, with a variable of the user's: theirs is what the command is now
            runManager.makeStable(first)
            ByManageLauncher.options(first).envVars = mutableMapOf("A" to "1")
            assertSame(first, ByManageLauncher.configuration(fixture.project, manage, listOf("migrate")))
            // run like one with other variables, it is another, and not under the name that would replace it
            val like = configuration("manage", "runserver").also { it.options.workingDir = "/p"; it.options.envVars = mutableMapOf("A" to "2") }
            val other = ByManageLauncher.configuration(fixture.project, like, listOf("migrate"))
            assertNotSame(first, other)
            assertNotEquals(first.name, other.name)
            assertEquals(mapOf("A" to "2"), ByManageLauncher.options(other).envVars.toMap())
            assertEquals("/p", ByManageLauncher.options(other).workingDir)
        } finally {
            runManager.removeConfiguration(first)
        }
    }

    private fun emulates(executor: Executor, target: String?): Boolean {
        val configuration = configuration("manage", "check").also { it.options.terminalInRun = true; it.options.remoteTarget = target }
        val environment = ExecutionEnvironmentBuilder.create(fixture.project, executor, configuration).build()
        val state = configuration.getState(executor, environment)!!
        return generateSequence<Class<*>>(state.javaClass) { it.superclass }.firstNotNullOf { type -> type.declaredMethods.firstOrNull { it.name == "getEmulateTerminal" } }
            .also { it.isAccessible = true }.invoke(state) as Boolean
    }

    @Test
    fun `a run here has a terminal and a debug session or a target has none`() = onEdt {
        assertTrue(emulates(DefaultRunExecutor.getRunExecutorInstance(), null))
        assertFalse(emulates(DefaultDebugExecutor.getDebugExecutorInstance(), null))
        assertFalse(emulates(DefaultRunExecutor.getRunExecutorInstance(), "docker"))
    }

    /** Through the listener plugin.xml registers, as the platform tells of a run. */
    @Test
    fun `the platform's runs are followed`() = onEdt {
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val environment = ExecutionEnvironmentBuilder.create(fixture.project, executor, configuration("manage", "check")).build()
        val handler = NopProcessHandler()
        fixture.project.messageBus.syncPublisher(ExecutionManager.EXECUTION_TOPIC).apply {
            processStarting(executor.id, environment, handler)
            processStarted(executor.id, environment, handler)
        }
        assertEquals(listOf("check"), ByManageRuns.getInstance(fixture.project).state.value.runs.map { it.command })
        handler.startNotify()
        handler.destroyProcess()
    }

    @Test
    fun `a manage py is one however it is named`() = onEdt {
        for (module in listOf("manage", "manage.py", "backend.manage", "backend/manage.py")) {
            follow(module, "check").destroyProcess()
            assertEquals("check", last().command, module)
        }
        assertEquals(4, ByManageRuns.getInstance(fixture.project).state.value.runs.size)
    }

    @Test
    fun `one that has ended as it is followed is not running`() = onEdt {
        val handler = NopProcessHandler().also { it.startNotify() }
        handler.destroyProcess()
        PlatformTestUtil.waitWithEventsDispatching("it should end", { handler.isProcessTerminated }, 10)
        ByManageRuns.getInstance(fixture.project).follow(DefaultRunExecutor.EXECUTOR_ID, configuration("manage", "check"), null, handler)
        assertFalse(last().running)
    }

    @Test
    fun `a debugger's copy of the output is read`() = onEdt {
        val configuration = configuration("manage", "runserver")
        val handler = NopProcessHandler()
        val runs = ByManageRuns.getInstance(fixture.project)
        runs.follow(DefaultDebugExecutor.EXECUTOR_ID, configuration, null, handler)
        handler.startNotify()
        runs.printed(configuration("manage", "other"), "Starting WSGI development server at http://127.0.0.1:1/\n")
        assertNull(last().facts.server)
        runs.printed(configuration, "Starting WSGI development server at http://127.0.0.1:1/\n")
        assertEquals("http://127.0.0.1:1/", last().facts.server)
    }

    @Test
    fun `clearing the runs that ended leaves what is done done and what is to be done to be`() = onEdt {
        val runs = ByManageRuns.getInstance(fixture.project)
        val server = follow("manage", "runserver")
        server.prints("You have 2 unapplied migration(s). Your project may not work properly until you apply the migrations for app(s): polls.\n")
        val warned = runs.state.value.warned.single()
        assertEquals("runserver", runs.saidBy(warned)?.options?.programArgs)
        server.prints("Starting WSGI development server at http://127.0.0.1:1/\n")
        assertEquals(1, runs.state.value.warned.size)
        server.prints("Starting WSGI development server at http://127.0.0.1:1/\n")
        assertTrue(runs.state.value.warned.isEmpty())
        follow("manage", "check").destroyProcess()
        PlatformTestUtil.waitWithEventsDispatching("it should end", { !last().running }, 10)
        runs.clearFinished()
        assertTrue(runs.state.value.warned.isEmpty())
    }

    private fun key(component: JComponent, code: Int, modifiers: Int = 0) {
        val event = KeyEvent(component, KeyEvent.KEY_PRESSED, 0L, modifiers, code, KeyEvent.CHAR_UNDEFINED)
        component.keyListeners.forEach { if (!event.isConsumed) it.keyPressed(event) }
    }

    private fun withCommands(body: (ByManageCommandsPanel) -> Unit) {
        val panel = ByManageCommandsPanel(fixture.project)
        val history = ByManageHistory.getInstance(fixture.project)
        try {
            listOf("check --deploy", "migrate polls", "migrate").forEach(history::ran)
            panel.line.text = "mig"
            PlatformTestUtil.waitWithEventsDispatching("the list should be narrowed", { panel.list.itemsCount == 2 }, 10)
            body(panel)
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `an arrow chooses a row and Enter or Tab takes it rather than running what was typed`() = onEdt {
        for (code in listOf(KeyEvent.VK_ENTER, KeyEvent.VK_TAB)) {
            withCommands { panel ->
                assertFalse(panel.line.focusTraversalKeysEnabled, "Tab has to reach the line")
                key(panel.line, KeyEvent.VK_DOWN)
                key(panel.line, KeyEvent.VK_DOWN)
                key(panel.line, code)
                assertEquals("migrate polls", panel.line.text)
                assertEquals(2, panel.list.itemsCount, "the list is narrowed by what was typed")
                assertTrue(ByManageRuns.getInstance(fixture.project).state.value.runs.isEmpty(), "nothing was run")
            }
        }
    }

    @Test
    fun `a click below the last row is nobody's and a right click is its row's`() = onEdt {
        withCommands { panel ->
            panel.list.setSize(300, 400)
            fun click(y: Int, button: Int) {
                val event = { id: Int -> MouseEvent(panel.list, id, 0L, 0, 5, y, 5, y, 1, button == MouseEvent.BUTTON3, button) }
                // the platform's own needs a screen
                panel.list.mouseListeners.filter { it.javaClass.name.startsWith("dev.basedpython") }.forEach { it.mousePressed(event(MouseEvent.MOUSE_PRESSED)); it.mouseClicked(event(MouseEvent.MOUSE_CLICKED)) }
            }
            click(390, MouseEvent.BUTTON1)
            assertEquals("mig", panel.line.text)
            panel.list.selectedIndex = 0
            click(panel.list.getCellBounds(1, 1).y + 2, MouseEvent.BUTTON3)
            assertEquals(1, panel.list.selectedIndex)
        }
    }

    @Test
    fun `an error from django is shown as it is`() = onEdt {
        val panel = ByManageCommandsPanel(fixture.project)
        try {
            panel.showAnswer("migrate", ByManageCommandArguments.State.Ready(ByDjangoCommandArguments(error = "TypeError: <module 'x'>")))
            val shown = UIUtil.findComponentsOfType(panel, JEditorPane::class.java).joinToString { it.document.getText(0, it.document.length) }
            assertTrue("TypeError: <module 'x'>" in shown, shown)
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `painting the command list takes no lock`() {
        lateinit var panel: ByManageCommandsPanel
        onEdt { panel = ByManageCommandsPanel(fixture.project) }
        try {
            val items = ByManageCommandList.items(projects, listOf("check"), setOf("migrate"), "")
            withLocksForbidden {
                val shown = JBList(items)
                for ((index, item) in items.withIndex()) {
                    val cell = panel.list.cellRenderer.getListCellRendererComponent(shown, item, index, false, false)
                    cell.setSize(400, 40)
                    cell.paint(BufferedImage(400, 40, BufferedImage.TYPE_INT_ARGB).graphics)
                }
            }
        } finally {
            onEdt { Disposer.dispose(panel) }
        }
    }

    @Test
    fun `the form and the line change each other`() = onEdt {
        val panel = ByManageCommandsPanel(fixture.project)
        try {
            panel.edit("migrate polls")
            val arguments = listOf(
                ByDjangoArgument("app_label", kind = ByDjangoArgument.VALUE, choices = listOf("polls", "library")),
                ByDjangoArgument("migration_name", kind = ByDjangoArgument.VALUE),
                ByDjangoArgument("database", listOf("--database"), ByDjangoArgument.VALUE, help = "A <b>database</b> " + "of those the settings name ".repeat(6)),
                ByDjangoArgument("fake", listOf("--fake"), ByDjangoArgument.FLAG),
                ByDjangoArgument("verbosity", listOf("-v", "--verbosity"), ByDjangoArgument.VALUE, choices = listOf("0", "1", "2"), general = true),
            )
            panel.showAnswer("migrate", ByManageCommandArguments.State.Ready(ByDjangoCommandArguments("Updates", arguments)))
            val box = UIUtil.findComponentsOfType(panel, JBCheckBox::class.java).single()
            val app = UIUtil.findComponentsOfType(panel, ComboBox::class.java).first { it.itemCount > 0 }
            val fields = UIUtil.findComponentsOfType(panel, JBTextField::class.java).filter { it !== panel.line }
            val database = fields[1]
            assertEquals("polls", app.selectedItem)
            // a narrow window narrows the fields rather than hiding their ends
            panel.setSize(560, 400)
            UIUtil.layoutRecursively(panel)
            assertTrue(SwingUtilities.convertPoint(database, database.width, 0, panel).x <= 560, "the fields end past the window")
            box.doClick()
            assertEquals("migrate polls --fake", panel.line.text)
            app.selectedItem = "library"
            assertEquals("migrate library --fake", panel.line.text)
            database.text = "a b"
            assertEquals("migrate library --fake --database \"a b\"", panel.line.text)
            panel.line.text = "migrate library 0001 --database other"
            PlatformTestUtil.waitWithEventsDispatching("the line should be read", { database.text == "other" }, 10)
            assertEquals("other", database.text)
            assertFalse(box.isSelected)
            // the migration's place would be the app's: it waits in its field
            val migration = fields[0]
            assertEquals("0001", migration.text)
            app.selectedItem = ""
            assertEquals("migrate --database other", panel.line.text)
            assertEquals("0001", migration.text)
            box.doClick()
            assertEquals("0001", migration.text)
            app.selectedItem = "polls"
            assertEquals("migrate polls 0001 --database other --fake", panel.line.text)
        } finally {
            Disposer.dispose(panel)
        }
    }

    @Test
    fun `a message says why there is no form`() = onEdt {
        val panel = ByManageCommandsPanel(fixture.project)
        try {
            panel.showAnswer("migrate", ByManageCommandArguments.State.Unsupported)
            assertEquals(listOf("Run migrate --help"), UIUtil.findComponentsOfType(panel, ActionLink::class.java).map { it.text })
        } finally {
            Disposer.dispose(panel)
        }
    }
}
