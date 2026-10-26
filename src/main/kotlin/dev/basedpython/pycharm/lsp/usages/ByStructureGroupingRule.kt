package dev.basedpython.pycharm.lsp.usages

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.usages.Usage
import com.intellij.usages.UsageGroup
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.FileStructureGroupRuleProvider
import com.intellij.usages.rules.UsageGroupingRule
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import javax.swing.Icon

/**
 * Find Usages' *Group by File Structure* for `.by` files: each usage under the classes and
 * functions it is written in, `Circle › __init__ › self.radius = radius`, as PyCharm groups a `.py`'s.
 *
 * The platform asks a language's own rule for this, from its PSI; a `.by` file's PSI is one leaf
 * per token, so without this a `.by` usage sat straight under its file. Where the classes and
 * functions are is `by`'s outline ([ByOutlines]), the same one the editor's structure-aware features
 * read: a statement with a name is a `def` or a class, and every one whose range — decorators to the
 * end of its body — holds the usage is a group, outermost first. A usage in a base list or an
 * annotation is in its class or its `def`, as in `.py`.
 *
 * Asked on the usage view's background thread; a file `by` gives no outline for keeps its usages
 * under the file, as before.
 */
internal class ByStructureGroupRuleProvider : FileStructureGroupRuleProvider {
    override fun getUsageGroupingRule(project: Project): UsageGroupingRule = ByStructureGroupingRule(project)
}

internal class ByStructureGroupingRule(private val project: Project) : UsageGroupingRule {

    override fun getParentGroupsFor(usage: Usage, targets: Array<out UsageTarget>): List<UsageGroup> {
        val info = (usage as? UsageInfo2UsageAdapter)?.usageInfo ?: return emptyList()
        val psi = info.file as? BasedPythonFile ?: return emptyList()
        val file = psi.virtualFile ?: return emptyList()
        val offset = info.navigationOffset.takeIf { it >= 0 } ?: return emptyList()
        val outline = ByOutlines.getInstance(project).forFile(psi) ?: return emptyList()
        val text = PsiDocumentManager.getInstance(project).getDocument(psi)?.immutableCharSequence ?: return emptyList()
        return enclosing(outline, offset, text).map { ByStructureGroup(project, file, it) }
    }

    companion object {
        /**
         * The classes and functions holding [offset], outermost first, read off [outline] of [text]:
         * each named statement whose range holds it.
         */
        fun enclosing(outline: ByOutline, offset: Int, text: CharSequence): List<Declared> {
            val found = mutableListOf<Declared>()
            var inClass = false
            fun search(statements: List<ByOutline.Statement>) {
                for (statement in statements) {
                    if (offset < statement.range.startOffset || offset >= statement.range.endOffset) continue
                    val name = statement.name
                    val keyword = statement.clauses.firstOrNull()?.keyword
                        ?.let { text.subSequence(it.startOffset, it.endOffset).toString() }
                    if (name != null) {
                        val kind = when {
                            keyword != "def" -> Declared.Kind.CLASS
                            inClass -> Declared.Kind.METHOD
                            else -> Declared.Kind.FUNCTION
                        }
                        found += Declared(text.subSequence(name.startOffset, name.endOffset).toString(), name.startOffset, kind)
                        inClass = kind == Declared.Kind.CLASS
                    }
                    for (clause in statement.clauses) search(clause.body)
                    return
                }
            }
            search(outline.statements)
            return found
        }
    }

    /** A class or function, by its name and where the name is. */
    data class Declared(val name: String, val offset: Int, val kind: Kind) {
        enum class Kind(val icon: Icon) {
            CLASS(AllIcons.Nodes.Class),
            FUNCTION(AllIcons.Nodes.Function),
            METHOD(AllIcons.Nodes.Method),
        }
    }
}

/** One class or function as a group of the usages written in it. Equal for every usage in it. */
private class ByStructureGroup(
    private val project: Project,
    private val file: VirtualFile,
    private val declared: ByStructureGroupingRule.Declared,
) : UsageGroup {

    override fun getPresentableGroupText(): String = declared.name

    override fun getIcon(): Icon = declared.kind.icon

    override fun isValid(): Boolean = file.isValid

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(project, file, declared.offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun compareTo(other: UsageGroup): Int =
        if (other is ByStructureGroup) declared.offset.compareTo(other.declared.offset)
        else presentableGroupText.compareTo(other.presentableGroupText)

    override fun equals(other: Any?): Boolean =
        other is ByStructureGroup && other.file == file && other.declared == declared

    override fun hashCode(): Int = 31 * file.hashCode() + declared.hashCode()
}
