package dev.basedpython.pycharm.mcp

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.impl.util.asTools
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The toolset as the IDE's MCP server reads it: by reflection, into tools with a JSON schema each.
 *
 * The server turns a toolset into tools when it starts, and one parameter or return type it cannot
 * describe fails the whole toolset there, in the IDE log, rather than at compile time. So the
 * conversion the server runs is run here.
 */
@TestApplication
class ByMcpToolsetTest {

    @Test
    fun `every tool converts, under the name an agent calls it by`() {
        val tools = ByMcpToolset().asTools()
        assertEquals(
            listOf(
                "basedpython_check_workspace",
                "basedpython_entry_point",
                "basedpython_explain_rule",
                "basedpython_test_items",
                "basedpython_transpile",
            ),
            tools.map { it.descriptor.name }.sorted(),
        )
        for (tool in tools) assertTrue(tool.descriptor.description.isNotBlank(), tool.descriptor.name)
    }

    @Test
    fun `the toolset is registered where the MCP server looks for it`() {
        val xml = checkNotNull(javaClass.getResourceAsStream("/META-INF/basedpython-mcp.xml")).reader().readText()
        assertTrue("implementation=\"${ByMcpToolset::class.java.name}\"" in xml, xml)
        assertTrue(McpToolset::class.java.isAssignableFrom(ByMcpToolset::class.java))
    }
}
