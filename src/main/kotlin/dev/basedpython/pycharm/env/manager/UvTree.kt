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
 * - `members` names the workspace's modules — `{name, path, id}` each — and each member's own
 *   `resolution` entry carries `dependency_groups` and `optional_dependencies` back-links naming
 *   the synthetic nodes that belong to it.
 *
 * So a group is a root whose kind carries a name, and the main dependency list is the root whose
 * kind is a plain package. Everything under them comes from following ids through `resolution`.
 *
 * ### Roots are per module, not per project
 *
 * A workspace has a manifest per module and uv emits a root for every list of every one of them, so
 * a two-module workspace produces *two* roots of kind `package` and can produce two `dev` groups.
 * Reading only `kind` therefore collapses them: two headings both called `dependencies`, and — far
 * worse — two groups that compare equal, so a removal from one is sent as a removal from the other.
 * `members` is what tells them apart, and [EnvDependencyList.module] is where the answer is kept.
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
     * The main list and `dev` are kept even when empty; every other group is dropped when it is.
     *
     * See [ALWAYS_SHOWN]. An empty `docs` heading is noise; an empty `dependencies` or `dev` is a
     * place the project has and you can add to.
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

        val modules = modules(root.asJsonObject, resolution)
        val groups = rootIds.mapNotNull { id -> group(id, entries, modules) }
        return groups.filter { it.target in ALWAYS_SHOWN || it.roots.isNotEmpty() }
            .sortedWith(order(modules))
    }

    /** One `members` entry. */
    private data class Member(val name: String, val id: String, val path: String?)

    /**
     * Which module owns each root, and which package each synthetic node belongs to.
     *
     * Both answers come from the same back-links, so they are read in one pass. Everything here
     * degrades to the pre-workspace behaviour when `members` is absent or reshaped: no module on any
     * group, and the self-edge found by name instead — see [selfEdge].
     */
    private class Modules(
        /** Member name by root id. Empty for a project that is not a workspace. */
        val ownerOf: Map<String, String>,
        /**
         * The member a synthetic node belongs to, by that node's id.
         *
         * Filled for groups as well as extras, since both are read from the same two arrays. Only
         * an extra has an edge back to it to drop — see [selfEdge].
         */
        val baseOf: Map<String, String>,
        /** The member that *is* the workspace root, whose lists sort first. Null when unknown. */
        val rootModule: String?,
    )

    private fun modules(root: JsonObject, resolution: JsonObject): Modules {
        val members = root.getAsJsonArray("members")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.mapNotNull { obj ->
                val name = obj.string("name") ?: return@mapNotNull null
                val id = obj.string("id") ?: return@mapNotNull null
                Member(name, id, obj.string("path"))
            }
            .orEmpty()

        val ownerOf = LinkedHashMap<String, String>()
        val baseOf = LinkedHashMap<String, String>()
        for (member in members) {
            ownerOf[member.id] = member.name
            val entry = resolution.get(member.id)?.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            for (key in OWNED_LISTS) {
                val owned = entry.getAsJsonArray(key) ?: continue
                for (element in owned) {
                    val id = element.takeIf { it.isJsonObject }?.asJsonObject?.string("id") ?: continue
                    ownerOf[id] = member.name
                    baseOf[id] = member.id
                }
            }
        }

        val workspaceRoot = root.string("workspace_root")
        val rootModule = members.firstOrNull { samePath(it.path, workspaceRoot) }?.name
        // A project that is not a workspace lists *itself* as the single member. Naming its module
        // would put a qualifier on every heading of a project with nothing to qualify against — and
        // would start passing `--package` to commands that have always managed without it.
        //
        // The test is that the one member *is* the root, not that there is one member. A manifest
        // holding only `[tool.uv.workspace]` — a virtual root, with no `[project]` of its own — and
        // one member also has a single entry, and that entry is the member, not the root. Counting
        // alone therefore called that project unmanaged and dropped `--package` from every command
        // it issued: `uv remove idna` answers "could not be found in `project.dependencies`" and
        // `uv add httpx` answers "Project is missing a `[project]` table". Verified on uv 0.12.10.
        val singlePackageProject = members.size == 1 && rootModule != null
        return Modules(
            ownerOf = if (singlePackageProject) emptyMap() else ownerOf,
            baseOf = baseOf,
            rootModule = rootModule,
        )
    }

    /** The member back-link arrays, both of which name synthetic root nodes. */
    private val OWNED_LISTS = listOf("dependency_groups", "optional_dependencies")

    /** uv writes a member's path with a trailing separator and the workspace root's without one. */
    private fun samePath(one: String?, other: String?): Boolean =
        one != null && other != null && one.trimEnd('/', '\\') == other.trimEnd('/', '\\')

    /**
     * Groups worth a heading even with nothing in them.
     *
     * The main list because it is where dependencies go by default, and `dev` because it is where
     * development ones go — both are places a project *has* rather than lists it happens to have
     * filled. Removing the last entry from either should leave an empty heading you can add to, not
     * make the group disappear and have to be conjured back by typing its name into a dialog.
     *
     * Every other group is dropped when empty: a `docs` heading with nothing under it is noise, and
     * unlike these two it is not somewhere a person expects to find.
     */
    private val ALWAYS_SHOWN = setOf(EnvDependencyTarget.Main, EnvDependencyTarget.DEV)

    /** One root's group, or null when the root is not something the view shows. */
    private fun group(
        rootId: String,
        entries: Map<String, Entry>,
        modules: Modules,
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
        val self = selfEdge(rootId, entry, entries, modules)
        val roots = entry.dependencyIds
            .filter { it != self }
            .mapNotNull { walk(it, entries, expanded, depth = 0) }
        return EnvDependencyGroup(EnvDependencyList(target, modules.ownerOf[rootId]), roots)
    }

    /**
     * The edge from an extra's synthetic node back to the package the extra belongs to.
     *
     * `sub[cli]` depends on `certifi` *and* on `sub` — that second edge is what "installing an extra
     * installs the package too" means in the graph. Following it would nest the whole main tree
     * under every extra, so it is dropped.
     *
     * Only that one edge, and only for a synthetic node. Dropping every edge that happens to land on
     * a root — which is what this used to do — is indistinguishable from it in a single-package
     * project and wrong in a workspace: a module that depends on a sibling depends on something that
     * is itself a root, and the sibling would vanish from the list that declares it.
     */
    private fun selfEdge(
        rootId: String,
        entry: Entry,
        entries: Map<String, Entry>,
        modules: Modules,
    ): String? {
        if (entry.kind !is Kind.Extra) return null
        modules.baseOf[rootId]?.let { return it }
        // No back-links to read: the edge is still the one to a package of the same name as the
        // node that carries the extra. Only when there is a name to match on — a nameless node would
        // otherwise match the next nameless one and drop an edge for the resemblance.
        if (entry.name.isEmpty()) return null
        return entry.dependencyIds.firstOrNull { entries[it]?.name == entry.name }
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
        // A row is drawn with its name, so an ordinary package must have one to be shown. The nodes
        // that head a *group* need none: the heading is the target's label, and the name was only
        // ever used to say which package the group belongs to.
        //
        // Which matters because uv does omit it. A virtual workspace root — a manifest holding only
        // `[tool.uv.workspace]` — has no distribution name, so its own `[dependency-groups]` arrive
        // as `workspace+/path:dev` with a `kind` and no `name`. Requiring one dropped those lists
        // from the tree entirely: the project's `dev` group was not shown as empty, it was not shown.
        val name = obj.string("name") ?: if (kind is Kind.Package) {
            return null
        } else {
            ""
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
     * The root module first and the rest alphabetically; within a module, the main list, then
     * extras, then groups.
     *
     * The main list leads because it is the project's actual dependencies and the answer to almost
     * every question the window is opened with. Extras come next as the other thing a *consumer*
     * can install, and named groups — a development concern that never ships — last. `dev` sorts
     * first among the groups, being the one that is nearly always there and nearly always meant.
     *
     * Module before target, rather than after, because a module's lists are what a person reads
     * together: interleaving them would put the root's `dev` between a member's `dependencies` and
     * its `dev`, and the qualifier on each heading would be the only thing holding the tree together.
     */
    private fun order(modules: Modules): Comparator<EnvDependencyGroup> =
        compareBy<EnvDependencyGroup> { if (it.module == null || it.module == modules.rootModule) 0 else 1 }
            .thenBy { it.module.orEmpty().lowercase() }
            .thenBy {
                when (it.target) {
                    EnvDependencyTarget.Main -> 0
                    is EnvDependencyTarget.Extra -> 1
                    is EnvDependencyTarget.Group -> 2
                }
            }
            .thenBy { it.target != EnvDependencyTarget.DEV }
            .thenBy { it.target.label.lowercase() }
}
