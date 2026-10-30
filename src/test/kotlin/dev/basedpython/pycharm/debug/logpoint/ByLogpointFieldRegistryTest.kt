package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText

/**
 * Hearing that a `.by` breakpoint went never brings [ByLogpointFieldRegistry] into being.
 *
 * Which is the whole of why the plugin unloads without a SEVERE: an unload unregisters the
 * registry and only then removes the breakpoint type, whose breakpoints go with it, and
 * [ByLogpointFields] hears each one. Asking for the registry with `service` at that moment logged
 * *"Cannot find service ByLogpointFieldRegistry"*. Here there is no unload to unregister it, so
 * the same request shows up as a registry that exists although no box was ever shown.
 *
 * A project of its own per test, unlike the shared light project the other log point tests use: in
 * that one an earlier test has always shown a box, and the registry is there already.
 */
@TestApplication
@TestFixtures
class ByLogpointFieldRegistryTest {

    private val project by projectFixture()
    private val dir by tempPathFixture()

    @Test
    fun `removing a breakpoint from an open file does not create the registry`() = onEdt {
        val path = dir.resolve("main.by").apply { writeText("def f(x):\n    return x\n") }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val editor = EditorFactory.getInstance().createEditor(document, project) as EditorEx
        try {
            val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!
            val breakpoints = XDebuggerManager.getInstance(project).breakpointManager
            // An ordinary breakpoint, so nothing shows a box when it arrives; its removal is still
            // reported, and asks the editor for one.
            val breakpoint = breakpoints.addLineBreakpoint(type, file.url, 1, null)

            breakpoints.removeBreakpoint(breakpoint)

            assertNull(ByLogpointField.of(editor, breakpoint))
            assertNull(
                project.serviceIfCreated<ByLogpointFieldRegistry>(),
                "looking for a box to take away must not create the registry that holds them",
            )
        } finally {
            EditorFactory.getInstance().releaseEditor(editor)
        }
    }
}
