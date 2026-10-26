package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.runner.GeneralToSMTRunnerEventsConvertor
import com.intellij.execution.testframework.sm.runner.SMTRunnerConsoleProperties
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.run.test.ByRerunTargets
import dev.basedpython.pycharm.run.test.ByTestConfiguration
import dev.basedpython.pycharm.run.test.ByTestConfigurationType
import dev.basedpython.pycharm.run.test.node.ByTestNodeService
import dev.basedpython.pycharm.run.test.node.ByTestState
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A run of `by run pytest -v` under `BY_TEST_REPORTER=teamcity`, through the console's converter into a
 * results tree, as the SM runner builds it.
 *
 * The output is what `by` (c7ed5b05b6aa) printed for a `tests/test_k.by`, paths shortened: its
 * diagnostics, the reporter's `enteredTheMatrix`, pytest's own `-v` lines, and the service messages,
 * some of them written on the end of a `-v` line. Once the reporter has spoken, the `-v` lines are
 * output and nothing else: reading them as well put every test in the tree twice.
 */
@TestFixtures
class ByTestReporterOutputTest {

    private val fixture by codeInsightFixture()

    private val output = """
          --> tests/test_k.by:62:12
           |
        62 |     assert flag
           |            ^^^^
        info: `False` is always falsy

        Found 3 diagnostics
        ##teamcity[enteredTheMatrix]
        ============================= test session starts ==============================
        configfile: pyproject.toml
        plugins: by-pytest-0, anyio-4.15.1
        collecting ... collected 13 items
        ##teamcity[testCount count='13']
        ##teamcity[testSuiteStarted name='tests/test_k.by' locationHint='file:///p/tests/test_k.by']
        ##teamcity[testStarted name='test_ok' locationHint='file:///p/tests/test_k.by:4' metainfo='tests/test_k.py::test_ok' captureStandardOutput='false']

        tests/test_k.py::test_ok PASSED                                          [  7%]##teamcity[testFinished name='test_ok' duration='0']
        ##teamcity[testStarted name='test_eq' locationHint='file:///p/tests/test_k.by:8' metainfo='tests/test_k.py::test_eq' captureStandardOutput='false']

        tests/test_k.py::test_eq FAILED                                          [ 15%]##teamcity[testFailed name='test_eq' message='assert 3 == 4' details='def test_eq():|n        let total = add(1, 2)|n>       assert total == 4|nE       assert 3 == 4|n|n/p/tests/test_k.by:10: AssertionError' type='comparisonFailure' expected='4' actual='3']
        ##teamcity[testFinished name='test_eq' duration='0']
        ##teamcity[testSuiteStarted name='TestGroup' locationHint='file:///p/tests/test_k.by:21']
        ##teamcity[testStarted name='test_in_class' locationHint='file:///p/tests/test_k.by:22' metainfo='tests/test_k.py::TestGroup::test_in_class' captureStandardOutput='false']

        tests/test_k.py::TestGroup::test_in_class FAILED                         [ 38%]##teamcity[testFailed name='test_in_class' message='AssertionError: assert {|'a|': 1} == {|'a|': 2}|n  |n  Differing items:|n  {|'a|': 1} != {|'a|': 2}|n  |n  Full diff:|n    {|n  -     |'a|': 2,...|n  |n  ...Full output truncated (4 lines hidden), use |'-vv|' to show' details='self = <tests.test_k.TestGroup object at 0x106fb3250>|n|n    def test_in_class(self):|n>       assert {"a": 1} == {"a": 2}|nE       AssertionError: assert {|'a|': 1} == {|'a|': 2}|nE         |nE         Differing items:|nE         {|'a|': 1} != {|'a|': 2}|nE         |nE         Full diff:|nE           {|nE         -     |'a|': 2,...|nE         |nE         ...Full output truncated (4 lines hidden), use |'-vv|' to show|n|n/p/tests/test_k.by:23: AssertionError' type='comparisonFailure' expected='{|'a|': 2}' actual='{|'a|': 1}']
        ##teamcity[testFinished name='test_in_class' duration='0']
        ##teamcity[testStarted name='test_raise' locationHint='file:///p/tests/test_k.by:25' metainfo='tests/test_k.py::TestGroup::test_raise' captureStandardOutput='false']

        tests/test_k.py::TestGroup::test_raise FAILED                            [ 46%]##teamcity[testFailed name='test_raise' message='ValueError: boom' details='self = <tests.test_k.TestGroup object at 0x106fb3390>|n|n    def test_raise(self):|n>       raise ValueError("boom")|nE       ValueError: boom|n|n/p/tests/test_k.by:26: ValueError']
        ##teamcity[testFinished name='test_raise' duration='0']
        ##teamcity[testSuiteFinished name='TestGroup']
        ##teamcity[testStarted name='test_out' locationHint='file:///p/tests/test_k.by:29' metainfo='tests/test_k.py::test_out' captureStandardOutput='false']

        tests/test_k.py::test_out PASSED                                         [ 53%]##teamcity[testStdOut name='test_out' out='to stdout|n']
        ##teamcity[testFinished name='test_out' duration='0']
        ##teamcity[testSuiteFinished name='tests/test_k.by']
    """.trimIndent() + "\n"

