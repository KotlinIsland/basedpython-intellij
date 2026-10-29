package dev.basedpython.pycharm.editor.scope

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.NoSettings
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.Cursor
import java.awt.Graphics2D
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A dimmed line after each scope of a `.by` file echoing the header that opened it, at the header's
 * indentation — `╰ for x in [1, 2]` under the loop's last line — so that the end of a block deep in
 * a function says which block it ends. A click on it goes to the header.
 *
 * The scopes are `by`'s, read off its outline of the text on screen ([ByOutlines], [ByScopeEnds]):
 * nothing here reads the source's structure for itself. Each echo is a block inlay, a line of the
 * editor's own and none of the document's, through the platform's inlay pass, which adds, keeps and
 * removes them as the outline changes and takes them out when the plugin unloads; it is listed under
 * *Settings | Editor | Inlay Hints* for basedpython, on by default.
 *
 * Not in a diff or merge editor, whose sides are lined up line for line: a line of one side's own
 * would push its side out of step with the other.
 */
class ByScopeEndInlayProvider : InlayHintsProvider<NoSettings>, DumbAware {

    override val key: SettingsKey<NoSettings> = SettingsKey("basedpython.scope.ends")

    override val name: String = BasedPythonBundle.message("inlay.scope.ends.name")

    override val description: String = BasedPythonBundle.message("inlay.scope.ends.description")

    override val previewText: String? = null

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = JPanel()
        }

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: NoSettings, sink: InlayHintsSink): InlayHintsCollector? {
        if (file !is BasedPythonFile) return null
        if (editor.editorKind == EditorKind.DIFF) return null
        return Collector()
    }

    /**
     * The whole file's echoes from one outline, once per pass: the pass calls [collect] for every
     * element of a file whose PSI is a leaf per token, on several threads at once, so the first call
     * claims the work and the rest return.
     */
    private class Collector : InlayHintsCollector {
        private val done = AtomicBoolean(false)

        override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
            if (!done.compareAndSet(false, true)) return false
            val file = element.containingFile as? BasedPythonFile ?: return false
            val document = editor.document
            val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return false
            if (outline.stamp != document.modificationStamp) return false
            val text = document.immutableCharSequence
            val factory = PresentationFactory(editor)
            for (end in ByScopeEnds.of(outline, text)) {
                val lineStart = document.getLineStartOffset(document.getLineNumber(end.header))
                val column = EditorUtil.calcColumnNumber(editor, text, lineStart, end.header)
                val echo = ByScopeEndPresentation(editor, "$ECHO ${end.text}", column)
                val clickable = factory.withCursorOnHover(
                    factory.onClick(echo, MouseButton.Left) { _, _ -> goTo(editor, end.header, outline.stamp) },
                    Cursor.getPredefinedCursor(Cursor.HAND_CURSOR),
                )
                // below the scope's last line; of the scopes ending there, the innermost first
                sink.addBlockElement(end.end, true, false, -end.depth, clickable)
            }
            return false
        }

        /** The header [offset] names, if the document is still the text it was read off. */
        private fun goTo(editor: Editor, offset: Int, stamp: Long) {
            if (editor.isDisposed || editor.document.modificationStamp != stamp) return
            editor.caretModel.moveToOffset(offset)
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
    }

    private companion object {
        const val ECHO = "╰"
    }
}

/**
 * One echo: [text] in the editor's own font and size, in the colour of inlay text without a
 * background, starting [column] columns in, so it lines up under the header it names.
 */
private class ByScopeEndPresentation(
    private val editor: Editor,
    private val text: String,
    private val column: Int,
) : BasePresentation() {

    private val metrics get() = editor.contentComponent.getFontMetrics(editor.colorsScheme.getFont(EditorFontType.PLAIN))

    private val indent: Int get() = column * EditorUtil.getPlainSpaceWidth(editor)

    override val width: Int
        get() = indent + metrics.stringWidth(text)

    override val height: Int
        get() = editor.lineHeight

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        val metrics = metrics
        val colour = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.INLAY_TEXT_WITHOUT_BACKGROUND)?.foregroundColor
            ?: editor.colorsScheme.defaultForeground
        g.font = metrics.font
        g.color = colour
        g.drawString(text, indent, (editor.lineHeight - (metrics.ascent + metrics.descent)) / 2 + metrics.ascent)
    }

    override fun toString(): String = text
}
