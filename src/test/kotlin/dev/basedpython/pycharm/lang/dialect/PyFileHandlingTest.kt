package dev.basedpython.pycharm.lang.dialect

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The choices under *Settings | basedpython | .py and .pyi files | Treat .py and .pyi as basedpython*:
 * each answers it, and each keeps the id it is persisted under.
 */
class PyFileHandlingTest {

    @Test
    fun `the choices answer Treat py and pyi as basedpython, persisted as before`() {
        assertEquals(
            listOf(
                "auto" to "Only when no Python plugin is installed",
                "never" to "Never: leave .py and .pyi to the Python plugin",
                "always" to "Always",
            ),
            PyFileHandling.entries.map { it.id to it.display },
        )
    }
}
