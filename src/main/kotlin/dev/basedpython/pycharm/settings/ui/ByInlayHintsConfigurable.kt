package dev.basedpython.pycharm.settings.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.codeInsight.hints.InlayContentListener
import com.intellij.codeInsight.hints.InlayHintsSettings
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.InputHandler
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.codeInsight.hints.presentation.StaticDelegatePresentation
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurableProvider
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.SegmentedButton
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.inlay.ByBlockHintPresentation
import dev.basedpython.pycharm.lsp.inlay.ByHintGroup
import dev.basedpython.pycharm.lsp.inlay.ByHintKind
import dev.basedpython.pycharm.lsp.inlay.ByHintKindInfo
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.lsp.inlay.ByHintPlacement
import dev.basedpython.pycharm.lsp.inlay.ByInlayCatalog
import dev.basedpython.pycharm.lsp.inlay.ByInlayHintCatalog
import dev.basedpython.pycharm.lsp.inlay.ByInlayHintPresentation
import dev.basedpython.pycharm.lsp.inlay.ByInlayHints
import dev.basedpython.pycharm.lsp.inlay.ByPushKey
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.Graphics2D
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Settings | Languages & Frameworks | basedpython | Inlay Hints: every kind of hint `by` computes, in
 * its families, each with what it is, how it is shown, and what it looks like.
 *
 * **What is listed is `by`'s.** The kinds, their families, titles, descriptions and examples come
 * from `by/inlayHintCatalog` ([ByInlayCatalog]), the example's hints included — `by` computed them
 * for the example the way it would for a file of the project. A kind `by` adds is here the day the
 * server has it, and nothing on this page can describe a kind differently from how the server
 * computes it, which a list of the plugin's own did (see [ByHintKind]).
 *
 * **The preview is drawn the way the editor draws hints**: the plugin's own presentation
 * ([ByInlayHintPresentation]) in a real basedpython editor, with the page's modes as they stand
 * before Apply. A kind set to never is not drawn; one set to show while the push key is held draws
 * only while it is held, over the preview as anywhere else. The kind selected draws as it would in
 * the editor and the rest of the example's hints are faded, which is how the kind is told apart
 * without drawing anything the editor never would. Clicking a hint selects its kind.
 *
 * **Changing modes**: the segmented switch for the selection — one kind, several, or a whole family
 * — the right-click menu, or Space to step a selection through always, while held, and never.
 */
internal class ByInlayHintsConfigurable(private val project: Project) : SearchableConfigurable {

    private val settings get() = BasedPythonSettings.getInstance(project)

    /** The modes as the page has them, applied on Apply; kinds the page has not touched are not here. */
    private val pending = HashMap<ByHintKind, ByHintMode>()
    private var pendingPushKey: ByPushKey = ByPushKey.CTRL_ALT

    private var ui: Ui? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Inlay Hints"

    override fun getHelpTopic(): String = ID

    override fun createComponent(): JComponent {
        ui?.let { Disposer.dispose(it) }
        val catalog = ByInlayCatalog.getInstance().catalog?.takeIf { it.sourceKinds.isNotEmpty() }
        val created = if (catalog == null) null else Ui(catalog)
        ui = created
        return created?.root ?: noCatalog()
    }

    /** Before any `by` has started in this IDE there is no list of kinds to show. */
    private fun noCatalog(): JComponent = panel {
        row {
            text(
                "by names the kinds of hint it computes when it starts, and it has not started in this IDE yet. " +
                    "Open a .by file, then come back here.",
                maxLineLength = MAX_LINE_LENGTH_WORD_WRAP,
            )
        }
    }

    private fun mode(kind: ByHintKind): ByHintMode = pending[kind] ?: settings.inlayMode(kind)

    override fun isModified(): Boolean {
        val catalog = ui?.catalog ?: return false
        return pendingPushKey != settings.inlayPushKey ||
            catalog.sourceKinds.any { mode(it.kind) != settings.inlayMode(it.kind) }
    }

    override fun apply() {
        val catalog = ui?.catalog ?: return
        val s = settings
        var neverChanged = false
        var changed = pendingPushKey != s.inlayPushKey
        for (info in catalog.sourceKinds) {
            val before = s.inlayMode(info.kind)
            val after = mode(info.kind)
            if (before == after) continue
            changed = true
            if ((before == ByHintMode.NEVER) != (after == ByHintMode.NEVER)) neverChanged = true
            s.setInlayMode(info.kind, after)
        }
        s.inlayPushKey = pendingPushKey
        pending.clear()
        // Which kinds `by` computes is read when it starts, from the modes; the other changes are
        // only in how the plugin draws what it already has.
        if (neverChanged) BasedPythonLspReloader.getInstance(project).onSettingsChanged()
        if (changed) redrawInlayHints()
        ui?.refresh()
    }

