package dev.basedpython.pycharm.run.target

import com.intellij.execution.CantRunException
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.TargetEnvironmentType
import com.intellij.execution.target.TargetEnvironmentsManager
import com.intellij.execution.target.TargetPlatform
import com.intellij.execution.target.TargetProgressIndicator
import com.intellij.execution.target.java.JavaLanguageRuntimeConfiguration
import com.intellij.execution.target.local.LocalTargetEnvironmentRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.run.ByRunConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JPanel
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText

/**
 * A `by run` configuration on a run target, through the platform's own target protocol.
 *
 * The target here is a directory on this machine that the project is *copied* into — the platform's
 * local environment copies an upload root that has a persistent path on the target — so what reaches
 * the program is the copy, in the copy's paths, which is what a Docker or SSH target does with its
 * own. Docker and SSH themselves are verified live, not here.
 */
@TestApplication
@TestFixtures
class ByRunOnTargetTest {

    /**
     * A project of its own rather than the shared light one, whose directory is the test JVM's whole
     * temporary directory — and so contains the directory the project is copied into.
     */
    private val project by projectFixture()

    @TempDir
    lateinit var dir: Path

    private val disposable = Disposer.newDisposable("run target test")

    private lateinit var target: DirTargetConfiguration

    @BeforeEach
    fun registerTarget() {
        TargetEnvironmentType.EXTENSION_NAME.point.registerExtension(DirTargetType(), disposable)
        target = DirTargetConfiguration().apply {
            displayName = "dir target"
            projectRootOnTarget = dir.resolve("target/project").toString()
        }
        TargetEnvironmentsManager.getInstance(project).addTarget(target)
    }

    @AfterEach
    fun removeTarget() {
        TargetEnvironmentsManager.getInstance(project).removeTarget(target)
        Disposer.dispose(disposable)
    }

    private fun configuration(module: String = "pkg.main"): ByRunConfiguration {
        val type = BasedPythonRunConfigurationType()
        return ByRunConfiguration(project, type.runFactory, "run").apply { options.module = module }
    }

    private fun withRuntime(byPath: String) {
        target.addLanguageRuntime(ByLanguageRuntimeConfiguration().also { it.byPath = byPath })
    }

    @Test
    fun `by runs on the target in the project's copy, with the target's by`() {
        val root = Path.of(project.basePath!!).createDirectories()
        root.resolve("pkg").createDirectories()
        root.resolve("pkg/main.by").writeText("print('hi')\n")
        val by = dir.resolve("bin/by").also {
            it.parent.createDirectories()
            it.writeText(
                """
                #!/bin/sh
                echo "cwd=${'$'}(pwd -P)"
                echo "args=${'$'}*"
                echo "pythonpath=${'$'}PYTHONPATH"
                echo "copied=${'$'}(cat pkg/main.by)"
                """.trimIndent() + "\n",
            )
            it.setPosixFilePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        }
        withRuntime(by.toString())
        val configuration = configuration().apply { defaultTargetName = target.displayName }

        val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        assertInstanceOf(ByTargetProgramRunner::class.java, environment.runner)
        val state = environment.state!!
        environment.prepareTargetEnvironment(state, TargetProgressIndicator.EMPTY)
        val result = state.execute(environment.executor, environment.runner)!!
        val output = ProcessOutput()
        try {
            val handler = result.processHandler!!
            handler.addProcessListener(object : CapturingProcessAdapter(output) {})
            handler.startNotify()
            assertTrue(handler.waitFor(TimeUnit.SECONDS.toMillis(30)), "by on the target did not finish")
        } finally {
            // the run console is the run tab's to dispose, and there is no run tab here
            result.executionConsole?.let(Disposer::dispose)
        }

        val onTarget = dir.resolve("target/project").toRealPath().toString()
        val lines = output.stdout.lines()
        assertEquals("", output.stderr)
        assertTrue("cwd=$onTarget" in lines, output.stdout)
        assertTrue("args=run pkg.main" in lines, output.stdout)
        assertTrue(lines.any { it == "pythonpath=$onTarget" || it == "pythonpath=${dir.resolve("target/project")}" }, output.stdout)
        assertTrue("copied=print('hi')" in lines, output.stdout)
    }

