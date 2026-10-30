package dev.basedpython.pycharm.debug

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.xdebugger.XDebuggerUtil
import dev.basedpython.pycharm.debug.logpoint.ByLogpoints
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `.by` breakpoint's own properties come back when the breakpoints are read out of the workspace.
 *
 * The way the breakpoint manager reads them, which is what an IDE restart and a plugin reload both
 * go through (`XBreakpointManagerImpl.prepareBreakpoint`): it asks the type's `createProperties`
 * for an object and loads the saved `<properties>` into that. A type that makes none gets its
 * breakpoints back with no properties at all, and a log point with nothing to log yet — what
 * Ctrl+Alt+F8 makes, and what only the flag says is a log point — came back an ordinary
 * non-suspending breakpoint with no `Log:` box.
 */
@TestApplication
class ByBreakpointPropertiesTest {

    private val type get() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!

    @Test
    fun `a saved log point's flag is read back into the properties the type makes`() {
        val saved = XmlSerializer.serialize(ByLogpoints.logpointProperties().state)

        val loaded = type.createProperties()
        assertNotNull(loaded, "the manager has nothing to read <properties> into")
        // What `ComponentSerializationUtil.loadComponentState` does with it.
        loaded!!.loadState(XmlSerializer.deserialize(saved, ByBreakpointProperties.State::class.java))

        assertTrue(loaded.isLogpoint)
    }
}
