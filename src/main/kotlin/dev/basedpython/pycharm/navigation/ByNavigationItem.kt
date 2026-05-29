package dev.basedpython.pycharm.navigation

import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.structure.IndentScanner
import javax.swing.Icon

/**
 * A lightweight [NavigationItem] that opens a `.by` [file] at a given [offset].
 *
 * The PSI for `.by` files is flat (token-only), so navigation is done purely by
 * file + offset via [OpenFileDescriptor] rather than by resolving a PSI element.
 */
class ByNavigationItem(
    private val project: Project,
    private val file: VirtualFile,
    private val itemName: String,
    private val offset: Int,
    private val kind: IndentScanner.NodeKind,
) : NavigationItem {

    override fun getName(): String = itemName

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = itemName
        override fun getLocationString(): String = file.name
        override fun getIcon(unused: Boolean): Icon? = BasedPythonFileType.INSTANCE.icon
    }

    val nodeKind: IndentScanner.NodeKind get() = kind
    val containingFile: VirtualFile get() = file

    override fun navigate(requestFocus: Boolean) {
        if (!file.isValid) return
        OpenFileDescriptor(project, file, offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = file.isValid

    override fun canNavigateToSource(): Boolean = file.isValid
}