    private fun run(text: String): SMTestProxy.SMRootTestProxy {
        val project = fixture.project
        val type = ByTestConfigurationType.getInstance()
        val configuration = type.configurationFactories.single().createTemplateConfiguration(project) as ByTestConfiguration
        val properties = SMTRunnerConsoleProperties(configuration, "pytest (by)", DefaultRunExecutor.getRunExecutorInstance())
        Disposer.register(fixture.testRootDisposable, properties)
        val root = SMTestProxy.SMRootTestProxy()
        // as the run's console does, which is how a tree is known to be a `.by` test run's
        root.setTestConsoleProperties(properties)
        val processor = GeneralToSMTRunnerEventsConvertor(project, root, "pytest (by)")
        val converter = ByTestEventsConverter("pytest (by)", properties)
        Disposer.register(fixture.testRootDisposable, converter)
        Disposer.register(fixture.testRootDisposable, processor)
        converter.setProcessor(processor)
        processor.onStartTesting()
        for (line in text.lineSequence()) converter.process(line + "\n", ProcessOutputTypes.STDOUT)
        converter.flushBufferOnProcessTermination(1)
        processor.onFinishTesting()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return root
    }

    private fun SMTestProxy.find(name: String): SMTestProxy = allTests.filterIsInstance<SMTestProxy>().single { it.name == name }

    @Test
    fun `the reporter's messages build the tree, once each`() = onEdt {
        val root = run(output)
        val tests = root.allTests.filter { it.isLeaf }.map { it.name }
        assertEquals(listOf("test_ok", "test_eq", "test_in_class", "test_raise", "test_out"), tests)
        assertEquals(listOf("tests/test_k.by"), root.children.map { it.name })
        assertEquals(listOf("test_ok", "test_eq", "TestGroup", "test_out"), root.children.single().children.map { it.name })
    }

    @Test
    fun `a failed == says what was expected and what came`() = onEdt {
        val failed = run(output).find("test_eq")
        assertTrue(failed.isDefect)
        assertEquals("assert 3 == 4", failed.errorMessage)
        val diff = failed.diffViewerProviders.single()
        assertEquals("4", diff.left)
        assertEquals("3", diff.right)
        assertTrue(failed.stacktrace!!.endsWith("/p/tests/test_k.by:10: AssertionError"), failed.stacktrace)
    }

    @Test
    fun `each test is at its by line and keeps its pytest node id`() = onEdt {
        val root = run(output)
        val test = root.find("test_in_class")
        assertEquals("file:///p/tests/test_k.by:22", test.locationUrl)
        assertEquals("tests/test_k.py::TestGroup::test_in_class", test.metainfo)
        assertEquals("tests/test_k.py::TestGroup::test_in_class", ByRerunTargets.nodeId(test.locationUrl, test.metainfo))
    }

    @Test
    fun `the node view hears each test's outcome by its node id`() = onEdt {
        run(output)
        val outcomes = ByTestNodeService.getInstance(fixture.project).outcomes
        assertEquals(ByTestState.FAILED, outcomes["tests/test_k.py::test_eq"])
        assertEquals(ByTestState.PASSED, outcomes["tests/test_k.py::test_out"])
        assertEquals(ByTestState.FAILED, outcomes["tests/test_k.py::TestGroup::test_raise"])
    }

    @Test
    fun `what a test printed is its output`() = onEdt {
        val out = StringBuilder()
        run(output).find("test_out").printOn(object : com.intellij.execution.testframework.Printer {
            override fun print(text: String, contentType: com.intellij.execution.ui.ConsoleViewContentType) { out.append(text) }
            override fun onNewAvailable(printable: com.intellij.execution.testframework.Printable) = printable.printOn(this)
            override fun printHyperlink(text: String, info: com.intellij.execution.filters.HyperlinkInfo?) { out.append(text) }
            override fun mark() = Unit
        })
        assertTrue(out.contains("to stdout"), out.toString())
    }

    @Test
    fun `without the reporter the -v lines still build the tree`() = onEdt {
        val withoutReporter = output.lineSequence()
            .filterNot { it.startsWith("##teamcity[") }
            .map { it.substringBefore("##teamcity[") }
            .joinToString("\n")
        val root = run(withoutReporter)
        assertEquals(listOf("test_ok", "test_eq", "test_in_class", "test_raise", "test_out"), root.allTests.filter { it.isLeaf }.map { it.name })
        assertEquals("by_test://tests/test_k.py::test_eq", root.find("test_eq").locationUrl)
    }
}
