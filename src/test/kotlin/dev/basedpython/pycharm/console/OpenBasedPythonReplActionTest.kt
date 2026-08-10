package dev.basedpython.pycharm.console

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Project-backed tests for [OpenBasedPythonReplAction]. None of these spawn a
 * real process: they exercise presentation/enablement logic, the configured
 * subcommand, and the graceful "binary missing" notification path (in CI no
 * `by` binary is resolvable, so `openRepl` must no-op without throwing).
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class OpenBasedPythonReplActionTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private val action = OpenBasedPythonReplAction()

    @AfterEach
    fun resetSettings() {
        val s = BasedPythonSettings.getInstance(project)
        s.byPath = null
        s.byExtraArgs = ""
    }

    @Test
    fun `is enabled with project`() {
        // The light fixture project always has a base path.
        assertTrue(action.isEnabled(project))
    }

    @Test
    fun `is disabled with null project`() {
        assertFalse(action.isEnabled(null))
    }

    @Test
    fun `default subcommand is repl`() {
        assertEquals("repl", action.replSubcommand())
    }

    @Test
    fun `update enables action when project present`() {
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        assertTrue(event.presentation.isVisible)
    }

    @Test
    fun `action update thread is background`() {
        assertEquals(
            com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
            action.actionUpdateThread,
        )
    }

    @Test
    fun `open repl with no binary does not throw`() {
        // No override + (in CI) no `by` on PATH -> resolves null -> notify, no crash.
        BasedPythonSettings.getInstance(project).byPath = null
        action.openRepl(project)
        // If we got here without an exception the contract held.
        assertTrue(true)
    }

    @Test
    fun `open repl with bogus binary does not throw`() {
        BasedPythonSettings.getInstance(project).byPath = "/definitely/not/a/real/path/by-xyz"
        action.openRepl(project)
        assertTrue(true)
    }

    @Test
    fun `action is registered via constructor`() {
        // The action can be instantiated with the no-arg ctor the platform requires.
        val fresh = OpenBasedPythonReplAction()
        assertNotNull(fresh)
    }

    @Test
    fun `extra args flow into parameters`() {
        // Independent of process launch: verify the args the action would pass.
        BasedPythonSettings.getInstance(project).byExtraArgs = "--verbose \"a b\""
        val params = ByReplCommandLine.parameters(
            action.replSubcommand(),
            BasedPythonSettings.getInstance(project).byExtraArgs,
        )
        assertEquals(listOf("repl", "--verbose", "a b"), params)
    }

    @Test
    fun `action manager has no stale registration`() {
        // Defensive: ensure our action id is not already taken by something else in
        // the test classpath (the orchestrator registers it via plugin.xml later).
        val existing = ActionManager.getInstance().getAction("basedpython.OpenRepl")
        // Either unregistered (null) in tests, or — if a previous run registered it —
        // it must be our class. Both are acceptable; we just must not crash.
        if (existing != null) {
            assertTrue(existing is OpenBasedPythonReplAction)
        }
    }
}
