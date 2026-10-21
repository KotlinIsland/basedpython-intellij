package dev.basedpython.pycharm.run.coverage

import com.intellij.coverage.CoverageExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.rt.coverage.data.LineData
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.run.BasedPythonRunConfigurationType
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.byArguments
import dev.basedpython.pycharm.run.test.ByPytest
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * *Run with Coverage*: which configurations it is offered for, what it adds to `by run`, and how the
 * report `by run` writes is read and summarised.
 */
@TestFixtures
class ByCoverageTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `it is offered for a by run and a by test configuration under the coverage executor only`() {
        val project = fixture.project
        val run = ByRunConfiguration(project, BasedPythonRunConfigurationType().runFactory, "run")
        val test = ByTestConfiguration(project, ByTestConfigurationType().testFactory, "test")
        val runner = ByCoverageProgramRunner()

        assertTrue(runner.canRun(CoverageExecutor.EXECUTOR_ID, run))
        assertTrue(runner.canRun(CoverageExecutor.EXECUTOR_ID, test))
        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, run), "a plain Run is not measured")
    }

    @Test
    fun `it is not offered for tests run with plain pytest, which never start by`() {
        val test = ByTestConfiguration(fixture.project, ByTestConfigurationType().testFactory, "test")
        test.options.plainPytest = true

        assertFalse(ByCoverageProgramRunner().canRun(CoverageExecutor.EXECUTOR_ID, test))
        assertFalse(ByCoverageEngine.measurable(test))
    }

    @Test
    fun `the report flag is by run's and comes before the program, pytest included`() {
        // `by run` hands everything after the module to the program, so a flag behind `pytest`
        // would reach pytest as an argument and no report would be written
        val args = byArguments(
            subcommand = "run",
            pythonVersionFlag = "--min-version",
            pythonVersion = "",
            subcommandArgs = ByPytest.arguments("tests/test_calc.py"),
            extraArgs = "-x",
            infrastructureArgs = listOf(ByCoverageProgramRunner.COVERAGE_FLAG, "/tmp/report.lcov"),
            extraArgsForProgram = true,
        )
        assertEquals(
            listOf("run", "--coverage", "/tmp/report.lcov", "--in-build", "pytest", "-v", "tests/test_calc.py", "-x"),
            args,
        )
    }

    @Test
    fun `the LCOV report by run writes loads onto its own files and lines`() {
        val report = Files.createTempFile("by-coverage", ".lcov")
        try {
            Files.writeString(
                report,
                """
                TN:
                SF:/p/pkg/calc.by
                DA:1,1
                DA:2,1
                DA:3,0
                DA:4,1
                LF:4
                LH:3
                end_of_record
                TN:
                SF:/p/pkg/plain.py
                DA:1,1
                DA:2,0
                LF:2
                LH:1
                end_of_record
                TN:
                SF:/p/pkg/__init__.py
                LF:0
                LH:0
                end_of_record
                TN:
                SF:/p/main.by
                DA:1,1
                LF:1
                LH:1
                end_of_record
                """.trimIndent() + "\n",
            )
            val data = checkNotNull(ByCoverageRunner().loadCoverageDataWithReporting(report, null))

            val calc = checkNotNull(data.getClassData(ByCoverageEngine.reportKey("/p/pkg/calc.by")))
            val hits = calc.lines.filterIsInstance<LineData>().associate { it.lineNumber to it.hits }
            assertEquals(mapOf(1 to 1, 2 to 1, 3 to 0, 4 to 1), hits)

            val summary = CoverageSummary.of(data)
            val file = checkNotNull(summary.files["/p/pkg/calc.by"])
            assertEquals(4 to 3, file.totalLineCount to file.coveredLineCount)
            val pkg = checkNotNull(summary.directories["/p/pkg"])
            assertEquals(2 to 2, pkg.totalFilesCount to pkg.coveredFilesCount)
            assertEquals(6 to 4, pkg.totalLineCount to pkg.coveredLineCount)
            assertNull(summary.files["/p/pkg/__init__.py"], "a file with no statements counts as neither")
            val root = checkNotNull(summary.directories["/p"])
            assertEquals(3 to 7, root.totalFilesCount to root.totalLineCount)
        } finally {
            Files.deleteIfExists(report)
        }
    }

    @Test
    fun `a run that wrote no report loads as nothing rather than as a stale one`() {
        val missing = Files.createTempDirectory("by-coverage").resolve("never-written.lcov")
        assertNull(ByCoverageRunner().loadCoverageDataWithReporting(missing, null))
    }
}
