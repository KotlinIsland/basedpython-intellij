package dev.basedpython.pycharm.run.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ByTestRunnersTest {

    @Test
    fun `django's runner runs a unittest test where there is a manage py`() {
        assertEquals(ByTestRunner.DJANGO, ByTestRunners.resolve(ByTestRunner.DJANGO, unittest = true, hasManage = { true }))
    }

    @Test
    fun `a pytest-style test, or no manage py, is pytest's`() {
        assertEquals(ByTestRunner.PYTEST, ByTestRunners.resolve(ByTestRunner.DJANGO, unittest = false, hasManage = { true }))
        assertEquals(ByTestRunner.PYTEST, ByTestRunners.resolve(ByTestRunner.DJANGO, unittest = true, hasManage = { false }))
        assertEquals(ByTestRunner.PYTEST, ByTestRunners.resolve(ByTestRunner.PYTEST, unittest = true, hasManage = { true }))
    }

    @Test
    fun `the wire and the settings name runners by id, an unknown one falling back`() {
        assertEquals(ByTestRunner.DJANGO, ByTestRunner.of("django"))
        assertEquals(null, ByTestRunner.of("nose"))
        assertEquals(ByTestRunnerChoice.AUTOMATIC, ByTestRunnerChoice.of("nose"))
        assertEquals(ByTestRunner.DJANGO, ByTestRunnerChoice.of("django").runner)
        assertEquals(null, ByTestRunnerChoice.AUTOMATIC.runner)
    }
}
