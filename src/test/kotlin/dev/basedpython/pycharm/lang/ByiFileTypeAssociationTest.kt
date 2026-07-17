package dev.basedpython.pycharm.lang

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies that basedpython stub files (`.byi`, the `by` analogue of `.pyi`) are recognized as
 * [BasedPythonFileType] through the plugin's declarative `fileType` registration, so they get the
 * same language, highlighting and tooling as `.by` sources.
 */
class ByiFileTypeAssociationTest : BasePlatformTestCase() {

    fun `test byi extension maps to basedpython file type`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            FileTypeManager.getInstance().getFileTypeByExtension("byi"),
        )
    }

    fun `test by extension still maps to basedpython file type`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            FileTypeManager.getInstance().getFileTypeByExtension("by"),
        )
    }

    fun `test a byi file is parsed as basedpython`() {
        val file = myFixture.addFileToProject("stub.byi", "def f() -> int: ...\n")
        assertSame(BasedPythonFileType.INSTANCE, file.fileType)
        assertSame(BasedPythonLanguage, file.language)
    }
}
