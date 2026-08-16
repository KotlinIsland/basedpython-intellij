package dev.basedpython.pycharm.env.manager

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Turning `uv tree --format json` into the grouped dependency tree the view shows.
 *
 * ### The shape uv gives
 *
 * Not a tree — a graph plus a list of entry points, which is the honest representation and leaves
 * the flattening to whoever displays it:
 *
 * - `resolution` maps an opaque package id to `{name, version, kind, dependencies: [{id}]}`.
 * - `kind` is the string `"package"` for an ordinary one, the string `"workspace"` for the container,
 *   or an object — `{"group": "dev"}`, `{"extra": "cli"}` — for the *synthetic* node that stands for
 *   "this project's dev group". That synthetic node's dependencies are exactly the requirements
 *   declared under that group, which is what makes the grouping possible at all.
 * - `roots` names the entry points: one per group and extra, plus the project itself.
 *
 * So a group is a root whose kind carries a name, and the main dependency list is the root whose
 * kind is a plain package. Everything under them comes from following ids through `resolution`.
 *
 * ### The schema says `preview`
 *
 * uv labels this schema as unstable, and this parser is written to survive it changing: every field
 * is read defensively and anything unrecognised yields an empty result rather than an exception. An
 * empty result is a supported outcome — [EnvPanel] falls back to the flat installed list — so a uv
 * that reshapes this JSON costs the grouping, not the window.
 */
object UvTree {

    /**
     * How deep the walk will go before giving up.
     *
     * The dedupe below already makes infinite recursion impossible — a package is expanded at most
     * once per group, so a cycle terminates the second time round. This is the guard for the case
     * that reasoning does not cover: a `resolution` map malformed in a way that makes the walk
     * generate fresh work forever. Far deeper than any real dependency chain.
     */
    private const val MAX_DEPTH = 100

    /** One entry in `resolution`, reduced to what the view needs. */
    private data class Entry(
        val name: String,
        val version: String,
        val kind: Kind,
        val dependencyIds: List<String>,
    )

    private sealed interface Kind {
        data object Package : Kind
        data object Workspace : Kind
        data class Group(val name: String) : Kind
        data class Extra(val name: String) : Kind
    }

    /**
     * Parses the JSON, or returns an empty list.
     *
     * Empty is the honest answer for every failure here — malformed JSON, a schema that moved, a uv
     * that printed a warning instead of a graph — because a partial dependency tree is worse than
     * none: it would silently claim a project has fewer dependencies than it does.
     */
    fun parse(stdout: String): List<EnvDependencyGroup> = try {
        parseOrThrow(stdout)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * The main list is kept even when it is empty; every other group is dropped when it is.
     *
     * An empty group heading is noise — a project that declares no `docs` group should not have a
     * `docs` row — but the main list is different in kind. It is where dependencies go by default,
     * so a project with none has an empty main list rather than no main list, and the row is the
     * thing that says so and the thing you select before pressing *Add*. Hiding it leaves a project
     * whose only dependencies are dev ones looking like it has no main list at all.
     */
    private fun parseOrThrow(stdout: String): List<EnvDependencyGroup> {
        val root = JsonParser.parseString(stdout.trim().ifEmpty { "{}" })
        if (!root.isJsonObject) return emptyList()

        val resolution = root.asJsonObject.getAsJsonObject("resolution") ?: return emptyList()
        val entries = LinkedHashMap<String, Entry>()
        for ((id, value) in resolution.entrySet()) {
            entry(value)?.let { entries[id] = it }
        }

        val rootIds = root.asJsonObject.getAsJsonArray("roots")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("id") }
            ?.toList()
            .orEmpty()

        val groups = rootIds.mapNotNull { id -> group(id, entries, rootIds.toSet()) }
        return groups.filter { it.target == EnvDependencyTarget.Main || it.roots.isNotEmpty() }
            .sortedWith(GROUP_ORDER)
    }

