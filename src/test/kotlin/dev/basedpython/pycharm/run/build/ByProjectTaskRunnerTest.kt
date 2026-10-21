package dev.basedpython.pycharm.run.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.SuccessResult
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.replaceService
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.asBasedPythonProject
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Build | Build Project in a basedpython project runs `by build` and reports it in the Build tool
 * window: its output as text, each diagnostic as a node at its place, and how it ended.
 *
 * Against a `by` that is a script printing what `by build --output-format github` prints.
 */
@TestFixtures
class ByProjectTaskRunnerTest {

    private val fixture by codeInsightFixture()

    @TempDir
    lateinit var dir: Path

    private lateinit var base: VirtualFile
    private val events = CopyOnWriteArrayList<BuildEvent>()
    private val listening = Disposer.newDisposable()

    @BeforeEach
    fun project() = onEdt {
        base = WriteAction.computeAndWait<VirtualFile, RuntimeException> { VfsUtil.createDirectories(project.basePath!!) }
        // The light project's module holds only an in-memory source root; a basedpython project's
        // module holds the project's directory.
        ModuleRootModificationUtil.addContentRoot(fixture.module, base)
        // The tests' own BuildViewManager drops every event; this one keeps them.
        project.replaceService(BuildViewManager::class.java, Recording(project, events), listening)
    }

    @AfterEach
    fun cleanUp() = onEdt {
        Disposer.dispose(listening)
        BasedPythonSettings.getInstance(project).byPath = null
        ModuleRootModificationUtil.updateModel(fixture.module) { model ->
            model.contentEntries.filter { it.file == base }.forEach(model::removeContentEntry)
        }
    }

    private val project get() = fixture.project

    private fun fakeBy(script: String) {
        val by = Files.createFile(
            dir.resolve("by"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-xr-x")),
        )
        Files.writeString(by, "#!/bin/sh\n$script\n")
        BasedPythonSettings.getInstance(project).byPath = by.toString()
    }

    private fun buildProject(): ProjectTaskRunner.Result {
        val task = ProjectTaskManager.getInstance(project).createAllModulesBuildTask(true, project)
        val promise = ByProjectTaskRunner().run(project, ProjectTaskContext(), task)
        return PlatformTestUtil.waitForPromise(promise, TimeUnit.SECONDS.toMillis(60))!!
    }

    @Test
    fun `it builds the module that holds a basedpython project, and nothing else`() = onEdt {
        val runner = ByProjectTaskRunner()
        val task = ProjectTaskManager.getInstance(project).createModulesBuildTask(fixture.module, true, false, false)

        // Not basedpython: left to the IDE's own compiler.
        assertFalse(runner.canRun(project, task, null))
        asBasedPythonProject(project) {
            assertTrue(runner.canRun(project, task, null))
            ModuleRootModificationUtil.updateModel(fixture.module) { model ->
                model.contentEntries.filter { it.file == base }.forEach(model::removeContentEntry)
            }
            // A module elsewhere in the project, as a Java one beside it would be.
            assertFalse(runner.canRun(project, task, null))
        }
    }

    @Test
    fun `a failed build shows its diagnostics at their places and its output as text`() = onEdt {
        fakeBy(
            """
            echo "${'$'}PWD/src/main.by -> ${'$'}PWD/build/main.py" >&2
            echo "::error title=ty (invalid-assignment),file=${'$'}PWD/src/main.by,line=2,col=5,endLine=2,endColumn=9::src/main.by:2:5: invalid-assignment: Object is not assignable" >&2
            echo "format ${'$'}TY_OUTPUT_FORMAT, arguments ${'$'}*" >&2
            exit 1
            """.trimIndent(),
        )

        assertEquals(TaskRunnerResults.FAILURE, buildProject())

        assertTrue(events.first() is StartBuildEvent)
        val message = events.filterIsInstance<FileMessageEvent>().single()
        assertEquals(MessageEvent.Kind.ERROR, message.kind)
        assertEquals("Object is not assignable", message.message)
        // `by` counts from 1, the window from 0.
        assertEquals(1, message.filePosition.startLine)
        assertEquals(4, message.filePosition.startColumn)
        val path = message.filePosition.path!!
        assertTrue(path.endsWith(Path.of("src", "main.by")), path.toString())
        val text = events.filterIsInstance<OutputBuildEvent>().joinToString("") { it.message }
        assertTrue("main.by -> " in text, text)
        // The build is asked for one line per diagnostic, and is the build watch mode runs.
        assertTrue("format github, arguments build" in text, text)
        assertFalse("::error" in text, text)
        assertTrue((events.last() as FinishBuildEvent).result is FailureResult)
    }

    @Test
    fun `a build that succeeds says so`() = onEdt {
        fakeBy("echo 'build complete (1 files)' >&2")

        assertEquals(TaskRunnerResults.SUCCESS, buildProject())

        assertTrue((events.last() as FinishBuildEvent).result is SuccessResult)
    }

    @Test
    fun `stop in the window ends the build and the process`() = onEdt {
        val pid = dir.resolve("pid")
        fakeBy("echo $$ > '$pid'; exec sleep 600")

        val task = ProjectTaskManager.getInstance(project).createAllModulesBuildTask(true, project)
        val promise = ByProjectTaskRunner().run(project, ProjectTaskContext(), task)
        val stop = ((events.first() as StartBuildEvent).buildDescriptor as DefaultBuildDescriptor).processHandler!!
        PlatformTestUtil.waitWithEventsDispatching("the build never started", { Files.isRegularFile(pid) && Files.readString(pid).isNotBlank() }, 30)
        val process = ProcessHandle.of(Files.readString(pid).trim().toLong())

        stop.destroyProcess()

        assertEquals(TaskRunnerResults.ABORTED, PlatformTestUtil.waitForPromise(promise, TimeUnit.SECONDS.toMillis(60)))
        assertFalse(process.map { it.isAlive }.orElse(false))
        assertTrue(stop.isProcessTerminated)
    }
}

/** The Build tool window, as far as what it is told. */
private class Recording(project: Project, private val events: MutableList<BuildEvent>) : BuildViewManager(project) {
    override fun onEvent(buildId: Any, event: BuildEvent) {
        events.add(event)
    }
}
