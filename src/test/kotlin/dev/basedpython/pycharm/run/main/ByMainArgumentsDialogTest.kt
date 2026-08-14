package dev.basedpython.pycharm.run.main

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The argument form builds, and gives back what it was opened on.
 *
 * Headless, so nothing here proves how the dialog *looks* — what it does prove is that the panel
 * the signature generates can actually be constructed (one row per parameter, a chooser for a
 * `Path`, a box for a `bool`), and that opening on a command line and closing without touching
 * anything is not a way to lose arguments.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByMainArgumentsDialogTest {

    private val fixture by codeInsightFixture()

    private fun main(signature: String): ByMainFunction {
        val lines = listOf("def main($signature):", "    ...")
        return ByMainSignature.find({ lines[it] }, lines.size)!!
    }

    private fun dialog(signature: String, initial: String, check: (ByMainArgumentsDialog) -> Unit) {
        val dialog = ByMainArgumentsDialog(fixture.project, "pkg.main", main(signature), initial)
        try {
            check(dialog)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    @Test
    fun `a form is built for every kind of parameter the command line fills`() {
        dialog("name: str, count: int = 1, out_dir: Path = Path('.'), verbose: bool = False, db: Db = x()", "") {
            assertEquals("", it.result().arguments)
        }
    }

    @Test
    fun `the command line it opens on is the one it gives back`() {
        dialog("name: str, count: int = 1", "--name bob --count 3") {
            assertEquals("--name bob --count 3", it.result().arguments)
        }
    }

    @Test
    fun `a positional command line comes back named`() {
        // The form writes the spelling that survives a reordered signature; both reach `main`.
        dialog("name: str, count: int = 1", "bob 3") {
            assertEquals("--name bob --count 3", it.result().arguments)
        }
    }

    @Test
    fun `a command line the form cannot express is handed back untouched`() {
        dialog("name: str", "--not-a-parameter 1") {
            assertEquals("--not-a-parameter 1", it.result().arguments)
        }
    }

    @Test
    fun `a missing required value is reported before the run starts`() {
        dialog("name: str, count: int = 1", "--count 3") {
            val errors = it.problems()
            assertEquals(1, errors.size, errors.joinToString { error -> error.message })
            assertTrue(errors.first().message.contains("name is required"), errors.first().message)
        }
    }

    @Test
    fun `a value the annotation cannot convert is too`() {
        dialog("count: int", "--count notanint") {
            val errors = it.problems()
            assertEquals(1, errors.size)
            assertTrue(errors.first().message.contains("invalid int value"), errors.first().message)
        }
    }
}
