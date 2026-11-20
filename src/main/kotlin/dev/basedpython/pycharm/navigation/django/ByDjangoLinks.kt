package dev.basedpython.pycharm.navigation.django

import dev.basedpython.pycharm.lsp.ext.ByDjangoStructure
import dev.basedpython.pycharm.lsp.ext.ByEndpoint
import dev.basedpython.pycharm.lsp.ext.ByEndpointSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/** What one of the gutter's django icons links. */
internal enum class ByDjangoLinkKind {
    /** On a view: the `path()`s routing to it. */
    ROUTES,

    /** On a `path()`: the view it routes to. */
    VIEW,

    /** On a model: the admin classes registering it. */
    ADMIN,

    /** On an admin class: the models it is registered for. */
    MODEL,

    /** On a view: the templates it renders. */
    TEMPLATES,

    /** At the top of a template: the views rendering it. */
    RENDERED_BY,
}

/**
 * One icon: where it sits in the file, what it links, the places it goes to, and what each place is
 * called in its tooltip — a path, a class's name, a template's.
 */
internal data class ByDjangoLink(
    val kind: ByDjangoLinkKind,
    val at: Position,
    val targets: List<Location>,
    val names: List<String>,
)

/**
 * The gutter's django icons for one file, from what `by` already answers for the whole project:
 * `by/endpoints` for which `path()` routes to which view, and `by/djangoStructure` for which admin
 * registers which model and which view renders which template.
 *
 * Nothing here decides what anything is. It only reads the two answers from the file's side: each
 * link both ways, an icon only where the other end is in another file — a FastAPI route's decorator
 * sits on its handler already — and no icon for a link with nowhere to go.
 */
internal object ByDjangoLinks {

    private val TOP = Position(0, 0)

    fun forFile(uri: String, endpoints: List<ByEndpoint>, structure: ByDjangoStructure?): List<ByDjangoLink> =
        routes(uri, endpoints) + admin(uri, structure) + templates(uri, structure)

    private fun routes(uri: String, endpoints: List<ByEndpoint>): List<ByDjangoLink> {
        val routed = endpoints.filter { it.handler?.uri != null && it.declaration?.uri != null && it.handler.uri != it.declaration.uri }
        val toViews = routed.filter { it.declaration?.uri == uri }.mapNotNull { endpoint ->
            val at = endpoint.declaration?.start() ?: return@mapNotNull null
            val target = endpoint.handler?.location() ?: return@mapNotNull null
            ByDjangoLink(ByDjangoLinkKind.VIEW, at, listOf(target), listOf(endpoint.path.orEmpty()))
        }
        val toRoutes = routed.filter { it.handler?.uri == uri }
            .groupBy { it.handler?.name() }
            .mapNotNull { (at, routes) ->
                ByDjangoLink(
                    ByDjangoLinkKind.ROUTES,
                    at ?: return@mapNotNull null,
                    routes.mapNotNull { it.declaration?.location() },
                    routes.map { it.path.orEmpty() },
                )
            }
        return toViews + toRoutes
    }

    private fun admin(uri: String, structure: ByDjangoStructure?): List<ByDjangoLink> {
        val apps = structure?.projects.orEmpty().flatMap { it.apps }
        val models = apps.flatMap { app -> app.models.map { model -> "${app.label}.${model.symbol?.name?.substringAfterLast('.')}" to model } }
        val toAdmins = models.filter { (_, model) -> model.symbol?.uri == uri }.mapNotNull { (_, model) ->
            val admins = model.admins.filter { it.uri != uri }
            link(ByDjangoLinkKind.ADMIN, model.symbol, admins.mapNotNull { it.location() }, admins.map { it.shortName() })
        }
        val byLabel = models.toMap()
        val toModels = apps.flatMap { it.admins }.filter { it.symbol?.uri == uri }.mapNotNull { admin ->
            val registered = admin.models.mapNotNull { label -> byLabel[label]?.symbol?.takeIf { it.uri != uri }?.let { label to it } }
            link(ByDjangoLinkKind.MODEL, admin.symbol, registered.mapNotNull { it.second.location() }, registered.map { it.first })
        }
        return toAdmins + toModels
    }

    private fun templates(uri: String, structure: ByDjangoStructure?): List<ByDjangoLink> {
        val views = structure?.projects.orEmpty().flatMap { it.apps }.flatMap { it.views }
        val toTemplates = views.filter { it.symbol?.uri == uri }.mapNotNull { view ->
            val templates = view.templates.filter { it.uri != null && it.uri != uri }
            link(ByDjangoLinkKind.TEMPLATES, view.symbol, templates.map { Location(it.uri, it.range ?: Range(TOP, TOP)) }, templates.map { it.name.orEmpty() })
        }
        val renderers = views.filter { view -> view.templates.any { it.uri == uri } && view.symbol?.uri != uri }
        val renderedBy = renderers.takeIf { it.isNotEmpty() }?.let {
            ByDjangoLink(ByDjangoLinkKind.RENDERED_BY, TOP, it.mapNotNull { view -> view.symbol?.location() }, it.map { view -> view.symbol?.name.orEmpty() })
        }
        return toTemplates + listOfNotNull(renderedBy)
    }

    private fun link(kind: ByDjangoLinkKind, on: ByEndpointSymbol?, targets: List<Location>, names: List<String>): ByDjangoLink? {
        if (targets.isEmpty()) return null
        return ByDjangoLink(kind, on?.name() ?: return null, targets, names)
    }

    /** Where a declaration's name starts, the icon's place. */
    private fun ByEndpointSymbol.name(): Position? = (selectionRange ?: range)?.start

    private fun ByEndpointSymbol.start(): Position? = range?.start

    private fun ByEndpointSymbol.location(): Location? = uri?.let { Location(it, selectionRange ?: range ?: return null) }

    private fun ByEndpointSymbol.shortName(): String = name?.substringAfterLast('.').orEmpty()
}
