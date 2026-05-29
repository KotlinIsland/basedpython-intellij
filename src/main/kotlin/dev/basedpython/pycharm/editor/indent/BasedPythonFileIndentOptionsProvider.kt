package dev.basedpython.pycharm.editor.indent

import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions
import com.intellij.psi.codeStyle.FileIndentOptionsProvider
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Infers indentation style (tabs vs spaces, indent size) directly from the text of a
 * `.by` file so the editor matches the file's existing style rather than the blind default.
 *
 * Scans up to the first ~200 non-blank, non-comment lines. If leading whitespace uses tabs,
 * tabs win. Otherwise the smallest positive delta between consecutive leading-space counts is
 * taken as the indent step, clamped to {2, 4, 8} (default 4).
 *
 * Returns null for any non-basedpython file so the platform default is used.
 */
class BasedPythonFileIndentOptionsProvider : FileIndentOptionsProvider() {

    override fun getIndentOptions(settings: CodeStyleSettings, file: PsiFile): IndentOptions? {
        if (!isBasedPythonFile(file)) return null

        val text = file.text ?: return null
        val detection = detectIndent(text)

        val options = settings.getIndentOptions(file.fileType).clone() as IndentOptions
        options.USE_TAB_CHARACTER = detection.useTabs
        if (!detection.useTabs) {
            options.INDENT_SIZE = detection.indentSize
            options.CONTINUATION_INDENT_SIZE = detection.indentSize * 2
        } else {
            // For tab-indented files a continuation of one extra tab reads cleanly.
            options.CONTINUATION_INDENT_SIZE = detection.indentSize
        }
        options.TAB_SIZE = detection.indentSize
        return options
    }

    private fun isBasedPythonFile(file: PsiFile): Boolean {
        if (file.fileType === BasedPythonFileType.INSTANCE) return true
        val ext = file.virtualFile?.extension ?: return false
        return ext.equals(BasedPythonFileType.INSTANCE.defaultExtension, ignoreCase = true)
    }

    private data class Detection(val useTabs: Boolean, val indentSize: Int)

    private fun detectIndent(text: CharSequence): Detection {
        val maxLines = 200
        var scanned = 0
        var sawTab = false
        var sawSpace = false
        // Distinct positive leading-space widths encountered (for spaces only).
        val spaceWidths = sortedSetOf<Int>()

        var i = 0
        val len = text.length
        while (i < len && scanned < maxLines) {
            val lineStart = i
            var spaces = 0
            var lineHasTab = false
            // measure leading whitespace
            while (i < len && (text[i] == ' ' || text[i] == '\t')) {
                if (text[i] == '\t') lineHasTab = true else spaces++
                i++
            }
            val contentStart = i
            // find end of line content
            while (i < len && text[i] != '\n' && text[i] != '\r') i++
            val contentEnd = i
            // consume newline
            if (i < len && text[i] == '\r') i++
            if (i < len && text[i] == '\n') i++

            // skip blank lines and comment lines
            val blank = contentStart >= contentEnd
            val comment = !blank && text[contentStart] == '#'
            if (blank || comment) continue

            scanned++
            if (lineHasTab) {
                sawTab = true
            } else if (spaces > 0) {
                sawSpace = true
                spaceWidths.add(spaces)
            }
        }

        if (sawTab && !sawSpace) {
            return Detection(useTabs = true, indentSize = pickSize(stepFromWidths(spaceWidths)))
        }

        val step = stepFromWidths(spaceWidths)
        return Detection(useTabs = false, indentSize = pickSize(step))
    }

    /** Smallest positive delta between consecutive distinct leading-space widths (incl. 0 baseline). */
    private fun stepFromWidths(widths: Set<Int>): Int {
        if (widths.isEmpty()) return 0
        var prev = 0
        var smallest = Int.MAX_VALUE
        for (w in widths) {
            val delta = w - prev
            if (delta in 1 until smallest) smallest = delta
            prev = w
        }
        return if (smallest == Int.MAX_VALUE) 0 else smallest
    }

    /** Clamp a detected step to one of {2, 4, 8}; default 4 when undetectable. */
    private fun pickSize(step: Int): Int = when {
        step <= 0 -> 4
        step <= 2 -> 2
        step <= 5 -> 4
        else -> 8
    }
}