    /** One root's group, or null when the root is not something the view shows. */
    private fun group(
        rootId: String,
        entries: Map<String, Entry>,
        rootIds: Set<String>,
    ): EnvDependencyGroup? {
        val entry = entries[rootId] ?: return null
        val target = when (val kind = entry.kind) {
            is Kind.Group -> EnvDependencyTarget.Group(kind.name)
            is Kind.Extra -> EnvDependencyTarget.Extra(kind.name)
            Kind.Package -> EnvDependencyTarget.Main
            // The workspace container is a bookkeeping node with no requirements of its own.
            Kind.Workspace -> return null
        }

        // Dedupe is per group rather than across the whole tree, which is where this deliberately
        // differs from uv's own text output. Globally deduping means a package that happens to
        // appear under `dev` first is shown as an unexpanded leaf under `dependencies` — the group
        // the user actually cares about — for no better reason than iteration order. Per group,
        // every group reads as a complete tree of its own, and the duplication is bounded by the
        // number of groups, which is a handful.
        val expanded = HashSet<String>()
        val roots = entry.dependencyIds
            // An extra's synthetic node depends on the base project as well as on the extra's own
            // requirements — that edge is what "extras include the package itself" means. Following
            // it would nest the whole main tree under every extra.
            .filter { it !in rootIds }
            .mapNotNull { walk(it, entries, expanded, depth = 0) }
        return EnvDependencyGroup(target, roots)
    }

    /** The node for [id] and everything under it. */
    private fun walk(
        id: String,
        entries: Map<String, Entry>,
        expanded: MutableSet<String>,
        depth: Int,
    ): EnvDependencyNode? {
        val entry = entries[id] ?: return null
        if (entry.kind == Kind.Workspace) return null

        // Already shown in full somewhere in this group: repeat the row, not the subtree. This is
        // also what makes a dependency cycle terminate.
        if (id in expanded || depth >= MAX_DEPTH) {
            return EnvDependencyNode(
                name = entry.name,
                version = entry.version,
                expandedElsewhere = entry.dependencyIds.isNotEmpty(),
            )
        }
        expanded += id

        val children = entry.dependencyIds
            .mapNotNull { walk(it, entries, expanded, depth + 1) }
            .sortedBy { it.name.lowercase() }
        return EnvDependencyNode(entry.name, entry.version, children)
    }

    /** One `resolution` entry, or null when it is not shaped like one. */
    private fun entry(value: JsonElement): Entry? {
        val obj = value.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val kind = kind(obj.get("kind")) ?: return null
        // The workspace container carries no name; everything else must have one to be shown.
        val name = obj.string("name") ?: return if (kind == Kind.Workspace) {
            Entry("", "", kind, emptyList())
        } else {
            null
        }
        val dependencies = obj.getAsJsonArray("dependencies")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.string("id") }
            ?.toList()
            .orEmpty()
        return Entry(name, obj.string("version").orEmpty(), kind, dependencies)
    }

    /**
     * `kind` is either a string or a single-key object naming a group or an extra.
     *
     * An unrecognised string is treated as an ordinary package rather than rejected: a uv that adds
     * a new kind should cost the label on those rows, not the whole tree.
     */
    private fun kind(value: JsonElement?): Kind? = when {
        value == null -> null
        value.isJsonPrimitive -> if (value.asString == "workspace") Kind.Workspace else Kind.Package
        value.isJsonObject -> value.asJsonObject.let { obj ->
            obj.string("group")?.let(Kind::Group)
                ?: obj.string("extra")?.let(Kind::Extra)
                ?: Kind.Package
        }
        else -> null
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }

    /**
     * Main list first, then extras, then groups, alphabetically within each.
     *
     * The main list leads because it is the project's actual dependencies and the answer to almost
     * every question the window is opened with. Extras come next as the other thing a *consumer*
     * can install, and named groups — a development concern that never ships — last. `dev` sorts
     * first among the groups, being the one that is nearly always there and nearly always meant.
     */
    private val GROUP_ORDER: Comparator<EnvDependencyGroup> =
        compareBy<EnvDependencyGroup> {
            when (it.target) {
                EnvDependencyTarget.Main -> 0
                is EnvDependencyTarget.Extra -> 1
                is EnvDependencyTarget.Group -> 2
            }
        }.thenBy { it.target != EnvDependencyTarget.DEV }
            .thenBy { it.target.label.lowercase() }
}
