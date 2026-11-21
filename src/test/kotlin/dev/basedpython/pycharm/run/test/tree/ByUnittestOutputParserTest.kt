package dev.basedpython.pycharm.run.test.tree

import dev.basedpython.pycharm.run.test.ByDjangoTests
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `manage.py test -v 2` as django 5.2 printed it under python 3.15, runner and program output
 * interleaved as they reached the console: two test classes, a pass, an error, a failure with a
 * docstring that printed, a subtest failing, a skip, and the first status written on the end of the
 * migration progress.
 */
class ByUnittestOutputParserTest {

    private val output = """
        Creating test database for alias 'default' ('file:memorydb_default?mode=memory&cache=shared')...
        Found 6 test(s).
        Operations to perform:
          Apply all migrations: admin, auth, contenttypes, polls, sessions
        Running migrations:
          Applying polls.0001_initial... OK
          Applying sessions.0001_initial...test_create (polls.tests.test_views.QuestionTests.test_create) ... ok
        test_error (polls.tests.test_views.QuestionTests.test_error) ... ERROR
        test_fail (polls.tests.test_views.QuestionTests.test_fail)
        Counts wrongly on purpose. ... FAIL
        test_sub (polls.tests.test_views.QuestionTests.test_sub) ...
          test_sub (polls.tests.test_views.QuestionTests.test_sub) (i=2) ... FAIL
        test_ok (polls.tests.test_views.OtherTests.test_ok) ... ok
        test_skip (polls.tests.test_views.OtherTests.test_skip) ... skipped 'not today'

        ======================================================================
        ERROR: test_error (polls.tests.test_views.QuestionTests.test_error)
        ----------------------------------------------------------------------
        Traceback (most recent call last):
          File "/p/polls/tests/test_views.py", line 17, in test_error
            raise RuntimeError("boom")
        RuntimeError: boom

        ======================================================================
        FAIL: test_fail (polls.tests.test_views.QuestionTests.test_fail)
        Counts wrongly on purpose.
        ----------------------------------------------------------------------
        Traceback (most recent call last):
          File "/p/polls/tests/test_views.py", line 14, in test_fail
            self.assertEqual(Question.objects.count(), 2)
        AssertionError: 0 != 2

        ======================================================================
        FAIL: test_sub (polls.tests.test_views.QuestionTests.test_sub) (i=2)
        ----------------------------------------------------------------------
        Traceback (most recent call last):
          File "/p/polls/tests/test_views.py", line 22, in test_sub
            self.assertLess(i, 2)
        AssertionError: 2 not less than 2

        ----------------------------------------------------------------------
        Ran 6 tests in 0.002s

        FAILED (failures=2, errors=1, skipped=1)
        Destroying test database for alias 'default' ('file:memorydb_default?mode=memory&cache=shared')...
         OK
        System check identified no issues (0 silenced).
        printed by test_fail
    """.trimIndent()

    private fun parse(text: String, base: String = ""): List<ByUnittestEvent> {
        val parser = ByUnittestOutputParser({ ByDjangoTests.moduleFile(it, base) })
        return text.lines().flatMap(parser::parseLine) + parser.finish()
    }

    private val file = "polls/tests/test_views.py"

    @Test
    fun `every test lands under its file and class, by node id`() {
        val started = parse(output).filterIsInstance<ByUnittestEvent.TestStarted>()
        assertEquals(
            listOf("test_create", "test_error", "test_fail", "test_sub", "test_ok", "test_skip").map { name ->
                val owner = if (name in setOf("test_ok", "test_skip")) "OtherTests" else "QuestionTests"
                ByUnittestEvent.TestStarted("$file::$owner::$name", "$file::$owner", name, "by_test://$file::$owner::$name")
            },
            started,
        )
        val suites = parse(output).filterIsInstance<ByUnittestEvent.SuiteStarted>()
        assertEquals(
            listOf(
                ByUnittestEvent.SuiteStarted(file, "0", file, "by_test://$file"),
                ByUnittestEvent.SuiteStarted("$file::QuestionTests", file, "QuestionTests", "by_test://$file::QuestionTests"),
                ByUnittestEvent.SuiteStarted("$file::OtherTests", file, "OtherTests", "by_test://$file::OtherTests"),
            ),
            suites,
        )
    }

    @Test
    fun `a failure carries the traceback printed after every test`() {
        val failed = parse(output).filterIsInstance<ByUnittestEvent.TestFailed>().associateBy { it.name }
        assertEquals(setOf("test_error", "test_fail", "test_sub"), failed.keys)
        assertEquals("RuntimeError: boom", failed.getValue("test_error").message)
        assertEquals("AssertionError: 0 != 2", failed.getValue("test_fail").message)
        assertTrue(failed.getValue("test_fail").details.contains("self.assertEqual(Question.objects.count(), 2)"))
        assertEquals("(i=2) AssertionError: 2 not less than 2", failed.getValue("test_sub").message)
    }

