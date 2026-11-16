// Scrolls one side of the open diff so LINE is at the top, and puts the caret on CARET; synced
// scrolling brings the other side along. SIDE is "by" (the source) or "py" (the generated side,
// whose document has no file). Scroll the longer side: the shorter one cannot scroll past its end.
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileDocumentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val eds = EditorFactory.getInstance().allEditors.filter { it.editorKind == EditorKind.DIFF && it.project == project }
    val isBy = { e: com.intellij.openapi.editor.Editor -> FileDocumentManager.getInstance().getFile(e.document)?.name?.endsWith(".by") == true }
    val side = if ("SIDE" == "by") eds.first(isBy) else eds.first { !isBy(it) }
    side.caretModel.moveToLogicalPosition(LogicalPosition(CARET - 1, 0))
    side.scrollingModel.disableAnimation()
    side.scrollingModel.scrollVertically(side.logicalPositionToXY(LogicalPosition(LINE - 1, 0)).y)
    println("scrolled the SIDE side to line LINE")
}
