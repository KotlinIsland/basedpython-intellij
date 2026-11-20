package dev.basedpython.pycharm.diagram

import com.intellij.diagram.AbstractDiagramElementManager
import com.intellij.diagram.BaseDiagramProvider
import com.intellij.diagram.DiagramBuilder
import com.intellij.diagram.DiagramCategory
import com.intellij.diagram.DiagramDataModel
import com.intellij.diagram.DiagramEdge
import com.intellij.diagram.DiagramEdgeBase
import com.intellij.diagram.DiagramElementManager
import com.intellij.diagram.DiagramNode
import com.intellij.diagram.DiagramNodeBase
import com.intellij.diagram.DiagramPresentationModel
import com.intellij.diagram.DiagramRelationshipInfo
import com.intellij.diagram.DiagramRelationshipInfoAdapter
import com.intellij.diagram.DiagramRelationshipManager
import com.intellij.diagram.DiagramVfsResolver
import com.intellij.diagram.presentation.DiagramLineType
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ui.SimpleColoredText
import com.intellij.ui.SimpleTextAttributes
import dev.basedpython.pycharm.lsp.ext.ByDjangoField
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import dev.basedpython.pycharm.navigation.django.ByDjangoModelGraph
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.Icon

/**
 * The django model diagram (*Show Model Diagram* in the *basedpython Django* tool window): every model
 * of the project, its fields listed inside it, an edge for each `ForeignKey`, `OneToOneField` and
 * `ManyToManyField` labelled with the field and its reverse accessor, and the models of other apps
 * the project's point at. Drawn from `by/djangoStructure` ([ByDjangoModelGraph]); a node opens its
 * class, and the diagram is redrawn from `by`'s latest answer as the code changes.
 *
 * An element is a model's `app_label.Model`, or [ALL] for the whole project's diagram, which is what
 * it is opened on ([ByShowDjangoDiagramAction]). Loaded only with the Diagrams plugin.
 */
internal class ByDjangoDiagramProvider : BaseDiagramProvider<String>() {

    private val elements = Elements().also { it.setUmlProvider(this) }

    override fun getID(): String = ID

    override fun getPresentableName(): String = BasedPythonBundle.message("django.diagram.name")

    override fun getElementManager(): DiagramElementManager<String> = elements

    override fun getVfsResolver(): DiagramVfsResolver<String> = Names

    override fun getRelationshipManager(): DiagramRelationshipManager<String> = Relations

    override fun createDataModel(project: Project, element: String?, file: VirtualFile?, presentationModel: DiagramPresentationModel): DiagramDataModel<String> =
        Model(project, this)

    private class Elements : AbstractDiagramElementManager<String>() {
        /** Opened only on the whole project, by its file; never on what a context holds. */
        override fun findInDataContext(context: DataContext): String? = null

        override fun isAcceptableAsNode(element: Any?): Boolean = element is String && element != ALL

        override fun getElementTitle(element: String): String = element

        override fun getNodeTooltip(element: String): String = element

        override fun getNodeItems(element: String): Array<Any> = emptyArray()

        override fun getNodeItems(element: String, builder: DiagramBuilder): Array<Any> =
            graph(builder.project).nodes.firstOrNull { it.label == element }?.model?.fields?.toTypedArray<Any>() ?: emptyArray()

        override fun getItemName(element: String?, item: Any?, builder: DiagramBuilder): SimpleColoredText? =
            (item as? ByDjangoField)?.symbol?.name?.let { SimpleColoredText(it, SimpleTextAttributes.REGULAR_ATTRIBUTES) }

        override fun getItemType(element: String?, item: Any?, builder: DiagramBuilder?): SimpleColoredText? {
            val field = item as? ByDjangoField ?: return null
            val text = field.relation?.target?.let { "${field.kind} → $it" } ?: listOfNotNull(field.kind, field.type).joinToString(": ")
            return SimpleColoredText(text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }

        override fun getItemIcon(element: String?, item: Any?, builder: DiagramBuilder?): Icon? =
            (item as? ByDjangoField)?.let { if (it.relation != null) AllIcons.Nodes.Related else AllIcons.Nodes.Field }
    }

