package dev.basedpython.pycharm.editor

import com.intellij.lang.Language
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.intellij.util.containers.ContainerUtil
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.ByRangeElement
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Breadcrumbs and sticky lines for `.by` files, from `by`'s outline, as PyCharm draws them for `.py`:
 * crumbs for each class (`Circle`), function (`area()`) and the clause of a control statement the
 * caret is in (`for x in xs`, `elif x < -100`, `else`, `with open("p") as fh`, `try`, `except
 * ValueError as error`, `finally`), and sticky lines for the classes, functions and `with`
 * statements a line is in.
 *
 * The platform's LSP breadcrumbs, turned off in `by`'s descriptor, made a crumb and a sticky line of
 * every document symbol, with no kind to tell them apart — a multi-line constant pinned to the top
 * of the editor, a class's fields pinned one line each — and no control statements, which document
 * symbols do not have. Registering a provider also lists basedpython on the Breadcrumbs and Sticky
 * Lines settings pages, so each can be turned off for it.
 *
 * The platform's PSI collector walks from the token at the caret up through [getParent]; a `.by`
 * file's PSI is one leaf per token, so the parents here are [ByBlock]s read off the outline of the
 * text on screen ([ByOutlines]). With no outline for this revision there are no crumbs, rather than
 * crumbs for the text as it was.
 */
class ByBreadcrumbsProvider : BreadcrumbsProvider {

    override fun getLanguages(): Array<Language> = arrayOf(BasedPythonLanguage)

    override fun acceptElement(element: PsiElement): Boolean = element is ByBlock

    override fun acceptStickyElement(element: PsiElement): Boolean = element is ByBlock && element.sticky

    override fun getElementInfo(element: PsiElement): String = (element as ByBlock).info

    override fun getParent(element: PsiElement): PsiElement? = when (element) {
        is ByBlock -> element.parent
        is PsiFile -> null
        else -> {
            val file = element.containingFile as? BasedPythonFile
            file?.let { ByBlocks.getInstance(it.project).innermost(it, element.textRange.startOffset) } ?: file
        }
    }
}

/**
 * A class, a function or a clause of a control statement, as a PSI element: where it is, what its
 * crumb says, and the block it is in. Made once per outline, so walking up from two tokens of one
 * block arrives at the same element, which the platform compares by identity.
 */
internal class ByBlock(
    private val file: PsiFile,
    range: TextRange,
    /** Where its header starts: the line a sticky line shows. */
    private val headerOffset: Int,
    val info: String,
    val sticky: Boolean,
    private val parentBlock: ByBlock?,
) : ByRangeElement(file, range) {

    val children = mutableListOf<ByBlock>()

    override fun getParent(): PsiElement = parentBlock ?: file

    override fun getTextOffset(): Int = headerOffset

    override fun toString(): String = "ByBlock($info)"
}

/** The [ByBlock]s of each `.by` file's outline, made once per outline. */
@Service(Service.Level.PROJECT)
internal class ByBlocks(private val project: Project) {

    /** Weak keys: an outline replaced by a newer one takes its blocks with it. */
    private val blocks: MutableMap<ByOutline, List<ByBlock>> = ContainerUtil.createConcurrentWeakMap()

    /** The innermost block holding [offset] in [file], or null outside any or with no outline. */
    fun innermost(file: BasedPythonFile, offset: Int): ByBlock? {
        val outline = ByOutlines.getInstance(project).forFile(file) ?: return null
        val roots = blocks.getOrPut(outline) { build(file, outline) }
        var found: ByBlock? = null
        var level: List<ByBlock> = roots
        while (true) {
            val next = level.firstOrNull { offset >= it.textRange.startOffset && offset <= it.textRange.endOffset } ?: break
            found = next
            level = next.children
        }
        return found
    }

    companion object {
        fun getInstance(project: Project): ByBlocks = project.service()

        /** The clause keywords PyCharm draws a crumb for. A `match` and its `case`s it does not. */
        private val CONTROL = setOf("if", "elif", "else", "for", "while", "try", "except", "finally", "with")

        /** Every block of [outline], the outermost as roots. */
        fun build(file: PsiFile, outline: ByOutline): List<ByBlock> {
            val text = file.viewProvider.document?.immutableCharSequence ?: return emptyList()
            val roots = mutableListOf<ByBlock>()
            fun add(block: ByBlock, parent: ByBlock?) = if (parent == null) roots.add(block) else parent.children.add(block)
            fun visit(statements: List<ByOutline.Statement>, parent: ByBlock?) {
                for (statement in statements) {
                    val name = statement.name
                    val first = statement.clauses.firstOrNull()
                    val keyword = first?.keyword?.let { text.subSequence(it.startOffset, it.endOffset).toString() }
                    if (name != null && first != null) {
                        val named = text.subSequence(name.startOffset, name.endOffset).toString()
                        val isDef = keyword == "def"
                        val block = ByBlock(
                            file, statement.range, first.range.startOffset,
                            if (isDef) "$named()" else named, sticky = true, parentBlock = parent,
                        )
                        add(block, parent)
                        for (clause in statement.clauses) visit(clause.body, block)
                        continue
                    }
                    for (clause in statement.clauses) {
                        val clauseKeyword = clause.keyword?.let { text.subSequence(it.startOffset, it.endOffset).toString() }
                        if (clauseKeyword !in CONTROL) {
                            visit(clause.body, parent)
                            continue
                        }
                        val header = clause.colon?.let { text.subSequence(clause.range.startOffset, it.startOffset) }
                            ?: clauseKeyword!!
                        val block = ByBlock(
                            file, clause.range, clause.range.startOffset,
                            header.toString().replace(Regex("\\s+"), " ").trim(),
                            sticky = clauseKeyword == "with", parentBlock = parent,
                        )
                        add(block, parent)
                        visit(clause.body, block)
                    }
                }
            }
            visit(outline.statements, null)
            return roots
        }
    }
}