    override fun reset() {
        pending.clear()
        pendingPushKey = settings.inlayPushKey
        ui?.refresh()
    }

    override fun disposeUIResources() {
        ui?.let { Disposer.dispose(it) }
        ui = null
    }

    /**
     * Re-collect the inlay hints of every open editor, so a changed mode is visible on Apply.
     *
     * A plain daemon restart is not enough: the platform's inlay pass keeps the PSI modification stamp
     * it last ran against and declines to build a pass at all when the file has not changed since,
     * which is precisely the case here. [InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass]
     * clears that stamp.
     */
    private fun redrawInlayHints() {
        InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
        DaemonCodeAnalyzer.getInstance(project).restart("basedpython inlay hint settings applied")
    }

    /** What a tree row stands for. */
    private sealed interface Node {
        data class Group(val group: ByHintGroup, val kinds: List<ByHintKindInfo>) : Node
        data class Kind(val info: ByHintKindInfo) : Node
    }

    private inner class Ui(val catalog: ByInlayHintCatalog) : Disposable {

        private val root0 = DefaultMutableTreeNode()
        private val model = DefaultTreeModel(root0)
        private val tree = Tree(model)
        private val filter = SearchTextField(false)

        private val title = JBLabel().apply { font = JBUI.Fonts.label().biggerOn(2f).asBold() }
        private val setting = JBLabel().apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }
        private lateinit var description: javax.swing.JEditorPane
        private lateinit var modes: SegmentedButton<ByHintMode>
        private lateinit var modeNote: javax.swing.JEditorPane

        private val document = EditorFactory.getInstance().createDocument("")
        private val editor = EditorFactory.getInstance().createViewer(document, project, EditorKind.PREVIEW) as EditorEx
        private val noExample = JBLabel("by has no standalone example of this kind.").apply {
            foreground = UIUtil.getContextHelpForeground()
            border = JBUI.Borders.empty(8)
        }
        private val previewHolder = JPanel(BorderLayout())

