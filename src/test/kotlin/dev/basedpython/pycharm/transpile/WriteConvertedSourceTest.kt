package dev.basedpython.pycharm.transpile

import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Where the *Convert in place* actions put what they converted, and what they refuse to clobber. */
@TestFixtures
class WriteConvertedSourceTest {

  private val fixture by codeInsightFixture()

  @TempDir
  lateinit var dir: Path

  private fun write(path: Path, content: String, confirm: Boolean = true) =
    writeConvertedSource(fixture.project, path, content, "convert") { confirm }

  @Test
  fun `an existing file is left alone when overwriting is declined`() = onEdt {
    val target = dir.resolve("hand_written.by")
    Files.writeString(target, "keep me\n")

    assertNull(write(target, "converted\n", confirm = false))

    assertEquals("keep me\n", Files.readString(target))
  }

  @Test
  fun `a new file lands in directories the VFS had never loaded, and the VFS knows it`() = onEdt {
    val target = dir.resolve("out/pkg/mod.py")

    val written = write(target, "x = 1\n")!!

    assertEquals("x = 1\n", Files.readString(target))
    assertEquals(written, LocalFileSystem.getInstance().findFileByNioFile(target))
  }

  @Test
  fun `overwriting an existing file can be undone`() = onEdt {
    val target = dir.resolve("mod.by")
    Files.writeString(target, "before\n")
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)!!
    val editor = FileEditorManagerEx.getInstanceEx(fixture.project).openFile(file, true)
      .filterIsInstance<TextEditor>().single()

    write(target, "after\n")
    val document = FileDocumentManager.getInstance().getDocument(file)!!
    assertEquals("after\n", document.text)

    val undo = UndoManager.getInstance(fixture.project)
    assertTrue(undo.isUndoAvailable(editor))
    undo.undo(editor)
    assertEquals("before\n", document.text)
    FileEditorManagerEx.getInstanceEx(fixture.project).closeFile(file)
  }
}
