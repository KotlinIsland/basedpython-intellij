package dev.basedpython.pycharm.navigation.django

import com.intellij.icons.AllIcons
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.util.ui.tree.TreeUtil
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.lsp.ext.ByEndpointSymbol
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import dev.basedpython.pycharm.run.django.ByManageToolWindow
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * The *Django* tool window: a project's apps and, in each, its models with their fields and
 * relations, its admin, the views its routes reach, its forms, serializers, signal receivers and
 * management commands — every row opening where it is written.
 *
 * PyCharm Professional's *Django Structure* window comes from the Python plugin, and IntelliJ IDEA
 * without it — or with it switched off, as a basedpython project leaves it — has no such view. This
 * one draws what `by/djangoStructure` answers ([ByDjangoStructureService]); what anything is, is
 * `by`'s ([ByDjangoStructureTree]).
 */
internal class ByDjangoStructureToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isBasedPythonProject(project) && ByDjangoStructureToolWindow.looksLikeDjango(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ByDjangoStructurePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** The tool window's identity, and when it is offered. */
internal object ByDjangoStructureToolWindow {

    /** Must match the `id` in plugin.xml. */
    const val ID: String = "basedpython Django"

    /** The group of plugin.xml whose actions the window's toolbar shows after its own. */
    const val TOOLBAR_GROUP: String = "basedpython.DjangoStructureToolbar"

    /**
     * Whether the project looks like a django one before anyone has asked `by`: a `manage.py` at
     * its root, or django among the dependencies its root declares. Asked while the project opens,
     * so it reads two small files and parses nothing. A project this misses gets the window the
     * first time `by` answers with a structure ([offer]).
     */
    fun looksLikeDjango(project: Project): Boolean {
        val base = project.basePath?.let(Path::of) ?: return false
        if (Files.isRegularFile(base.resolve("manage.py"))) return true
        return listOf("pyproject.toml", "requirements.txt").any { name ->
            val file = base.resolve(name)
            Files.isRegularFile(file) && runCatching { Files.readString(file) }.getOrDefault("").contains("django", ignoreCase = true)
        }
    }

    /** Shows the stripe buttons of this window and the *manage.py* one once `by` has answered with a structure. One-way. EDT. */
    fun offer(project: Project) {
        for (id in listOf(ID, ByManageToolWindow.ID)) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(id) ?: continue
            if (!window.isAvailable) window.isAvailable = true
        }
    }
}

