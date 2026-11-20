package dev.basedpython.pycharm.navigation.django

import dev.basedpython.pycharm.lsp.ext.ByDjangoApp
import dev.basedpython.pycharm.lsp.ext.ByDjangoField
import dev.basedpython.pycharm.lsp.ext.ByDjangoModel
import dev.basedpython.pycharm.lsp.ext.ByDjangoProject
import dev.basedpython.pycharm.lsp.ext.ByDjangoStructure
import dev.basedpython.pycharm.lsp.ext.ByEndpointSymbol

/** What a row of the *Django* tool window is, which decides its icon. */
internal enum class ByDjangoNodeKind {
    PROJECT, APP, EXTERNAL_APP, GROUP, MODEL, FIELD, RELATION, ADMIN, VIEW_FUNCTION, VIEW_CLASS, FORM, SERIALIZER,
    RECEIVER, COMMAND,
}

/**
 * One row: its name, what is written after it in grey, where it opens, and its rows below.
 *
 * [warning] is a fact worth seeing at a glance — an admin class nothing registers. Equality is the
 * whole row, so the tree can tell an answer that changed nothing from one that did.
 */
internal data class ByDjangoNode(
    val kind: ByDjangoNodeKind,
    val text: String,
    val detail: String? = null,
    val symbol: ByEndpointSymbol? = null,
    val children: List<ByDjangoNode> = emptyList(),
    val warning: Boolean = false,
) {
    /** What a tree path is remembered by across answers: the kind and the name, not the detail. */
    override fun toString(): String = "${kind.name.lowercase()}:$text"
}

/**
 * `by/djangoStructure`'s answer as the rows of the *Django* tool window.
 *
 * Nothing here decides what anything *is* — that a class is a model, which app a view belongs to,
 * what a relation's reverse accessor is called. That is all `by`'s, in the answer. This only lays
 * it out: one project's apps at the top (a project row each when the session has several), each
 * app's models, admin, views, forms, serializers, receivers and commands in groups, a group left out
 * when the app has none of it, and the apps that are not the project's own — `django.contrib.*` —
 * only when [showExternal], with their models alone, since those are what the project's relations
 * point at.
 */
internal object ByDjangoStructureTree {

    fun build(structure: ByDjangoStructure, showExternal: Boolean): List<ByDjangoNode> {
        val projects = structure.projects
        if (projects.size == 1) return apps(projects.single(), showExternal)
        return projects.map { project ->
            ByDjangoNode(
                ByDjangoNodeKind.PROJECT,
                project.settings?.name ?: "settings",
                detail = project.urlconf?.name?.let { "urls: $it" },
                symbol = project.settings,
                children = apps(project, showExternal),
            )
        }
    }

    private fun apps(project: ByDjangoProject, showExternal: Boolean): List<ByDjangoNode> =
        project.apps.filter { showExternal || !it.external }.map(::app)

    private fun app(app: ByDjangoApp): ByDjangoNode {
        val label = app.label ?: app.name ?: "app"
        val detail = app.name?.takeIf { it != label }
        val symbol = app.config ?: app.module
        if (app.external) {
            return ByDjangoNode(ByDjangoNodeKind.EXTERNAL_APP, label, detail, symbol, app.models.map(::model))
        }
        val groups = listOfNotNull(
            group("Models", app.models.map(::model)),
            group("Admin", admin(app)),
            group(
                "Views",
                app.views.map { view ->
                    ByDjangoNode(
                        if (view.kind == "class") ByDjangoNodeKind.VIEW_CLASS else ByDjangoNodeKind.VIEW_FUNCTION,
                        view.symbol?.name ?: "view",
                        detail = view.routes.joinToString(", ").ifEmpty { null },
                        symbol = view.symbol,
                    )
                },
            ),
            group("Forms", app.forms.map { ByDjangoNode(ByDjangoNodeKind.FORM, it.name ?: "form", symbol = it) }),
            group(
                "Serializers",
                app.serializers.map { ByDjangoNode(ByDjangoNodeKind.SERIALIZER, it.name ?: "serializer", symbol = it) },
            ),
            group(
                "Receivers",
                app.receivers.map { receiver ->
                    val detail = listOfNotNull(receiver.signal, receiver.sender?.let { "from $it" }).joinToString(" ")
                    ByDjangoNode(ByDjangoNodeKind.RECEIVER, receiver.symbol?.name ?: "receiver", detail.ifEmpty { null }, receiver.symbol)
                },
            ),
            group("Commands", app.commands.map { ByDjangoNode(ByDjangoNodeKind.COMMAND, it.name ?: "command", symbol = it) }),
        )
        return ByDjangoNode(ByDjangoNodeKind.APP, label, detail, symbol, groups)
    }

    /**
     * The app's admin classes, and its models registered with none — `admin.site.register(Tag)` —
     * which are in the admin all the same, under the model's own name.
     */
    private fun admin(app: ByDjangoApp): List<ByDjangoNode> {
        val classes = app.admins.map { admin ->
            val detail = when {
                admin.inline -> "inline"
                !admin.registered -> "not registered"
                else -> admin.models.joinToString(", ").ifEmpty { null }
            }
            ByDjangoNode(ByDjangoNodeKind.ADMIN, admin.symbol?.name ?: "admin", detail, admin.symbol, warning = !admin.registered && !admin.inline)
        }
        val plain = app.models.filter { it.inAdmin && it.admins.isEmpty() }.map { model ->
            ByDjangoNode(ByDjangoNodeKind.ADMIN, model.symbol?.name ?: "model", "default ModelAdmin", model.symbol)
        }
        return classes + plain
    }

    private fun model(model: ByDjangoModel): ByDjangoNode {
        val flags = listOfNotNull("abstract".takeIf { model.abstract }, "proxy".takeIf { model.proxy })
        return ByDjangoNode(
            ByDjangoNodeKind.MODEL,
            model.symbol?.name ?: "model",
            detail = flags.joinToString(", ").ifEmpty { null },
            symbol = model.symbol,
            children = model.fields.map(::field),
        )
    }

    private fun field(field: ByDjangoField): ByDjangoNode {
        val relation = field.relation
        val described = if (relation != null) {
            listOfNotNull(
                field.kind,
                relation.target?.let { "→ $it" },
                relation.relatedName?.let { "(reverse $it)" },
            ).joinToString(" ")
        } else {
            listOfNotNull(field.kind, field.type).joinToString(": ")
        }
        val detail = listOfNotNull(described.ifEmpty { null }, field.inheritedFrom?.let { "from $it" }).joinToString(", ")
        return ByDjangoNode(
            if (relation != null) ByDjangoNodeKind.RELATION else ByDjangoNodeKind.FIELD,
            field.symbol?.name ?: "field",
            detail = detail.ifEmpty { null },
            symbol = field.symbol,
        )
    }

    private fun group(name: String, children: List<ByDjangoNode>): ByDjangoNode? =
        children.takeIf { it.isNotEmpty() }?.let { ByDjangoNode(ByDjangoNodeKind.GROUP, name, it.size.toString(), children = it) }
}
