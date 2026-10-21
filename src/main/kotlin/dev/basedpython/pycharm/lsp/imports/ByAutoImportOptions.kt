package dev.basedpython.pycharm.lsp.imports

import com.intellij.application.options.editor.AutoImportOptionsProvider
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.application.ApplicationBundle
import com.intellij.openapi.options.UiDslUnnamedConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * basedpython's part of *Settings | Editor | General | Auto Import*: whether unambiguous imports are
 * added on the fly ([ByReferenceImporter]), and whether pasted code gets the imports it needs
 * ([ByPasteImports]).
 *
 * The paste option is the platform's own, `CodeInsightSettings.ADD_IMPORTS_ON_PASTE`, the one Java
 * and Kotlin read. Nothing shows it outside the Java plugin, so a PyCharm has no other way to set it;
 * in an IDE with Java there are two combo boxes for the one value. A combo box bound the usual way
 * writes its value whenever it differs from the stored one, so whichever of two is applied second
 * would write back what it showed, undoing a change made in the first. This one writes only when it
 * was itself changed, and is listed last ([AutoImportOptionsProvider]'s `order="last"`), so a change
 * made in either one holds.
 */
internal class ByAutoImportOptions : UiDslUnnamedConfigurable.Simple(), AutoImportOptionsProvider {

    override fun Panel.createContent() {
        val settings = BasedPythonAppSettings.getInstance()
        group(BasedPythonBundle.message("auto.import.group")) {
            row(BasedPythonBundle.message("auto.import.paste")) {
                val shown = ComboBox(PASTE_CHOICES.toTypedArray())
                shown.renderer = textListCellRenderer { pasteChoiceText(it) }
                var loaded = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE
                cell(shown)
                    .onReset {
                        loaded = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE
                        shown.selectedItem = loaded
                    }
                    .onIsModified { shown.selectedItem != loaded }
                    .onApply {
                        val chosen = shown.selectedItem as? Int ?: return@onApply
                        if (chosen == loaded) return@onApply
                        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = chosen
                        loaded = chosen
                    }
            }
            row {
                checkBox(ApplicationBundle.message("checkbox.add.unambiguous.imports.on.the.fly"))
                    .bindSelected(settings::addUnambiguousImportsOnTheFly)
            }
        }
    }

    private companion object {
        val PASTE_CHOICES = listOf(CodeInsightSettings.YES, CodeInsightSettings.ASK, CodeInsightSettings.NO)

        fun pasteChoiceText(choice: Int?): String = when (choice) {
            CodeInsightSettings.YES -> ApplicationBundle.message("combobox.insert.imports.all")
            CodeInsightSettings.NO -> ApplicationBundle.message("combobox.insert.imports.none")
            CodeInsightSettings.ASK -> ApplicationBundle.message("combobox.insert.imports.ask")
            else -> ""
        }
    }
}
