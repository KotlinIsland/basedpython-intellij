package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.testframework.sm.ServiceMessageBuilder
import com.intellij.execution.testframework.sm.runner.events.TreeNodeEvent
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes

/**
 * Gives service messages that name their tests and suites alone the node ids a tree built by node id
 * needs, which is sound for a run told of in order: a suite is in the one opened last, and a message
 * about a test is about the one of its name started last. One of a message's own ids is kept.
 */
internal class ByNodeIds {

    /** The suites open, outermost first, each a name and its id. */
    private val suites = ArrayDeque<Pair<String, String>>()

    /** The id of the test last started under each name, until it has finished. */
    private val tests = HashMap<String, String>()
    private var given = 0

    fun identified(message: ServiceMessage): ServiceMessage {
        val attributes = message.attributes
        val name = attributes[NAME] ?: return message
        if (NODE_ID in attributes) return message
        return when (message.messageName) {
            ServiceMessageTypes.TEST_SUITE_STARTED -> {
                val parent = parent()
                val id = next()
                suites.addLast(name to id)
                message.with(NODE_ID to id, PARENT_NODE_ID to parent)
            }
            ServiceMessageTypes.TEST_SUITE_FINISHED -> {
                val open = suites.indexOfLast { it.first == name }
                if (open < 0) return message
                val id = suites[open].second
                while (suites.size > open) suites.removeLast()
                message.with(NODE_ID to id)
            }
            ServiceMessageTypes.TEST_STARTED -> {
                val id = next()
                tests[name] = id
                message.with(NODE_ID to id, PARENT_NODE_ID to parent())
            }
            ServiceMessageTypes.TEST_FINISHED -> tests.remove(name)?.let { message.with(NODE_ID to it) } ?: message
            ServiceMessageTypes.TEST_FAILED,
            ServiceMessageTypes.TEST_IGNORED,
            ServiceMessageTypes.TEST_STD_OUT,
            ServiceMessageTypes.TEST_STD_ERR,
            -> tests[name]?.let { message.with(NODE_ID to it) } ?: message
            else -> message
        }
    }

    private fun parent(): String = suites.lastOrNull()?.second ?: TreeNodeEvent.ROOT_NODE_ID

    private fun next(): String = "by:${++given}"

    private fun ServiceMessage.with(vararg ids: Pair<String, String>): ServiceMessage {
        val builder = ServiceMessageBuilder(messageName)
        for ((key, value) in attributes) builder.addAttribute(key, value)
        for ((key, value) in ids) builder.addAttribute(key, value)
        return checkNotNull(ServiceMessage.parse(builder.toString())) { "not a service message: $builder" }
    }

    private companion object {
        const val NAME = "name"
        const val NODE_ID = "nodeId"
        const val PARENT_NODE_ID = "parentNodeId"
    }
}
