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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.run.ByBuildConfiguration
import dev.basedpython.pycharm.run.ByCheckConfiguration
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import com.intellij.execution.configurations.RunConfiguration
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

    /**
     * A `by` on the target that says where it ran and with what.
     *
     * Asked `by command-line-flags`, it prints [flags], the table its language server would answer
     * `initialize` with, and notes where it was asked in [asked]; with no [flags] it refuses the
     * command as a `by` from before it does, with clap's exit 2.
     */
    private fun fakeBy(flags: String? = null): Path = dir.resolve("bin/by").also {
        it.parent.createDirectories()
        val answer = if (flags == null) {
            "echo \"error: unrecognized subcommand 'command-line-flags'\" >&2; exit 2"
        } else {
            "printf '%s\\n' '$flags'; exit 0"
        }
        it.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = command-line-flags ]; then
              echo "${'$'}(pwd -P)" >> '$asked'
              $answer
            fi
            echo "cwd=${'$'}(pwd -P)"
            echo "args=${'$'}*"
            echo "reporter=${'$'}BY_TEST_REPORTER"
            """.trimIndent() + "\n",
        )
        it.setPosixFilePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    /** Where [fakeBy] notes each directory it was asked its flags in, one a line. */
    private val asked: Path get() = dir.resolve("asked")

    /** The directories [fakeBy] was asked its flags in. */
    private fun askedIn(): List<String> = if (asked.toFile().exists()) asked.toFile().readLines() else emptyList()

    /** Runs [configuration] with Run, as the IDE does, and answers what its `by` printed. */
    private fun runOnTarget(configuration: RunConfiguration): ProcessOutput {
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
            // a test console, like the run tab it is shown in, is the EDT's to dispose
            result.executionConsole?.let { ApplicationManager.getApplication().invokeAndWait { Disposer.dispose(it) } }
        }
        return output
    }

    @Test
    fun `tests, by build and by check run on the target, each as its own subcommand`() {
        Path.of(project.basePath!!).createDirectories()
        withRuntime(fakeBy().toString())
        val onTarget = dir.resolve("target/project").let { it.createDirectories(); it.toRealPath().toString() }
        val type = BasedPythonRunConfigurationType()
        val configurations = mapOf(
            "args=run --in-build pytest -v tests/test_a.py" to ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests")
                .apply { options.paths = "tests/test_a.py" },
            "args=build" to ByBuildConfiguration(project, type.buildFactory, "build"),
            "args=check pkg" to ByCheckConfiguration(project, type.checkFactory, "check").apply { options.paths = "pkg" },
        )
        for ((args, configuration) in configurations) {
            configuration.defaultTargetName = target.displayName
            configuration.checkConfiguration()
            val lines = runOnTarget(configuration).stdout.lines()
            assertTrue(args in lines, "$args: $lines")
            assertTrue("cwd=$onTarget" in lines, "$args: $lines")
        }
        assertEquals(listOf(onTarget), askedIn(), "only the test run, which starts a program, asks the target's by")
    }

    /**
     * The target's `by` says its `by run` runs programs in place, so the tests are named as the project
     * holds them, `.by` included, and `--in-build`, which that `by` refuses, is not passed.
     */
    @Test
    fun `a test run on a target whose by runs in place names the project's tests, without --in-build`() {
        Path.of(project.basePath!!).createDirectories()
        withRuntime(fakeBy(flags = IN_PLACE_TABLE).toString())
        val onTarget = dir.resolve("target/project").let { it.createDirectories(); it.toRealPath().toString() }
        val configuration = ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests").apply {
            defaultTargetName = target.displayName
            options.paths = "tests/test_calc.by::test_add tests/test_b.py"
        }

        val lines = runOnTarget(configuration).stdout.lines()
        assertTrue("args=run --in-place pytest -v tests/test_calc.by::test_add tests/test_b.py" in lines, "$lines")
        assertEquals(listOf(onTarget), askedIn(), "the target's by is asked in the project's copy")
    }

    @Test
    fun `a run on a target is in place exactly when the target's by says so`() {
        Path.of(project.basePath!!).createDirectories()
        withRuntime(fakeBy(flags = IN_PLACE_TABLE).toString())
        val inPlace = runOnTarget(configuration().apply { defaultTargetName = target.displayName }).stdout.lines()
        assertTrue("args=run --in-place pkg.main" in inPlace, "$inPlace")

        fakeBy(flags = """{"run":{"launcher":true}}""")
        val notNamed = runOnTarget(configuration().apply { defaultTargetName = target.displayName }).stdout.lines()
        assertTrue("args=run pkg.main" in notNamed, "$notNamed")
    }

    /** A `by` from before `by command-line-flags` is run as it can run a program: staged. */
    @Test
    fun `a run on a target whose by refuses the question is staged`() {
        Path.of(project.basePath!!).createDirectories()
        withRuntime(fakeBy().toString())
        val run = runOnTarget(configuration().apply { defaultTargetName = target.displayName }).stdout.lines()
        assertTrue("args=run pkg.main" in run, "$run")
        val tests = ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests").apply {
            defaultTargetName = target.displayName
            options.paths = "tests/test_b.py"
        }
        val lines = runOnTarget(tests).stdout.lines()
        assertTrue("args=run --in-build pytest -v tests/test_b.py" in lines, "$lines")
    }

    /** What the user wrote among `by`'s options decides, and the target's `by` is not asked. */
    @Test
    fun `a mode written among by's options on a target is not asked about`() {
        Path.of(project.basePath!!).createDirectories()
        withRuntime(fakeBy(flags = IN_PLACE_TABLE).toString())
        val configuration = configuration().apply {
            defaultTargetName = target.displayName
            options.extraArgs = "--in-place"
        }
        val lines = runOnTarget(configuration).stdout.lines()
        assertTrue("args=run --in-place pkg.main" in lines, "$lines")
        assertEquals(emptyList<String>(), askedIn())
    }

    /**
     * The whole chain with a real `by` on the target, named by `BASEDPYTHON_BY_UNDER_TEST` (skipped
     * otherwise): the plugin asks it its flags and runs the program in place, in the project's copy,
     * with nothing written among the configuration's `by` options.
     */
    @Test
    fun `a real by on the target runs the program in place without being told to`() {
        val by = System.getenv("BASEDPYTHON_BY_UNDER_TEST")?.let(Path::of)?.takeIf { it.toFile().canExecute() }
        org.junit.jupiter.api.Assumptions.assumeTrue(by != null, "set BASEDPYTHON_BY_UNDER_TEST to run a real by")
        val root = Path.of(project.basePath!!).createDirectories()
        root.resolve("pyproject.toml").writeText("[project]\nname = \"probe\"\nversion = \"0\"\n")
        root.resolve("pkg").createDirectories()
        root.resolve("pkg/__init__.py").writeText("")
        root.resolve("pkg/main.by").writeText(
            "import os\n\ndef main():\n    print(\"file=\" + __file__)\n    print(\"cwd=\" + os.getcwd())\n",
        )
        withRuntime(by.toString())
        val onTarget = dir.resolve("target/project").let { it.createDirectories(); it.toRealPath().toString() }
        val configuration = configuration().apply { defaultTargetName = target.displayName }

        val output = runOnTarget(configuration)
        val lines = output.stdout.lines().map { it.trim() }
        assertTrue(lines.any { it == "file=$onTarget/pkg/main.by" || it == "file=${dir.resolve("target/project/pkg/main.by")}" }, "${output.stdout}\n${output.stderr}")
        assertTrue(lines.any { it == "cwd=$onTarget" }, "${output.stdout}\n${output.stderr}")
    }

    /**
     * A test run with a real `by` on the target, named by `BASEDPYTHON_BY_UNDER_TEST`, and a python that
     * has pytest, named by `BASEDPYTHON_PYTHON_UNDER_TEST` (skipped otherwise): the `.by` test is named as
     * the project holds it and runs, where `--in-build` and a staged path are refused by such a `by`.
     */
    @Test
    fun `a real by on the target runs the tests of a test configuration in place, named as the project holds them`() {
        val by = System.getenv("BASEDPYTHON_BY_UNDER_TEST")?.let(Path::of)?.takeIf { it.toFile().canExecute() }
        val python = System.getenv("BASEDPYTHON_PYTHON_UNDER_TEST")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            by != null && python != null,
            "set BASEDPYTHON_BY_UNDER_TEST and BASEDPYTHON_PYTHON_UNDER_TEST, a python with pytest, to run a real by",
        )
        val root = Path.of(project.basePath!!).createDirectories()
        root.resolve("pyproject.toml").writeText("[project]\nname = \"probe\"\nversion = \"0\"\n")
        root.resolve("tests").createDirectories()
        root.resolve("tests/test_calc.by").writeText("def test_add():\n    assert 1 + 1 == 2\n")
        withRuntime(by.toString())
        val configuration = ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests").apply {
            defaultTargetName = target.displayName
            options.paths = "tests/test_calc.by"
            options.envVars = mutableMapOf("PYTHON" to python!!)
        }

        val output = runOnTarget(configuration)
        assertEquals(0, output.exitCode, "${output.stdout}\n${output.stderr}")
        assertTrue("tests/test_calc.by::test_add PASSED" in output.stdout, "${output.stdout}\n${output.stderr}")
    }

    /**
     * A debug session on a target reads whether the run is in place off the run, once the target's
     * `by` has said: hot reload of a program run in place goes through `by/retranspile`, and one of a
     * staged program through `by/transpileForBuild`.
     */
    @Test
    fun `a debug session on a target knows the run is in place when the target's by says so`() {
        Path.of(project.basePath!!).createDirectories()
        withRuntime(fakeBy().toString())
        for ((flags, inPlace) in listOf(IN_PLACE_TABLE to true, null to false)) {
            fakeBy(flags)
            val configuration = configuration().apply { defaultTargetName = target.displayName }
            val environment = ExecutionEnvironmentBuilder.create(project, DefaultDebugExecutor.getDebugExecutorInstance(), configuration).build()
            assertInstanceOf(dev.basedpython.pycharm.debug.ByTargetDebugRunner::class.java, environment.runner)
            val state = environment.state as dev.basedpython.pycharm.run.ByCommandLineState
            dev.basedpython.pycharm.debug.ByDapLaunchArgumentsProvider().getLaunchArguments(project, configuration)
            val setup = dev.basedpython.pycharm.debug.ByDebugSetups.getInstance(project).peek(configuration)!!
            try {
                val onTarget = setup.onTarget!!
                state.targetInfrastructure = onTarget
                val descriptor = dev.basedpython.pycharm.debug.ByDebugAdapterDescriptor(project)
                descriptor.configureProfileState(environment, state)
                environment.prepareTargetEnvironment(state, TargetProgressIndicator.EMPTY)

                assertEquals(inPlace, descriptor.run?.inPlace, "flags $flags")
                assertEquals(inPlace, "--in-place" in state.buildCommand(onTarget.arguments()), "flags $flags")
            } finally {
                setup.delete()
            }
        }
    }

    @Test
    fun `a test run on the target still reports to the test tree`() {
        Path.of(project.basePath!!).createDirectories()
        withRuntime(fakeBy().toString())
        val configuration = ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests")
            .apply { defaultTargetName = target.displayName }

        assertTrue("reporter=teamcity" in runOnTarget(configuration).stdout.lines())
    }

    @Test
    fun `coverage is refused on a target for every by configuration`() {
        withRuntime("by")
        val type = BasedPythonRunConfigurationType()
        val configurations = listOf(
            configuration(),
            ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests"),
            ByBuildConfiguration(project, type.buildFactory, "build"),
            ByCheckConfiguration(project, type.checkFactory, "check"),
        )
        val coverage = object : com.intellij.execution.Executor() {
            override fun getToolWindowId() = "Run"
            override fun getToolWindowIcon() = AllIcons.General.RunWithCoverage
            override fun getIcon() = AllIcons.General.RunWithCoverage
            override fun getDisabledIcon() = AllIcons.General.RunWithCoverage
            override fun getDescription() = "coverage"
            override fun getActionName() = "Coverage"
            override fun getId() = "Coverage"
            override fun getStartActionText() = "Run with Coverage"
            override fun getContextActionId() = "RunCoverage"
            override fun getHelpId(): String? = null
        }
        for (configuration in configurations) {
            configuration.defaultTargetName = target.displayName
            val environment = ExecutionEnvironmentBuilder(project, coverage)
                .runProfile(configuration)
                .runner(ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)!!)
                .build()
            val e = assertThrows<CantRunException> { configuration.getState(environment.executor, environment) }
            assertTrue(e.message!!.contains("dir target"), e.message)
        }
    }

    @Test
    fun `debugging on a target goes through the runner that prepares it, and only with bpd`() {
        withRuntime("by")
        val configuration = configuration().apply { defaultTargetName = target.displayName }
        assertInstanceOf(
            dev.basedpython.pycharm.debug.ByTargetDebugRunner::class.java,
            ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, configuration),
        )
        val local = configuration()
        assertFalse(ProgramRunner.getRunner(DefaultDebugExecutor.EXECUTOR_ID, local) is dev.basedpython.pycharm.debug.ByTargetDebugRunner)

        val settings = dev.basedpython.pycharm.settings.BasedPythonSettings.getInstance(project)
        val backend = settings.debugBackend
        try {
            settings.debugBackend = dev.basedpython.pycharm.debug.bpd.ByDebugBackend.DEBUGPY
            val e = assertThrows<com.intellij.execution.ExecutionException> {
                dev.basedpython.pycharm.debug.ByDapLaunchArgumentsProvider().getLaunchArguments(project, configuration)
            }
            assertTrue("bpd" in e.message!!, e.message)
        } finally {
            settings.debugBackend = backend
        }
    }

    @Test
    fun `plain pytest, which runs this machine's python, is refused on a target`() {
        withRuntime("by")
        val configuration = ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests").apply {
            defaultTargetName = target.displayName
            options.plainPytest = true
        }
        assertThrows<RuntimeConfigurationError> { configuration.checkConfiguration() }
        val environment = ExecutionEnvironmentBuilder.create(project, DefaultRunExecutor.getRunExecutorInstance(), configuration).build()
        assertThrows<CantRunException> { configuration.getState(environment.executor, environment) }
    }

    @Test
    fun `a test configuration on this machine keeps the default runner`() {
        val configuration = ByTestConfiguration(project, ByTestConfigurationType().testFactory, "tests")
        val runner = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration)
        assertFalse(runner is ByTargetProgramRunner, "a local run must not go through the target runner: $runner")
    }

    @Test
    fun `a configuration left on this machine keeps the default runner`() {
        val runner = ProgramRunner.getRunner(DefaultRunExecutor.EXECUTOR_ID, configuration())
        assertFalse(runner is ByTargetProgramRunner, "a local run must not go through the target runner: $runner")
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
        config.bpdPath = "/opt/bpd"
        assertEquals(null, ByLanguageRuntimeType().createIntrospector(config), "configured paths are not second-guessed")
    }

    @Test
    fun `the runtime finds bpd on the target's PATH too, and leaves a configured by alone`() {
        val config = ByLanguageRuntimeConfiguration().apply { byPath = "/opt/by" }
        val subject = object : LanguageRuntimeType.Introspectable() {
            override val targetPlatform = CompletableFuture.completedFuture(TargetPlatform())
            override fun promiseExecuteScript(script: List<String>) = CompletableFuture.completedFuture(
                when (script) {
                    listOf("sh", "-c", "command -v by") -> ProcessOutput("/usr/local/bin/by\n", "", 0, false, false)
                    listOf("sh", "-c", "command -v bpd") -> ProcessOutput("/usr/local/bin/bpd\n", "", 0, false, false)
                    else -> ProcessOutput("", "", 127, false, false)
                },
            )
        }

        val found = ByLanguageRuntimeType().createIntrospector(config)!!.introspect(subject).get(10, TimeUnit.SECONDS)
        assertEquals("/opt/by", found.byPath)
        assertEquals("/usr/local/bin/bpd", found.bpdPath)
    }

    @Test
    fun `the runtime's path survives a copy`() {
        val type = ByLanguageRuntimeType()
        val original = ByLanguageRuntimeConfiguration().apply { byPath = "/opt/venv/bin/by" }

        assertEquals("/opt/venv/bin/by", type.duplicateConfig(original).byPath)
    }
}

/** What `by command-line-flags` prints for a `by` that runs every program in place (`by` 4a8b90853). */
private const val IN_PLACE_TABLE =
    """{"repl":{"events":true,"inPlace":true,"module":true,"sessionFile":true},""" +
        """"run":{"coverage":true,"inPlace":true,"launcher":true,"notes":true,"profile":true}}"""

/** A run target that is a directory here: the project is copied to [DirTargetConfiguration.projectRootOnTarget]. */
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