    /** An element is its own name: a model's `app_label.Model` survives a diagram saved and reopened. */
    private object Names : DiagramVfsResolver<String> {
        override fun getQualifiedName(element: String?): String? = element

        override fun resolveElementByFQN(fqn: String, project: Project): String = fqn
    }

    private object Relations : DiagramRelationshipManager<String> {
        override fun getDependencyInfo(from: String?, to: String?, category: DiagramCategory?): DiagramRelationshipInfo? = null
    }

    private class Node(provider: ByDjangoDiagramProvider, private val node: ByDjangoModelGraph.Node, private val project: Project) :
        DiagramNodeBase<String>(provider) {
        override fun getIdentifyingElement(): String = node.label

        override fun getTooltip(): String = node.model.symbol?.name ?: node.label

        override fun getIcon(): Icon = if (node.external) AllIcons.Nodes.PpLib else AllIcons.Nodes.DataTables

        override fun canNavigate(): Boolean = node.model.symbol?.uri != null

        override fun navigate(requestFocus: Boolean) {
            val symbol = node.model.symbol ?: return
            val file = symbol.uri?.let(ByWorkspaceEditFiles::fileOf) ?: return
            val at = (symbol.selectionRange ?: symbol.range)?.start
            OpenFileDescriptor(project, file, at?.line ?: 0, at?.character ?: 0).navigate(requestFocus)
        }
    }

    private class Edge(from: DiagramNode<String>, to: DiagramNode<String>, field: ByDjangoField) :
        DiagramEdgeBase<String>(from, to, relationship(field))

    /** The diagram's nodes and edges, rebuilt from `by`'s latest structure each time the diagram is refreshed. */
    private class Model(project: Project, private val provider: ByDjangoDiagramProvider) : DiagramDataModel<String>(project, provider) {
        private var nodes: List<Node> = emptyList()
        private var edges: List<Edge> = emptyList()

        init {
            refreshDataModel()
        }

        override fun getNodes(): Collection<DiagramNode<String>> = nodes

        override fun getEdges(): Collection<DiagramEdge<String>> = edges

        override fun getNodeName(node: DiagramNode<String>): String = node.identifyingElement

        override fun addElement(element: String?): DiagramNode<String>? = nodes.firstOrNull { it.identifyingElement == element }

        override fun getModificationTracker(): ModificationTracker = PsiModificationTracker.getInstance(project)

        override fun refreshDataModel() {
            val graph = graph(project)
            val byLabel = graph.nodes.associate { it.label to Node(provider, it, project) }
            nodes = byLabel.values.toList()
            edges = graph.edges.mapNotNull { edge ->
                Edge(byLabel[edge.from] ?: return@mapNotNull null, byLabel[edge.to] ?: return@mapNotNull null, edge.field)
            }
        }

        override fun dispose() {}
    }

    companion object {
        const val ID = "basedpython.DjangoModels"

        /** The element a diagram of every model is opened on. */
        const val ALL = "*"

        private fun graph(project: Project) = ByDjangoModelGraph.build(ByDjangoStructureService.getInstance(project).current())

        /**
         * The edge for [field]: a line to the model it names, a diamond at a many-to-many's ends,
         * labelled with the field and, at the target, the reverse accessor django gives it.
         */
        private fun relationship(field: ByDjangoField): DiagramRelationshipInfo {
            val relation = field.relation
            val many = relation?.kind == "manyToMany"
            return DiagramRelationshipInfoAdapter.Builder()
                .setName(relation?.kind ?: "relation")
                .setLineType(DiagramLineType.SOLID)
                .setSourceArrow(if (many) DiagramRelationshipInfo.DIAMOND else DiagramRelationshipInfo.NONE)
                .setTargetArrow(DiagramRelationshipInfo.ANGLE)
                .setUpperCenterLabel(field.symbol?.name)
                .setUpperTargetLabel(relation?.relatedName)
                .create()
        }
    }
}
