// Throws away unsaved edits in every open document.
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.codeInsight.lookup.LookupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    LookupManager.getInstance(project).hideActiveLookup()
    val fdm = FileDocumentManager.getInstance()
    for (f in FileEditorManager.getInstance(project).openFiles) {
        val d = fdm.getCachedDocument(f) ?: continue
        if (fdm.isDocumentUnsaved(d)) { fdm.reloadFromDisk(d); println("reverted ${f.name}") }
    }
}
