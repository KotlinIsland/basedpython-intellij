package dev.basedpython.pycharm

import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Verifies static properties of [BasedPythonFileType] without an IDE fixture.
 * No application is needed — just object construction and field access.
 */
class FileTypeTest {

    @Test
    fun `INSTANCE is non-null singleton`() {
        assertNotNull(BasedPythonFileType.INSTANCE)
        assertSame(BasedPythonFileType.INSTANCE, BasedPythonFileType.INSTANCE)
    }

    @Test
    fun `name is BasedPython`() {
        assertEquals("BasedPython", BasedPythonFileType.INSTANCE.name)
    }

    @Test
    fun `default extension is by`() {
        assertEquals("by", BasedPythonFileType.INSTANCE.defaultExtension)
    }

    @Test
    fun `language is BasedPythonLanguage`() {
        assertSame(BasedPythonLanguage, BasedPythonFileType.INSTANCE.language)
    }

    @Test
    fun `description is non-empty`() {
        assertNotNull(BasedPythonFileType.INSTANCE.description)
        assert(BasedPythonFileType.INSTANCE.description.isNotBlank()) {
            "description should not be blank"
        }
    }
}
