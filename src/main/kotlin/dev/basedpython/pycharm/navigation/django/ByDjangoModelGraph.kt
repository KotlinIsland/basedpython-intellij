package dev.basedpython.pycharm.navigation.django

import dev.basedpython.pycharm.lsp.ext.ByDjangoField
import dev.basedpython.pycharm.lsp.ext.ByDjangoModel
import dev.basedpython.pycharm.lsp.ext.ByDjangoStructure

/**
 * A django project's models as a graph, for the model diagram: a node for each of the project's own
 * models, keyed `app_label.Model` as django keys them, and for each model of another app — `auth.User`
 * — one of them points at; an edge for each relation field, from the model declaring it to the model
 * it names. `by/djangoStructure` says what is a model and where a relation points; this only joins
 * the two.
 */
internal object ByDjangoModelGraph {

    data class Node(val label: String, val model: ByDjangoModel, val external: Boolean)

    data class Edge(val from: String, val to: String, val field: ByDjangoField)

    data class Graph(val nodes: List<Node>, val edges: List<Edge>)

    fun build(structure: ByDjangoStructure?): Graph {
        val apps = structure?.projects.orEmpty().flatMap { it.apps }
        val all = apps.flatMap { app ->
            app.models.mapNotNull { model ->
                val name = model.symbol?.name?.substringAfterLast('.') ?: return@mapNotNull null
                Node("${app.label}.$name", model, app.external)
            }
        }.distinctBy { it.label }
        val byLabel = all.associateBy { it.label }
        val own = all.filter { !it.external }
        val edges = own.flatMap { node ->
            node.model.fields.mapNotNull { field ->
                val target = field.relation?.target?.takeIf { it in byLabel } ?: return@mapNotNull null
                Edge(node.label, target, field)
            }
        }
        val pointedAt = edges.map { it.to }.toSet()
        return Graph(own + all.filter { it.external && it.label in pointedAt }, edges)
    }
}
