// Inserts TEXT at the start of line AT (1-based), caret where TEXT has '|', and opens basic completion.
import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.ScrollType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    val doc = editor.document
    val raw = "TEXT".replace("\\n", "\n")
    val caret = raw.indexOf('|')
    val text = raw.replace("|", "")
    val at = doc.getLineStartOffset(AT - 1)
    WriteCommandAction.runWriteCommandAction(project) { doc.insertString(at, text) }
    editor.caretModel.moveToOffset(at + caret)
    editor.scrollingModel.disableAnimation()
    editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
}
kotlinx.coroutines.delay(DELAY)
withContext(Dispatchers.EDT) {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    CodeCompletionHandlerBase(CompletionType.BASIC, true, false, true).invokeCompletion(project, editor)
    println("completion invoked at ${editor.caretModel.logicalPosition}")
}
