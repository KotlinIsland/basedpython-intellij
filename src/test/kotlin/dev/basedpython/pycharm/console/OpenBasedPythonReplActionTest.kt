package dev.basedpython.pycharm.console

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Project-backed tests for [OpenBasedPythonReplAction]. None of these spawn a
 * real process: they exercise presentation/enablement logic, the configured
 * subcommand, and the graceful "binary missing" notification path (in CI no
 * `by` binary is resolvable, so `openRepl` must no-op without throwing).
 *
 * JUnit3-style ([BasePlatformTestCase]) — methods start with `test`.
 */
class OpenBasedPythonReplActionTest : BasePlatformTestCase() {

    private val action = OpenBasedPythonReplAction()

    override fun tearDown() {
        try {
            val s = BasedPythonSettings.getInstance(project)
            s.byPath = null
            s.byExtraArgs = ""
        } finally {
            super.tearDown()
        }
    }

    fun testIsEnabledWithProject() {
        // BasePlatformTestCase always has a base path.
        assertTrue(action.isEnabled(project))
    }

    fun testIsDisabledWithNullProject() {
        assertFalse(action.isEnabled(null))
    }

    fun testDefaultSubcommandIsRepl() {
        assertEquals("repl", action.replSubcommand())
    }

    fun testUpdateEnablesActionWhenProjectPresent() {
        val event = TestActionEvent.createTestEvent(action)
        action.update(event)
        assertTrue(event.presentation.isEnabled)
        assertTrue(event.presentation.isVisible)
    }

    fun testActionUpdateThreadIsBackground() {
        assertEquals(
            com.intellij.openapi.actionSystem.ActionUpdateThread.BGT,
            action.actionUpdateThread,
        )
    }

    fun testOpenReplWithNoBinaryDoesNotThrow() {
        // No override + (in CI) no `by` on PATH -> resolves null -> notify, no crash.
        BasedPythonSettings.getInstance(project).byPath = null
        action.openRepl(project)
        // If we got here without an exception the contract held.
        assertTrue(true)
    }

    fun testOpenReplWithBogusBinaryDoesNotThrow() {
        BasedPythonSettings.getInstance(project).byPath = "/definitely/not/a/real/path/by-xyz"
        action.openRepl(project)
        assertTrue(true)
    }

    fun testActionIsRegisteredViaConstructor() {
        // The action can be instantiated with the no-arg ctor the platform requires.
        val fresh = OpenBasedPythonReplAction()
        assertNotNull(fresh)
    }

    fun testExtraArgsFlowIntoParameters() {
        // Independent of process launch: verify the args the action would pass.
        BasedPythonSettings.getInstance(project).byExtraArgs = "--verbose \"a b\""
        val params = ByReplCommandLine.parameters(
            action.replSubcommand(),
            BasedPythonSettings.getInstance(project).byExtraArgs,
        )
        assertEquals(listOf("repl", "--verbose", "a b"), params)
    }

    fun testActionManagerHasNoStaleRegistration() {
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
