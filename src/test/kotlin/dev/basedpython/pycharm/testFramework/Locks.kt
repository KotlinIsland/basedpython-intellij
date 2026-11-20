package dev.basedpython.pycharm.testFramework

import com.intellij.openapi.application.ex.ApplicationManagerEx
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Runs [action] where the platform forbids the read and write locks, as it does while Swing paints
 * an editor, and fails if [action] took one.
 *
 * For code the platform calls from paint, where no annotation can say "takes no lock": a
 * `FileTypeOverrider` is asked for a file's type from `MinimapPanel.paint`, and anything that can be
 * reached from there has to answer from what it already holds. The platform reports a lock taken
 * there, through `withLocksSoftlyProhibited`, and lets it proceed, so in the IDE the mistake is a
 * line in the error log and nothing visibly breaks; here it fails the test.
 *
 * Call it on a thread holding no lock, so that a lock [action] needs is a lock it takes: on the EDT
 * the test's write-intent lock would already grant read access.
 */
fun <T> withLocksForbidden(action: () -> T): T {
    val reported = mutableListOf<Throwable>()
    val result = ApplicationManagerEx.getApplicationEx()
        .withLocksSoftlyProhibited("as while painting", { reported += it }) { action() }
    assertEquals(emptyList<Throwable>(), reported) { "took a lock where paint forbids one" }
    return result
}
