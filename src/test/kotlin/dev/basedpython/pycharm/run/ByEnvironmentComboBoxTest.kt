package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.env.ByEnvironmentKind
import junit.framework.TestCase

/**
 * The Environment picker must be able to display every kind that can be persisted.
 *
 * A non-editable `JComboBox` silently ignores `setSelectedItem` for a value outside its model — it
 * does not throw, and the previous selection stands. So a kind missing from the model would make
 * `resetEditorFrom` a no-op and `applyEditorTo` write the *displayed* value back, silently replacing
 * whatever the configuration actually held. These tests pin the round-trip that prevents that.
 */
class ByEnvironmentComboBoxTest : TestCase() {

    fun `test every kind survives a set-then-get round-trip`() {
        val combo = ByEnvironmentComboBox()
        for (kind in ByEnvironmentKind.entries) {
            combo.kind = kind
            assertEquals("$kind is not in the combo model, so selecting it was silently ignored", kind, combo.kind)
        }
    }

    fun `test the model holds exactly the known kinds`() {
        val combo = ByEnvironmentComboBox()
        val model = (0 until combo.itemCount).map { combo.getItemAt(it) }
        assertEquals(ByEnvironmentKind.entries.toList(), model)
    }

    fun `test the editor round-trip preserves a non-default choice`() {
        // The concrete data-loss path: load a config, touch nothing, apply. The value must survive.
        val options = ByCommonOptions()
        options.environmentKind = ByEnvironmentKind.UV

        val combo = ByEnvironmentComboBox()
        combo.kind = options.environmentKind  // resetEditorFrom
        options.environmentKind = combo.kind  // applyEditorTo

        assertEquals(ByEnvironmentKind.UV, options.environmentKind)
        assertEquals("uv", options.environment)
    }
}
