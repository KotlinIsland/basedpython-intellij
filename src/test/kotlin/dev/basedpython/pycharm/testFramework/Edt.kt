package dev.basedpython.pycharm.testFramework

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.DEFAULT_TEST_TIMEOUT
import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.Dispatchers
import kotlin.time.Duration

/**
 * Runs a test body on the EDT, where the code insight fixture's editor, PSI, document and command
 * APIs have to be called.
 *
 * What `@RunInEdt(writeIntent = true)` did for every method of a class, which the platform
 * deprecated in favour of a bounded `timeoutRunBlocking` with an explicit context. The context is
 * `Dispatchers.EDT` rather than the lock-free `Dispatchers.UI` because these bodies are fixture
 * calls throughout — model access, not Swing alone — and it holds the write-intent lock, as
 * `writeIntent = true` did. The bound is the platform's default unless [timeout] says otherwise: a
 * body that waits on a real process has to be bounded above its own waits, or the machine's load
 * decides whether it passes.
 *
 * Returns `Unit` whatever [body] ends with: JUnit runs only `void` test methods, and silently skips
 * one that returns a value. [body] has no receiver, so `this` in it is still the test instance
 * rather than a coroutine scope.
 */
fun onEdt(timeout: Duration = DEFAULT_TEST_TIMEOUT, body: suspend () -> Unit) {
    timeoutRunBlocking(timeout, context = Dispatchers.EDT) { body() }
}