/** The tree, its toolbar, and what it says when there is no tree to show. */
internal class ByDjangoStructurePanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = ByDjangoStructureService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
    }

    /** Whether `django.contrib.*` and other apps outside the workspace are listed. */
    private var showExternal = false

    /** What was last drawn, so an answer that changes nothing leaves the tree — and its selection — alone. */
    private var drawn: List<ByDjangoNode>? = null
    private var expandedOnce = false

    init {
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        installNavigation()
        toolbar = ActionManager.getInstance().createActionToolbar("BasedPythonDjangoStructure", actions(), true)
            .also { it.targetComponent = tree }.component

        val connection = project.messageBus.connect(this)
        connection.subscribe(
            ByLspLifecycleListener.TOPIC,
            object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) = service.refresh()
                override fun serverStopped(serverName: String, shutdownNormally: Boolean) = service.refresh()
            },
        )
        // an edit anywhere can add an app, a model or a route; asked again only while it is on screen
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { if (isShowing) service.refresh() })

        service.watch(this) { state -> ApplicationManager.getApplication().invokeLater({ show(state) }, project.disposed) }
        service.refresh()
    }

    override fun dispose() = Unit

    private fun show(state: ByDjangoStructureService.State) {
        val nodes = (state as? ByDjangoStructureService.State.Ready)?.let { ByDjangoStructureTree.build(it.structure, showExternal) }
        tree.emptyText.text = BasedPythonBundle.message(
            when (state) {
                ByDjangoStructureService.State.Idle -> "django.structure.idle"
                ByDjangoStructureService.State.NoServer -> "django.structure.noServer"
                ByDjangoStructureService.State.Unsupported -> "django.structure.unsupported"
                ByDjangoStructureService.State.Failed -> "django.structure.failed"
                ByDjangoStructureService.State.NotDjango -> "django.structure.notDjango"
                is ByDjangoStructureService.State.Ready -> "django.structure.empty"
            },
        )
        draw(nodes.orEmpty())
    }

    private fun draw(nodes: List<ByDjangoNode>) {
        if (nodes == drawn) return
        drawn = nodes
        val expanded = TreeUtil.collectExpandedPaths(tree).map { path -> path.path.drop(1).map { it.toString() } }.toSet()
        root.removeAllChildren()
        nodes.forEach { root.add(swing(it)) }
        model.reload()
        if (!expandedOnce && nodes.isNotEmpty()) {
            // the apps, open on their groups: what the project has, at a glance
            expandedOnce = true
            TreeUtil.expand(tree, 2)
        } else {
            TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java).forEach { node ->
                val path = node.path.drop(1).map { it.toString() }
                if (path.isNotEmpty() && path in expanded) tree.expandPath(javax.swing.tree.TreePath(node.path))
            }
        }
    }

    private fun swing(node: ByDjangoNode): DefaultMutableTreeNode =
        DefaultMutableTreeNode(node).also { swing -> node.children.forEach { swing.add(swing(it)) } }

    private fun installNavigation() {
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean = open(selected()?.symbol)
        }.installOn(tree)
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                if (event.keyCode == KeyEvent.VK_ENTER && open(selected()?.symbol)) event.consume()
            }
        })
    }

    private fun selected(): ByDjangoNode? =
        (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ByDjangoNode

    /** Opens [symbol] at the name it is written under; false when there is nowhere to go. */
    private fun open(symbol: ByEndpointSymbol?): Boolean {
        val file = symbol?.uri?.let(ByWorkspaceEditFiles::fileOf) ?: return false
        val at = (symbol.selectionRange ?: symbol.range)?.start
        OpenFileDescriptor(project, file, at?.line ?: 0, at?.character ?: 0).navigate(true)
        return true
    }

    private fun actions() = DefaultActionGroup().apply {
        add(object : DumbAwareAction(BasedPythonBundle.message("django.structure.refresh"), null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = service.refresh()
        })
        add(object : DumbAwareToggleAction(BasedPythonBundle.message("django.structure.showExternal"), null, AllIcons.Nodes.PpLibFolder) {
            override fun isSelected(e: AnActionEvent): Boolean = showExternal

            override fun setSelected(e: AnActionEvent, state: Boolean) {
                showExternal = state
                show(service.state.value)
            }

            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })
        addSeparator()
        val expander = DefaultTreeExpander(tree)
        add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree))
        add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree))
        // the optional descriptors' actions: DATABASES as data sources, with the Database plugin
        (ActionManager.getInstance().getAction(ByDjangoStructureToolWindow.TOOLBAR_GROUP) as? DefaultActionGroup)?.let { more ->
            addSeparator()
            add(more)
        }
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? ByDjangoNode ?: return
            icon = iconOf(node.kind)
            append(node.text, if (node.kind == ByDjangoNodeKind.GROUP) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            node.detail?.let {
                append("  ")
                append(it, if (node.warning) WARNING else SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            toolTipText = node.symbol?.uri?.substringAfterLast('/')
        }

        private fun iconOf(kind: ByDjangoNodeKind): Icon = when (kind) {
            ByDjangoNodeKind.PROJECT -> AllIcons.Nodes.Project
            ByDjangoNodeKind.APP -> AllIcons.Nodes.Module
            ByDjangoNodeKind.EXTERNAL_APP -> AllIcons.Nodes.PpLib
            ByDjangoNodeKind.GROUP -> AllIcons.Nodes.Folder
            ByDjangoNodeKind.MODEL -> AllIcons.Nodes.DataTables
            ByDjangoNodeKind.FIELD -> AllIcons.Nodes.Field
            ByDjangoNodeKind.RELATION -> AllIcons.Nodes.Related
            ByDjangoNodeKind.ADMIN -> AllIcons.Nodes.Class
            ByDjangoNodeKind.VIEW_FUNCTION -> AllIcons.Nodes.Function
            ByDjangoNodeKind.VIEW_CLASS -> AllIcons.Nodes.Class
            ByDjangoNodeKind.FORM -> AllIcons.Nodes.Class
            ByDjangoNodeKind.SERIALIZER -> AllIcons.Nodes.Class
            ByDjangoNodeKind.RECEIVER -> AllIcons.Nodes.Lambda
            ByDjangoNodeKind.COMMAND -> AllIcons.Nodes.Console
        }

        companion object {
            private val WARNING = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.ORANGE)
        }
    }
}