        /** The stub the selected kinds' examples were computed against, for kinds whose hints come from one. */
        private val stubDocument = EditorFactory.getInstance().createDocument("")
        private val stubEditor = EditorFactory.getInstance().createViewer(stubDocument, project, EditorKind.PREVIEW) as EditorEx
        private val exampleCaption = caption("Module")
        private val stubHolder = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            add(caption("Its stub, which the hints come from"), BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
                    add(stubEditor.component, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            isVisible = false
        }

        /** Which kind each drawn inlay is, for a click on one to select it. */
        private val inlayKinds = HashMap<Inlay<*>, ByHintKind>()

        /** Set while the selection is being restored after a rebuild, so it does not count as the user's. */
        private var syncing = false

        val root: JComponent

        init {
            configureEditor()
            buildTree("")
            tree.isRootVisible = false
            tree.showsRootHandles = true
            tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
            tree.cellRenderer = Renderer()
            tree.addTreeSelectionListener { if (!syncing) showSelection() }
            tree.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode != KeyEvent.VK_SPACE) return
                    val kinds = selectedKinds()
                    if (kinds.isEmpty()) return
                    val next = when (kinds.map { mode(it.kind) }.distinct().singleOrNull()) {
                        ByHintMode.ALWAYS -> ByHintMode.ON_PUSH
                        ByHintMode.ON_PUSH -> ByHintMode.NEVER
                        else -> ByHintMode.ALWAYS
                    }
                    setModes(kinds, next)
                    e.consume()
                }
            })
            PopupHandler.installPopupMenu(tree, modeActions(), "BasedPythonInlayHintsSettings")
            filter.textEditor.emptyText.text = "Filter kinds"
            filter.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = buildTree(filter.text.trim())
            })

            val left = JPanel(BorderLayout()).apply {
                add(filter, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
            }
            val header = panel {
                row { cell(title); cell(setting) }
                row { description = text("", maxLineLength = MAX_LINE_LENGTH_WORD_WRAP).align(AlignX.FILL).component }
                row {
                    modes = segmentedButton(ByHintMode.entries.sortedBy { MODE_ORDER.indexOf(it) }) { text = label(it) }
                    modes.whenItemSelected(this@Ui) { selected -> if (!syncing) setModes(selectedKinds(), selected) }
                }
                row { modeNote = comment("", maxLineLength = MAX_LINE_LENGTH_WORD_WRAP).component }
            }
            previewHolder.border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
            val right = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                border = JBUI.Borders.emptyLeft(12)
                add(header, BorderLayout.NORTH)
                // the preview as tall as its example, with what can be done with it right under it,
                // rather than stretched over the rest of the page
                add(
                    JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                        // the stub, when the kinds' hints come from one, over the module they are drawn in
                        add(
                            JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                                add(stubHolder, BorderLayout.NORTH)
                                add(
                                    JPanel(BorderLayout(0, JBUI.scale(2))).apply {
                                        add(exampleCaption, BorderLayout.NORTH)
                                        add(previewHolder, BorderLayout.CENTER)
                                    },
                                    BorderLayout.CENTER,
                                )
                            },
                            BorderLayout.NORTH,
                        )
                        add(
                            JBLabel("Hold the push key over the preview to see the hints set to show while it is held. Click a hint to find its kind.").apply {
                                foreground = UIUtil.getContextHelpForeground()
                                font = JBUI.Fonts.smallFont()
                                verticalAlignment = javax.swing.SwingConstants.TOP
                            },
                            BorderLayout.CENTER,
                        )
                    },
                    BorderLayout.CENTER,
                )
            }
            val splitter = OnePixelSplitter(false, "basedpython.inlay.settings.splitter", 0.36f).apply {
                firstComponent = left
                secondComponent = right
            }
            val top = panel {
                if (!InlayHintsSettings.instance().hintsEnabled(BasedPythonLanguage)) {
                    row {
                        comment(
                            "Inlay hints for basedpython are off in <a href='inlay'>Editor | Inlay Hints</a>, so none of these is drawn.",
                            maxLineLength = MAX_LINE_LENGTH_WORD_WRAP,
                            action = { e -> selectInSettings(e.source as? java.awt.Component, "inlay.hints") },
                        )
                    }
                }
                row("Push key:") {
                    val combo = ComboBox(ByPushKey.entries.toTypedArray()).apply {
                        renderer = textListCellRenderer("") { it.display }
                        selectedItem = pendingPushKey
                        addActionListener {
                            pendingPushKey = selectedItem as? ByPushKey ?: ByPushKey.CTRL_ALT
                            showSelection()
                        }
                    }
                    cell(combo)
                }
            }
            root = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
                add(top, BorderLayout.NORTH)
                add(splitter, BorderLayout.CENTER)
            }
            selectFirstKind()
        }

        private fun configureEditor() {
            configureViewer(editor)
            configureViewer(stubEditor)
            editor.addEditorMouseListener(object : EditorMouseListener {
                override fun mouseClicked(event: EditorMouseEvent) {
                    val kind = event.inlay?.let(inlayKinds::get) ?: return
                    select(kind)
                }
            }, this)
        }

        /** A read-only basedpython editor sized to what it shows, as the preview and the stub are. */
        private fun configureViewer(editor: EditorEx) {
            editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, BasedPythonFileType.INSTANCE)
            editor.settings.apply {
                isLineNumbersShown = false
                isFoldingOutlineShown = false
                isLineMarkerAreaShown = false
                isIndentGuidesShown = false
                isRightMarginShown = false
                isCaretRowShown = false
                additionalLinesCount = 1
                additionalColumnsCount = 0
                isUseSoftWraps = false
            }
            editor.setCaretEnabled(false)
            // sized to its example (see preview), so it never has anything to scroll; a family's
            // longer one makes the page taller instead
            editor.setVerticalScrollbarVisible(false)
            editor.setHorizontalScrollbarVisible(false)
            editor.contentComponent.border = JBUI.Borders.empty(6, 4)
        }

        /** Shows the stubs of [kinds] that have one over the preview, or nothing when none has. */
        private fun showStubs(kinds: List<ByHintKindInfo>) {
            val stubs = kinds.mapNotNull { info -> info.exampleStub?.takeIf { it.isNotBlank() }?.let { info to it } }
            stubHolder.isVisible = stubs.isNotEmpty()
            exampleCaption.isVisible = stubs.isNotEmpty()
            if (stubs.isEmpty()) return
            val text = stubs.joinToString("\n") { (info, stub) ->
                (if (stubs.size > 1) "# ${info.title}\n" else "") + stub.trimEnd('\n') + "\n"
            }
            com.intellij.openapi.application.runWriteAction { stubDocument.setText(text) }
            stubEditor.component.preferredSize = java.awt.Dimension(0, stubEditor.lineHeight * (stubDocument.lineCount + 1) + JBUI.scale(14))
        }

        private fun caption(text: String) = JBLabel(text).apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }

        /** The tree for [query]: every family with a kind matching it, or every family. */
        private fun buildTree(query: String) {
            val keep = selectedKinds().map { it.kind }.toSet()
            root0.removeAllChildren()
            val kinds = catalog.sourceKinds
            val groupIds = (catalog.groups.orEmpty().map { it.id } + kinds.map { it.group }).distinct()
            for (id in groupIds) {
                val members = kinds.filter { it.group == id && matches(it, query) }
                if (members.isEmpty()) continue
                val group = catalog.group(id) ?: ByHintGroup(id, id)
                val node = DefaultMutableTreeNode(Node.Group(group, kinds.filter { it.group == id }))
                members.forEach { node.add(DefaultMutableTreeNode(Node.Kind(it))) }
                root0.add(node)
            }
            model.reload()
            TreeUtil.expandAll(tree)
            if (keep.isNotEmpty()) selectKinds(keep) else selectFirstKind()
        }

        private fun matches(info: ByHintKindInfo, query: String): Boolean =
            query.isEmpty() || listOf(info.title, info.description, info.setting).any { it.contains(query, ignoreCase = true) }

        private fun selectFirstKind() {
            val first = TreeUtil.treeNodeTraverser(root0).filter(DefaultMutableTreeNode::class.java)
                .firstOrNull { it.userObject is Node.Kind } ?: return
            tree.selectionPath = TreePath(first.path)
        }

        private fun nodesFor(kinds: Set<ByHintKind>): List<DefaultMutableTreeNode> =
            TreeUtil.treeNodeTraverser(root0).filter(DefaultMutableTreeNode::class.java)
                .filter { (it.userObject as? Node.Kind)?.info?.kind in kinds }.toList()

        private fun selectKinds(kinds: Set<ByHintKind>) {
            val paths = nodesFor(kinds).map { TreePath(it.path) }
            if (paths.isEmpty()) return selectFirstKind()
            tree.selectionPaths = paths.toTypedArray()
        }

        /** Selects [kind], clearing the filter if it hides it, and brings it into view. */
        fun select(kind: ByHintKind) {
            if (nodesFor(setOf(kind)).isEmpty() && filter.text.isNotEmpty()) filter.text = ""
            val node = nodesFor(setOf(kind)).firstOrNull() ?: return
            tree.selectionPath = TreePath(node.path)
            tree.scrollPathToVisible(tree.selectionPath)
        }

        /** The kinds the selection covers: each kind selected, and every kind of each family selected. */
        private fun selectedKinds(): List<ByHintKindInfo> =
            tree.selectionPaths.orEmpty().flatMap { path ->
                when (val node = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject) {
                    is Node.Kind -> listOf(node.info)
                    is Node.Group -> node.kinds
                    else -> emptyList()
                }
            }.distinctBy { it.setting }

        private fun setModes(kinds: List<ByHintKindInfo>, mode: ByHintMode?) {
            if (mode == null || kinds.isEmpty()) return
            for (info in kinds) pending[info.kind] = mode
            tree.repaint()
            showSelection()
        }

        /** Shows what is selected: its title and description, its mode, and its example. */
        fun showSelection() {
            val selection = tree.selectionPaths.orEmpty().mapNotNull { (it.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Node }
            val kinds = selectedKinds()
            val single = selection.singleOrNull()
            when {
                single is Node.Kind -> {
                    title.text = single.info.title
                    setting.text = single.info.setting
                    description.text = single.info.description
                }
                single is Node.Group -> {
                    title.text = single.group.title
                    setting.text = "${single.kinds.size} kinds"
                    description.text = single.kinds.joinToString(", ") { it.title } + "."
                }
                else -> {
                    title.text = if (kinds.isEmpty()) "" else "${kinds.size} kinds"
                    setting.text = ""
                    description.text = kinds.joinToString(", ") { it.title }
                }
            }
            val current = kinds.map { mode(it.kind) }.distinct().singleOrNull()
            syncing = true
            try {
                modes.selectedItem = current
            } finally {
                syncing = false
            }
            modeNote.text = when (current) {
                ByHintMode.NEVER -> "by does not compute these hints."
                ByHintMode.ON_PUSH -> "Drawn only while ${pendingPushKey.display} is held."
                ByHintMode.ALWAYS -> ""
                null -> if (kinds.isEmpty()) "" else "The selected kinds are shown in different ways."
            }
            preview(kinds, emphasise = single !is Node.Group)
        }

        /**
         * Draws the examples of [kinds] with the hints `by` computed for them.
         *
         * Each example's hints are its own kind's and any other kind's the example happens to have,
         * each drawn — or not — by its own mode on this page, so the example looks as the same code
         * would in the editor with these settings. With [emphasise], hints of a kind not selected are
         * faded so the selected ones stand out without being drawn differently from the editor.
         */
        private fun preview(kinds: List<ByHintKindInfo>, emphasise: Boolean) {
            val examples = kinds.mapNotNull { info -> info.example?.takeIf { it.source.isNotEmpty() }?.let { info to it } }
            previewHolder.removeAll()
            showStubs(examples.map { it.first })
            if (examples.isEmpty()) {
                inlayKinds.keys.forEach { Disposer.dispose(it) }
                inlayKinds.clear()
                if (kinds.isNotEmpty()) previewHolder.add(noExample, BorderLayout.NORTH)
                previewHolder.revalidate()
                previewHolder.repaint()
                return
            }
            previewHolder.add(editor.component, BorderLayout.CENTER)

            val text = StringBuilder()
            val placed = ArrayList<Pair<Int, org.eclipse.lsp4j.InlayHint>>()
            val starts = ArrayList<Int>()
            for ((index, pair) in examples.withIndex()) {
                val (info, example) = pair
                if (index > 0) text.append("\n")
                // several examples at once, as a family is: each under a comment saying whose it is
                if (examples.size > 1) text.append("# ").append(info.title).append('\n')
                starts += text.length
                text.append(example.source.trimEnd('\n')).append('\n')
            }
            val content = text.toString()
            // the old example's inlays go before its text does, rather than being pushed to the
            // start of the new text while it replaces the old
            inlayKinds.keys.forEach { Disposer.dispose(it) }
            inlayKinds.clear()
            com.intellij.openapi.application.runWriteAction { document.setText(content) }
            for ((index, pair) in examples.withIndex()) {
                val lineOffsets = lineStarts(content, starts[index])
                for (hint in pair.second.hints.orEmpty()) {
                    val position = hint.position ?: continue
                    val lineStart = lineOffsets.getOrNull(position.line) ?: continue
                    placed += (lineStart + position.character).coerceAtMost(content.length) to hint
                }
            }

            val chosen = kinds.map { it.kind }.toSet()
            val catalogs = ByInlayCatalog.getInstance()
            // what the hints drawn as lines of their own take, held whether they show now or only
            // under the push key, so the preview does not change size when it goes down
            var blockLines = 0
            for ((offset, hint) in placed.sortedBy { it.first }) {
                val kind = ByInlayHints.kindOf(hint)
                val mode = mode(kind)
                if (!mode.isCollected) continue
                val label = ByInlayHints.partsOf(hint).joinToString("") { it.text }.ifEmpty { continue }
                val relates = catalog.kind(kind)?.relatesToPrecedingText ?: catalogs.relatesToPrecedingText(kind)
                val placement = ByInlayHints.placementOf(hint)
                if (placement.isBlock) {
                    val block = ByBlockHintPresentation(editor = editor, text = label, mode = mode, pushKey = pendingPushKey)
                    val shown: InlayPresentation = if (emphasise && kind !in chosen) Faded(block) else block
                    val inlay = editor.inlayModel.addBlockElement(
                        offset,
                        relates,
                        placement == ByHintPlacement.ABOVE,
                        0,
                        BlockRenderer(shown),
                    ) ?: continue
                    shown.addListener(InlayContentListener(inlay))
                    inlayKinds[inlay] = kind
                    blockLines += label.count { it == '\n' } + 1
                    continue
                }
                val drawn = ByInlayHintPresentation(
                    editor = editor,
                    text = ByInlayHints.truncate(label),
                    padLeft = hint.paddingLeft == true,
                    padRight = hint.paddingRight == true,
                    mode = mode,
                    pushKey = pendingPushKey,
                )
                val presentation: InlayPresentation = if (emphasise && kind !in chosen) Faded(drawn) else drawn
                val inlay = editor.inlayModel.addInlineElement(
                    offset,
                    relates,
                    PresentationRenderer(presentation),
                ) ?: continue
                presentation.addListener(InlayContentListener(inlay))
                inlayKinds[inlay] = kind
            }
            // exactly the example's lines: an editor asks a scroll pane for whatever room it is given
            editor.component.preferredSize =
                java.awt.Dimension(0, editor.lineHeight * (document.lineCount + blockLines + 1) + JBUI.scale(14))
            previewHolder.revalidate()
            previewHolder.repaint()
        }

        /** The offset each line of [content] from [from] on starts at, in order. */
        private fun lineStarts(content: String, from: Int): List<Int> {
            val result = arrayListOf(from)
            var i = from
            while (i < content.length) {
                if (content[i] == '\n') result += i + 1
                i++
            }
            return result
        }

        fun refresh() {
            tree.repaint()
            showSelection()
        }

        private fun modeActions() = DefaultActionGroup().apply {
            for (mode in MODE_ORDER) {
                add(object : DumbAwareAction(label(mode)) {
                    override fun actionPerformed(e: AnActionEvent) = setModes(selectedKinds(), mode)
                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                })
            }
        }

        override fun dispose() {
            inlayKinds.keys.forEach { Disposer.dispose(it) }
            inlayKinds.clear()
            EditorFactory.getInstance().releaseEditor(editor)
            EditorFactory.getInstance().releaseEditor(stubEditor)
        }

        private inner class Renderer : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(
                tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
            ) {
                when (val node = (value as? DefaultMutableTreeNode)?.userObject) {
                    is Node.Group -> {
                        append(node.group.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        val distinct = node.kinds.map { mode(it.kind) }.distinct()
                        append("  " + (distinct.singleOrNull()?.let(::shortLabel) ?: "mixed"), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    is Node.Kind -> {
                        val mode = mode(node.info.kind)
                        append(node.info.title, if (mode == ByHintMode.NEVER) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("  " + shortLabel(mode), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * A hint drawn as lines of its own in the preview, as tall as it is: the platform's
     * [PresentationRenderer] is for a hint in a line, and takes the line's height.
     */
    private class BlockRenderer(private val presentation: InlayPresentation) :
        EditorCustomElementRenderer, InputHandler by presentation {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int = presentation.width

        override fun calcHeightInPixels(inlay: Inlay<*>): Int = presentation.height

        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.translate(targetRegion.x, targetRegion.y)
                presentation.paint(g2, textAttributes)
            } finally {
                g2.dispose()
            }
        }
    }

    /** Another kind's hint in the preview: drawn as the editor draws it, at a fraction of its strength. */
    private class Faded(inner: InlayPresentation) : StaticDelegatePresentation(inner) {
        override fun paint(g: Graphics2D, attributes: TextAttributes) {
            val composite = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, FADE)
            try {
                super.paint(g, attributes)
            } finally {
                g.composite = composite
            }
        }
    }

    private fun label(mode: ByHintMode): String = when (mode) {
        ByHintMode.NEVER -> "Never"
        ByHintMode.ON_PUSH -> "While held"
        ByHintMode.ALWAYS -> "Always"
    }

    private fun shortLabel(mode: ByHintMode): String = when (mode) {
        ByHintMode.NEVER -> "off"
        ByHintMode.ON_PUSH -> "while held"
        ByHintMode.ALWAYS -> "always"
    }

    companion object {
        const val ID: String = "dev.basedpython.pycharm.settings.inlayHints"

        private val MODE_ORDER = listOf(ByHintMode.NEVER, ByHintMode.ON_PUSH, ByHintMode.ALWAYS)

        /** How strongly a hint of a kind not selected is drawn in the preview. */
        private const val FADE = 0.35f
    }
}

internal class ByInlayHintsConfigurableProvider(private val project: Project) : ConfigurableProvider() {
    /** Where the basedpython page has hints to configure: the same projects Editor | Inlay Hints lists basedpython in. */
    override fun canCreateConfigurable(): Boolean = BasedPythonProjectDetector.isPythonProject(project)

    override fun createConfigurable(): Configurable = ByInlayHintsConfigurable(project)
}
