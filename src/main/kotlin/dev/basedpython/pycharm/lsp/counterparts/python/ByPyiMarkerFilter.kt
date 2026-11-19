package dev.basedpython.pycharm.lsp.counterparts.python

import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.openapi.editor.GutterMarkPreprocessor
import com.jetbrains.python.pyi.PyiRelatedItemLineMarkerProvider
import dev.basedpython.pycharm.lsp.counterparts.ByCounterpartMarkers
import dev.basedpython.pycharm.lsp.counterparts.ByStubMarkFilter

/**
 * Takes PyCharm's stub icon out of the gutter wherever [ByCounterpartMarkers] has put one on the same
 * name, so that a declaration of a `.py` or a `.pyi` has one stub icon, and it says what `by` says.
 *
 * Measured in PyCharm 2026.3 (PY-263.8311.9): in a basedpython project whose `.py` files are left to
 * the Python plugin (*Treat .py files as basedpython*: automatic, which is what PyCharm gets), its
 * `PyiRelatedItemLineMarkerProvider` draws *Has stub item in mod.pyi* on every declaration of
 * `mod.py` the stub declares and *Stub for item in mod.py* on the stub's, resolving the other end
 * once the project has an interpreter — so beside this plugin's icons there were two on every such
 * name. It draws nothing on a `.by` or a `.byi`, which are not its files, nor anywhere once `.py` is
 * basedpython's: the module a stub resolves to is then not one of its `PyFile`s.
 *
 * Done in the gutter rather than by keeping PyCharm's provider from running, because nothing else is
 * open to a plugin: a line marker provider cannot be switched off for one project — Settings | Editor
 * | General | Gutter Icons is the whole IDE's, and PyCharm's has no entry there to switch — and taking
 * it out of the extension point takes it out of every project, with no way to put it back on unload.
 * Only where this plugin's icon is drawn ([ByStubMarkFilter]), so a project `by` does not serve, a
 * `by` from before `by/documentCounterparts`, or a name `by` gives no counterpart keeps PyCharm's.
 *
 * Loaded only with the Python plugin (`basedpython-python.xml`), whose provider is recognised by its
 * icon and by its navigation handler being one of its own lambdas: it builds plain
 * `RelatedItemLineMarkerInfo`s, with nothing else to tell them apart by.
 */
class ByPyiMarkerFilter : GutterMarkPreprocessor {
    override fun processMarkers(list: List<GutterMark>): List<GutterMark> =
        ByStubMarkFilter.process(list, PyiRelatedItemLineMarkerProvider::class.java.name, PyiRelatedItemLineMarkerProvider.ICON)
}