    @Test
    fun `a configuration left on this machine keeps the default runner`() {
        val runner = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration())
        assertFalse(runner is ByTargetProgramRunner, "a local run must not go through the target runner: $runner")
    }

    @Test
    fun `debugging on a target is refused before the debugger starts`() {
        withRuntime("by")
        val configuration = configuration().apply { defaultTargetName = target.displayName }
        val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), configuration)
            .runner(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)!!)
            .build()

        val e = assertThrows<CantRunException> { configuration.getState(environment.executor, environment) }
        assertTrue(e.message!!.contains("dir target"), e.message)
    }

    /**
     * On a target the local `by` is irrelevant — this test machine has none — and what is checked is
     * the target's runtime instead.
     */
    @Test
    fun `a target run is checked against the target's runtime, not a local by`() {
        val configuration = configuration().apply { defaultTargetName = target.displayName }

        target.addLanguageRuntime(JavaLanguageRuntimeConfiguration().also { it.homePath = "/usr/lib/jvm/java" })
        val javaOnly = assertThrows<RuntimeConfigurationError> { configuration.checkConfiguration() }
        assertTrue("has no basedpython runtime" in javaOnly.localizedMessage, javaOnly.localizedMessage)

        withRuntime("")
        val blank = assertThrows<RuntimeConfigurationException> { configuration.checkConfiguration() }
        assertTrue("Set where by is" in blank.localizedMessage, blank.localizedMessage)

        target.runtimes.findByType(ByLanguageRuntimeConfiguration::class.java)!!.byPath = "by"
        configuration.checkConfiguration()
    }

    @Test
    fun `only a target with a basedpython runtime is offered`() {
        val configuration = configuration()
        assertFalse(configuration.canRunOn(target))
        withRuntime("by")
        assertTrue(configuration.canRunOn(target))
        assertInstanceOf(ByLanguageRuntimeType::class.java, configuration.defaultLanguageRuntimeType)
    }

    @Test
    fun `the target choice is persisted with the configuration`() {
        val configuration = configuration().apply { defaultTargetName = target.displayName }
        val element = org.jdom.Element("configuration")
        configuration.writeExternal(element)

        val read = configuration()
        read.readExternal(element)
        assertEquals("dir target", read.defaultTargetName)
    }

    @Test
    fun `the runtime finds by on the target's PATH`() {
        val config = ByLanguageRuntimeConfiguration()
        val introspector = ByLanguageRuntimeType().createIntrospector(config)!!
        val subject = object : LanguageRuntimeType.Introspectable() {
            override val targetPlatform = CompletableFuture.completedFuture(TargetPlatform())
            override fun promiseExecuteScript(script: List<String>) = CompletableFuture.completedFuture(
                ProcessOutput("/usr/local/bin/by\n", "", 0, false, false).takeIf { script == listOf("sh", "-c", "command -v by") }
                    ?: ProcessOutput("", "", 127, false, false),
            )
        }

        assertEquals("/usr/local/bin/by", introspector.introspect(subject).get(10, TimeUnit.SECONDS).byPath)
    }

    @Test
    fun `a target without by leaves the path for the user`() {
        val config = ByLanguageRuntimeConfiguration()
        val subject = object : LanguageRuntimeType.Introspectable() {
            override val targetPlatform = CompletableFuture.completedFuture(TargetPlatform())
            override fun promiseExecuteScript(script: List<String>) =
                CompletableFuture.completedFuture(ProcessOutput("", "", 1, false, false))
        }

        assertEquals("", ByLanguageRuntimeType().createIntrospector(config)!!.introspect(subject).get(10, TimeUnit.SECONDS).byPath)
        config.byPath = "/opt/by"
        assertEquals(null, ByLanguageRuntimeType().createIntrospector(config), "a configured path is not second-guessed")
    }

    @Test
    fun `the runtime's path survives a copy`() {
        val type = ByLanguageRuntimeType()
        val original = ByLanguageRuntimeConfiguration().apply { byPath = "/opt/venv/bin/by" }

        assertEquals("/opt/venv/bin/by", type.duplicateConfig(original).byPath)
    }
}

/** A run target that is a directory here: the project is copied to [projectRootOnTarget]. */
private class DirTargetType : TargetEnvironmentType<DirTargetConfiguration>(ID) {
    override val displayName = "Directory"
    override val icon: Icon = AllIcons.Nodes.Folder
    override fun createSerializer(config: DirTargetConfiguration): PersistentStateComponent<*> = config
    override fun createDefaultConfig() = DirTargetConfiguration()
    override fun duplicateConfig(config: DirTargetConfiguration) = duplicatePersistentComponent(this, config)

    /** Leaves the request's `projectPathOnTarget` blank, as Docker's does: the path is the configuration's. */
    override fun createEnvironmentRequest(project: Project?, config: DirTargetConfiguration): TargetEnvironmentRequest =
        object : LocalTargetEnvironmentRequest() {
            override val configuration: TargetEnvironmentConfiguration get() = config
        }

    override fun createConfigurable(
        project: Project,
        config: DirTargetConfiguration,
        defaultLanguage: LanguageRuntimeType<*>?,
        parentConfigurable: Configurable?,
    ): Configurable = object : Configurable {
        override fun getDisplayName() = "Directory"
        override fun createComponent() = JPanel()
        override fun isModified() = false
        override fun apply() = Unit
    }

    companion object {
        const val ID = "basedpython.test.directory"
    }
}

private class DirTargetConfiguration :
    TargetEnvironmentConfiguration(DirTargetType.ID),
    PersistentStateComponent<DirTargetConfiguration.State> {

    override var projectRootOnTarget: String = ""

    override fun getState() = State().also { it.root = projectRootOnTarget }
    override fun loadState(state: State) {
        projectRootOnTarget = state.root.orEmpty()
    }

    class State : BaseState() {
        var root: String? by string()
    }
}

