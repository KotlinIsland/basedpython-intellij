package dev.basedpython.pycharm.lang

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies that basedpython stub files (`.byi`, the `by` analogue of `.pyi`) are recognized as
 * [BasedPythonFileType] through the plugin's declarative `fileType` registration, so they get the
 * same language, highlighting and tooling as `.by` sources.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByiFileTypeAssociationTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `byi extension maps to basedpython file type`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            FileTypeManager.getInstance().getFileTypeByExtension("byi"),
        )
    }

    @Test
    fun `by extension still maps to basedpython file type`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            FileTypeManager.getInstance().getFileTypeByExtension("by"),
        )
    }

    @Test
    fun `a byi file is parsed as basedpython`() {
        val file = fixture.addFileToProject("stub.byi", "def f() -> int: ...\n")
        assertSame(BasedPythonFileType.INSTANCE, file.fileType)
        assertSame(BasedPythonLanguage, file.language)
    }
}