    @Test
    fun `passes finish at once and failures once their traceback is read`() {
        val events = parse(output)
        val finishedAt = events.withIndex().filter { it.value is ByUnittestEvent.TestFinished }
            .associate { (it.value as ByUnittestEvent.TestFinished).name to it.index }
        val okStarted = events.indexOfFirst { it is ByUnittestEvent.TestStarted && it.name == "test_ok" }
        assertTrue(finishedAt.getValue("test_create") < okStarted)
        assertTrue(finishedAt.getValue("test_fail") > okStarted)
        assertEquals("not today", events.filterIsInstance<ByUnittestEvent.TestIgnored>().single().message)
        // every node closes, suites after their tests
        assertEquals(6, finishedAt.size)
        assertEquals(3, events.count { it is ByUnittestEvent.SuiteFinished })
        assertTrue(events.last() is ByUnittestEvent.SuiteFinished)
    }

    @Test
    fun `what the program printed before a status is its output`() {
        val output = parse(output).filterIsInstance<ByUnittestEvent.Output>()
        assertEquals(listOf(ByUnittestEvent.Output("  Applying sessions.0001_initial...")), output)
    }

    @Test
    fun `before python 3·11 the qualifier is the class alone`() {
        val started = parse("test_ok (polls.tests.test_views.OtherTests) ... ok\n\nRan 1 test in 0.000s")
            .filterIsInstance<ByUnittestEvent.TestStarted>().single()
        assertEquals("$file::OtherTests::test_ok", started.id)
    }

    /** As django printed it, discovered and named by a label, for `polls/tests/test_bad.py` holding `import nope`. */
    @Test
    fun `a module that failed to import is that file's failure`() {
        fun failure(name: String, labels: List<String>): ByUnittestEvent.TestFailed {
            val text = """
                $name (unittest.loader._FailedTest.$name) ... ERROR

                ======================================================================
                ERROR: $name (unittest.loader._FailedTest.$name)
                ----------------------------------------------------------------------
                ImportError: Failed to import test module: $name
                Traceback (most recent call last):
                  File "/p/polls/tests/test_bad.py", line 1, in <module>
                    import nope  # noqa
                    ^^^^^^^^^^^
                ModuleNotFoundError: No module named 'nope'


                ----------------------------------------------------------------------
                Ran 1 test in 0.000s
            """.trimIndent()
            val parser = ByUnittestOutputParser({ ByDjangoTests.moduleFile(it, "") }, labels = labels)
            return (text.lines().flatMap(parser::parseLine) + parser.finish()).filterIsInstance<ByUnittestEvent.TestFailed>().single()
        }
        for (failed in listOf(failure("polls.tests.test_bad", emptyList()), failure("test_bad", listOf("polls.tests.test_bad")))) {
            assertEquals("polls/tests/test_bad.py::test_bad", failed.id)
            assertEquals("ModuleNotFoundError: No module named 'nope'", failed.message)
        }
    }

    @Test
    fun `a manage py in a directory names files under it`() {
        val started = parse("test_ok (polls.tests.OtherTests.test_ok) ... ok", base = "site/")
            .filterIsInstance<ByUnittestEvent.TestStarted>().single()
        assertEquals("site/polls/tests.py::OtherTests::test_ok", started.id)
    }

    @Test
    fun `a run stopped before its tracebacks still closes every node`() {
        val events = parse("test_error (polls.tests.test_views.QuestionTests.test_error) ... ERROR")
        assertEquals("ERROR", events.filterIsInstance<ByUnittestEvent.TestFailed>().single().message)
        assertEquals(1, events.count { it is ByUnittestEvent.TestFinished })
        assertEquals(2, events.count { it is ByUnittestEvent.SuiteFinished })
    }

    @Test
    fun `the message is the exception, not the diff that follows it`() {
        val text = """
            test_wrong (polls.tests.test_greet.GreetTests.test_wrong) ... FAIL

            ======================================================================
            FAIL: test_wrong (polls.tests.test_greet.GreetTests.test_wrong)
            ----------------------------------------------------------------------
            Traceback (most recent call last):
              File "/p/polls/tests/test_greet.by", line 11, in test_wrong
                self.assertEqual(greet("a"), "bye")
            AssertionError: 'hello a' != 'bye'
            - hello a
            + bye


            ----------------------------------------------------------------------
            Ran 1 test in 0.000s
        """.trimIndent()
        val failed = parse(text).filterIsInstance<ByUnittestEvent.TestFailed>().single()
        assertEquals("AssertionError: 'hello a' != 'bye'", failed.message)
        assertTrue(failed.details.endsWith("+ bye"), failed.details)
    }

    @Test
    fun `labels name a node id to manage py test`() {
        assertEquals("polls.tests.test_views.QuestionTests.test_create", ByDjangoTests.label("$file::QuestionTests::test_create", ""))
        assertEquals("polls.tests.test_views", ByDjangoTests.label(file, ""))
        assertEquals("polls.tests", ByDjangoTests.label("site/polls/tests.py::T", "site/")?.substringBefore(".T"))
        assertEquals("polls", ByDjangoTests.label("polls/__init__.py", ""))
        assertEquals(null, ByDjangoTests.label("other/polls/tests.py", "site/"))
        assertEquals(null, ByDjangoTests.label("my-app/tests.py", ""))
    }
}
