package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.testframework.sm.runner.GeneralIdBasedToSMTRunnerEventsConvertor
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
 * A run of `manage.py test -v 2` through the Django console's converter into a results tree built by
 * node id, as the SM runner builds one: the output django 5.2 printed under python 3.15 for two classes,
 * every failure's traceback after the last test.
 */
@TestFixtures
class ByDjangoTestOutputTest {

    private val fixture by codeInsightFixture()

    private val output = """
        Found 4 test(s).
          Applying sessions.0001_initial...test_create (polls.tests.test_views.QuestionTests.test_create) ... ok
        test_fail (polls.tests.test_views.QuestionTests.test_fail)
        Counts wrongly on purpose. ... FAIL
        test_ok (polls.tests.test_views.OtherTests.test_ok) ... ok
        test_skip (polls.tests.test_views.OtherTests.test_skip) ... skipped 'not today'

        ======================================================================
        FAIL: test_fail (polls.tests.test_views.QuestionTests.test_fail)
        Counts wrongly on purpose.
        ----------------------------------------------------------------------
        Traceback (most recent call last):
          File "/p/polls/tests/test_views.py", line 14, in test_fail
            self.assertEqual(Question.objects.count(), 2)
        AssertionError: 0 != 2

        ----------------------------------------------------------------------
        Ran 4 tests in 0.002s

        FAILED (failures=1, skipped=1)
    """.trimIndent()

    private fun run(text: String): SMTestProxy.SMRootTestProxy {
        val project = fixture.project
        val type = ByTestConfigurationType.getInstance()
        val configuration = type.configurationFactories.single().createTemplateConfiguration(project) as ByTestConfiguration
        val properties = SMTRunnerConsoleProperties(configuration, FRAMEWORK, DefaultRunExecutor.getRunExecutorInstance())
        properties.isIdBasedTestTree = true
        Disposer.register(fixture.testRootDisposable, properties)
        val root = SMTestProxy.SMRootTestProxy()
        root.setTestConsoleProperties(properties)
        val processor = GeneralIdBasedToSMTRunnerEventsConvertor(project, root, FRAMEWORK)
        val converter = ByDjangoTestEventsConverter(FRAMEWORK, properties, "")
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
    fun `tests sit under their file and class`() = onEdt {
        val root = run(output)
        assertEquals(listOf("polls/tests/test_views.py"), root.children.map { it.name })
        val file = root.children.single()
        assertEquals(listOf("QuestionTests", "OtherTests"), file.children.map { it.name })
        assertEquals(listOf("test_create", "test_fail"), file.children[0].children.map { it.name })
        assertEquals(listOf("test_ok", "test_skip"), file.children[1].children.map { it.name })
    }

    @Test
    fun `a failure carries the traceback printed after the last test`() = onEdt {
        val failed = run(output).find("test_fail")
        assertTrue(failed.isDefect)
        assertEquals("AssertionError: 0 != 2", failed.errorMessage)
        assertTrue(failed.stacktrace!!.contains("self.assertEqual(Question.objects.count(), 2)"), failed.stacktrace)
        val root = run(output)
        assertTrue(root.find("test_create").isPassed)
        assertTrue(root.find("test_skip").isIgnored)
    }

    @Test
    fun `each test keeps its node id, for rerun and the node view`() = onEdt {
        val root = run(output)
        val test = root.find("test_fail")
        assertEquals("by_test://polls/tests/test_views.py::QuestionTests::test_fail", test.locationUrl)
        assertEquals("polls/tests/test_views.py::QuestionTests::test_fail", ByRerunTargets.nodeId(test.locationUrl, test.metainfo))
        val outcomes = ByTestNodeService.getInstance(fixture.project).outcomes
        assertEquals(ByTestState.FAILED, outcomes["polls/tests/test_views.py::QuestionTests::test_fail"])
        assertEquals(ByTestState.PASSED, outcomes["polls/tests/test_views.py::OtherTests::test_ok"])
    }

    private companion object {
        const val FRAMEWORK = "Django tests (by)"
    }
}
