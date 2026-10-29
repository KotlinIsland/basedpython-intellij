package dev.basedpython.pycharm.lang.dialect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The choices under *Settings | basedpython | Python interop | Apply to .py files*: each reads as
 * the end of that sentence, and each keeps the id it is persisted under.
 */
class PyFileHandlingTest {

    @Test
    fun `the choices read under Apply to py files, persisted as before`() {
        assertEquals(
            listOf(
                "auto" to "Only when no Python plugin is installed",
                "never" to "Never: leave .py to the Python plugin",
                "always" to "Always",
            ),
            PyFileHandling.entries.map { it.id to it.display },
        )
    }
}
