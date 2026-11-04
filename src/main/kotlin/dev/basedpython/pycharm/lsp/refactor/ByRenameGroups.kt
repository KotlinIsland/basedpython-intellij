package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.refactoring.rename.UnresolvableCollisionUsageInfo
import com.intellij.ui.dsl.builder.panel
import com.intellij.usageView.UsageInfo
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.RenameFile
import java.net.URI
import java.nio.file.Path
import javax.swing.JCheckBox
import javax.swing.JComponent

/** `by/rename`'s [answer] for renaming to [newName], asked of the text at the document's [stamp]. */
internal data class ByAnnotatedRenameFor(val newName: String, val stamp: Long, val answer: ByAnnotatedRename)

/**
 * The groups of renames `by` says go with a rename ([ByRenameGroup]): each a checkbox named by its
 * label, its descriptions under it, checked unless the user unchecked that label before.
 */
internal class ByRenameGroupChoices {

    private val unchecked = HashSet<String>()
    private val boxes = LinkedHashMap<String, JCheckBox>()

    /** The labels checked of [groups]. */
    fun kept(groups: List<ByRenameGroup>): Set<String> = groups.map { it.label }.filterTo(LinkedHashSet()) { it !in unchecked }

    /** A panel of a checkbox per group of [groups], remembering what is unchecked across panels. */
    fun panel(groups: List<ByRenameGroup>): DialogPanel {
        boxes.clear()
        return panel {
            for (group in groups) {
                row {
                    val box = checkBox(group.label).applyToComponent {
                        isSelected = group.label !in unchecked
                        isFocusable = false
                        addActionListener { if (isSelected) unchecked -= group.label else unchecked += group.label }
                    }
                    if (group.descriptions.isNotEmpty()) box.comment(describe(group))
                    boxes[group.label] = box.component
                }
            }
        }
    }

    /** The checkbox of the group labelled [label] in the last [panel], for a test to click. */
    internal fun box(label: String): JCheckBox? = boxes[label]

    /** `by`'s descriptions, one to a line; they quote names in backticks, which a label would show as backticks. */
    private fun describe(group: ByRenameGroup): String =
        group.descriptions.joinToString("<br>") { StringUtil.escapeXmlEntities(it.replace("`", "")) }
}

/** Asks which groups to keep: [ask] false when cancelled, else [kept] the labels kept. */
internal interface ByRenameGroupsAsker {
    fun ask(): Boolean
    fun kept(): Set<String>
}

/**
 * The dialog an in-place rename asks in when `by` says other renames go with it, as PyCharm asks
 * whether to rename a method's overriding methods once its name has been typed: one checkbox per
 * group, all checked to start with. Cancel renames nothing.
 */
internal class ByRenameGroupsDialog(
    project: Project,
    oldName: String,
    newName: String,
    private val groups: List<ByRenameGroup>,
) : DialogWrapper(project, true), ByRenameGroupsAsker {

    val choices = ByRenameGroupChoices()

    init {
        title = BasedPythonBundle.message("rename.by.groups.title", oldName, newName)
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(BasedPythonBundle.message("rename.by.groups.message")) }
        row { cell(choices.panel(groups)) }
    }

    override fun ask(): Boolean = showAndGet()

    /** The labels the user kept. */
    override fun kept(): Set<String> = choices.kept(groups)
}

/** Moving the files of the groups a rename keeps. */
internal object ByRenameGroups {

    /**
     * Of [renames], the ones that are not renames of a file in its directory, as conflicts of the
     * rename of [element]: such a move is not one a rename makes, and the platform's conflicts dialog
     * is where the user decides. A file the IDE does not have is one too.
     */
    @RequiresReadLock
    fun conflicts(element: PsiElement, renames: List<RenameFile>, fileOf: (String) -> VirtualFile? = ByWorkspaceEditFiles::fileOf): List<UnresolvableCollisionUsageInfo> =
        renames.filter { target(it, fileOf) == null }.map { move ->
            object : UnresolvableCollisionUsageInfo(element, element) {
                override fun getDescription(): String =
                    BasedPythonBundle.message("rename.by.groups.moveElsewhere", fileName(move.oldUri), fileName(move.newUri))
            }
        }

    /**
     * Renames each of [renames]' files as the IDE renames any file, so the VFS, open editors and VCS
     * follow it — in the rename's command, after every edit, the edits to the file itself among them.
     * A move that is not a rename in place ([conflicts]) is left alone.
     */
    @RequiresWriteLock
    fun moveFiles(project: Project, renames: List<RenameFile>, fileOf: (String) -> VirtualFile? = ByWorkspaceEditFiles::fileOf) {
        val psiManager = PsiManager.getInstance(project)
        for (move in renames) {
            val (file, newName) = target(move, fileOf) ?: continue
            psiManager.findFile(file)?.takeIf { it.isValid }?.name = newName
        }
    }

    /** The file [move] renames and its new name, when it renames a file in its directory. */
    private fun target(move: RenameFile, fileOf: (String) -> VirtualFile?): Pair<VirtualFile, String>? {
        val file = fileOf(move.oldUri) ?: return null
        val newPath = runCatching { Path.of(URI.create(move.newUri)) }.getOrNull() ?: return null
        val oldPath = runCatching { Path.of(URI.create(move.oldUri)) }.getOrNull() ?: return null
        if (newPath.parent != oldPath.parent) return null
        return file to newPath.fileName.toString()
    }

    private fun fileName(uri: String): String = uri.substringAfterLast('/')
}
