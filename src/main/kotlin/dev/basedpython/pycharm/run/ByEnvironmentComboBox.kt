package dev.basedpython.pycharm.run

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import dev.basedpython.pycharm.env.ByEnvironmentKind
import javax.swing.JList

/**
 * The "Environment" picker shared by every `by` run-configuration editor.
 *
 * Selects which source resolves the `by` binary and the environment it runs in — see
 * [dev.basedpython.pycharm.env.ByEnvironments.resolve]. [ByEnvironmentKind.AUTO] is the default and
 * tries every source in order; the others pin resolution to one source and fail rather than falling
 * back, which is what makes an explicit choice meaningful.
 */
class ByEnvironmentComboBox : ComboBox<ByEnvironmentKind>(ByEnvironmentKind.entries.toTypedArray()) {

    init {
        renderer = object : SimpleListCellRenderer<ByEnvironmentKind>() {
            override fun customize(
                list: JList<out ByEnvironmentKind>,
                value: ByEnvironmentKind?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                text = value?.display.orEmpty()
            }
        }
        toolTipText = "Where the by binary and its Python environment come from. " +
            "Auto-detect tries .venv, then the configured interpreter, then PATH. " +
            "uv is opt-in because it may create the environment and download an interpreter."
    }

    var kind: ByEnvironmentKind
        get() = selectedItem as? ByEnvironmentKind ?: ByEnvironmentKind.AUTO
        set(value) { selectedItem = value }
}
