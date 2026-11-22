package dev.basedpython.pycharm.run.test.tree

import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The node ids given to messages of a run told of in order, which name tests and suites alone. */
class ByNodeIdsTest {

    private val ids = ByNodeIds()

    private fun of(message: String): Pair<String?, String?> =
        ids.identified(ServiceMessage.parse("##teamcity[$message]")!!).attributes.let { it["nodeId"] to it["parentNodeId"] }

    @Test
    fun `a suite is in the one opened last, and a message is of the test last started`() {
        assertEquals("by:1" to "0", of("testSuiteStarted name='a.py'"))
        assertEquals("by:2" to "by:1", of("testSuiteStarted name='TestA'"))
        assertEquals("by:3" to "by:2", of("testStarted name='test_x'"))
        assertEquals("by:3" to null, of("testStdOut name='test_x' out='said'"))
        assertEquals("by:3" to null, of("testFailed name='test_x' message='no'"))
        assertEquals("by:3" to null, of("testFinished name='test_x'"))
        assertEquals("by:2" to null, of("testSuiteFinished name='TestA'"))
        assertEquals("by:4" to "by:1", of("testStarted name='test_x'"))
        assertEquals("by:4" to null, of("testIgnored name='test_x'"))
        assertEquals("by:1" to null, of("testSuiteFinished name='a.py'"))
        assertEquals("by:5" to "0", of("testSuiteStarted name='a.py'"))
    }

    @Test
    fun `a message's own ids, and one about no node, are kept as they are`() {
        assertEquals("mine" to "0", of("testStarted name='test_x' nodeId='mine' parentNodeId='0'"))
        assertEquals(null to null, of("testCount count='3'"))
        assertEquals(null to null, of("testFailed name='never_started' message='no'"))
    }

    @Test
    fun `the rest of a message is kept, escaped as it was`() {
        val message = ServiceMessage.parse("##teamcity[testStarted name='test_x|[a|'b|]' locationHint='file:///p/a.py:1' metainfo='a|nb||c']")!!
        val attributes = ids.identified(message).attributes
        assertEquals(message.attributes + mapOf("nodeId" to "by:1", "parentNodeId" to "0"), attributes)
    }
}
